/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.testing.database

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import net.corda.core.utilities.loggerFor
import net.corda.testing.database.DatabaseConstants.DATA_SOURCE_CLASSNAME
import net.corda.testing.database.DatabaseConstants.DATA_SOURCE_PASSWORD
import net.corda.testing.database.DatabaseConstants.DATA_SOURCE_URL
import net.corda.testing.database.DatabaseConstants.DATA_SOURCE_USER
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.EncodedResource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.*
import org.springframework.util.StringUtils
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLWarning
import java.util.*

object DbScriptRunner {
    private val logger = loggerFor<DbScriptRunner>()

    // System properties set in main 'corda-project' build.gradle
    private const val TEST_DB_ADMIN_USER = "test.db.admin.user"
    private const val TEST_DB_ADMIN_PASSWORD = "test.db.admin.password"

    private fun createDataSource(dbProvider: String) : DriverManagerDataSource {
        val parseOptions = ConfigParseOptions.defaults()
        val allSystemProperties = System.getProperties().toList().map { it.first.toString() to it.second.toString() }.toMap()
        val dataSourceSystemProperties = Properties()
        val dataSourceKeys = listOf(TEST_DB_ADMIN_USER, TEST_DB_ADMIN_PASSWORD, DATA_SOURCE_URL, DATA_SOURCE_CLASSNAME,
                DATA_SOURCE_USER, DATA_SOURCE_PASSWORD)
        dataSourceKeys.filter { allSystemProperties.containsKey(it) }.forEach { dataSourceSystemProperties.setProperty(it, allSystemProperties[it]) }
        val databaseConfig = ConfigFactory.parseProperties(dataSourceSystemProperties, parseOptions)
                .withFallback(ConfigFactory.parseResources("$dbProvider.conf", parseOptions.setAllowMissing(false)))

        val dataSource = DriverManagerDataSource()
        dataSource.setDriverClassName(databaseConfig.getString(DATA_SOURCE_CLASSNAME))
        dataSource.url = databaseConfig.getString(DATA_SOURCE_URL)
        dataSource.username = databaseConfig.getString(TEST_DB_ADMIN_USER)
        dataSource.password = databaseConfig.getString(TEST_DB_ADMIN_PASSWORD)
        return dataSource
    }

    fun runDbScript(dbProvider: String, initScript: String? = null, databaseSchemas: List<String> = emptyList()) {
        if (initScript != null) {
            val initSchema = ClassPathResource(initScript)
            if (initSchema.exists()) {
                val encodedResource = EncodedResource(initSchema)
                val inputString = encodedResource.inputStream.bufferedReader().use { it.readText().split("\n") }
                val resolvedScripts = merge(inputString, databaseSchemas)
                logger.info("Executing DB Script for schemas $databaseSchemas with ${resolvedScripts.size} statements.")
                DatabasePopulatorUtils.execute(ListPopulator(false, true, resolvedScripts),
                        createDataSource(dbProvider))
            } else logger.warn("DB Script missing: $initSchema")
        }
    }

    fun merge(scripts: List<String>, schema: String): List<String> =
            scripts.map { it.replace("\${schema}", schema) }

    fun merge(scripts: List<String>, schemas: List<String>): List<String> =
            if(schemas.isEmpty()) scripts else schemas.map { merge(scripts, it) }.flatten()
}

//rewritten version of org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
class ListPopulator(private val continueOnError: Boolean,
                    private val ignoreFailedDrops: Boolean,
                    private val statements: List<String>) : DatabasePopulator {
    private val logger = loggerFor<DbScriptRunner>()
    override fun populate(connection: Connection) {
        try {
            logger.info("Executing SQL script")
            val startTime = System.currentTimeMillis()
            var stmtNumber = 0
            val stmt = connection.createStatement()
            try {
                for (statement in statements) {
                    stmtNumber++
                    try {
                        stmt.execute(statement)
                        val rowsAffected = stmt.updateCount
                        if (logger.isDebugEnabled) {
                            logger.debug(rowsAffected.toString() + " returned as update count for SQL: " + statement)
                            var warningToLog: SQLWarning? = stmt.warnings
                            while (warningToLog != null) {
                                logger.debug("SQLWarning ignored: SQL state '" + warningToLog.sqlState +
                                        "', error code '" + warningToLog.errorCode +
                                        "', message [" + warningToLog.message + "]")
                                warningToLog = warningToLog.nextWarning
                            }
                        }
                    } catch (ex: SQLException) {
                        val dropStatement = StringUtils.startsWithIgnoreCase(statement.trim { it <= ' ' }, "drop")
                        if ((continueOnError || dropStatement && ignoreFailedDrops)) {
                            val dropUserStatement = StringUtils.startsWithIgnoreCase(statement.trim { it <= ' ' }, "drop user ")
                            if (dropUserStatement) { // log to help spotting a node still logged on database after test has finished (happens on Oracle db)
                                logger.warn("SQLException for $statement: SQL state '" + ex.sqlState +
                                        "', error code '" + ex.errorCode +
                                        "', message [" + ex.message + "]")
                            } else {
                                logger.debug("SQLException for $statement", ex)
                            }
                        } else {
                            throw ex
                        }
                    }
                }
            } finally {
                try {
                    stmt.close()
                } catch (ex: Throwable) {
                    logger.debug("Could not close JDBC Statement", ex)
                }
            }
            val elapsedTime = System.currentTimeMillis() - startTime
            val resource = if (statements.isNotEmpty()) statements[0] + " [...]" else ""
            logger.info("Executed ${statements.size} SQL statements ($resource) in $elapsedTime ms.")
        } catch (ex: Exception) {
            if (ex is ScriptException) {
                throw ex
            }
            throw UncategorizedScriptException(
                    "Failed to execute database script from resource [resource]", ex)
        }
    }
}
