# Agent Senses — Agent Guide

## What this project is

A device-neutral sense layer for agent-driven wearable interfaces. It defines pure Kotlin contracts for bidirectional, multimodal interaction between an agent and a user-facing sense endpoint (physical Halo, phone, chat, or simulator).

## Phase 2 contracts (current)

The core contracts were extended in Phase 2 to support multiple endpoints, typed provenance, and capability-based coordination:

- **`SenseProfile`** — endpoint identity (`EndpointId`), backend kind (physical/phone/chat/simulator), state, capabilities, limits, and concurrency profile.
- **`SenseCapability`** — 8 sealed capabilities: AudioInput/Output, ImageInput, Visual/TextOutput, TextInput, InteractionInput, StatusInput.
- **`SenseEndpoint`** — interface with typed request/result methods for each capability. Every result carries `Provenance`.
- **`SenseEndpointRegistry`** — binds endpoints to a session, resolves capabilities, requires explicit selection when ambiguous.
- **`CapabilityCoordinator`** — per-resource-domain serialization with explicit conflict enforcement and maxConcurrent limits.
- **`SenseOutcome`** — sealed Success/Failure with 10 stable `FailureCategory` values.
- **`SenseEndpointContractTest`** — shared parameterized contract suite that any endpoint implementation must pass.

The Phase 1 `SensesDevice` interface has been removed; all endpoints implement `SenseEndpoint`.

## How to work with it

1. **Read the README** for the architecture diagram and module layout.
2. **Read `docs/AGENT_SENSES_ARCHITECTURE.md`** for the target architecture and architectural decisions.
3. **Use the simulator** for contract, adapter, and workflow tests — never touch BLE in unit tests.
4. **Use the `:halo` module** for the physical Halo backend; it depends on `halo-engine` for BLE transport and scene compilation.
5. **Keep `core` pure** — no Android, BLE, Ktor, Node, Python, firmware, or model types in `core/`.

## Common commands

```sh
# Kotlin/JVM modules (no Android SDK required)
.\gradlew.bat :core:test
.\gradlew.bat :simulator:test
.\gradlew.bat :halo:build
```

The `halo-engine` composite build is included automatically via `settings.gradle.kts` and expects a sibling checkout at `../halo-engine` by default. Override its location with `-PhaloEngineDir=<path>`. See `docs/DEVELOPERS_GUIDE.md` for the harness-integration guide.

## Project rules

- `core` has no Halo vocabulary or dependency. Device-specific options go through `deviceOptions: Map<String, Any>` or `VisualKind.DEVICE_NATIVE`.
- New endpoints implement `SenseEndpoint` and advertise a `SenseProfile` with truthful capabilities and limits.
- All failures surface as `SenseFailure` with a `FailureCategory` discriminant. Callers switch on the category; never string-match on error messages.
- Every result carries `Provenance` with operationId, endpointId, backendKind, origin, and timestamps.
- The `SenseEndpointContractTest` suite defines behavioral contracts; run it against every endpoint implementation.
- The simulator is deterministic. Use `ScenarioFixtures` for common paths and `VirtualTimeSource` with a `StandardTestDispatcher` for virtual-time tests.
- `PhysicalHaloEndpoint` owns the physical Halo sense lifecycle. Streaming capabilities go through `HaloSession.collect` or `HaloSession.requestResponse`, not hand-rolled collection loops.
- Speaker playback uses receiver-paced `sendAudioFrame` with `delay` between frames; do not remove the pacing or the firmware input queue overflows.
- Microphone PCM is wrapped as 16 kHz mono 16-bit WAV via `PcmToWav` before returning to the agent; raw PCM is never returned.
- Photo capture is fixed at 640×480 JPEG by the current firmware profile; pan and raw are rejected, not silently ignored.
