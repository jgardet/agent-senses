# Agent Senses

A device-neutral Kotlin sense layer for agent-driven interfaces. It defines typed
contracts for audio input/output, image input, visual/text output, text input,
interaction input, and status input across phone, physical Halo, chat, and simulator
endpoints.

## Current architecture

The Phase 1 `SensesDevice` contract has been removed. The current API is:

- `SenseProfile` — endpoint identity, backend kind, state, capabilities, limits, and
  concurrency profile.
- `SenseCapability` — the supported input/output capability set.
- `SenseEndpoint` — typed request/result methods; every result carries `Provenance`.
- `SenseEndpointRegistry` — explicit endpoint binding and capability resolution.
- `CapabilityCoordinator` — resource-domain conflict and concurrency control.
- `SenseOutcome` / `SenseFailure` — stable failure categories.
- `SemanticSenseWorkflows` — orchestration for transcription, vision, TTS, and combined
  operations.

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

## Modules

| Module | Role |
|---|---|
| `core` | Pure Kotlin contracts, profiles, failures, coordination, runtime lifecycle, and WAV helpers |
| `halo` | `PhysicalHaloEndpoint` backed by `graphic-engine-halo` transport/session abstractions |
| `simulator` | Deterministic `SimulatedHaloEndpoint`, scripted scenarios, virtual time, and test fixtures |
| `routes` | Authenticated Ktor routes for the generic `/v1/sense/*` API |
| `orchestration` | Semantic workflows and multi-modal operation composition |

The `graphic-engine-halo` composite build is included by `settings.gradle.kts` and
can be overridden with `-PhaloEngineDir=<path>`.

## Quick start

Prerequisites: JDK 17 and the sibling `graphic-engine-halo` checkout. The JVM modules
build without an Android SDK.

```powershell
.\gradlew.bat :core:test
.\gradlew.bat :simulator:test
.\gradlew.bat :routes:test
.\gradlew.bat :halo:build
```

The literal `gradle` command requires a system Gradle installation matching the
wrapper version; using the wrapper avoids version drift.

## Route surface

`routes` exposes an authenticated, generic capability API:

- `GET /v1/sense/capabilities`
- `POST /v1/sense/listen`
- `POST /v1/sense/look`
- `POST /v1/sense/wait`
- `POST /v1/sense/speak`
- `POST /v1/sense/say`
- `POST /v1/sense/present`
- `POST /v1/sense/status`

Routes return typed failure categories and provenance. Invalid requests and internal
failures use generic public messages rather than echoing exception details. Diagnostic
media callbacks contain bounded media artifacts and metadata only; transcripts,
image descriptions, and TTS text are not copied into provenance.

## Endpoint behavior

Every endpoint advertises truthful capabilities and limits through `SenseProfile`.
Physical Halo operations use `HaloSession.collect` or `HaloSession.requestResponse`;
hand-rolled streaming loops are not part of the endpoint implementation. Phone image
capture is fixed at 640×640 JPEG for the current firmware profile. Microphone PCM is
wrapped as 16 kHz mono 16-bit WAV. Speaker writes are receiver-paced.

The simulator is deterministic and intended for unit, contract, workflow, and UI
regression tests. It is not a substitute for physical-Halo validation.

## Repository structure

```text
core/          pure Kotlin contracts and helpers
halo/          PhysicalHaloEndpoint
simulator/     deterministic endpoint and test fixtures
routes/        authenticated Ktor route adapters
orchestration/ semantic workflows
gradle/        Gradle wrapper
LICENSE
THIRD_PARTY_NOTICES.md
```

## Scope and limitations

- The `core` module has no Android, BLE, Ktor, Node, Python, Gemma, or model imports.
- Physical-Halo throughput, callback behavior, and Android instrumented validation are
  still required before claiming hardware production readiness.
- Simulator classes must not be placed on a production release classpath; the
  dsh-android integration keeps the simulator dependency debug-only.
- The engine and product templates are deliberately outside this repository.

## License

MIT. This is an independent research project and is not affiliated with Brilliant
Labs or any device manufacturer. See `THIRD_PARTY_NOTICES.md` for dependency guidance.
