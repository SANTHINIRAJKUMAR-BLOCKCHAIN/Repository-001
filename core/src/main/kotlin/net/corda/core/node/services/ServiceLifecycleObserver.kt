package net.corda.core.node.services

import java.lang.Exception

/**
 * Specifies that given [CordaService] is interested to know about important milestones of Corda Node lifecycle and potentially react to them.
 * Subscription can be performed via [net.corda.core.node.AppServiceHub].
 */
interface ServiceLifecycleObserver {
    /**
     * A handler for [ServiceLifecycleEvent]s.
     * Default implementation does nothing.
     */
    fun onServiceLifecycleEvent(event: ServiceLifecycleEvent) {}
}

enum class ServiceLifecycleEvent {
    /**
     * This event is dispatched when Corda Node is fully started such that [net.corda.core.node.AppServiceHub] available
     * for [CordaService] to be use.
     *
     * If a handler for this event throws [CordaServiceCriticalFailureException] - this is the way to flag that it will not make
     * sense for Corda node to continue its operation. The lifecycle events dispatcher will endeavor to terminate node's JVM as soon
     * as practically possible.
     */
    NODE_STARTED,

    /**
     * Notification to inform that Corda Node is shutting down. In response to this event [CordaService] may perform clean-up of some critical
     * resources.
     */
    NODE_SHUTTING_DOWN
}

class CordaServiceCriticalFailureException(message : String, cause: Throwable?) : Exception(message, cause) {
    constructor(message : String) : this(message, null)
}