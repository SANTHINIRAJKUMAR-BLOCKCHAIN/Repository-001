package net.corda.core.crypto


import com.esotericsoftware.kryo.serializers.MapSerializer
import net.corda.contracts.asset.Cash
import net.corda.core.contracts.*
import net.corda.core.serialization.*
import net.corda.core.transactions.*
import net.corda.core.utilities.*
import net.corda.testing.MEGA_CORP
import net.corda.testing.MEGA_CORP_PUBKEY
import net.corda.testing.ledger
import org.junit.Test
import java.util.*
import kotlin.test.*

class PartialMerkleTreeTest {
    val nodes = "abcdef"
    val hashed = nodes.map { it.serialize().sha256() }
    val root = SecureHash.parse("28B0EBAED5FD550AE90D4F477323EB4F9C1F90553C70DD279F4AD6B43E5A873D")
    val merkleTree = MerkleTree.getMerkleTree(hashed)

    val testLedger = ledger {
        unverifiedTransaction {
            output("MEGA_CORP cash") {
                Cash.State(
                        amount = 1000.DOLLARS `issued by` MEGA_CORP.ref(1, 1),
                        owner = MEGA_CORP_PUBKEY
                )
            }
            output("dummy cash 1") {
                Cash.State(
                        amount = 900.DOLLARS `issued by` MEGA_CORP.ref(1, 1),
                        owner = DUMMY_PUBKEY_1
                )
            }
        }

        transaction {
            input("MEGA_CORP cash")
            output("MEGA_CORP cash".output<Cash.State>().copy(owner = DUMMY_PUBKEY_1))
            command(MEGA_CORP_PUBKEY) { Cash.Commands.Move() }
            timestamp(TEST_TX_TIME)
            this.verifies()
        }

        // 3 leaves transaction.
        transaction {
            input("MEGA_CORP cash")
            input("dummy cash 1")
            output("MEGA_CORP cash".output<Cash.State>().copy(owner = DUMMY_PUBKEY_1))
            this.fails()
        }

        // 4 leaves transaction with two the same outputs.
        transaction {
            input("MEGA_CORP cash")
            input("dummy cash 1")
            output("MEGA_CORP cash".output<Cash.State>().copy(owner = DUMMY_PUBKEY_1))
            output("MEGA_CORP cash".output<Cash.State>().copy(owner = DUMMY_PUBKEY_1))
            this.fails()
        }
    }

    val txs = testLedger.interpreter.transactionsToVerify
    val testTx = txs[0]

    // Building full Merkle Tree tests.
    @Test
    fun `building Merkle tree with 6 nodes - no rightmost nodes`() {
        assertEquals(root, merkleTree.hash)
    }

    @Test
    fun `building Merkle tree - no hashes`() {
        assertFailsWith<MerkleTreeException> { MerkleTree.Companion.getMerkleTree(emptyList()) }
    }

    @Test
    fun `building Merkle tree one node`() {
        val node = 'a'.serialize().sha256()
        val mt = MerkleTree.getMerkleTree(listOf(node))
        assertEquals(node, mt.hash)
    }

    @Test
    fun `building Merkle tree odd number of nodes`() {
        val odd = hashed.subList(0, 3)
        val h1 = hashed[0].hashConcat(hashed[1])
        val h2 = hashed[2].hashConcat((hashed[2].bytes + 3.toByte()).sha256())
        val expected = h1.hashConcat(h2)
        val mt = MerkleTree.getMerkleTree(odd)
        assertEquals(mt.hash, expected)
    }

    @Test
    fun `building Merkle tree for a transaction`() {
        val filterFuns = FilterFuns(
                filterCommands = { x -> MEGA_CORP_PUBKEY in x.signers },
                filterOutputs = { it.data.participants[0].keys == DUMMY_PUBKEY_1.keys },
                filterInputs = { true })
        val mt = testTx.buildFilteredTransaction(filterFuns)
        val leaves = mt.filteredLeaves
        val d = WireTransaction.deserialize(testTx.serialized)
        assertEquals(testTx.id, d.id)
        assertEquals(1, leaves.commands.size)
        assertEquals(1, leaves.outputs.size)
        assertEquals(1, leaves.inputs.size)
        assertTrue(mt.filteredLeaves.timestamp != null)
        assert(mt.verify(testTx.id))
    }

    @Test
    fun `transactions with different notaries have different ids`() {
        fun makeWtx(notary: Party): WireTransaction =
                WireTransaction(
                        inputs = listOf(StateRef(SecureHash.sha256("0"), 0)),
                        attachments = emptyList(),
                        outputs = emptyList(),
                        commands = emptyList(),
                        notary = notary,
                        signers = listOf(DUMMY_KEY_1.public.composite, DUMMY_KEY_2.public.composite),
                        type = TransactionType.General(),
                        timestamp = null
                )
        val wtx1 = makeWtx(DUMMY_NOTARY)
        val wtx2 = makeWtx(MEGA_CORP)
        assertNotEquals(wtx1.id, wtx2.id)
    }

