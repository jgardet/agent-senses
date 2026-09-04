package com.nyooran.agent.senses

/**
 * Phase 5 L5-03: Release-safe endpoint binding.
 *
 * Makes endpoint binding explicit and visible. Provides a release-safety
 * check that throws if simulator classes are found on the classpath in
 * a release build.
 *
 * The simulator must never be packaged, selected, or fallen back to in
 * a release build. This is enforced by:
 *   1. The `android` module's build.gradle.kts must NOT depend on `:simulator`
 *   2. [ReleaseSafetyCheck] verifies at runtime that simulator classes
 *      are not on the classpath when [isRelease] is true
 *   3. [EndpointBinder] makes binding explicit — no hidden auto-selection
 *
 * Debug-only endpoint binding helpers live in debug source sets, not in
 * the main source set.
 */

/**
 * Checks that simulator classes are not on the classpath in release builds.
 * Call this early in startup — if it throws, the build is misconfigured.
 */
object ReleaseSafetyCheck {

    private val simulatorClassNames = listOf(
        "com.nyooran.agent.senses.simulator.SimulatedHaloEndpoint",
        "com.nyooran.agent.senses.simulator.FixtureEndpoint",
        "com.nyooran.agent.senses.ChatEndpoint",
        "com.nyooran.agent.senses.orchestration.SemanticSenseWorkflows",
    )

    /**
     * Verifies that no simulator classes are on the classpath.
     * Throws [ReleaseSafetyException] if any simulator class is found.
     *
     * @param isRelease true for release builds, false for debug
     */
    fun verify(isRelease: Boolean) {
        if (!isRelease) return
        val found = simulatorClassNames.filter { className ->
            try {
                Class.forName(className, false, this::class.java.classLoader)
                true
            } catch (e: ClassNotFoundException) {
                false
            }
        }
        if (found.isNotEmpty()) {
            throw ReleaseSafetyException(
                "Simulator classes found on release classpath: $found. " +
                    "The :simulator module must not be a dependency of release builds.",
            )
        }
    }

    /**
     * Returns true if any simulator class is on the classpath.
     * Useful for debug builds that want to warn if the simulator is present.
     */
    fun isSimulatorPresent(): Boolean = simulatorClassNames.any { className ->
        try {
            Class.forName(className, false, this::class.java.classLoader)
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}

/**
 * Thrown when simulator classes are found on the release classpath.
 * This indicates a build configuration error.
 */
class ReleaseSafetyException(message: String) : RuntimeException(message)

/**
 * Explicit endpoint binding API.
 *
 * Makes endpoint binding visible and auditable. No hidden auto-selection
 * based on BuildConfig.DEBUG. The caller must explicitly decide which
 * endpoints to bind.
 *
 * Usage in production:
 * ```
 * val binder = EndpointBinder(runtime)
 * binder.bind(haloEndpoint)  // explicit
 * ```
 *
 * Usage in debug (from debug source set):
 * ```
 * val binder = EndpointBinder(runtime)
 * if (BuildConfig.DEBUG) {
 *     binder.bindSimulator()  // debug-only helper
 * }
 * ```
 */
class EndpointBinder(private val runtime: SenseRuntime) {

    /**
     * Binds an endpoint to the runtime's registry.
     * Explicit and visible — no auto-selection.
     */
    suspend fun bind(endpoint: SenseEndpoint) {
        runtime.registry.bind(endpoint)
    }

    /**
     * Unbinds an endpoint by ID.
     */
    suspend fun unbind(endpointId: EndpointId) {
        runtime.registry.unbind(endpointId)
    }

    /**
     * Lists all currently bound endpoints.
     */
    fun boundEndpoints(): List<SenseProfile> = runtime.registry.endpointProfiles.value

    /**
     * Verifies that the current binding state is release-safe.
     * Throws [ReleaseSafetyException] if a simulator endpoint is bound
     * in a release build.
     *
     * @param isRelease true for release builds
     */
    fun verifyReleaseSafe(isRelease: Boolean) {
        if (!isRelease) return
        val bound = boundEndpoints()
        val simulatorEndpoints = bound.filter { profile ->
            profile.backendKind == BackendKind.SIMULATOR
        }
        if (simulatorEndpoints.isNotEmpty()) {
            throw ReleaseSafetyException(
                "Simulator endpoints are bound in a release build: " +
                    simulatorEndpoints.map { it.endpointId.value },
            )
        }
    }
}
