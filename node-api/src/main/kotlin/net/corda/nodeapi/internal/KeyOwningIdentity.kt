package net.corda.nodeapi.internal

import java.util.*

/**
 * A [KeyOwningIdentity] represents an entity that owns a public key. In this case, the "owner" refers to either an identifier provided
 * when the key was generated, or the node itself if no identifier was supplied.
 */
sealed class KeyOwningIdentity {

    abstract val uuid: UUID?

    /**
     * [NodeLegalIdentity] is used for keys that belong to the node identity. This is any key created on this node that did not have a UUID
     * assigned to it on generation.
     */
    object NodeLegalIdentity : KeyOwningIdentity() {
        override fun toString(): String {
            return "NODE_LEGAL_IDENTITY"
        }

        override val uuid: UUID? = null
    }

    /**
     * [MappedIdentity] is used for keys that have an assigned UUID. Keys belonging to a mapped identity were assigned a UUID at the point
     * they were generated. This UUID may refer to something outside the core of the node, for example an account.
     */
    data class MappedIdentity(override val uuid: UUID) : KeyOwningIdentity() {
        override fun toString(): String {
            return uuid.toString()
        }
    }

    companion object {
        fun fromUUID(uuid: UUID?): KeyOwningIdentity {
            return if (uuid != null) {
                MappedIdentity(uuid)
            } else {
                NodeLegalIdentity
            }
        }
    }
}