package br.com.m4trixdev.listeners

import br.com.m4trixdev.Main
import br.com.m4trixdev.config.MessageType
import br.com.m4trixdev.utils.ColorUtil
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

class ChatListener(private val plugin: Main) : Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player

        if (!plugin.databaseManager.isGPTEnabled(player.uniqueId)) {
            return
        }

        val message = PlainTextComponentSerializer.plainText().serialize(event.message())
        val marker = plugin.configManager.getTriggerMarker()

        if (!message.startsWith(marker)) {
            return
        }

        event.isCancelled = true

        val requestMessage = message.removePrefix(marker).trim()

        if (requestMessage.isBlank()) {
            player.sendMessage("§c[MatrixGPT] Você precisa especificar um pedido após o marcador '$marker'")
            return
        }

        val msg = plugin.configManager.getMessage("request-sent")
        if (msg != null) {
            sendMessage(player, msg)
        } else {
            player.sendMessage("§a[MatrixGPT] Pedido ao GPT enviado com sucesso...")
        }

        plugin.gptManager.processRequest(player, requestMessage)
    }

    private fun sendMessage(player: org.bukkit.entity.Player, msg: br.com.m4trixdev.config.MessageConfig) {
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