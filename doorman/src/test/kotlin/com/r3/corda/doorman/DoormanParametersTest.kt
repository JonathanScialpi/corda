package com.r3.corda.doorman

import com.typesafe.config.ConfigException
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DoormanParametersTest {
    private val testDummyPath = ".${File.separator}testDummyPath.jks"

    @Test
    fun `parse arg correctly`() {
        val ref = javaClass.getResource("/node.conf")
        val params = DoormanParameters(arrayOf("--keygen", "--keystorePath", testDummyPath, "--configFile", ref.path))
        assertEquals(DoormanParameters.Mode.CA_KEYGEN, params.mode)
        assertEquals(testDummyPath, params.keystorePath.toString())
        assertEquals(8080, params.port)

        val params2 = DoormanParameters(arrayOf("--keystorePath", testDummyPath, "--port", "1000"))
        assertEquals(DoormanParameters.Mode.DOORMAN, params2.mode)
        assertEquals(testDummyPath, params2.keystorePath.toString())
        assertEquals(1000, params2.port)
    }

    @Test
    fun `should fail when config missing`() {
        // dataSourceProperties is missing from node_fail.conf and it should fail when accessed, and shouldn't use default from reference.conf.
        val params = DoormanParameters(arrayOf("--keygen", "--keystorePath", testDummyPath, "--configFile", javaClass.getResource("/node_fail.conf").path))
        assertFailsWith<ConfigException.Missing> { params.dataSourceProperties }
    }
}
