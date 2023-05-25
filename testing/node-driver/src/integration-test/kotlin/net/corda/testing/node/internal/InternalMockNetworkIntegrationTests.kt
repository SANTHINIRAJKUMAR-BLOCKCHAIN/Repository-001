package net.corda.testing.node.internal

import net.corda.core.internal.div
import net.corda.testing.common.internal.ProjectStructure.projectRootDir
import net.corda.testing.node.internal.ProcessUtilities.startJavaProcess
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertEquals

class InternalMockNetworkIntegrationTests {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            InternalMockNetwork().run {
                repeat(2) { createNode() }
                runNetwork()
                stopNodes()
            }
        }
    }

    @Test(timeout=300_000)
    @Ignore("TODO JDK17: Fixme")
	fun `does not leak non-daemon threads`() {
        val quasar = projectRootDir / "lib" / "quasar.jar"
        assertEquals(0, startJavaProcess<InternalMockNetworkIntegrationTests>(emptyList(), extraJvmArguments = listOf("-javaagent:$quasar")).waitFor())
    }
}
