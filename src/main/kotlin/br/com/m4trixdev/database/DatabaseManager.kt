package br.com.m4trixdev.database

import br.com.m4trixdev.Main
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DatabaseManager(private val plugin: Main) {

    private lateinit var dataSource: HikariDataSource
    private val gptStatusCache = ConcurrentHashMap<UUID, Boolean>()

    fun initialize() {
        val config = HikariConfig()

        when (plugin.configManager.getDatabaseType()) {
            "MYSQL" -> {
                config.jdbcUrl = "jdbc:mysql://${plugin.configManager.getMySQLHost()}:${plugin.configManager.getMySQLPort()}/${plugin.configManager.getMySQLDatabase()}"
                config.username = plugin.configManager.getMySQLUsername()
                config.password = plugin.configManager.getMySQLPassword()
                config.driverClassName = "com.mysql.cj.jdbc.Driver"
            }
            "SQLITE" -> {
                val dbFile = plugin.dataFolder.resolve(plugin.configManager.getSQLiteFile())
                config.jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
                config.driverClassName = "org.sqlite.JDBC"
            }
        }

        config.maximumPoolSize = 10
        config.minimumIdle = 2
        config.connectionTimeout = 30000
        config.idleTimeout = 600000
        config.maxLifetime = 1800000

        dataSource = HikariDataSource(config)

        createTables()
    }

    private fun createTables() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS gpt_users (
                        uuid VARCHAR(36) PRIMARY KEY,
                        gpt_enabled BOOLEAN DEFAULT FALSE,
                        last_updated BIGINT
                    )
                """.trimIndent())

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS gpt_history (
                        id INTEGER PRIMARY KEY ${if (plugin.configManager.getDatabaseType() == "SQLITE") "AUTOINCREMENT" else "AUTO_INCREMENT"},
                        uuid VARCHAR(36),
                        request TEXT,
                        response TEXT,
                        timestamp BIGINT
                    )
                """.trimIndent())
            }
        }
    }

    fun isGPTEnabled(uuid: UUID): Boolean {
        return gptStatusCache.getOrPut(uuid) {
            getConnection().use { conn ->
                conn.prepareStatement("SELECT gpt_enabled FROM gpt_users WHERE uuid = ?").use { stmt ->
                    stmt.setString(1, uuid.toString())
                    val rs = stmt.executeQuery()
                    if (rs.next()) rs.getBoolean("gpt_enabled") else false
                }
            }
        }
    }

    fun setGPTEnabled(uuid: UUID, enabled: Boolean) {
        gptStatusCache[uuid] = enabled
        val timestamp = System.currentTimeMillis()

        getConnection().use { conn ->
            val sql = if (plugin.configManager.getDatabaseType() == "SQLITE") {
                """
                    INSERT INTO gpt_users (uuid, gpt_enabled, last_updated) 
                    VALUES (?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET gpt_enabled = ?, last_updated = ?
                """.trimIndent()
            } else {
                """
                    INSERT INTO gpt_users (uuid, gpt_enabled, last_updated) 
                    VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE gpt_enabled = ?, last_updated = ?
                """.trimIndent()
            }

            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.setBoolean(2, enabled)
                stmt.setLong(3, timestamp)
                stmt.setBoolean(4, enabled)
                stmt.setLong(5, timestamp)
                stmt.executeUpdate()
            }
        }
    }

    fun saveHistory(uuid: UUID, request: String, response: String) {
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO gpt_history (uuid, request, response, timestamp)
                VALUES (?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.setString(2, request)
                stmt.setString(3, response)
                stmt.setLong(4, System.currentTimeMillis())
                stmt.executeUpdate()
            }
        }
    }

    fun getConnection(): Connection {
        return dataSource.connection
    }

    fun close() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }
}