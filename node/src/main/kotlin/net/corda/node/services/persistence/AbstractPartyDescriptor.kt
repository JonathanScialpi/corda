/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.node.services.persistence

import net.corda.core.identity.AbstractParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.IdentityService
import net.corda.core.utilities.contextLogger
import org.hibernate.type.descriptor.WrapperOptions
import org.hibernate.type.descriptor.java.AbstractTypeDescriptor
import org.hibernate.type.descriptor.java.ImmutableMutabilityPlan
import org.hibernate.type.descriptor.java.MutabilityPlan

class AbstractPartyDescriptor(private val identityService: IdentityService) : AbstractTypeDescriptor<AbstractParty>(AbstractParty::class.java) {
    companion object {
        private val log = contextLogger()
    }

    override fun fromString(dbData: String?): AbstractParty? {
        return if (dbData != null) {
            val party = identityService.wellKnownPartyFromX500Name(CordaX500Name.parse(dbData))
            if (party == null) log.warn("Identity service unable to resolve X500name: $dbData")
            party
        } else {
            null
        }
    }

    override fun getMutabilityPlan(): MutabilityPlan<AbstractParty> = ImmutableMutabilityPlan()

    override fun toString(party: AbstractParty?): String? {
        return if (party != null) {
            val partyName = party.nameOrNull() ?: identityService.wellKnownPartyFromAnonymous(party)?.name
            if (partyName == null) log.warn("Identity service unable to resolve AbstractParty: $party")
            partyName.toString()
        } else {
            return null // non resolvable anonymous parties
        }
    }

    override fun <X : Any> wrap(value: X?, options: WrapperOptions): AbstractParty? {
        return if (value != null) {
            if (String::class.java.isInstance(value)) {
                return fromString(value as String)!!
            }
            if (AbstractParty::class.java.isInstance(value)) {
                return value as AbstractParty
            }
            throw unknownWrap(value::class.java)
        } else {
            null
        }
    }

    override fun <X : Any> unwrap(value: AbstractParty?, type: Class<X>, options: WrapperOptions): X? {
        return if (value != null) {
            if (AbstractParty::class.java.isAssignableFrom(type)) {
                return value as X
            }
            if (String::class.java.isAssignableFrom(type)) {
                return toString(value) as X
            }
            throw unknownUnwrap(type)
        } else {
            null
        }
    }
}