# Developer Guide

This guide covers building, testing, and extending **agent-senses**: a
device-neutral Kotlin sense layer for agent-driven wearable interfaces.

If you are looking for the design rationale, read
[`docs/AGENT_SENSES_ARCHITECTURE.md`](docs/AGENT_SENSES_ARCHITECTURE.md) first —
it defines the contracts this code implements (AD-1 through AD-10). For
day-to-day agent work in this repo, `AGENTS.md` is the quick reference.

## Requirements

| Requirement | Notes |
|---|---|
| JDK 17 | All modules are Kotlin/JVM targeting Java 17. |
| `halo-engine` checkout | Required only by `:halo` and `:simulator` (composite build). |
| Android SDK | **Not required.** Every module is a plain JVM project. |
| BLE hardware | Not required for any unit test. Never test BLE in unit tests. |

## Getting `halo-engine`

`settings.gradle.kts` includes `halo-engine` as a Gradle composite build and
expects it at `../halo-engine` relative to this repository:

```text
work/
├── agent-senses/     ← this repository
└── halo-engine/      ← clone alongside
```

If it lives elsewhere, point the build at it:

```sh
./gradlew :halo:build -PhaloEngineDir=/path/to/halo-engine
```

`:core`, `:routes`, and `:orchestration` do not depend on `halo-engine`, but
the composite include is configured at settings evaluation time, so the
directory must exist even if you only build `:core`.

## Module map

```text
core/            contracts only — SenseEndpoint, SenseProfile, SenseCapability,
                 SenseOutcome/FailureCategory, Provenance, CapabilityCoordinator,
                 endpoint registry, runtime lifecycle, WAV helpers.
                 NO Android, BLE, Ktor, firmware, or model imports.
halo/            PhysicalHaloEndpoint — the physical Halo backend. Uses
                 halo-engine transport, session, and scene compilation.
simulator/       SimulatedHaloEndpoint + SimulatedHaloBleTransport, scripted
                 ScenarioFixtures, VirtualTimeSource. Deterministic by design.
                 Also exposes test fixtures consumed by other modules' tests.
routes/          Authenticated Ktor adapters exposing the generic
                 /v1/sense/* HTTP API over the registry.
orchestration/   SemanticSenseWorkflows — multi-modal composition
                 (transcription, vision observation, TTS, speak+present).
```

Dependency direction is one-way: everything may depend on `core`; `core`
depends on nothing in this repo. The simulator and `routes` are consumers of
the contracts — they must never be imported by `core` or `halo`.

## Build and test

```sh
# Everything (JVM only — safe without an Android SDK)
./gradlew build

# Per-module
./gradlew :core:test
./gradlew :simulator:test
./gradlew :routes:test
./gradlew :halo:test
./gradlew :halo:build
```

Windows: use `gradlew.bat` instead of `./gradlew`.

## Testing model

1. **Unit tests are pure JVM.** No BLE, no Android, no real time. The
   simulator is the only device stand-in.
2. **`SenseEndpointContractTest`** (in `core` test fixtures) is the shared
   parameterized contract suite. Every `SenseEndpoint` implementation must
   pass it — run it against any new endpoint you add.
3. **`ScenarioFixtures`** (in `:simulator`) provides deterministic scenarios
   for common paths — success, typed failures, cancellation, limits. Prefer
   composing scenarios over hand-rolled mocks.
4. **`VirtualTimeSource` + `StandardTestDispatcher`** for anything
   time-dependent; tests must not sleep on wall-clock time.
5. Physical Halo behavior is validated on hardware separately — a green JVM
   suite says nothing about BLE, firmware, or acoustic behavior, and claims
   about hardware require documented hardware tests.

## Extending the library

### Adding a new endpoint

1. Implement `SenseEndpoint` in the appropriate module (or a new module if it
   is a new device family).
2. Advertise a `SenseProfile` that is **truthful**: only capabilities the
   backend genuinely supports, with real limits and a concurrency profile.
3. Return `SenseFailure` with a stable `FailureCategory` — callers switch on
   the category, never on message strings.
4. Attach `Provenance` to every result: operationId, endpointId, backendKind,
   origin, timestamps.
5. Register it in `SenseEndpointContractTest` and make it pass.

### Adding a new capability

1. Add the sealed `SenseCapability` case in `core`, with its request/result
   types and limits.
2. Keep it device-neutral — a capability describes *what* the user perceives
   or does, not which device provides it. Device-specific tuning belongs in
   `deviceOptions: Map<String, Any>` or `VisualKind.DEVICE_NATIVE` payloads.
3. Update the endpoints that can truthfully support it; endpoints that cannot
   return `Unavailable`. Absence is normal (AD-4).
4. Extend the contract suite so the capability is covered for all endpoints.

### Adding a streaming capability to the physical Halo

- Extend `HaloProtocol` in `halo-engine` (message codes, payload codec) and
  the device-side Lua runtime; gate the capability on the negotiated firmware
  profile string so older runtimes report `Unavailable`.
- Drive it through `HaloSession` primitives (`collect` for start/chunk/final
  streams, `requestResponse` for one-shots). Do not hand-roll collection
  loops — the session owns timeout, cancellation, and stop-on-timeout
  behavior.
- Mirror it in `SimulatedHaloEndpoint`/`SimulatedHaloBleTransport` so the
  feature is testable without hardware.

## Project rules (enforced in review)

- `core` stays pure: no Android, BLE, Ktor, Node, Python, firmware, or model
  types, and no Halo vocabulary.
- Capability absence returns typed `Unavailable` — infrastructure never
  substitutes a fixture, a different endpoint, or a plausible value. Fallback
  is an agent-layer decision made after a structured failure.
- Every result carries provenance; fixture output must be traceable as
  fixture output (`backendKind=simulator`, stable `fixtureId`).
- Operations are cancellable and have deadlines.
- Simulator code must not land on a production classpath; consuming
  applications keep the simulator dependency debug/test-only.
- Speaker playback uses receiver-paced `sendAudioFrame` with `delay` between
  frames — the pacing protects the firmware input queue.
- Microphone PCM is wrapped as 16 kHz mono 16-bit WAV via `PcmToWav` before it
  leaves the endpoint; raw PCM is never returned.
- Kotlin official code style (`kotlin.code.style=official`), Java 17
  toolchain everywhere.

## Contributing workflow

1. Branch from `main` (or the active development branch), keep changes
   focused.
2. Add or update tests — contract tests for endpoint behavior, scenario
   fixtures for orchestration paths.
3. `./gradlew build` must be green, including the contract suite.
4. Update `README.md`, `DEVELOPERS.md`, or `docs/` when contracts, modules,
   or workflows change. Keep `THIRD_PARTY_NOTICES.md` accurate when
   dependencies change.
5. Physical-device claims require hardware validation notes; a green JVM
   suite alone is not hardware readiness.

## Versioning and distribution

The project is published as source (group `agent.senses`, version `0.1.0`).
There is no Maven publishing plugin configured yet; consumers include it as a
composite or source dependency. If you add binary publishing, generate a
notice bundle from the resolved Gradle artifacts per `THIRD_PARTY_NOTICES.md`.

## License

MIT — see `LICENSE`. This is an independent research project and is not
affiliated with Brilliant Labs or any device manufacturer.
