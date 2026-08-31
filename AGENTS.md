# Agent Senses — Agent Guide

## What this project is

A device-neutral sense layer for agent-driven wearable interfaces. It defines a pure Kotlin `SensesDevice` contract for microphone capture, photo capture, speaker playback, display presentation, input events, and battery status, with two implementations: a physical Halo BLE adapter and a deterministic in-memory simulator.

## How to work with it

1. **Read the README** for the architecture diagram and module layout.
2. **Use the simulator** for contract, adapter, and workflow tests — never touch BLE in unit tests.
3. **Use the Android module** for the physical Halo backend; it depends on `graphic-engine-halo` for BLE transport and scene compilation.
4. **Keep `core` pure** — no Android, BLE, Ktor, Node, Python, firmware, or model types in `core/`.

## Common commands

```sh
# Kotlin/JVM modules (no Android SDK required)
gradle :core:test
gradle :simulator:test
gradle :halo:build

# Android library (requires Android SDK with compileSdk = 36)
gradle :android:assembleDebug
gradle :android:testDebugUnitTest
```

The `graphic-engine-halo` composite build is included automatically via `settings.gradle.kts`. Override its location with `-PhaloEngineDir=<path>`.

## Project rules

- `SensesDevice` is the only contract; never add backend-specific methods to it. New capabilities go through new `SensesDevice` methods with DTOs in `core`.
- All failures surface as `SensesError` with a `Category` discriminant. Callers switch on the category; never string-match on error messages.
- The simulator is deterministic. Use `ScenarioFixtures` for common paths and `VirtualTimeSource` with a `StandardTestDispatcher` for virtual-time tests.
- `HaloConnectionManager` owns the BLE connection lifecycle and the operation mutex. Streaming capabilities go through `HaloSession.collect` or `HaloSession.requestResponse`, not hand-rolled collection loops.
- Speaker playback uses receiver-paced `sendAudioFrame` with `delay` between frames; do not remove the pacing or the firmware input queue overflows.
- Microphone PCM is wrapped as 16 kHz mono 16-bit WAV via `PcmToWav` before returning to the agent; raw PCM is never returned.
- Photo capture is fixed at 640×640 JPEG by the current firmware profile; pan and raw are rejected, not silently ignored.
- HRP and Lua presentation formats are defined in `PresentationFormat` but not yet wired in the Halo backend; they throw `SensesError.Unavailable`.
