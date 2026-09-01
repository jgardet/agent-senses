# Agent Senses

**A device-neutral sense layer for agent-driven wearable interfaces.**

Agent Senses provides a portable Kotlin contract for the input and output capabilities a wearable device exposes to an AI agent: microphone capture, photo capture, speaker playback, display presentation, input events, and battery status. The same contract runs against a physical Brilliant Labs Halo BLE backend and a deterministic in-memory simulator, so agent workflows, adapter tests, and UI debugging share one typed surface.

> **Status:** research prototype. The `SensesDevice` contract, the Halo BLE adapter, and the deterministic simulator are implemented and covered by unit tests. Physical-Halo validation, throughput measurements, and Android instrumented tests remain outstanding.

## Problem

An agent that drives a wearable needs to listen, look, speak, present, and feel input without knowing whether the backend is a pair of smart glasses over BLE or a scripted simulator. Coupling agent workflows to transport details produces code that works against hardware but is untestable without it, and code that passes a simulator but fails on the device because the two surfaces drifted.

## Approach

The project separates **what the agent asks for** from **how the device delivers it**:

```text
Agent workflow or application
        │
        ▼
SensesDevice (pure Kotlin contract)
        │
        ├── physical Halo ──► HaloSensesDevice (Android BLE)
        │                        │
        │                        ▼
        │                   HaloConnectionManager
        │                        │
        │                        ▼
        │                   graphic-engine-halo
        │                   (HaloHost, HaloSession, AndroidBleTransport)
        │                        │
        │                        ▼
        │                   Halo Lua runtime and BLE firmware
        │
        └── simulator ──────► SimulatedSensesDevice (in-memory)
                                 │
                                 ▼
                            Scenario fixtures
                            (deterministic, virtual time)
```

`SensesDevice` is a pure Kotlin interface with no Android, BLE, Ktor, Node, Python, firmware, or model types. Implementations are provided by a concrete backend. The same contract powers contract tests, adapter tests, workflow tests, and the debug UI.

### Error model

All failures surface as `SensesError`, a sealed `RuntimeException` with a `Category` discriminant (`Unavailable`, `Disconnected`, `Timeout`, `Cancelled`, `Rejected`, `LimitExceeded`, `Protocol`, `PermissionDenied`, `ModelUnavailable`, `Internal`). Callers switch on the category to decide retry, user feedback, or escalation.

## Modules

| Module | Type | Role |
|--------|------|------|
| `core` | Kotlin/JVM | `SenseEndpoint` contract, `SenseEndpointRegistry`, `SenseProfile`, `SenseCapability`, `SenseFailure`, and audio helpers (`PcmToWav`, `WavReader`). No Android or BLE dependencies. |
| `halo` | Kotlin/JVM | Halo-specific `PhysicalHaloEndpoint` bridging `core` and the `graphic-engine-halo` engine abstractions. |
| `simulator` | Kotlin/JVM | Deterministic `SimulatedHaloEndpoint`, `SimulatedHaloBleTransport`, `Scenario` scripts, `ScenarioFixtures`, and `TimeSource` for virtual-time tests. |

The `graphic-engine-halo` composite build is included via `settings.gradle.kts` and provides `halo.engine:kotlin` and `halo.engine:android` through dependency substitution.

## Quick start

### Prerequisites

- JDK 17
- Android SDK (for the `android` module)
- `graphic-engine-halo` as a sibling directory (or override with `-PhaloEngineDir=<path>`)

### Build

```sh
gradle :core:test
gradle :simulator:test
gradle :halo:build
gradle :android:assembleDebug
```

Kotlin/JVM modules (`core`, `halo`, `simulator`) build and test without an Android SDK. The `android` library target requires `compileSdk = 36` and `minSdk = 33`.

### Run tests

```sh
gradle :core:test
gradle :simulator:test
gradle :android:testDebugUnitTest
```

### Use the simulator

```kotlin
val device = SimulatedSensesDevice(ScenarioFixtures.happyPath())
device.connect(null)
val audio = device.captureAudio(AudioCaptureRequest(maxDurationMillis = 1000, maxBytes = 64_000))
val image = device.captureImage(ImageCaptureRequest(resolution = 256, qualityIndex = 4, maxBytes = 64_000))
val battery = device.battery()
device.disconnect()
```

### Use the physical Halo backend

