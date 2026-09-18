# Agent Senses

A device-neutral Kotlin sense layer for agent-driven interfaces. It defines
typed contracts for bidirectional, multimodal interaction between an agent and
a user-facing sense endpoint — audio input/output, image input, visual/text
output, text input, interaction input, and status input — across physical
wearables, phones, chat surfaces, and deterministic simulators.

The goal: an agent works in terms of **capabilities** (`sense_listen`,
`sense_look`, `sense_speak`, `sense_present`, `sense_wait`, `sense_status`),
not devices. A physical Halo, a phone, a chat window, and a simulator are
different endpoints with different truthful capability profiles, and every
observation and effect carries provenance.

## Highlights

- **One contract, explicit profiles** — `SenseEndpoint` +
  `SenseProfile` describe what an endpoint can actually do; capability
  absence is a typed `Unavailable`, never a substituted value.
- **Typed provenance everywhere** — every result records operationId,
  endpointId, backendKind, origin, and transformations. Fixture data is
  always identifiable as fixture data.
- **Capability-based concurrency** — `CapabilityCoordinator` serializes
  declared resource conflicts and permits independent operations to overlap.
- **Deterministic simulator** — scripted scenarios, virtual time, and
  fixtures for contract, workflow, and UI regression tests. No BLE in unit
  tests.
- **Shared contract suite** — `SenseEndpointContractTest` defines behavioral
  contracts every endpoint implementation must pass.

## Architecture

```text
Application / agent tools
          │
          ▼
SenseCapabilityRoutes (/v1/sense/*)
          │
          ▼
SemanticSenseWorkflows + SenseEndpointRegistry
          │
    ┌─────┼──────────────┬────────────┐
    ▼     ▼              ▼            ▼
  Phone  Physical Halo  Chat       Simulator
```

The current API:

- `SenseProfile` — endpoint identity, backend kind, state, capabilities,
  limits, and concurrency profile.
- `SenseCapability` — the supported input/output capability set.
- `SenseEndpoint` — typed request/result methods; every result carries
  `Provenance`.
- `SenseEndpointRegistry` — explicit endpoint binding and capability
  resolution.
- `CapabilityCoordinator` — resource-domain conflict and concurrency control.
- `SenseOutcome` / `SenseFailure` — stable failure categories.
- `SemanticSenseWorkflows` — orchestration for transcription, vision, TTS,
  and combined operations.

See [`docs/AGENT_SENSES_ARCHITECTURE.md`](docs/AGENT_SENSES_ARCHITECTURE.md)
for the design decisions behind these contracts.

## Modules

| Module | Role |
|---|---|
| `core` | Pure Kotlin contracts, profiles, failures, coordination, runtime lifecycle, and WAV helpers |
| `halo` | `PhysicalHaloEndpoint` backed by `halo-engine` transport/session abstractions — autorun-aware connect probing, device sprite file caching, mpix camera pipeline defaults, and the `hud` overlay (`sendHud`/`clearHud`, gated on `hudSupported` so hosts degrade to card+speech when the glasses runtime lacks the capability) |
| `simulator` | Deterministic `SimulatedHaloEndpoint`/`SimulatedHaloBleTransport` with sprite-cache and image-path parity, scripted scenarios, virtual time, and test fixtures |
| `routes` | Authenticated Ktor routes for the generic `/v1/sense/*` API |
| `orchestration` | Semantic workflows and multi-modal operation composition |

## Quick start

**Prerequisites:** JDK 17. No Android SDK is required — every module is a
plain Kotlin/JVM project.

The `:halo` and `:simulator` modules consume `halo-engine` through a Gradle
composite build. Clone it as a sibling of this repository:

```text
work/
├── agent-senses/     ← this repository
└── halo-engine/      ← required by :halo and :simulator
```

Or point the build at another location with `-PhaloEngineDir=<path>`.

```sh
# JVM modules — no Android SDK needed
./gradlew :core:test
./gradlew :simulator:test
./gradlew :routes:test
./gradlew :halo:build
```

Windows: use `gradlew.bat`.

## Repository structure

```text
core/          pure Kotlin contracts and helpers
halo/          PhysicalHaloEndpoint
simulator/     deterministic endpoint and test fixtures
routes/        authenticated Ktor route adapters
orchestration/ semantic workflows
docs/          architecture and developer documentation
gradle/        Gradle wrapper
LICENSE        MIT
THIRD_PARTY_NOTICES.md
```

## Scope and limitations

- The `core` module has no Android, BLE, Ktor, Node, Python, or model imports.
- Physical-Halo throughput, callback behavior, and instrumented validation are
  still required before claiming hardware production readiness.
- Simulator classes must not be placed on a production release classpath;
  consuming applications keep the simulator dependency debug/test-only.
- Device firmware, the `halo-engine` transport internals, and product
  presentation templates are deliberately outside this repository.
- Published as source (group `agent.senses`); no Maven artifact yet.

## Integrating

See [`docs/DEVELOPERS_GUIDE.md`](docs/DEVELOPERS_GUIDE.md) for the integration
guide: the `/v1/sense/*` HTTP contract for harnesses in any language, and the
embedded-module path for Kotlin/JVM or Android hosts.

## Contributing

See `AGENTS.md` for the repo workflow: build/test commands, the contract-test
requirement for new endpoints, streaming-capability conventions, and the
project rules enforced in review.

## License

MIT — see `LICENSE`. This is an independent research project and is not
affiliated with Brilliant Labs or any device manufacturer. See
`THIRD_PARTY_NOTICES.md` for dependency guidance.
