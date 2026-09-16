# Integrating agent-senses into your harness

This guide is for **harness authors** — people building agents with
OpenClaw, Hermes, Claude Code, a custom loop, or any other orchestrator —
who want to give their agent senses on a physical Halo (or a phone, chat
surface, or simulator) without writing device code.

If you want to modify agent-senses itself, read `AGENTS.md` instead — this
document treats the library as a dependency, not a workspace.

## What your agent gets

A device-neutral sense API. Your agent works in terms of **capabilities** —
listen, look, speak, present, wait, status — not devices. A physical Halo, a
phone, a chat window, and a simulator are different *endpoints* with
different, truthful capability profiles. Every result carries provenance
(which endpoint, which backend, what transformations ran), and every failure
is a typed category your agent can switch on.

Two integration modes exist; pick per how your harness runs:

| | Mode A — HTTP bridge | Mode B — Embedded |
|---|---|---|
| Your harness runs in | Any language / process | Kotlin/JVM or Android |
| You call | `POST /v1/sense/*` on a host app | `SenseEndpoint` methods directly |
| Host requirement | A running bridge app (e.g. dsh-android) | Your process owns everything |
| BLE ownership | The host app | You (via `HaloBleTransport`) |

Mode A is the normal path for OpenClaw/Hermes/Claude-Code-style harnesses:
they can't embed Kotlin, so a host application owns BLE and the model
adapters and your harness exposes thin `sense_*` tools that proxy HTTP.

Mode B is for Kotlin hosts embedding the library directly — an Android app,
a desktop service, or a test rig.

---

## Mode A — the `/v1/sense/*` bridge

A host application (the reference is `dsh-android`) mounts
`senseCapabilityRoutes` on an HTTP server, typically loopback, protected by
a bearer token. Your harness never touches BLE, firmware, or models — it
exposes tools that translate agent calls into these routes.

### Authentication

Every request carries `Authorization: Bearer <token>`. The token is issued
by the host app at startup; there is no other auth surface. A missing or
wrong token is `401`.

### Route surface

| Route | Purpose | Notes |
|---|---|---|
| `GET /v1/sense/capabilities` | List bound endpoints, live state, capabilities | Call first — endpoints appear/disappear as devices connect |
| `POST /v1/sense/listen` | Capture audio → transcribe → return transcript | Semantic route; needs a `TranscriptionModel` on the host |
| `POST /v1/sense/look` | Capture image → observe → return description | Semantic route; needs a `VisionModel` on the host |
| `POST /v1/sense/wait` | Block until an interaction event or timeout | tap / button / approval / selection / text_entry / disconnected |
| `POST /v1/sense/speak` | Play raw audio bytes | Low-level; you supply WAV/PCM |
| `POST /v1/sense/say` | Text → TTS → play | Semantic route; needs a `TtsModel` on the host |
| `POST /v1/sense/present` | Put content on a display | `kind`: `device_native` (HSD/HRP), `image`, or `text` |
| `POST /v1/sense/status` | Battery and endpoint state | Cheap; safe to poll sparingly |

Every request takes an optional `endpoint_id`. Omit it for capability-based
resolution; the registry picks the endpoint that supports the capability.
When several endpoints qualify and you omitted `endpoint_id`, you get a
`409` with `category: "AMBIGUOUS"` listing the eligible IDs — re-issue with
an explicit `endpoint_id`. Your agent should learn endpoint IDs from
`/v1/sense/capabilities`, never hardcode them.

### Error contract

Failures return non-2xx with `{ "error": ..., "category": ..., "endpoint_id": ... }`.
**Switch on `category`, never on message text:**

| `category` | HTTP | Meaning for your agent |
|---|---|---|
| `UNAVAILABLE` | 404 | Capability absent or no endpoint — degrade, don't retry |
| `DISCONNECTED` | 410 | The endpoint went away — check capabilities, maybe reconnect |
| `TIMEOUT` / `CANCELLED` | 408 | Deadline hit — safe to retry with a longer timeout |
| `REJECTED` / `PROTOCOL` | 400 | Bad request or device protocol error — fix the request |
| `LIMIT_EXCEEDED` | 413 | Payload over the endpoint's advertised limits — shrink it |
| `PERMISSION_DENIED` | 403 | User/OS denied access — ask the user, don't retry |
| `MODEL_UNAVAILABLE` | 503 | Host's model is down — fall back to raw capture or text |
| `INTERNAL` | 500 | Host bug — report, don't retry blindly |
| `AMBIGUOUS` | 409 | Multiple endpoints qualify — pass `endpoint_id` |

### Provenance

Every success carries `provenance`: `operation_id`, `endpoint_id`,
`backend_name`, `backend_kind` (`PHYSICAL` / `PHONE` / `CHAT` / `SIMULATOR`
/ `FIXTURE`), `capability`, `origin`, `started_at`, `completed_at`.
`backend_kind` is how you know a result came from a simulator or fixture —
surface it to the user or your logs; never let fixture data impersonate real
sensing.

### Interaction events (`/v1/sense/wait`)

Interaction events are **broadcast, not queued**. A `wait` call subscribes
at call time; events emitted before your call are *not* delivered (the
underlying flow is `replay = 0`). `event_type: "disconnected"` unblocks the
wait immediately when the link drops — treat it as an operation failure, not
an empty result. `accepted_gestures` filters by interaction type
(`TAP_SINGLE`, `BUTTON_LONG`, `APPROVAL`, …); an empty list accepts all.

### Raw media

