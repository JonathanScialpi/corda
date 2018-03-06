/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.nodeapi.internal.serialization

import net.corda.core.node.ServiceHub
import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.SerializationFactory
import net.corda.core.serialization.SerializeAsToken
import net.corda.core.serialization.SerializeAsTokenContext

val serializationContextKey = SerializeAsTokenContext::class.java

fun SerializationContext.withTokenContext(serializationContext: SerializeAsTokenContext): SerializationContext = this.withProperty(serializationContextKey, serializationContext)

/**
 * A context for mapping SerializationTokens to/from SerializeAsTokens.
 *
 * A context is initialised with an object containing all the instances of [SerializeAsToken] to eagerly register all the tokens.
 * In our case this can be the [ServiceHub].
 *
 * Then it is a case of using the companion object methods on [SerializeAsTokenSerializer] to set and clear context as necessary
 * when serializing to enable/disable tokenization.
 */
class SerializeAsTokenContextImpl(override val serviceHub: ServiceHub, init: SerializeAsTokenContext.() -> Unit) : SerializeAsTokenContext {
    constructor(toBeTokenized: Any, serializationFactory: SerializationFactory, context: SerializationContext, serviceHub: ServiceHub) : this(serviceHub, {
        serializationFactory.serialize(toBeTokenized, context.withTokenContext(this))
    })

    private val classNameToSingleton = mutableMapOf<String, SerializeAsToken>()
    private var readOnly = false

    init {
        /**
         * Go ahead and eagerly serialize the object to register all of the tokens in the context.
         *
         * This results in the toToken() method getting called for any [SingletonSerializeAsToken] instances which
         * are encountered in the object graph as they are serialized and will therefore register the token to
         * object mapping for those instances.  We then immediately set the readOnly flag to stop further adhoc or
         * accidental registrations from occuring as these could not be deserialized in a deserialization-first
         * scenario if they are not part of this iniital context construction serialization.
         */
        init(this)
        readOnly = true
    }

    override fun putSingleton(toBeTokenized: SerializeAsToken) {
        val className = toBeTokenized.javaClass.name
        if (className !in classNameToSingleton) {
            // Only allowable if we are in SerializeAsTokenContext init (readOnly == false)
            if (readOnly) {
                throw UnsupportedOperationException("Attempt to write token for lazy registered ${className}. All tokens should be registered during context construction.")
            }
            classNameToSingleton[className] = toBeTokenized
        }
    }

    override fun getSingleton(className: String) = classNameToSingleton[className] ?: throw IllegalStateException("Unable to find tokenized instance of $className in context $this")
}