package br.com.m4trixdev

import br.com.m4trixdev.commands.GPTCommand
import br.com.m4trixdev.config.ConfigManager
import br.com.m4trixdev.database.DatabaseManager
import br.com.m4trixdev.listeners.ChatListener
import br.com.m4trixdev.manager.GPTManager
import org.bukkit.plugin.java.JavaPlugin

class Main : JavaPlugin() {

    lateinit var configManager: ConfigManager
    lateinit var databaseManager: DatabaseManager
    lateinit var gptManager: GPTManager

    override fun onEnable() {
        try {
            configManager = ConfigManager(this)
            configManager.loadConfig()

            databaseManager = DatabaseManager(this)
            databaseManager.initialize()

            gptManager = GPTManager(this)

            registerCommands()
            registerListeners()

            logger.info("MatrixGPT habilitado com sucesso!")
        } catch (e: Exception) {
            logger.severe("ERRO FATAL ao inicializar o plugin: ${e.message}")
            e.printStackTrace()
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        if (::gptManager.isInitialized) {
            gptManager.shutdown()
        }
        if (::databaseManager.isInitialized) {
            databaseManager.close()
        }
        logger.info("MatrixGPT desabilitado!")
    }

    private fun registerCommands() {
        getCommand("gpt")?.setExecutor(GPTCommand(this))
    }

    private fun registerListeners() {
        server.pluginManager.registerEvents(ChatListener(this), this)
    }

    fun reload() {
        configManager.loadConfig()
    }
}