package com.nyooran.agent.senses

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 2 capability-based coordination (C2-06).
 *
 * Replaces the single global operation mutex with per-resource-domain
 * serialization. Operations declare which [SenseCapability] they use;
 * the coordinator checks the endpoint's [ConcurrencyProfile] to decide
 * whether two operations can overlap.
 *
 * Rules (from AD-8):
 * - Operations in the same resource domain are serialized.
 * - Operations in different domains may overlap unless explicitly conflicting.
 * - Gemma inference is scheduled separately from endpoint I/O.
 * - Combined outputs return one result per modality.
 * - Replacement behavior is per-resource, not global.
 */

/**
 * Coordinates concurrent operations across endpoints.
 *
 * Each endpoint has its own set of resource-domain locks. Operations
 * on different endpoints never block each other.
 */
class CapabilityCoordinator {

    /** Per-endpoint, per-domain locks. */
    private val domainLocks = ConcurrentHashMap<EndpointId, MutableMap<String, Mutex>>()

    /** Per-endpoint, per-capability semaphores for maxConcurrent limits. */
    private val concurrencySemaphores = ConcurrentHashMap<EndpointId, MutableMap<SenseCapability, Semaphore>>()

    /**
     * Execute [block] while holding the resource-domain lock for
     * [capability] on [endpoint].
     *
     * If another operation using a conflicting capability is in progress
     * on the same endpoint, this call suspends until that operation completes.
     */
    suspend fun <T> withCapability(
        endpoint: SenseEndpoint,
        capability: SenseCapability,
        block: suspend () -> T,
    ): T {
        val profile = endpoint.profile
        val endpointId = profile.endpointId
        val concurrency = profile.concurrency

        // Collect all domains this capability conflicts with:
        // its own domain, plus domains of explicitly conflicting capabilities.
        val ownDomain = concurrency.resourceDomains[capability]
        val conflictDomains = mutableListOf<String>()
        if (ownDomain != null) conflictDomains.add(ownDomain)
        for (other in concurrency.resourceDomains.keys) {
            if (other == capability) continue
            if (!concurrency.canOverlap(capability, other)) {
                val otherDomain = concurrency.resourceDomains[other]
                if (otherDomain != null && otherDomain !in conflictDomains) {
                    conflictDomains.add(otherDomain)
                }
            }
        }

        // Acquire all conflict-domain locks in sorted order to prevent deadlock
        val locks = conflictDomains.sorted().map { getDomainLock(endpointId, it) }

        // Acquire concurrency semaphore if maxConcurrent is set
        val semaphore = getConcurrencySemaphore(endpointId, capability, profile)

        return acquireAll(locks) {
            if (semaphore != null) semaphore.withPermit { block() }
            else block()
        }
    }

    /**
     * Execute [block] for a combined operation using multiple capabilities
     * on the same endpoint. All required domain locks and concurrency permits
     * are acquired before the block executes, in a deterministic order to
     * prevent deadlock.
     */
    suspend fun <T> withCombinedCapabilities(
        endpoint: SenseEndpoint,
        capabilities: Set<SenseCapability>,
        block: suspend () -> T,
    ): T {
        val profile = endpoint.profile
        val endpointId = profile.endpointId
        val domains = capabilities.mapNotNull { profile.concurrency.resourceDomains[it] }.toSet().sorted()

        if (domains.isEmpty() && capabilities.all { getConcurrencySemaphore(endpointId, it, profile) == null }) {
            return block()
        }

        // Acquire all domain locks in sorted order to prevent deadlock
        val locks = domains.map { getDomainLock(endpointId, it) }
        // Acquire one permit per limited capability, ordered by capability name
        val permits = capabilities
            .mapNotNull { cap -> getConcurrencySemaphore(endpointId, cap, profile)?.let { cap to it } }
            .sortedBy { (cap, _) -> cap.toString() }

        return acquireAll(locks) {
            withAllPermits(permits) { block() }
        }
    }

    private fun getDomainLock(endpointId: EndpointId, domain: String): Mutex {
        return domainLocks.getOrPut(endpointId) { ConcurrentHashMap() }.getOrPut(domain) { Mutex() }
    }

    private fun getConcurrencySemaphore(
        endpointId: EndpointId,
        capability: SenseCapability,
        profile: SenseProfile,
    ): Semaphore? {
        val limits = profile.limitsFor(capability) ?: return null
        val max = limits.maxConcurrent ?: return null
        if (max <= 0) return null
        return concurrencySemaphores.getOrPut(endpointId) { ConcurrentHashMap() }
            .getOrPut(capability) { Semaphore(max) }
    }

    /** Recursively acquire all locks in order, then execute block. */
    private suspend fun <T> acquireAll(locks: List<Mutex>, block: suspend () -> T): T {
        if (locks.isEmpty()) return block()
        return locks[0].withLock {
            acquireAll(locks.drop(1), block)
        }
    }

    /** Recursively acquire all semaphores in order, then execute block. */
    private suspend fun <T> withAllPermits(permits: List<Pair<SenseCapability, Semaphore>>, block: suspend () -> T): T {
        if (permits.isEmpty()) return block()
        val (_, semaphore) = permits[0]
        return semaphore.withPermit {
            withAllPermits(permits.drop(1), block)
        }
    }

    /** Clear all locks for an endpoint (e.g. on disconnect). */
    fun clearEndpoint(endpointId: EndpointId) {
        domainLocks.remove(endpointId)
        concurrencySemaphores.remove(endpointId)
    }
}
