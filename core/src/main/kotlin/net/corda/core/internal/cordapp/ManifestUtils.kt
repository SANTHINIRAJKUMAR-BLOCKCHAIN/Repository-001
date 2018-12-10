package net.corda.core.internal.cordapp

import net.corda.core.utilities.loggerFor
import java.util.jar.Manifest

operator fun Manifest.set(key: String, value: String): String? {
    return mainAttributes.putValue(key, value)
}

operator fun Manifest.set(key: Attributes.Name, value: String): Any? {
    return mainAttributes.put(key, value)
}

operator fun Manifest.get(key: String): String? = mainAttributes.getValue(key)

val Manifest.targetPlatformVersion: Int
    get() {
        val minPlatformVersion = this[MIN_PLATFORM_VERSION]?.toIntOrNull() ?: 1
        return this[TARGET_PLATFORM_VERSION]?.toIntOrNull() ?: minPlatformVersion
    }

fun Manifest.toCordappInfo(defaultName: String): CordappInfo {

    val log = loggerFor<Manifest>()

    val minPlatformVersion = this[MIN_PLATFORM_VERSION]?.toIntOrNull() ?: 1
    val targetPlatformVersion = this[TARGET_PLATFORM_VERSION]?.toIntOrNull() ?: minPlatformVersion

    /** new identifiers (Corda 4) */
    // is it a Contract Jar?
    if (this[CORDAPP_CONTRACT_NAME] != null) {
        val name = this[CORDAPP_CONTRACT_NAME] ?: defaultName
        val version = try {
            this[CORDAPP_CONTRACT_VERSION]?.toIntOrNull()
        } catch (nfe: NumberFormatException) {
            log.warn("Invalid version identifier ${this[CORDAPP_CONTRACT_VERSION]}. Defaulting to $DEFAULT_CORDAPP_VERSION")
            DEFAULT_CORDAPP_VERSION
        } ?: DEFAULT_CORDAPP_VERSION
        val vendor = this[CORDAPP_CONTRACT_VENDOR] ?: UNKNOWN_VALUE
        val licence = this[CORDAPP_CONTRACT_LICENCE] ?: UNKNOWN_VALUE
        return Contract(
                name = name,
                vendor = vendor,
                versionId = version,
                licence = licence,
                minimumPlatformVersion = minPlatformVersion,
                targetPlatformVersion = targetPlatformVersion
        )
    }
    // is it a Contract Jar?
    if (this[CORDAPP_WORKFLOW_NAME] != null) {
        val name = this[CORDAPP_WORKFLOW_NAME] ?: defaultName
        val version = try {
            this[CORDAPP_WORKFLOW_VERSION]?.toIntOrNull()
        } catch (nfe: NumberFormatException) {
            log.warn("Invalid version identifier ${this[CORDAPP_CONTRACT_VERSION]}. Defaulting to $DEFAULT_CORDAPP_VERSION")
            DEFAULT_CORDAPP_VERSION
        } ?: DEFAULT_CORDAPP_VERSION
        val vendor = this[CORDAPP_WORKFLOW_VENDOR] ?: UNKNOWN_VALUE
        val licence = this[CORDAPP_WORKFLOW_LICENCE] ?: UNKNOWN_VALUE
        return Workflow(
                name = name,
                vendor = vendor,
                versionId = version,
                licence = licence,
                minimumPlatformVersion = minPlatformVersion,
                targetPlatformVersion = targetPlatformVersion
        )
    }

    /** need to maintain backwards compatibility so use old identifiers if existent */
    val shortName = this["Name"] ?: defaultName
    val vendor = this["Implementation-Vendor"] ?: UNKNOWN_VALUE
    val version = this["Implementation-Version"] ?: UNKNOWN_VALUE
    return CordappImpl.Info(
            shortName = shortName,
            vendor = vendor,
            version = version,
            minimumPlatformVersion = minPlatformVersion,
            targetPlatformVersion = targetPlatformVersion
    )
}
