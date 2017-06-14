package net.corda.node.utilities.registration

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.mock
import net.corda.core.crypto.*
import net.corda.core.exists
import net.corda.core.toTypedArray
import net.corda.core.utilities.ALICE
import net.corda.testing.TestNodeConfiguration
import net.corda.testing.getTestX509Name
import org.bouncycastle.cert.X509CertificateHolder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkRegistrationHelperTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun buildKeyStore() {
        val id = SecureHash.randomSHA256().toString()

        val identities = listOf("CORDA_CLIENT_CA",
                "CORDA_INTERMEDIATE_CA",
                "CORDA_ROOT_CA")
                .map { getTestX509Name(it) }
        val certs = identities.stream().map { X509Utilities.createSelfSignedCACertificate(it, Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)) }
                .map { it.cert }.toTypedArray()

        val certService: NetworkRegistrationService = mock {
            on { submitRequest(any()) }.then { id }
            on { retrieveCertificates(eq(id)) }.then { certs }
        }

        val config = TestNodeConfiguration(
                baseDirectory = tempFolder.root.toPath(),
                myLegalName = ALICE.name,
                networkMapService = null)

        assertFalse(config.nodeKeystore.exists())
        assertFalse(config.sslKeystore.exists())
        assertFalse(config.trustStoreFile.exists())

        NetworkRegistrationHelper(config, certService).buildKeystore()

        assertTrue(config.nodeKeystore.exists())
        assertTrue(config.sslKeystore.exists())
        assertTrue(config.trustStoreFile.exists())

        val nodeKeystore = KeyStoreUtilities.loadKeyStore(config.nodeKeystore, config.keyStorePassword)
        val sslKeystore = KeyStoreUtilities.loadKeyStore(config.sslKeystore, config.keyStorePassword)
        val trustStore = KeyStoreUtilities.loadKeyStore(config.trustStoreFile, config.trustStorePassword)


        nodeKeystore.run {
            assertTrue(containsAlias(X509Utilities.CORDA_CLIENT_CA))
            assertFalse(containsAlias(X509Utilities.CORDA_INTERMEDIATE_CA))
            assertFalse(containsAlias(X509Utilities.CORDA_ROOT_CA))
            assertFalse(containsAlias(X509Utilities.CORDA_CLIENT_TLS))
            val certificateChain = getCertificateChain(X509Utilities.CORDA_CLIENT_CA)
            assertEquals(3, certificateChain.size)
            assertEquals(listOf("CORDA_CLIENT_CA", "CORDA_INTERMEDIATE_CA", "CORDA_ROOT_CA"), certificateChain.map { X509CertificateHolder(it.encoded).subject.commonName })
        }

        sslKeystore.run {
            assertFalse(containsAlias(X509Utilities.CORDA_CLIENT_CA))
            assertFalse(containsAlias(X509Utilities.CORDA_INTERMEDIATE_CA))
            assertFalse(containsAlias(X509Utilities.CORDA_ROOT_CA))
            assertTrue(containsAlias(X509Utilities.CORDA_CLIENT_TLS))
            val certificateChain = getCertificateChain(X509Utilities.CORDA_CLIENT_TLS)
            assertEquals(4, certificateChain.size)
            assertEquals(listOf("CORDA_CLIENT_CA", "CORDA_CLIENT_CA", "CORDA_INTERMEDIATE_CA", "CORDA_ROOT_CA"), certificateChain.map { X509CertificateHolder(it.encoded).subject.commonName })
        }

        trustStore.run {
            assertFalse(containsAlias(X509Utilities.CORDA_CLIENT_CA))
            assertFalse(containsAlias(X509Utilities.CORDA_INTERMEDIATE_CA))
            assertTrue(containsAlias(X509Utilities.CORDA_ROOT_CA))
        }
    }
}
