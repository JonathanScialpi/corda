/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.node.services.transactions

import net.corda.core.internal.concurrent.fork
import net.corda.core.node.services.TransactionVerifierService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.transactions.LedgerTransaction
import java.util.concurrent.Executors

class InMemoryTransactionVerifierService(numberOfWorkers: Int) : SingletonSerializeAsToken(), TransactionVerifierService {
    private val workerPool = Executors.newFixedThreadPool(numberOfWorkers)
    override fun verify(transaction: LedgerTransaction) = workerPool.fork(transaction::verify)
}
