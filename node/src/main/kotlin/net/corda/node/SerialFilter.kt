package net.corda.node

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy

internal object SerialFilter {
    private val filterInterface: Class<*>
    private val serialClassGetter: Method
    private val undecided: Any
    private val rejected: Any
    private val serialFilterLock: Any
    private val serialFilterField: Field

    init {
        // ObjectInputFilter and friends are in java.io in Java 9 but sun.misc in backports:
        fun getFilterInterface(packageName: String): Class<*>? {
            return try {
                Class.forName("$packageName.ObjectInputFilter")
            } catch (e: ClassNotFoundException) {
                null
            }
        }
        // JDK 8u121 is the earliest JDK8 JVM that supports this functionality.
        filterInterface = getFilterInterface("java.io")
                ?: getFilterInterface("sun.misc")
                ?: failStartUp("Corda forbids Java deserialisation. Please upgrade to at least JDK 8u121.")
        serialClassGetter = Class.forName("${filterInterface.name}\$FilterInfo").getMethod("serialClass")
        val statusEnum = Class.forName("${filterInterface.name}\$Status")
        undecided = statusEnum.getField("UNDECIDED").get(null)
        rejected = statusEnum.getField("REJECTED").get(null)
        val configClass = Class.forName("${filterInterface.name}\$Config")
        serialFilterLock = configClass.getDeclaredField("serialFilterLock").also { it.isAccessible = true }.get(null)
        serialFilterField = configClass.getDeclaredField("serialFilter").also { it.isAccessible = true }
    }

    internal fun install(acceptClass: (Class<*>) -> Boolean) {
        val filter = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(filterInterface)) { _, _, args ->
            val serialClass = serialClassGetter.invoke(args[0]) as Class<*>?
            if (applyPredicate(acceptClass, serialClass)) {
                undecided
            } else {
                rejected
            }
        }
        // Can't simply use the setter as in non-trampoline mode Capsule has inited the filter in premain:
        synchronized(serialFilterLock) {
            serialFilterField.set(null, filter)
        }
    }

    internal fun applyPredicate(acceptClass: (Class<*>) -> Boolean, serialClass: Class<*>?): Boolean {
        // Similar logic to jdk.serialFilter, our concern is side-effects at deserialisation time:
        if (null == serialClass) return true
        var componentType: Class<*> = serialClass
        while (componentType.isArray) componentType = componentType.componentType
        if (componentType.isPrimitive) return true
        return acceptClass(componentType)
    }
}

internal fun defaultSerialFilter(@Suppress("UNUSED_PARAMETER") clazz: Class<*>) = false
