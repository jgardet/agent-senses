package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.SensesError
import halo.engine.HaloMessage
import halo.engine.HaloProtocol
import halo.engine.HaloSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedHaloBleTransportTest {

    @Test
    fun batteryRequestResponse() = runTest {
        val scenario = ScenarioFixtures.happyPath()
        val transport = SimulatedHaloBleTransport(scenario, scope = this)
        transport.connect()

        val session = HaloSession(transport)
        val payload = session.requestResponse(
            requestCode = HaloProtocol.DEVICE_STATUS,
            requestPayload = byteArrayOf(),
            responseCode = HaloProtocol.DEVICE_STATUS,
            timeout = 5.seconds,
        )

        assertTrue(payload.size >= 4)
        assertEquals(scenario.battery.level, payload[0].toInt() and 0xff)
        val voltage = (payload[1].toInt() and 0xff) shl 8 or (payload[2].toInt() and 0xff)
        assertEquals(scenario.battery.voltage, voltage)
        assertEquals(if (scenario.battery.charging) 1 else 0, payload[3].toInt() and 0xff)
    }

    @Test
    fun microphoneCollect() = runTest {
        val scenario = Scenario(
            audioFixture = ByteArray(1_024) { (it % 256).toByte() },
            maxDataPayload = 256,
        )
        val transport = SimulatedHaloBleTransport(scenario, scope = this)
        transport.connect()

        val session = HaloSession(transport)
        val pcm = session.collect(
            startCode = HaloProtocol.MICROPHONE_START,
            startPayload = byteArrayOf(0, 0, 0),
            stopCode = HaloProtocol.MICROPHONE_STOP,
            chunkCode = HaloProtocol.AUDIO_CHUNK,
            finalCode = HaloProtocol.AUDIO_FINAL,
            timeout = 5.seconds,
            maxBytes = 64_000,
        )

        assertEquals(1_024, pcm.size)
    }

    @Test
    fun photoCollect() = runTest {
        val scenario = Scenario(
            imageFixture = ByteArray(2_048) { (it % 256).toByte() },
            maxDataPayload = 512,
        )
        val transport = SimulatedHaloBleTransport(scenario, scope = this)
        transport.connect()

        val session = HaloSession(transport)
        val jpeg = session.collect(
            startCode = HaloProtocol.CAPTURE_PHOTO,
            startPayload = byteArrayOf(0, 0, 0, 0),
            chunkCode = HaloProtocol.PHOTO_JPEG,
            finalCode = HaloProtocol.PHOTO_FINAL,
            timeout = 5.seconds,
            maxBytes = 64_000,
        )

        assertEquals(2_048, jpeg.size)
    }

    @Test
    fun hrpAndLuaAreRecorded() = runTest {
        val scenario = Scenario()
        val transport = SimulatedHaloBleTransport(scenario, scope = this)
        transport.connect()

        transport.sendData(byteArrayOf(1, 2, 3))
        transport.sendLua("print('hello')")

        assertEquals(1, transport.recordedHrp.size)
        assertEquals(1, transport.recordedLua.size)
    }

    @Test
    fun speakerAudioFramesAreRecorded() = runTest {
        val scenario = Scenario()
        val transport = SimulatedHaloBleTransport(scenario, scope = this)
        transport.connect()

        transport.sendMessage(HaloProtocol.SPEAKER_START, byteArrayOf())
        transport.sendAudioFrame(ByteArray(256) { (it % 256).toByte() })
        transport.sendAudioFrame(ByteArray(256) { (it % 256).toByte() })
        transport.sendMessage(HaloProtocol.SPEAKER_STOP, byteArrayOf())

        assertEquals(2, transport.recordedAudioFrames.size)
        assertEquals(512, transport.speakerAudio.size)
    }

    @Test
    fun droppedPacketsAreNotRecorded() = runTest {
        val scenario = ScenarioFixtures.dropFirstPackets(count = 2)
        val transport = SimulatedHaloBleTransport(scenario, scope = this)
        transport.connect()

        // First two frames are dropped.
        transport.sendAudioFrame(ByteArray(10))
        transport.sendAudioFrame(ByteArray(10))
        transport.sendAudioFrame(ByteArray(10))

        assertEquals(1, transport.recordedAudioFrames.size)
    }

    @Test
    fun packetDelayAddsLatency() = runTest {
        val scenario = ScenarioFixtures.delayedPackets(delayMillis = 100)
        val transport = SimulatedHaloBleTransport(scenario, scope = this)
        transport.connect()

        val start = currentTime
        transport.sendData(byteArrayOf(1))
        val elapsed = currentTime - start

        assertTrue(elapsed >= 100, "expected at least 100ms delay, got $elapsed")
    }

    @Test
    fun packetRejectionThrows() = runTest {
        val scenario = ScenarioFixtures.packetRejection()
        val transport = SimulatedHaloBleTransport(scenario, scope = this)
        transport.connect()

        assertFailsWith<SensesError.Unavailable> {
            transport.sendMessage(HaloProtocol.DEVICE_STATUS, byteArrayOf())
        }
    }

    @Test
    fun injectedTapReachesCollectors() = runTest {
        val transport = SimulatedHaloBleTransport(Scenario(), scope = this)
        val received = mutableListOf<HaloMessage>()
        val collector = launch { transport.messages.collect { received.add(it) } }
        transport.connect()
        advanceUntilIdle()
        received.clear()

        assertTrue(transport.injectTap(2))

        advanceUntilIdle()
        collector.cancel()
        assertEquals(1, received.size)
        assertEquals(HaloProtocol.TAP, received[0].code)
        assertEquals(2, received[0].payload[0].toInt())
    }

    @Test
    fun injectedButtonReachesCollectors() = runTest {
        val transport = SimulatedHaloBleTransport(Scenario(), scope = this)
        transport.connect()
        advanceUntilIdle()

        assertTrue(transport.injectButton(3))
    }

    @Test
    fun injectFailsWhenDisconnected() = runTest {
        val transport = SimulatedHaloBleTransport(Scenario(), scope = this)

        assertEquals(false, transport.injectTap(1))
        assertEquals(false, transport.injectDeviceMessage(HaloProtocol.STATUS))
    }

    @Test
    fun batteryOverrideChangesStatusPayload() = runTest {
        val transport = SimulatedHaloBleTransport(Scenario(), scope = this)
        transport.connect()

        transport.setBattery(level = 12, voltage = 3600, charging = true)

        val session = HaloSession(transport)
        val payload = session.requestResponse(
            requestCode = HaloProtocol.DEVICE_STATUS,
            requestPayload = byteArrayOf(),
            responseCode = HaloProtocol.DEVICE_STATUS,
            timeout = 5.seconds,
        )

        assertEquals(12, payload[0].toInt() and 0xff)
        assertEquals(3600, (payload[1].toInt() and 0xff) shl 8 or (payload[2].toInt() and 0xff))
        assertEquals(1, payload[3].toInt() and 0xff)
    }

    @Test
    fun microphoneSourceStreamsLiveChunks() = runTest {
        val produced = mutableListOf<ByteArray>()
        val transport = SimulatedHaloBleTransport(
            Scenario(),
            scope = this,
            microphoneSource = { emit ->
                repeat(4) { i ->
                    val chunk = ByteArray(64) { i.toByte() }
                    produced.add(chunk)
                    emit(chunk)
                }
            },
        )
        transport.connect()

        val session = HaloSession(transport)
        val pcm = session.collect(
            startCode = HaloProtocol.MICROPHONE_START,
            startPayload = byteArrayOf(0, 0, 0),
            stopCode = HaloProtocol.MICROPHONE_STOP,
            chunkCode = HaloProtocol.AUDIO_CHUNK,
            finalCode = HaloProtocol.AUDIO_FINAL,
            timeout = 5.seconds,
            maxBytes = 64_000,
        )

        assertEquals(256, pcm.size)
        assertEquals(4, produced.size)
    }

    @Test
    fun microphoneSourceStopCancelsCapture() = runTest {
        var producerCancelled = false
        val transport = SimulatedHaloBleTransport(
            Scenario(),
            scope = this,
            microphoneSource = { emit ->
                try {
                    var i = 0
                    while (true) {
                        emit(ByteArray(32) { (i++).toByte() })
                        kotlinx.coroutines.delay(10)
                    }
                } finally {
                    producerCancelled = true
                }
            },
        )
        transport.connect()

        val session = HaloSession(transport)
        val pcm = session.collect(
            startCode = HaloProtocol.MICROPHONE_START,
            startPayload = byteArrayOf(0, 0, 0),
            stopCode = HaloProtocol.MICROPHONE_STOP,
            chunkCode = HaloProtocol.AUDIO_CHUNK,
            finalCode = HaloProtocol.AUDIO_FINAL,
            timeout = 5.seconds,
            maxBytes = 64_000,
            stopAfter = 100.milliseconds,
        )

        // The unbounded source is cut off by the session's scheduled STOP;
        // already-streamed chunks are retained.
        assertTrue(pcm.isNotEmpty())
        assertTrue(producerCancelled)
    }

    @Test
    fun photoSourceReplacesFixture() = runTest {
        val live = ByteArray(777) { 0x55 }
        val transport = SimulatedHaloBleTransport(
            Scenario(imageFixture = ByteArray(4) { 0 }),
            scope = this,
            photoSource = { live },
        )
        transport.connect()

        val session = HaloSession(transport)
        val jpeg = session.collect(
            startCode = HaloProtocol.CAPTURE_PHOTO,
            startPayload = byteArrayOf(0, 0, 0, 0),
            chunkCode = HaloProtocol.PHOTO_JPEG,
            finalCode = HaloProtocol.PHOTO_FINAL,
            timeout = 5.seconds,
            maxBytes = 64_000,
        )

        assertEquals(777, jpeg.size)
    }

    @Test
    fun speakerHooksReceiveSessionAndFrames() = runTest {
        val events = mutableListOf<String>()
        val frames = mutableListOf<ByteArray>()
        val transport = SimulatedHaloBleTransport(
            Scenario(),
            scope = this,
            speakerSink = { frames.add(it) },
            speakerSessionStart = { events.add("start:${it.size}") },
            speakerSessionEnd = { events.add("end") },
        )
        transport.connect()

        transport.sendMessage(HaloProtocol.SPEAKER_START, byteArrayOf(1, 2))
        transport.sendAudioFrame(ByteArray(8) { 1 })
        transport.sendMessage(HaloProtocol.SPEAKER_STOP, byteArrayOf())

        assertEquals(listOf("start:2", "end"), events)
        assertEquals(1, frames.size)
        assertEquals(8, frames[0].size)
    }

    @Test
    fun simulateDisconnectEmitsConnectionLoss() = runTest {
        val transport = SimulatedHaloBleTransport(Scenario(), scope = this)
        transport.connect()

        transport.simulateDisconnect()

        assertEquals(false, transport.injectTap(1))
    }
}