```kotlin
val backend = DefaultSensesDeviceFactory.create(context, scope)
val device = backend.device
device.connect(DeviceTarget(name = "Halo", address = "AA:BB:CC:DD:EE:FF"))
device.present(DevicePresentation(format = PresentationFormat.HSD, payload = sceneJson.toByteArray()))
device.disconnect()
backend.close()
```

## Capabilities

| Capability | `SensesDevice` method | Halo backend |
|------------|----------------------|--------------|
| Microphone capture | `captureAudio` | `HaloSession.collect` over BLE, PCM 16 kHz mono, WAV-wrapped |
| Photo capture | `captureImage` | `HaloSession.collect` over BLE, JPEG at 640×640 |
| Speaker playback | `playAudio` | `AndroidBleTransport.sendAudioFrame` with receiver-paced pacing |
| Display presentation | `present` / `clearDisplay` | `HaloHost.showScene` (HSD); HRP and Lua formats are defined but not yet wired |
| Input events | `awaitInput` / `events` | Button and tap notifications from the Halo firmware |
| Battery | `battery` | `HaloSession.requestResponse` over the device-status protocol |
| Text-to-speech | `HaloConnectionManager.speak` | Android TTS synthesized to WAV, then streamed as speaker audio |

## Simulator scenarios

`ScenarioFixtures` provides deterministic scripts for common test paths:

| Fixture | Demonstrates |
|---------|-------------|
| `happyPath()` | Fully-featured device, connected immediately |
| `slowConnection(delayMillis)` | Connection takes time to become ready |
| `disconnectDuringOperation(afterMillis)` | Device disconnects mid-operation |
| `oversizedImage(resolution, maxBytes)` | Capture exceeds the request byte limit |
| `deviceError()` | Capture rejects with a simulated subsystem failure |
| `tapAfter(delayMillis, source, gesture)` | A single input event is delivered after a delay |
| `disconnectDuringAudio(...)` | Device disconnects while audio capture is in progress |
| `unsupportedFeatures(features)` | A capability is rejected because it is not in `supportedFeatures` |
| `missingFinalAudio()` | Audio stream ends without a final frame |
| `micSpeakerConflict()` | Playback rejects while capture is active |
| `dropFirstPackets(count)` | The first host packets are silently dropped |
| `delayedPackets(delayMillis)` | Every host packet is delayed |
| `packetRejection(error)` | The transport rejects the next host packet |

For virtual-time tests, pass a `CoroutineScope` backed by a `StandardTestDispatcher` and a `VirtualTimeSource`.

## Repository structure

```text
core/          SensesDevice contract, DTOs, SensesError, InputEvent
halo/          Halo-specific protocol bridges to graphic-engine-halo
android/       Physical Halo BLE adapter, SensesDeviceFactory, audio helpers
simulator/     Deterministic SimulatedSensesDevice, SimulatedHaloBleTransport, Scenario, fixtures
gradle/        Gradle wrapper
```

## Tech stack

- **Kotlin 2.3.0**, **AGP 8.13.0**, **Java 17**
- **kotlinx-coroutines** — suspendible capture, playback, and input flows
- **kotlinx-serialization** — JSON for presentation payloads
- **graphic-engine-halo** (composite build) — `HaloHost`, `HaloSession`, `AndroidBleTransport`, `HsdHrpCompiler`

## Scope and limitations

- HRP and Lua presentation formats are defined in `PresentationFormat` but the Halo backend currently implements only HSD. HRP and Lua throw `SensesError.Unavailable`.
- Camera capture is fixed at 640×640 JPEG; pan and raw capture are rejected by the current firmware profile.
- The simulator is Kotlin/JVM only; it is not a Python emulator and does not render scenes.
- Physical-Halo validation, throughput measurements, and Android instrumented tests are outstanding.

## References

- [Brilliant Labs Halo](https://brilliant.xyz/products/halo)
- [Halo Hardware Manual](https://docs.brilliant.xyz/halo/hardware/)
- [Halo Bluetooth specifications](https://docs.brilliant.xyz/halo/halo-sdk-bluetooth-specs/)
- [graphic-engine-halo](../graphic-engine-halo) — HSD compiler, HRP protocol, and BLE transport
- [dsh-android](../dsh-android) — Android app that wires `agent-senses` to the dsh agent loop

## License

MIT. This is an independent research project and is not affiliated with Brilliant Labs or Garmin.
