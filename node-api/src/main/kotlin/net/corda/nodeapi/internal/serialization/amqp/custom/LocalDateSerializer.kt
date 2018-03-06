/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.nodeapi.internal.serialization.amqp.custom

import net.corda.nodeapi.internal.serialization.amqp.CustomSerializer
import net.corda.nodeapi.internal.serialization.amqp.SerializerFactory
import java.time.LocalDate

/**
 * A serializer for [LocalDate] that uses a proxy object to write out the year, month and day.
 */
class LocalDateSerializer(factory: SerializerFactory) : CustomSerializer.Proxy<LocalDate, LocalDateSerializer.LocalDateProxy>(LocalDate::class.java, LocalDateProxy::class.java, factory) {
    override fun toProxy(obj: LocalDate): LocalDateProxy = LocalDateProxy(obj.year, obj.monthValue.toByte(), obj.dayOfMonth.toByte())

    override fun fromProxy(proxy: LocalDateProxy): LocalDate = LocalDate.of(proxy.year, proxy.month.toInt(), proxy.day.toInt())

    data class LocalDateProxy(val year: Int, val month: Byte, val day: Byte)
}