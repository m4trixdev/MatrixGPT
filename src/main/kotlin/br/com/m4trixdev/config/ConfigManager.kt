package br.com.m4trixdev.config

import br.com.m4trixdev.Main
import org.bukkit.configuration.file.FileConfiguration

class ConfigManager(private val plugin: Main) {

    private lateinit var config: FileConfiguration

    fun loadConfig() {
        plugin.saveDefaultConfig()
        plugin.reloadConfig()
        config = plugin.config
        validateConfig()
    }

    private fun validateConfig() {
        val requiredKeys = listOf(
            "database.type",
            "openai.api-key",
            "openai.model",
            "trigger.marker"
        )

        for (key in requiredKeys) {
            if (!config.contains(key)) {
                throw IllegalStateException("ERRO FATAL: Chave obrigatória '$key' não encontrada em config.yml")
            }
        }

        val dbType = config.getString("database.type")?.uppercase()
        if (dbType != "MYSQL" && dbType != "SQLITE") {
            throw IllegalStateException("ERRO FATAL: Tipo de banco de dados inválido. Use 'MYSQL' ou 'SQLITE'")
        }

        val provider = config.getString("openai.provider")?.uppercase() ?: "OPENAI"
        if (provider !in listOf("OPENAI", "ANTHROPIC", "GROQ", "OLLAMA", "OPENROUTER")) {
            throw IllegalStateException("ERRO FATAL: Provedor de IA inválido: $provider")
        }
    }

    fun getDatabaseType(): String = config.getString("database.type", "SQLITE")!!.uppercase()
    fun getMySQLHost(): String = config.getString("database.mysql.host", "localhost")!!
    fun getMySQLPort(): Int = config.getInt("database.mysql.port", 3306)
    fun getMySQLDatabase(): String = config.getString("database.mysql.database", "matrixgpt")!!
    fun getMySQLUsername(): String = config.getString("database.mysql.username", "root")!!
    fun getMySQLPassword(): String = config.getString("database.mysql.password", "")!!
    fun getSQLiteFile(): String = config.getString("database.sqlite.file", "matrixgpt.db")!!

    fun getAIProvider(): String = config.getString("openai.provider", "OPENAI")!!.uppercase()
    fun getAPIKey(): String = config.getString("openai.api-key", "")!!
    fun getModel(): String = config.getString("openai.model", "gpt-4")!!
    fun getMaxTokens(): Int = config.getInt("openai.max-tokens", 500)
    fun getTemperature(): Double = config.getDouble("openai.temperature", 0.7)
    fun getBaseUrl(): String? = config.getString("openai.base-url")

    fun getTriggerMarker(): String = config.getString("trigger.marker", "gpt,")!!

    fun getMessage(path: String): MessageConfig? {
        val fullPath = "messages.$path"
        if (!config.contains(fullPath)) return null

        val type = config.getString("$fullPath.type", "CHAT")!!.uppercase()
        val content = config.getString("$fullPath.content", "")
        val title = config.getString("$fullPath.title", "")
        val subtitle = config.getString("$fullPath.subtitle", "")

        return MessageConfig(
            type = MessageType.valueOf(type),
            content = content,
            title = title,
            subtitle = subtitle
        )
    }

    fun getSafeLocation(): SafeLocation {
        return SafeLocation(
            world = config.getString("safe-location.world", "world")!!,
            x = config.getDouble("safe-location.x", 0.0),
            y = config.getDouble("safe-location.y", 100.0),
            z = config.getDouble("safe-location.z", 0.0)
        )
    }
}

data class MessageConfig(
    val type: MessageType,
    val content: String?,
    val title: String?,
    val subtitle: String?
)

enum class MessageType {
    CHAT, TITLE, ACTIONBAR
}

data class SafeLocation(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double
)