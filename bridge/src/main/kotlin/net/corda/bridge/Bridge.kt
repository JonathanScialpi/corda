/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

@file:JvmName("Bridge")

package net.corda.bridge

import net.corda.bridge.internal.BridgeStartup
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(if (BridgeStartup(args).run()) 0 else 1)
}