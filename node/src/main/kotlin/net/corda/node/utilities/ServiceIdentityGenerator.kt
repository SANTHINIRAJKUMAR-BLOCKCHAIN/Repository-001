package net.corda.node.utilities

import net.corda.core.crypto.CertificateAndKeyPair
import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.X509Utilities
import net.corda.core.crypto.generateKeyPair
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.serialization.serialize
import net.corda.core.serialization.storageKryo
import net.corda.core.utilities.loggerFor
import net.corda.core.utilities.trace
import org.bouncycastle.asn1.x500.X500Name
import java.nio.file.Files
import java.nio.file.Path

object ServiceIdentityGenerator {
    private val log = loggerFor<ServiceIdentityGenerator>()

    /**
     * Generates signing key pairs and a common distributed service identity for a set of nodes.
     * The key pairs and the group identity get serialized to disk in the corresponding node directories.
     * This method should be called *before* any of the nodes are started.
     *
     * @param dirs List of node directories to place the generated identity and key pairs in.
     * @param serviceCa Certificate authority to use when signing identity certificates.
     * @param serviceId The service id of the distributed service.
     * @param serviceName The legal name of the distributed service.
     * @param threshold The threshold for the generated group [CompositeKey].
     */
    // TODO: This needs to write out to the key store, not just files on disk
    fun generateToDisk(dirs: List<Path>,
                       serviceCa: CertificateAndKeyPair,
                       serviceId: String,
                       serviceName: X500Name,
                       threshold: Int = 1): PartyAndCertificate {
        log.trace { "Generating a group identity \"serviceName\" for nodes: ${dirs.joinToString()}" }
        val keyPairs = (1..dirs.size).map { generateKeyPair() }
        val notaryKey = CompositeKey.Builder().addKeys(keyPairs.map { it.public }).build(threshold)
        // TODO: This doesn't work until we have composite keys in X.509 certificates, so we make up a certificate that nothing checks
        // val notaryCert = X509Utilities.createCertificate(CertificateType.IDENTITY, serviceCa.certificate,
        //        serviceCa.keyPair, serviceName, notaryKey)
        val notaryCert = X509Utilities.createSelfSignedCACertificate(serviceName, generateKeyPair())
        val notaryCertPath = X509Utilities.createCertificatePath(serviceCa.certificate, notaryCert, revocationEnabled = false)
        val notaryParty = PartyAndCertificate(serviceName, notaryKey, notaryCert, notaryCertPath)
        val notaryPartyBytes = notaryParty.serialize()
        val privateKeyFile = "$serviceId-private-key"
        val publicKeyFile = "$serviceId-public"
        keyPairs.zip(dirs) { keyPair, dir ->
            Files.createDirectories(dir)
            notaryPartyBytes.writeToFile(dir.resolve(publicKeyFile))
            // Use storageKryo as our whitelist is not available in the gradle build environment:
            keyPair.serialize(storageKryo()).writeToFile(dir.resolve(privateKeyFile))
        }
        return notaryParty
    }
}
