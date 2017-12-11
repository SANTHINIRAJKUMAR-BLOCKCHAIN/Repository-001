package net.corda.core.identity

import net.corda.core.serialization.CordaSerializable
import java.security.PublicKey
import java.security.cert.*

/**
 * A full party plus the X.509 certificate and path linking the party back to a trust root. Equality of
 * [PartyAndCertificate] instances is based on the party only, as certificate and path are data associated with the party,
 * not part of the identifier themselves.
 */
@CordaSerializable
class PartyAndCertificate(val certPath: CertPath) {
    @Transient
    val certificate: X509Certificate

    init {
        require(certPath.type == "X.509") { "Only X.509 certificates supported" }
        val certs = certPath.certificates
        require(certs.size >= 2) { "Certificate path must at least include subject and issuing certificates" }
        certificate = certs[0] as X509Certificate
        val role = CertRole.extract(certificate)
        require(role?.isIdentity ?: false) { "Party certificate ${certificate.subjectDN} does not have a well known or confidential identity role. Found: $role" }
    }

    @Transient
    val party: Party = Party(certificate)

    val owningKey: PublicKey get() = party.owningKey
    val name: CordaX500Name get() = party.name

    operator fun component1(): Party = party
    operator fun component2(): X509Certificate = certificate

    override fun equals(other: Any?): Boolean = other === this || other is PartyAndCertificate && other.party == party
    override fun hashCode(): Int = party.hashCode()
    override fun toString(): String = party.toString()

    /** Verify the certificate path is valid. */
    fun verify(trustAnchor: TrustAnchor): PKIXCertPathValidatorResult {
        val parameters = PKIXParameters(setOf(trustAnchor)).apply { isRevocationEnabled = false }
        val validator = CertPathValidator.getInstance("PKIX")
        val result = validator.validate(certPath, parameters) as PKIXCertPathValidatorResult
        // Apply Corda-specific validity rules to the chain
        var parentRole: CertRole? = CertRole.extract(result.trustAnchor.trustedCert)
        for (certIdx in (0 until certPath.certificates.size).reversed()) {
            val certificate = certPath.certificates[certIdx]
            val role = CertRole.extract(certificate)
            if (parentRole != null) {
                if (role == null) {
                    throw CertPathValidatorException("Child certificate whose issuer includes a Corda role, must also specify Corda role")
                }
                if (role.parent != parentRole) {
                    throw CertPathValidatorException("Expected certificate $certificate to have parent ${role.parent} but was $parentRole")
                }
            }
            parentRole = role
        }
        return result
    }
}