Semantic routes return text by default — the transcript, the description —
and keep raw capture ephemeral. Pass `raw: true` to also get
`audio_base64`/`image_base64`. Don't request raw media your agent doesn't
need; it's the expensive path over BLE and it carries privacy weight.

---

## Mode B — embedding the Kotlin library

Add the modules as a composite or source dependency (group `agent.senses`,
version `0.1.0`; no Maven publishing yet):

```kotlin
implementation("agent.senses:core:0.1.0")          // contracts, registry, runtime, ChatEndpoint
implementation("agent.senses:orchestration:0.1.0") // SemanticSenseWorkflows
implementation("agent.senses:halo:0.1.0")          // PhysicalHaloEndpoint (needs halo-engine)
implementation("agent.senses:routes:0.1.0")        // Ktor bridge, if you re-expose HTTP
debugImplementation("agent.senses:simulator:0.1.0")// debug/test only — never ship it
```

The physical Halo path also needs `halo-engine` (`halo.engine:android` on
Android) for `HaloBleTransport`, `HaloRuntimeInstaller`, and HSD/HRP
compilation. On non-Android JVM hosts there is no shipped BLE transport —
provide your own `HaloBleTransport` implementation or use the simulator.

### Minimal wiring

```kotlin
val runtime = SenseRuntime()
runtime.startGeneration()

// Endpoints: implement SenseEndpoint or use the shipped ones.
val halo = PhysicalHaloEndpoint(
    transport = myTransport,                       // halo-engine HaloBleTransport
    config = HaloEndpointConfig(
        endpointId = EndpointId("halo-1"),
        runtimeInstaller = { t -> installer.installAndStart(t) },
        onInteraction = { event -> /* forward to your harness */ },
    ),
)
halo.connect()                                     // BLE + runtime install + feature negotiation
runtime.registry.bind(halo)

// Models: you supply these. Gemma, Whisper, Android TTS, a cloud API —
// anything that satisfies the port contracts in core.
val workflows = SemanticSenseWorkflows(
    registry = runtime.registry,
    transcriptionModel = myAsr,    // TranscriptionModel — null disables sense_listen's transcript
    visionModel = myVision,        // VisionModel — null disables sense_look's description
    ttsModel = myTts,              // TtsModel — null disables sense_say
)

// Optionally re-expose the same HTTP surface for an out-of-process agent:
routing { senseCapabilityRoutes(runtime.registry, authToken, workflows) }

// On exit:
runtime.shutdown()
```

Rules that apply to embedded hosts:

- `SenseProfile` must be **truthful** — advertise only capabilities the
  backend supports, with real limits. `RequestValidator` enforces them.
- `SenseEndpointContractTest` (in `core` test fixtures) is the shared
  contract suite; run it against any endpoint you add.
- `core` stays pure — no Android, BLE, Ktor, or model types. Device-specific
  tuning goes through `deviceOptions` / `VisualKind.DEVICE_NATIVE`.
- Keep `simulator` off production classpaths (`debugImplementation`); its
  results carry `backend_kind=SIMULATOR`, but that honesty only helps if it
  isn't silently selectable in release.

## Exposing senses to your agent

Whichever mode you use, the tool surface your agent sees should be the
generic one — capability verbs, not device names:

```text
sense_capabilities → GET  /v1/sense/capabilities
sense_listen       → POST /v1/sense/listen
sense_look         → POST /v1/sense/look
sense_wait         → POST /v1/sense/wait
sense_speak        → POST /v1/sense/speak
sense_say          → POST /v1/sense/say
sense_present      → POST /v1/sense/present
sense_status       → POST /v1/sense/status
```

Tool-description guidance that matters:

1. **Discover first.** Tell the agent to call `sense_capabilities` before
   assuming a Halo is bound — endpoints come and go.
2. **Degrade, don't invent.** On `UNAVAILABLE`/`DISCONNECTED`, the agent
   should answer with what it has, not fabricate a sensor reading. There is
   no silent fallback in the API; there should be none in your agent either.
3. **Endpoint targeting is explicit.** When the user says "on my glasses",
   resolve the endpoint via capabilities and pass `endpoint_id`; don't guess
   IDs.
4. **Respect provenance.** `backend_kind` and `fixture_id` exist so your
   agent (and your users) can tell simulation from hardware.

For scene authoring on the Halo display (`kind: "device_native"`,
`format: "hsd"`), the sibling `halo-engine` repository owns the HSD
instruction set (`docs/HSD_INSTRUCTION_SET.md`) and ships a stdio MCP server
(`python -m halo_engine.mcp_server`) that harnesses speaking MCP can use for
compile/preview tooling. HSD stays a device-native payload — agent-senses
carries it without interpreting it.

## Non-negotiables

- **Never fabricate device state.** If the bridge is down, that's
  `UNAVAILABLE`/`DISCONNECTED` — not a plausible-looking battery level.
- **Deadlines everywhere.** Pass `timeout_millis`; don't let a `wait` or
  `listen` hang an agent turn indefinitely.
- **Bounded payloads.** `max_bytes` is enforced by the endpoint, but set it
  deliberately — BLE is slow and model context is scarce.
- **Ephemeral by default.** Raw media, prompts, and private content stay out
  of logs and long-lived context unless the user asked for retention.

## Versioning and license

Published as source only (group `agent.senses`, version `0.1.0`) — consume
via Gradle composite build or vendored source. MIT license
(`LICENSE`); independent research project, not affiliated with Brilliant
Labs.
