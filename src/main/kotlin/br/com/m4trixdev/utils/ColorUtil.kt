package br.com.m4trixdev.utils

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import java.awt.Color

object ColorUtil {

    private val miniMessage = MiniMessage.miniMessage()
    private val legacySerializer = LegacyComponentSerializer.builder()
        .character('&')
        .hexColors()
        .build()

    fun translate(text: String): Component {
        var processedText = text

        processedText = translateGradient(processedText)
        processedText = translateLegacy(processedText)

        return miniMessage.deserialize(processedText)
    }

    private fun translateLegacy(text: String): String {
        return text
            .replace("&", "§")
            .replace("§0", "<black>")
            .replace("§1", "<dark_blue>")
            .replace("§2", "<dark_green>")
            .replace("§3", "<dark_aqua>")
            .replace("§4", "<dark_red>")
            .replace("§5", "<dark_purple>")
            .replace("§6", "<gold>")
            .replace("§7", "<gray>")
            .replace("§8", "<dark_gray>")
            .replace("§9", "<blue>")
            .replace("§a", "<green>")
            .replace("§b", "<aqua>")
            .replace("§c", "<red>")
            .replace("§d", "<light_purple>")
            .replace("§e", "<yellow>")
            .replace("§f", "<white>")
            .replace("§k", "<obfuscated>")
            .replace("§l", "<bold>")
            .replace("§m", "<strikethrough>")
            .replace("§n", "<underlined>")
            .replace("§o", "<italic>")
            .replace("§r", "<reset>")
    }

    fun translateGradient(text: String): String {
        val gradientRegex = "<gradient:(#[0-9A-Fa-f]{6}):(#[0-9A-Fa-f]{6})>(.*?)</gradient>".toRegex()

        return gradientRegex.replace(text) { matchResult ->
            val startColor = matchResult.groupValues[1]
            val endColor = matchResult.groupValues[2]
            val content = matchResult.groupValues[3]

            "<gradient:$startColor:$endColor>$content</gradient>"
        }
    }

    private fun hexToRgb(hex: String): Triple<Int, Int, Int> {
        val color = Color.decode(hex)
        return Triple(color.red, color.green, color.blue)
    }

    fun stripColors(text: String): String {
        return text.replace("§[0-9a-fk-or]".toRegex(), "")
            .replace("&[0-9a-fk-or]".toRegex(), "")
    }
}