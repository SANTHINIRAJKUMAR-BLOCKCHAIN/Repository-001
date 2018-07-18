package net.corda.core.flows

import co.paralleluniverse.fibers.Suspendable
import com.natpryce.hamkrest.*
import net.corda.core.contracts.Attachment
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.FetchAttachmentsFlow
import net.corda.core.internal.FetchDataFlow
import net.corda.core.internal.hash
import net.corda.node.internal.StartedNode
import net.corda.node.services.persistence.NodeAttachmentService
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNodeParameters
import net.corda.testing.node.internal.startFlow
import org.junit.AfterClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import com.natpryce.hamkrest.assertion.assert
import net.corda.core.matchers.*

class AttachmentTests {
    companion object {
        val mockNet = InternalMockNetwork()

        @JvmStatic
        @AfterClass
        fun cleanUp() = mockNet.stopNodes()
    }

    // Test nodes
    private val aliceNode = makeNode(ALICE_NAME)
    private val bobNode = makeNode(BOB_NAME)
    private val alice = aliceNode.info.singleIdentity()

    @Test
    fun `download and store`() {
        // Insert an attachment into node zero's store directly.
        val id = aliceNode.importAttachment(fakeAttachment())

        // Get node one to run a flow to fetch it and insert it.
        assert.that(
            bobNode.startAttachmentFlow(id, alice),
            succeedsWith(noAttachments()))

        // Verify it was inserted into node one's store.
        val attachment = bobNode.getAttachmentWithId(id)
        assert.that(attachment, hashesTo(id))

        // Shut down node zero and ensure node one can still resolve the attachment.
        aliceNode.dispose()

        assert.that(
            bobNode.startAttachmentFlow(id, alice),
            succeedsWith(soleAttachment(attachment)))
    }

    @Test
    fun missing() {
        val hash: SecureHash = SecureHash.randomSHA256()

        // Get node one to fetch a non-existent attachment.
        assert.that(
            bobNode.startAttachmentFlow(hash, alice),
            failsWith<FetchDataFlow.HashNotFound>(
                has("requested hash", { it.requested }, equalTo(hash))))
    }

    @Test
    fun maliciousResponse() {
        // Make a node that doesn't do sanity checking at load time.
        val badAliceNode = makeBadNode(ALICE_NAME)
        val badAlice = badAliceNode.info.singleIdentity()

        // Insert an attachment into node zero's store directly.
        val attachment = fakeAttachment()
        val id = badAliceNode.importAttachment(attachment)

        // Corrupt its store.
        val corruptBytes = "arggghhhh".toByteArray()
        System.arraycopy(corruptBytes, 0, attachment, 0, corruptBytes.size)

        val corruptAttachment = NodeAttachmentService.DBAttachment(attId = id.toString(), content = attachment)
        badAliceNode.updateAttachment(corruptAttachment)

        // Get n1 to fetch the attachment. Should receive corrupted bytes.
        assert.that(
            bobNode.startAttachmentFlow(id, badAlice),
            failsWith<FetchDataFlow.DownloadedVsRequestedDataMismatch>()
        )
    }

    @InitiatingFlow
    private class InitiatingFetchAttachmentsFlow(val otherSide: Party, val hashes: Set<SecureHash>) : FlowLogic<FetchDataFlow.Result<Attachment>>() {
        @Suspendable
        override fun call(): FetchDataFlow.Result<Attachment> {
            val session = initiateFlow(otherSide)
            return subFlow(FetchAttachmentsFlow(hashes, session))
        }
    }

    @InitiatedBy(InitiatingFetchAttachmentsFlow::class)
    private class FetchAttachmentsResponse(val otherSideSession: FlowSession) : FlowLogic<Void?>() {
        @Suspendable
        override fun call() = subFlow(TestDataVendingFlow(otherSideSession))
    }

    //region Generators
    private fun makeNode(name: CordaX500Name) =
        mockNet.createPartyNode(randomiseName(name)).apply {
            registerInitiatedFlow(FetchAttachmentsResponse::class.java)
        }

    // Makes a node that doesn't do sanity checking at load time.
    private fun makeBadNode(name: CordaX500Name) = mockNet.createNode(
            InternalMockNodeParameters(legalName = randomiseName(name)),
            nodeFactory = { args ->
                object : InternalMockNetwork.MockNode(args) {
                    override fun start() = super.start().apply { attachments.checkAttachmentsOnLoad = false }
                }
            }).apply { registerInitiatedFlow(FetchAttachmentsResponse::class.java) }

    private fun randomiseName(name: CordaX500Name) = name.copy(commonName = "${name.commonName}_${UUID.randomUUID()}")

    private fun fakeAttachment(): ByteArray =
        ByteArrayOutputStream().use { baos ->
            JarOutputStream(baos).use { jos ->
                jos.putNextEntry(ZipEntry("file1.txt"))
                jos.writer().apply {
                    append("Some useful content")
                    flush()
                }
                jos.closeEntry()
            }
            baos.toByteArray()
        }
    //endregion

    //region Operations
    private fun StartedNode<*>.importAttachment(attachment: ByteArray) = database.transaction {
        attachments.importAttachment(attachment.inputStream(), "test", null)
    }.andRunNetwork()

    private fun StartedNode<*>.updateAttachment(attachment:  NodeAttachmentService.DBAttachment) =
            database.transaction { session.update(attachment) }.andRunNetwork()

    private fun StartedNode<*>.startAttachmentFlow(hash: SecureHash, otherSide: Party) = services.startFlow(
            InitiatingFetchAttachmentsFlow(otherSide, setOf(hash))).andRunNetwork()

    private fun StartedNode<*>.getAttachmentWithId(id: SecureHash) = database.transaction {
        attachments.openAttachment(id)!!
    }

    private fun <T : Any> T.andRunNetwork(): T {
        mockNet.runNetwork()
        return this
    }
    //endregion

    //region Matchers
    private fun noAttachments() = has(FetchDataFlow.Result<Attachment>::fromDisk, isEmpty)
    private fun soleAttachment(attachment: Attachment) = has(FetchDataFlow.Result<Attachment>::fromDisk,
            hasSize(equalTo(1)) and
                    hasElement(attachment))

    private fun hashesTo(hash: SecureHash) = has<Attachment, SecureHash>(
        "hash",
        { it.open().hash() },
        equalTo(hash))
    //endregion

}