    @Test
    fun `only timestamp`() {
        val filterFuns = FilterFuns()
        val mt = testTx.buildFilteredTransaction(filterFuns)
        assertTrue(mt.filteredLeaves.attachments.isEmpty())
        assertTrue(mt.filteredLeaves.commands.isEmpty())
        assertTrue(mt.filteredLeaves.inputs.isEmpty())
        assertTrue(mt.filteredLeaves.outputs.isEmpty())
        assertTrue(mt.filteredLeaves.timestamp != null)
        assert(mt.verify(testTx.id))
    }

    // Partial Merkle Tree building tests
    @Test
    fun `build Partial Merkle Tree, only left nodes branch`() {
        val inclHashes = listOf(hashed[3], hashed[5])
        val pmt = PartialMerkleTree.build(merkleTree, inclHashes)
        assert(pmt.verify(merkleTree.hash, inclHashes))
    }

    @Test
    fun `build Partial Merkle Tree, include zero leaves`() {
        val pmt = PartialMerkleTree.build(merkleTree, emptyList())
        assert(pmt.verify(merkleTree.hash, emptyList()))
    }

    @Test
    fun `build Partial Merkle Tree, include all leaves`() {
        val pmt = PartialMerkleTree.build(merkleTree, hashed)
        assert(pmt.verify(merkleTree.hash, hashed))
    }

    @Test
    fun `build Partial Merkle Tree - duplicate leaves failure`() {
        val inclHashes = arrayListOf(hashed[3], hashed[5], hashed[3], hashed[5])
        assertFailsWith<MerkleTreeException> { PartialMerkleTree.build(merkleTree, inclHashes) }
    }

    @Test
    fun `build Partial Merkle Tree - only duplicate leaves, less included failure`() {
        val leaves = "aaa"
        val hashes = leaves.map { it.serialize().hash }
        val mt = MerkleTree.getMerkleTree(hashes)
        assertFailsWith<MerkleTreeException> { PartialMerkleTree.build(mt, hashes.subList(0, 1)) }
    }

    @Test
    fun `verify Partial Merkle Tree - too many leaves failure`() {
        val inclHashes = arrayListOf(hashed[3], hashed[5])
        val pmt = PartialMerkleTree.build(merkleTree, inclHashes)
        inclHashes.add(hashed[0])
        assertFalse(pmt.verify(merkleTree.hash, inclHashes))
    }

    @Test
    fun `verify Partial Merkle Tree - too little leaves failure`() {
        val inclHashes = arrayListOf(hashed[3], hashed[5], hashed[0])
        val pmt = PartialMerkleTree.build(merkleTree, inclHashes)
        inclHashes.remove(hashed[0])
        assertFalse(pmt.verify(merkleTree.hash, inclHashes))
    }

    @Test
    fun `verify Partial Merkle Tree - duplicate leaves failure`() {
        val mt = MerkleTree.getMerkleTree(hashed.subList(0, 5)) // Odd number of leaves. Last one is duplicated.
        val inclHashes = arrayListOf(hashed[3], hashed[4])
        val pmt = PartialMerkleTree.build(mt, inclHashes)
        inclHashes.add(hashed[4])
        assertFalse(pmt.verify(mt.hash, inclHashes))
    }

    @Test
    fun `verify Partial Merkle Tree - different leaves failure`() {
        val inclHashes = arrayListOf(hashed[3], hashed[5])
        val pmt = PartialMerkleTree.build(merkleTree, inclHashes)
        assertFalse(pmt.verify(merkleTree.hash, listOf(hashed[2], hashed[4])))
    }

    @Test
    fun `verify Partial Merkle Tree - wrong root`() {
        val inclHashes = listOf(hashed[3], hashed[5])
        val pmt = PartialMerkleTree.build(merkleTree, inclHashes)
        val wrongRoot = hashed[3].hashConcat(hashed[5])
        assertFalse(pmt.verify(wrongRoot, inclHashes))
    }

    @Test
    fun `hash map serialization`() {
        val hm1 = hashMapOf("a" to 1, "b" to 2, "c" to 3, "e" to 4)
        assert(serializedHash(hm1) == serializedHash(hm1.serialize().deserialize())) // It internally uses the ordered HashMap extension.
        val kryo = extendKryoHash(createKryo())
        assertTrue(kryo.getSerializer(HashMap::class.java) is OrderedSerializer)
        assertTrue(kryo.getSerializer(LinkedHashMap::class.java) is MapSerializer)
        val hm2 = hm1.serialize(kryo).deserialize(kryo)
        assert(hm1.hashCode() == hm2.hashCode())
    }

    @Test
    fun `transactions with the same ids, duplicated last node bug`() {
        val tx1 = txs[1]
        val tx2 = txs[2]
        assertFalse(tx1.id == tx2.id)
    }
}
