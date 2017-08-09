package net.corda.core.schemas.converters

import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.node.services.IdentityService
import org.bouncycastle.asn1.x500.X500Name
import javax.persistence.AttributeConverter
import javax.persistence.Converter

@Converter
class AbstractPartyConverter(val identitySvc: IdentityService) : AttributeConverter<AbstractParty, String> {

    override fun convertToDatabaseColumn(party: AbstractParty?): String {
        val partyName =
            when (party) {
                is AnonymousParty -> identitySvc.partyFromAnonymous(party).toString()
                is Party -> party.nameOrNull().toString()
                else -> throw IdentityService.UnknownAnonymousPartyException("Unable to resolve identity: $party")
        }
        return partyName
    }

    override fun convertToEntityAttribute(dbData: String?): AbstractParty {
        dbData?.let {
            val party = identitySvc.partyFromX500Name(X500Name(dbData))
            return party as AbstractParty
        }
        throw IdentityService.UnknownAnonymousPartyException("Unable to resolve identity: $dbData")
    }
}
