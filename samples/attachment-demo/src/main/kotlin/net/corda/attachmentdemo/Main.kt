package net.corda.attachmentdemo

import net.corda.core.internal.div
import net.corda.core.node.services.ServiceInfo
import net.corda.testing.DUMMY_BANK_A
import net.corda.testing.DUMMY_BANK_B
import net.corda.testing.DUMMY_NOTARY
import net.corda.node.services.transactions.SimpleNotaryService
import net.corda.nodeapi.User
import net.corda.testing.driver.driver

/**
 * This file is exclusively for being able to run your nodes through an IDE (as opposed to running deployNodes)
 * Do not use in a production environment.
 */
fun main(args: Array<String>) {
    val demoUser = listOf(User("demo", "demo", setOf("StartFlow.net.corda.flows.FinalityFlow")))
    driver(isDebug = true, driverDirectory = "build" / "attachment-demo-nodes") {
        startNode(DUMMY_NOTARY.name, setOf(ServiceInfo(SimpleNotaryService.type)))
        startNode(DUMMY_BANK_A.name, rpcUsers = demoUser)
        startNode(DUMMY_BANK_B.name, rpcUsers = demoUser)
        waitForAllNodesToFinish()
    }
}
