package net.corda.node.internal.cordapp

import net.corda.core.cordapp.Cordapp
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.ContractUpgradeFlow
import net.corda.core.internal.cordapp.CordappImpl
import net.corda.core.internal.location
import net.corda.node.VersionInfo
import net.corda.node.services.transactions.NodeNotarySchemaV1
import net.corda.node.services.transactions.SimpleNotaryService

internal object VirtualCordapp {
    /** A list of the core RPC flows present in Corda */
    private val coreRpcFlows = listOf(
            ContractUpgradeFlow.Initiate::class.java,
            ContractUpgradeFlow.Authorise::class.java,
            ContractUpgradeFlow.Deauthorise::class.java
    )

    /** A Cordapp representing the core package which is not scanned automatically. */
    fun generateCoreCordapp(versionInfo: VersionInfo): CordappImpl {
        return CordappImpl(
                contractClassNames = listOf(),
                initiatedFlows = listOf(),
                rpcFlows = coreRpcFlows,
                serviceFlows = listOf(),
                schedulableFlows = listOf(),
                services = listOf(),
                serializationWhitelists = listOf(),
                serializationCustomSerializers = listOf(),
                customSchemas = setOf(),
                info = Cordapp.Info.Default("corda-core", versionInfo.vendor, versionInfo.releaseVersion, "Open Source (Apache 2)"),
                allFlows = listOf(),
                jarPath = ContractUpgradeFlow.javaClass.location, // Core JAR location
                jarHash = SecureHash.allOnesHash,
                minimumPlatformVersion = 1,
                targetPlatformVersion = versionInfo.platformVersion,
                notaryService = null,
                isLoaded = false
        )
    }

    /** A Cordapp for the built-in notary service implementation. */
    fun generateSimpleNotaryCordapp(versionInfo: VersionInfo): CordappImpl {
        return CordappImpl(
                contractClassNames = listOf(),
                initiatedFlows = listOf(),
                rpcFlows = listOf(),
                serviceFlows = listOf(),
                schedulableFlows = listOf(),
                services = listOf(),
                serializationWhitelists = listOf(),
                serializationCustomSerializers = listOf(),
                customSchemas = setOf(NodeNotarySchemaV1),
                info = Cordapp.Info.Default("corda-notary", versionInfo.vendor, versionInfo.releaseVersion, "Open Source (Apache 2)"),
                allFlows = listOf(),
                jarPath = SimpleNotaryService::class.java.location,
                jarHash = SecureHash.allOnesHash,
                minimumPlatformVersion = 1,
                targetPlatformVersion = versionInfo.platformVersion,
                notaryService = SimpleNotaryService::class.java,
                isLoaded = false
        )
    }
}