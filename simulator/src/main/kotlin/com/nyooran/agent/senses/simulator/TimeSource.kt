package com.nyooran.agent.senses.simulator

/**
 * Source of wall-clock or virtual time for the simulator.
 *
 * Production runs use [WallClock]; deterministic tests use [VirtualTimeSource].
 */
interface TimeSource {
    fun currentTimeMillis(): Long
}

/** Wall-clock time source. */
object WallClock : TimeSource {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

/** Mutable virtual time source for deterministic tests. */
class VirtualTimeSource(private var current: Long = 0L) : TimeSource {
    override fun currentTimeMillis(): Long = current

    /** Advance the virtual clock by [millis]. */
    fun advance(millis: Long) {
        current += millis
    }
}
