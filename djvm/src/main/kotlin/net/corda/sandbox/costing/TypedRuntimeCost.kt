package net.corda.sandbox.costing

/**
 * Cost metric to be used in a sandbox environment. The metric has a threshold and a mechanism for reporting violations.
 * The implementation assumes that each metric is tracked on a per-thread basis, i.e., that each sandbox runs on its own
 * thread.
 *
 * @param initialValue The initial value of this metric.
 * @property thresholdPredicate A delegate for determining whether a threshold has been reached or not.
 * @property errorMessage A delegate for generating an error message based on the thread it was reported from.
 */
open class TypedRuntimeCost<T>(
        initialValue: T,
        private val thresholdPredicate: (T) -> Boolean,
        private val errorMessage: (Thread) -> String
) {

    /**
     * The thread-local container for the cost accumulator.
     */
    private val costValue = object : ThreadLocal<T>() {
        override fun initialValue() = initialValue
    }

    /**
     * Property getter for accessing the current accumulated cost.
     */
    val value: T
        get() = costValue.get()

    /**
     * Helper function for doing a guarded increment of the cost value, with a mechanism for consistent error reporting
     * and nuking of the current thread environment if threshold breaches are encountered.
     */
    protected fun incrementAndCheck(increment: (T) -> T) {
        val currentThread = getAndCheckThread() ?: return
        val newValue = increment(costValue.get())
        costValue.set(newValue)
        if (thresholdPredicate(newValue)) {
            throw ThresholdViolationException(errorMessage(currentThread))
        }
    }

    /**
     * If [filteredThreads] is specified, this method will filter out those threads whenever threshold constraints are
     * being tested. This can be used to disable cost accounting on a primary thread, for instance.
     */
    private fun getAndCheckThread(): Thread? {
        val currentThread = Thread.currentThread()
        if (filteredThreads.contains(currentThread)) {
            return null
        }
        return currentThread
    }

    companion object {

        /**
         * A set of threads to which cost accounting will be disabled.
         */
        private val filteredThreads: List<Thread> = emptyList()

    }

}
