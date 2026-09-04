package com.nyooran.agent.senses

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InteractionFilterTest {

    @Test
    fun tapSingleMapsToTapSingle() {
        val event = InteractionEvent.Tap(TapGesture.SINGLE)
        assertEquals(InteractionType.TAP_SINGLE, event.toInteractionType())
    }

    @Test
    fun tapDoubleMapsToTapDouble() {
        val event = InteractionEvent.Tap(TapGesture.DOUBLE)
        assertEquals(InteractionType.TAP_DOUBLE, event.toInteractionType())
    }

    @Test
    fun tapTripleMapsToTapTriple() {
        val event = InteractionEvent.Tap(TapGesture.TRIPLE)
        assertEquals(InteractionType.TAP_TRIPLE, event.toInteractionType())
    }

    @Test
    fun buttonSingleMapsToButtonSingle() {
        val event = InteractionEvent.Button(ButtonGesture.SINGLE)
        assertEquals(InteractionType.BUTTON_SINGLE, event.toInteractionType())
    }

    @Test
    fun buttonLongMapsToButtonLong() {
        val event = InteractionEvent.Button(ButtonGesture.LONG)
        assertEquals(InteractionType.BUTTON_LONG, event.toInteractionType())
    }

    @Test
    fun approvalMapsToApproval() {
        val event = InteractionEvent.Approval(approved = true)
        assertEquals(InteractionType.APPROVAL, event.toInteractionType())
    }

    @Test
    fun selectionMapsToSelection() {
        val event = InteractionEvent.Selection(selectedIndex = 2)
        assertEquals(InteractionType.SELECTION, event.toInteractionType())
    }

    @Test
    fun textEntryMapsToTextEntry() {
        val event = InteractionEvent.TextEntry("hello")
        assertEquals(InteractionType.TEXT_ENTRY, event.toInteractionType())
    }

    @Test
    fun disconnectedMapsToNull() {
        assertNull(InteractionEvent.Disconnected.toInteractionType())
    }

    // ------------------------------------------------------------------ filtering

    @Test
    fun emptyAcceptedGesturesAcceptsAll() {
        val request = InteractionInputRequest(acceptedGestures = emptySet())
        assertTrue(InteractionFilter(InteractionEvent.Tap(TapGesture.SINGLE), request))
        assertTrue(InteractionFilter(InteractionEvent.Button(ButtonGesture.LONG), request))
        assertTrue(InteractionFilter(InteractionEvent.Approval(true), request))
    }

    @Test
    fun specificGestureAccepted() {
        val request = InteractionInputRequest(acceptedGestures = setOf(InteractionType.TAP_SINGLE))
        assertTrue(InteractionFilter(InteractionEvent.Tap(TapGesture.SINGLE), request))
    }

    @Test
    fun nonMatchingGestureRejected() {
        val request = InteractionInputRequest(acceptedGestures = setOf(InteractionType.TAP_SINGLE))
        assertFalse(InteractionFilter(InteractionEvent.Tap(TapGesture.DOUBLE), request))
        assertFalse(InteractionFilter(InteractionEvent.Button(ButtonGesture.SINGLE), request))
    }

    @Test
    fun multipleAcceptedGestures() {
        val request = InteractionInputRequest(
            acceptedGestures = setOf(InteractionType.TAP_SINGLE, InteractionType.BUTTON_LONG)
        )
        assertTrue(InteractionFilter(InteractionEvent.Tap(TapGesture.SINGLE), request))
        assertTrue(InteractionFilter(InteractionEvent.Button(ButtonGesture.LONG), request))
        assertFalse(InteractionFilter(InteractionEvent.Tap(TapGesture.DOUBLE), request))
    }

    @Test
    fun disconnectedEventRejectedWhenGesturesSpecified() {
        val request = InteractionInputRequest(acceptedGestures = setOf(InteractionType.TAP_SINGLE))
        assertFalse(InteractionFilter(InteractionEvent.Disconnected, request))
    }

    @Test
    fun disconnectedEventAcceptedWhenNoGesturesSpecified() {
        val request = InteractionInputRequest(acceptedGestures = emptySet())
        assertTrue(InteractionFilter(InteractionEvent.Disconnected, request))
    }

    // ------------------------------------------------------------------ broadcaster

    @Test
    fun broadcastDeliversToSubscriber() = runTest {
        val broadcaster = InteractionBroadcaster()
        val received = mutableListOf<InteractionEvent>()

        val collector = launch {
            broadcaster.events.collect { received.add(it) }
        }
        runCurrent()

        broadcaster.broadcast(InteractionEvent.Tap(TapGesture.SINGLE))
        runCurrent()

        collector.cancel()
        assertEquals(1, received.size)
        assertTrue(received[0] is InteractionEvent.Tap)
    }

    @Test
    fun broadcastDeliversToMultipleSubscribers() = runTest {
        val broadcaster = InteractionBroadcaster()
        val received1 = mutableListOf<InteractionEvent>()
        val received2 = mutableListOf<InteractionEvent>()

        val c1 = launch { broadcaster.events.collect { received1.add(it) } }
        val c2 = launch { broadcaster.events.collect { received2.add(it) } }
        runCurrent()

        broadcaster.broadcast(InteractionEvent.Tap(TapGesture.SINGLE))
        broadcaster.broadcast(InteractionEvent.Button(ButtonGesture.LONG))
        runCurrent()

        c1.cancel()
        c2.cancel()
        assertEquals(2, received1.size)
        assertEquals(2, received2.size)
    }

    @Test
    fun totalBroadcastCount() = runTest {
        val broadcaster = InteractionBroadcaster()
        val collector = launch { broadcaster.events.collect {} }
        runCurrent()

        broadcaster.broadcast(InteractionEvent.Tap(TapGesture.SINGLE))
        broadcaster.broadcast(InteractionEvent.Approval(true))
        broadcaster.broadcast(InteractionEvent.Selection(0))
        runCurrent()

        collector.cancel()
        assertEquals(3, broadcaster.totalBroadcast)
    }

    @Test
    fun tryBroadcastDoesNotSuspend() = runTest {
        val broadcaster = InteractionBroadcaster()
        // tryBroadcast should work even without a subscriber
        val result = broadcaster.tryBroadcast(InteractionEvent.Tap(TapGesture.SINGLE))
        assertTrue(result)
        assertEquals(1, broadcaster.totalBroadcast)
    }

    @Test
    fun lateSubscriberDoesNotReceiveOldEvents() = runTest {
        val broadcaster = InteractionBroadcaster()
        val collector1 = launch { broadcaster.events.collect {} }
        runCurrent()

        broadcaster.broadcast(InteractionEvent.Tap(TapGesture.SINGLE))
        runCurrent()

        val received2 = mutableListOf<InteractionEvent>()
        val collector2 = launch { broadcaster.events.collect { received2.add(it) } }
        runCurrent()

        broadcaster.broadcast(InteractionEvent.Button(ButtonGesture.SINGLE))
        runCurrent()

        collector1.cancel()
        collector2.cancel()
        // Late subscriber should only get the second event
        assertEquals(1, received2.size)
        assertTrue(received2[0] is InteractionEvent.Button)
    }
}
