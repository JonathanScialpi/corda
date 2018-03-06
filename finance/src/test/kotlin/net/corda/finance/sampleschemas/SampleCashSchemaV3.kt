/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.finance.sampleschemas

import net.corda.core.contracts.MAX_ISSUER_REF_SIZE
import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import org.hibernate.annotations.Type
import javax.persistence.*

/**
 * First version of a cash contract ORM schema that maps all fields of the [Cash] contract state as it stood
 * at the time of writing.
 */
object SampleCashSchemaV3 : MappedSchema(schemaFamily = CashSchema.javaClass, version = 3,
        mappedTypes = listOf(PersistentCashState::class.java)) {

    override val migrationResource = "sample-cash-v3.changelog-init"

    @Entity
    @Table(name = "cash_states_v3")
    class PersistentCashState(
            /** [ContractState] attributes */

            /** X500Name of participant parties **/
            @ElementCollection
            @CollectionTable(name="cash_state_participants", joinColumns = arrayOf(
                    JoinColumn(name = "output_index", referencedColumnName = "output_index"),
                    JoinColumn(name = "transaction_id", referencedColumnName = "transaction_id")))
            var participants: MutableSet<AbstractParty>? = null,

            /** X500Name of owner party **/
            @Column(name = "owner_name")
            var owner: AbstractParty,

            @Column(name = "pennies")
            var pennies: Long,

            @Column(name = "ccy_code", length = 3)
            var currency: String,

            /** X500Name of issuer party **/
            @Column(name = "issuer_name")
            var issuer: AbstractParty,

            @Column(name = "issuer_ref", length = MAX_ISSUER_REF_SIZE)
            @Type(type = "corda-wrapper-binary")
            var issuerRef: ByteArray
    ) : PersistentState()
}
