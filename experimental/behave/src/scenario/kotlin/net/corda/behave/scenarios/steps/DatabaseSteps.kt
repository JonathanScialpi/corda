/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.behave.scenarios.steps

import net.corda.behave.scenarios.StepsBlock

fun databaseSteps(steps: StepsBlock) = steps {

    Then<String>("^user can connect to the database of node (\\w+)$") { name ->
        withNetwork {
            database.canConnectTo(name)
        }
    }

}
