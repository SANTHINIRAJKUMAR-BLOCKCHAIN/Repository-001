package net.corda.node.services.network

import net.corda.cordform.CordformNode
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.SignedData
import net.corda.core.internal.createDirectories
import net.corda.core.internal.div
import net.corda.core.internal.isDirectory
import net.corda.core.internal.isRegularFile
import net.corda.core.internal.list
import net.corda.core.internal.readAll
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.KeyManagementService
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.utilities.loggerFor
import rx.Observable
import rx.Scheduler
import rx.schedulers.Schedulers
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.streams.toList

/**
 * Class containing the logic to
 * - Serialize and de-serialize a [NodeInfo] to disk and reading it back.
 * - Poll a directory for new serialized [NodeInfo]
 *
 * @param path the base path of a node.
 * @param scheduler a [Scheduler] for the rx [Observable] returned by [nodeInfoUpdates], this is mainly useful for
 *        testing. It defaults to the io scheduler which is the appropriate value for production uses.
 */
class NodeInfoWatcher(private val nodePath: Path,
                      private val scheduler: Scheduler = Schedulers.io()) {

    private val nodeInfoDirectory = nodePath / CordformNode.NODE_INFO_DIRECTORY

    companion object {
        private val logger = loggerFor<NodeInfoWatcher>()

        /**
         * Saves the given [NodeInfo] to a path.
         * The node is 'encoded' as a SignedData<NodeInfo>, signed with the owning key of its first identity.
         * The name of the written file will be "nodeInfo-" followed by the hash of the content. The hash in the filename
         * is used so that one can freely copy these files without fearing to overwrite another one.
         *
         * @param path the path where to write the file, if non-existent it will be created.
         * @param nodeInfo the NodeInfo to serialize.
         * @param keyManager a KeyManagementService used to sign the NodeInfo data.
         */
        fun saveToFile(path: Path, nodeInfo: NodeInfo, keyManager: KeyManagementService) {
            try {
                path.createDirectories()
                val serializedBytes = nodeInfo.serialize()
                val regSig = keyManager.sign(serializedBytes.bytes,
                        nodeInfo.legalIdentities.first().owningKey)
                val signedData = SignedData(serializedBytes, regSig)
                val file = (path / ("nodeInfo-" + SecureHash.sha256(serializedBytes.bytes).toString())).toFile()
                file.writeBytes(signedData.serialize().bytes)
            } catch (e: Exception) {
                logger.warn("Couldn't write node info to file", e)
            }
        }
    }

    /**
     * Read all the files contained in [nodePath] / [CordformNode.NODE_INFO_DIRECTORY] and keep watching
     * the folder for further updates.
     *
     * @return an [Observable] returning [NodeInfo]s, there is no guarantee that the same value isn't returned more
     *      than once.
     */
    fun nodeInfoUpdates(): Observable<NodeInfo> {
        val pollForFiles = Observable.interval(5, TimeUnit.SECONDS, scheduler)
                .flatMapIterable { loadFromDirectory() }
        val readCurrentFiles = Observable.from(loadFromDirectory())
        return readCurrentFiles.mergeWith(pollForFiles)
    }

    /**
     * Loads all the files contained in a given path and returns the deserialized [NodeInfo]s.
     * Signatures are checked before returning a value.
     *
     * @return a list of [NodeInfo]s
     */
    private fun loadFromDirectory(): List<NodeInfo> {
        if (!nodeInfoDirectory.isDirectory()) {
            logger.info("$nodeInfoDirectory isn't a Directory, not loading NodeInfo from files")
            return emptyList()
        }
        val result = nodeInfoDirectory.list { paths ->
            paths.filter { it.isRegularFile() }
                    .map { processFile(it) }
                    .toList()
                    .filterNotNull()
        }
        logger.info("Successfully read ${result.size} NodeInfo files.")
        return result
    }

    private fun processFile(file: Path) : NodeInfo? {
        try {
            logger.info("Reading NodeInfo from file: $file")
            val signedData = file.readAll().deserialize<SignedData<NodeInfo>>()
            return signedData.verified()
        } catch (e: Exception) {
            logger.warn("Exception parsing NodeInfo from file. $file", e)
            return null
        }
    }
}
