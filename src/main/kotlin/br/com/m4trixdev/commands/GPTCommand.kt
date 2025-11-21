package br.com.m4trixdev.commands

import br.com.m4trixdev.Main
import br.com.m4trixdev.config.MessageType
import br.com.m4trixdev.utils.ColorUtil
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class GPTCommand(private val plugin: Main) : CommandExecutor, TabCompleter {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cApenas jogadores podem usar este comando.")
            return true
        }

        if (!sender.hasPermission("matrixgpt.admin")) {
            val msg = plugin.configManager.getMessage("no-permission")
            if (msg != null) {
                sendMessage(sender, msg)
            } else {
                sender.sendMessage("§cVocê não tem permissão para usar este comando.")
            }
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage("§e[MatrixGPT] Uso: /gpt <on|off|reload>")
            return true
        }

        when (args[0].lowercase()) {
            "on" -> {
                plugin.databaseManager.setGPTEnabled(sender.uniqueId, true)
                val msg = plugin.configManager.getMessage("gpt-enabled")
                if (msg != null) {
                    sendMessage(sender, msg)
                } else {
                    sender.sendMessage("§a[MatrixGPT] Sistema de IA ativado!")
                }
            }

            "off" -> {
                plugin.databaseManager.setGPTEnabled(sender.uniqueId, false)
                val msg = plugin.configManager.getMessage("gpt-disabled")
                if (msg != null) {
                    sendMessage(sender, msg)
                } else {
                    sender.sendMessage("§c[MatrixGPT] Sistema de IA desativado!")
                }
            }

            "reload" -> {
                if (!sender.hasPermission("matrixgpt.admin.reload")) {
                    val msg = plugin.configManager.getMessage("no-permission")
                    if (msg != null) {
                        sendMessage(sender, msg)
                    } else {
                        sender.sendMessage("§cVocê não tem permissão para recarregar o plugin.")
                    }
                    return true
                }

                try {
                    plugin.reload()
                    val msg = plugin.configManager.getMessage("reload-success")
                    if (msg != null) {
                        sendMessage(sender, msg)
                    } else {
                        sender.sendMessage("§a[MatrixGPT] Configurações recarregadas com sucesso!")
                    }
                } catch (e: Exception) {
                    sender.sendMessage("§c[MatrixGPT] Erro ao recarregar: ${e.message}")
                    plugin.logger.severe("Erro ao recarregar configurações: ${e.message}")
                }
            }

            else -> {
                sender.sendMessage("§e[MatrixGPT] Uso: /gpt <on|off|reload>")
            }
        }

        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        if (args.size == 1) {
            return mutableListOf("on", "off", "reload")
                .filter { it.startsWith(args[0].lowercase()) }
                .toMutableList()
        }
        return mutableListOf()
    }

    private fun sendMessage(player: Player, msg: br.com.m4trixdev.config.MessageConfig) {
        when (msg.type) {
            MessageType.CHAT -> {
                if (!msg.content.isNullOrBlank()) {
                    player.sendMessage(ColorUtil.translate(msg.content))
                }
            }
            MessageType.TITLE -> {
                if (!msg.title.isNullOrBlank() || !msg.subtitle.isNullOrBlank()) {
                    player.showTitle(
                        net.kyori.adventure.title.Title.title(
                            ColorUtil.translate(msg.title ?: ""),
                            ColorUtil.translate(msg.subtitle ?: "")
                        )
                    )
                }
            }
            MessageType.ACTIONBAR -> {
                if (!msg.content.isNullOrBlank()) {
                    player.sendActionBar(ColorUtil.translate(msg.content))
                }
            }
        }
    }
}