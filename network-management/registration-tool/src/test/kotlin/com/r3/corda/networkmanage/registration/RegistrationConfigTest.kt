package com.r3.corda.networkmanage.registration

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.CertRole
import net.corda.nodeapi.internal.config.parseAs
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

class RegistrationConfigTest {

    @Test
    fun `parse config file correctly`() {
        val testConfig = """
legalName {
    organisationUnit = "R3 Corda"
    organisation = "R3 LTD"
    locality = "London"
    country = "GB"
}
email = "test@email.com"
compatibilityZoneURL = "http://doorman.url.com"
networkRootTrustStorePath = "networkRootTrustStore.jks"
certRole = "NODE_CA"

networkRootTrustStorePassword = "password"
keyStorePassword = "password"
trustStorePassword = "password"
""".trimIndent()

        val config = ConfigFactory.parseString(testConfig, ConfigParseOptions.defaults().setAllowMissing(false))
                .resolve()
                .parseAs<RegistrationConfig>()

        assertEquals(CertRole.NODE_CA, config.certRole)
        assertEquals(CordaX500Name.parse("OU=R3 Corda, O=R3 LTD, L=London, C=GB"), config.legalName)
        assertEquals("http://doorman.url.com", config.compatibilityZoneURL.toString())
        assertEquals("test@email.com", config.email)
        assertEquals(Paths.get("networkRootTrustStore.jks"), config.networkRootTrustStorePath)
        assertEquals("password", config.networkRootTrustStorePassword)
    }
}