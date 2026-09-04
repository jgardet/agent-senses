package com.nyooran.agent.senses

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Phase 2 C2-08: Interaction filtering and broadcast.
 *
 * [InteractionFilter] matches incoming [InteractionEvent]s against the
 * accepted gestures in an [InteractionInputRequest]. Events that don't
 * match are silently dropped (not delivered to the waiting caller).
 *
 * [InteractionBroadcaster] broadcasts interaction events to multiple
 * subscribers (agent waiters, diagnostics, UI) without destructive races.
 * Each subscriber gets its own copy of events via a [SharedFlow].
 */

/**
 * Maps an [InteractionEvent] to its [InteractionType], or null if unmappable.
 */
fun InteractionEvent.toInteractionType(): InteractionType? = when (this) {
    is InteractionEvent.Tap -> when (gesture) {
        TapGesture.SINGLE -> InteractionType.TAP_SINGLE
        TapGesture.DOUBLE -> InteractionType.TAP_DOUBLE
        TapGesture.TRIPLE -> InteractionType.TAP_TRIPLE
    }
    is InteractionEvent.Button -> when (gesture) {
        ButtonGesture.SINGLE -> InteractionType.BUTTON_SINGLE
        ButtonGesture.DOUBLE -> InteractionType.BUTTON_DOUBLE
        ButtonGesture.LONG -> InteractionType.BUTTON_LONG
    }
    is InteractionEvent.Approval -> InteractionType.APPROVAL
    is InteractionEvent.Selection -> InteractionType.SELECTION
    is InteractionEvent.TextEntry -> InteractionType.TEXT_ENTRY
    InteractionEvent.Disconnected -> null
}

/**
 * Returns true if [event] matches the accepted gestures in [request].
 *
 * If [request.acceptedGestures] is empty, all events are accepted.
 */
fun InteractionFilter(event: InteractionEvent, request: InteractionInputRequest): Boolean {
    if (request.acceptedGestures.isEmpty()) return true
    val type = event.toInteractionType() ?: return false
    return type in request.acceptedGestures
}

/**
 * Broadcasts interaction events to multiple subscribers.
 *
 * Uses a [SharedFlow] with replay=0 so late subscribers don't receive
 * old events. Each subscriber collects independently without affecting
 * others — no destructive races.
 */
class InteractionBroadcaster(
    /** Extra buffer capacity for the SharedFlow. */
    extraBufferCapacity: Int = 64,
) {
    private val _events = MutableSharedFlow<InteractionEvent>(
        replay = 0,
        extraBufferCapacity = extraBufferCapacity,
    )

    /** Observable stream of interaction events. */
    val events: SharedFlow<InteractionEvent> = _events.asSharedFlow()

    /** Total number of events broadcast. */
    @Volatile
    var totalBroadcast: Int = 0
        private set

    /**
     * Broadcast [event] to all subscribers.
     */
    suspend fun broadcast(event: InteractionEvent) {
        totalBroadcast++
        _events.emit(event)
    }

    /**
     * Try to broadcast [event] without suspending.
     * Returns true if emitted, false if the buffer is full.
     */
    fun tryBroadcast(event: InteractionEvent): Boolean {
        totalBroadcast++
        return _events.tryEmit(event)
    }
}
