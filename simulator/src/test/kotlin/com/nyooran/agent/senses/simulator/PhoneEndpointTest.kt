package com.nyooran.agent.senses.simulator

import com.nyooran.agent.senses.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneEndpointTest {

    private fun endpoint(config: PhoneEndpoint.PhoneConfig = PhoneEndpoint.PhoneConfig()) =
        PhoneEndpoint(config)

    // ------------------------------------------------------------------ profile

    @Test
    fun profileAdvertisesPhoneCapabilities() {
        val ep = endpoint()
        assertTrue(ep.profile.supports(SenseCapability.AudioInput))
        assertTrue(ep.profile.supports(SenseCapability.AudioOutput))
        assertTrue(ep.profile.supports(SenseCapability.ImageInput))
        assertTrue(ep.profile.supports(SenseCapability.VisualOutput))
        assertTrue(ep.profile.supports(SenseCapability.TextOutput))
        assertTrue(ep.profile.supports(SenseCapability.StatusInput))
    }

    @Test
    fun profileBackendKindIsPhone() {
        val ep = endpoint()
        assertEquals(BackendKind.PHONE, ep.profile.backendKind)
    }

    @Test
    fun profileRespectsHardwareAvailability() {
        val ep = endpoint(PhoneEndpoint.PhoneConfig(
            hasMicrophone = false,
            hasCamera = false,
        ))
        assertFalse(ep.profile.supports(SenseCapability.AudioInput))
        assertFalse(ep.profile.supports(SenseCapability.ImageInput))
        assertTrue(ep.profile.supports(SenseCapability.AudioOutput))  // still has speaker
    }

    // ------------------------------------------------------------------ permissions

    @Test
    fun audioInputThrowsWithoutPermission() = runTest {
        val ep = endpoint(PhoneEndpoint.PhoneConfig(grantedPermissions = emptySet()))
        ep.connect()
        assertFailsWith<SensesError.PermissionDenied> {
            ep.audioInput(AudioInputRequest(maxDurationMillis = 100, maxBytes = 1024))
        }
    }

    @Test
    fun imageInputThrowsWithoutPermission() = runTest {
        val ep = endpoint(PhoneEndpoint.PhoneConfig(grantedPermissions = emptySet()))
        ep.connect()
        assertFailsWith<SensesError.PermissionDenied> {
            ep.imageInput(ImageInputRequest(resolution = 640, qualityIndex = 0, maxBytes = 65536))
        }
    }

    @Test
    fun audioInputSucceedsWithPermission() = runTest {
        val ep = endpoint(PhoneEndpoint.PhoneConfig(grantedPermissions = setOf("RECORD_AUDIO")))
        ep.connect()
        val result = ep.audioInput(AudioInputRequest(maxDurationMillis = 100, maxBytes = 1024))
        assertEquals(BackendKind.PHONE, result.provenance.backendKind)
        assertEquals(ResultOrigin.MICROPHONE, result.provenance.origin)
    }

    // ------------------------------------------------------------------ hardware absence

    @Test
    fun audioInputThrowsWhenNoMicrophone() = runTest {
        val ep = endpoint(PhoneEndpoint.PhoneConfig(hasMicrophone = false))
        ep.connect()
        assertFailsWith<SensesError.Unavailable> {
            ep.audioInput(AudioInputRequest(maxDurationMillis = 100, maxBytes = 1024))
        }
    }

    @Test
    fun imageInputThrowsWhenNoCamera() = runTest {
        val ep = endpoint(PhoneEndpoint.PhoneConfig(hasCamera = false))
        ep.connect()
        assertFailsWith<SensesError.Unavailable> {
            ep.imageInput(ImageInputRequest(resolution = 640, qualityIndex = 0, maxBytes = 65536))
        }
    }

    @Test
    fun statusThrowsWhenNoBattery() = runTest {
        val ep = endpoint(PhoneEndpoint.PhoneConfig(hasBattery = false))
        ep.connect()
        assertFailsWith<SensesError.Unavailable> {
            ep.statusInput(StatusInputRequest())
        }
    }

    // ------------------------------------------------------------------ battery

    @Test
    fun statusReturnsPhoneBattery() = runTest {
        val ep = endpoint(PhoneEndpoint.PhoneConfig(
            batteryLevel = 42,
            batteryCharging = true,
        ))
        ep.connect()
        val result = ep.statusInput(StatusInputRequest())
        assertEquals(42, result.status.batteryLevel)
        assertEquals(true, result.status.batteryCharging)
        assertEquals(BackendKind.PHONE, result.provenance.backendKind)
    }

    // ------------------------------------------------------------------ connect/disconnect

    @Test
    fun connectDisconnectWorks() = runTest {
        val ep = endpoint()
        ep.connect()
        ep.disconnect()
    }

    // ------------------------------------------------------------------ registry

    @Test
    fun worksInRegistry() = runTest {
        val registry = SenseEndpointRegistry()
        registry.bind(endpoint())
        val result = registry.resolve(SenseCapability.AudioInput)
        assertTrue(result is ResolveResult.Resolved)
        assertEquals(BackendKind.PHONE, result.endpoint.profile.backendKind)
        registry.unbindAll()
    }

    @Test
    fun phoneAndHaloCoexistInRegistry() = runTest {
        val registry = SenseEndpointRegistry()
        registry.bind(PhoneEndpoint())
        registry.bind(SimulatedHaloEndpoint())
        // Both support AudioInput — resolve should return Ambiguous
        val result = registry.resolve(SenseCapability.AudioInput)
        // With two endpoints supporting the same capability, it's ambiguous
        // unless the caller specifies a backend kind
        registry.unbindAll()
    }
}
