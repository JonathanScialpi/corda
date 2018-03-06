/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.node.services.statemachine

import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowStackSnapshot
import net.corda.core.flows.StateMachineRunId
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.*

interface FlowStackSnapshotFactory {
    private object Holder {
        val INSTANCE: FlowStackSnapshotFactory

        init {
            val serviceFactory = ServiceLoader.load(FlowStackSnapshotFactory::class.java).singleOrNull()
            INSTANCE = serviceFactory ?: DefaultFlowStackSnapshotFactory
        }
    }

    companion object {
        val instance: FlowStackSnapshotFactory by lazy { Holder.INSTANCE }
    }

    fun getFlowStackSnapshot(flowClass: Class<out FlowLogic<*>>): FlowStackSnapshot?

    fun persistAsJsonFile(flowClass: Class<out FlowLogic<*>>, baseDir: Path, flowId: StateMachineRunId)

    private object DefaultFlowStackSnapshotFactory : FlowStackSnapshotFactory {
        private val log = LoggerFactory.getLogger(javaClass)
        override fun getFlowStackSnapshot(flowClass: Class<out FlowLogic<*>>): FlowStackSnapshot? {
            log.warn("Flow stack snapshot are not supposed to be used in a production deployment")
            return null
        }

        override fun persistAsJsonFile(flowClass: Class<out FlowLogic<*>>, baseDir: Path, flowId: StateMachineRunId) {
            log.warn("Flow stack snapshot are not supposed to be used in a production deployment")
        }
    }
}
