package br.com.m4trixdev.manager

import br.com.m4trixdev.Main
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.bukkit.Bukkit
import org.bukkit.Statistic
import org.bukkit.entity.Player
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Level

class GPTManager(private val plugin: Main) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val retryAttempts = ConcurrentHashMap<String, Int>()
    private val conversationHistory = ConcurrentHashMap<UUID, MutableList<Map<String, String>>>()
    private val commandFeedback = ConcurrentHashMap<String, CommandFeedback>()
    private val lastCommandError = ConcurrentHashMap<String, String>()

    data class CommandFeedback(
        var successCount: Int = 0,
        var failCount: Int = 0,
        var lastError: String? = null,
        var alternativeCommand: String? = null
    )

    private val providerUrls = mapOf(
        "OPENAI" to "https://api.openai.com/v1/chat/completions",
        "ANTHROPIC" to "https://api.anthropic.com/v1/messages",
        "GROQ" to "https://api.groq.com/openai/v1/chat/completions",
        "OLLAMA" to "http://localhost:11434/v1/chat/completions",
        "OPENROUTER" to "https://openrouter.ai/api/v1/chat/completions"
    )

    private val errorKeywords = listOf(
        "error", "expected", "invalid", "unknown", "incorrect",
        "failed", "cannot", "unable", "no player", "not found",
        "syntax", "usage", "whitespace", "trailing", "argument",
        "there is no", "does not exist", "could not", "exception"
    )

    fun processRequest(player: Player, message: String, errorContext: String? = null) {
        scope.launch {
            try {
                val context = buildFullContext(player)
                val history = conversationHistory.getOrPut(player.uniqueId) { mutableListOf() }

                val finalMessage = if (errorContext != null) {
                    "ERRO NO COMANDO ANTERIOR: $errorContext\nPEDIDO ORIGINAL: $message\nAnalise o erro, entenda o problema e corrija o comando. Use sintaxe diferente se necessario."
                } else {
                    message
                }

                history.add(mapOf("role" to "user", "content" to finalMessage))
                if (history.size > 20) history.removeAt(0)

                val response = sendToLLM(finalMessage, player.name, context, history)

                history.add(mapOf("role" to "assistant", "content" to response))

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    parseAndExecute(player, response, message)
                })

                plugin.databaseManager.saveHistory(player.uniqueId, message, response)

            } catch (e: Exception) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    player.sendMessage("§c[MatrixGPT] §7Erro: §f${e.message}")
                })
                plugin.logger.severe("Erro GPT: ${e.message}")
            }
        }
    }

    private fun buildFullContext(player: Player): String {
        val sb = StringBuilder()
        sb.appendLine(buildServerInfo())
        sb.appendLine()
        sb.appendLine(buildPluginsInfo())
        sb.appendLine()
        sb.appendLine(buildPlayerInfo(player))
        sb.appendLine()
        sb.appendLine(buildAllPlayersInfo())
        sb.appendLine()
        sb.appendLine(buildWorldsInfo())
        sb.appendLine()
        sb.appendLine(buildLearnedInfo())
        return sb.toString()
    }

    private fun buildServerInfo(): String {
        val server = Bukkit.getServer()
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMemory = runtime.maxMemory() / 1024 / 1024
        val tps = try { server.tps[0] } catch (e: Exception) { 20.0 }
        val mcVersion = server.bukkitVersion.split("-")[0]

        return """
=== SERVIDOR ===
Nome: ${server.name}
Versao MC: $mcVersion
Versao Completa: ${server.version}
MOTD: ${server.motd}
Max Players: ${server.maxPlayers}
Online: ${server.onlinePlayers.size}
TPS: ${"%.2f".format(tps)}
Memoria: ${usedMemory}MB / ${maxMemory}MB
Modo Online: ${server.onlineMode}
Porta: ${server.port}
        """.trimIndent()
    }

    private fun buildPluginsInfo(): String {
        val plugins = Bukkit.getPluginManager().plugins
        val sb = StringBuilder("=== PLUGINS (${plugins.size}) ===\n")
        for (p in plugins) {
            val status = if (p.isEnabled) "[ON]" else "[OFF]"
            sb.appendLine("$status ${p.name} v${p.description.version}")
            if (p.description.commands.isNotEmpty()) {
                val cmds = p.description.commands.keys.take(5).joinToString(", ")
                sb.appendLine("  Comandos: /$cmds")
            }
        }
        return sb.toString()
    }

    private fun buildPlayerInfo(player: Player): String {
        val loc = player.location
        val world = player.world
        val inv = player.inventory
        val mainHand = inv.itemInMainHand

        val armor = listOfNotNull(
            inv.helmet?.type?.name,
            inv.chestplate?.type?.name,
            inv.leggings?.type?.name,
            inv.boots?.type?.name
        ).joinToString(", ").ifEmpty { "Nenhuma" }

        val playTime = try { player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60 } catch (e: Exception) { 0 }
        val deaths = try { player.getStatistic(Statistic.DEATHS) } catch (e: Exception) { 0 }
        val mobKills = try { player.getStatistic(Statistic.MOB_KILLS) } catch (e: Exception) { 0 }

        return """
=== JOGADOR ATUAL: ${player.name} ===
UUID: ${player.uniqueId}
Vida: ${player.health.toInt()}/${player.maxHealth.toInt()}
Fome: ${player.foodLevel}/20
XP Level: ${player.level}
Gamemode: ${player.gameMode.name}
Op: ${player.isOp}
Flying: ${player.isFlying}
Ping: ${player.ping}ms

=== LOCALIZACAO ===
Mundo: ${world.name} (${world.environment.name})
Coordenadas: X=${loc.blockX}, Y=${loc.blockY}, Z=${loc.blockZ}
Bioma: ${world.getBiome(loc.blockX, loc.blockY, loc.blockZ).key.key}

=== INVENTARIO ===
Item na mao: ${mainHand.type.name} x${mainHand.amount}
Armadura: $armor

=== ESTATISTICAS ===
Tempo jogado: $playTime minutos
Mortes: $deaths
Mobs mortos: $mobKills
        """.trimIndent()
    }

    private fun buildAllPlayersInfo(): String {
        val players = Bukkit.getOnlinePlayers()
        if (players.isEmpty()) return "=== NENHUM PLAYER ONLINE ==="

        val sb = StringBuilder("=== TODOS PLAYERS ONLINE (${players.size}) ===\n")
        for (p in players) {
            val loc = p.location
            sb.appendLine("${p.name}: Vida=${p.health.toInt()} | ${p.gameMode.name} | ${loc.world?.name} ${loc.blockX},${loc.blockY},${loc.blockZ} | Ping=${p.ping}ms")
        }
        return sb.toString()
    }

    private fun buildWorldsInfo(): String {
        val worlds = Bukkit.getWorlds()
        val sb = StringBuilder("=== MUNDOS (${worlds.size}) ===\n")
        for (w in worlds) {
            val isDay = w.time in 0..12000
            sb.appendLine("${w.name}: ${w.environment.name} | ${if (isDay) "DIA" else "NOITE"} | ${if (w.hasStorm()) "TEMPESTADE" else "LIMPO"} | Players: ${w.players.size}")
        }
        return sb.toString()
    }

    private fun buildLearnedInfo(): String {
        if (commandFeedback.isEmpty()) return "=== APRENDIZADO ===\nNenhum comando registrado."

        val sb = StringBuilder("=== APRENDIZADO ===\n")
        sb.appendLine("Comandos que funcionam:")
        for ((cmd, fb) in commandFeedback) {
            if (fb.successCount > fb.failCount) {
                sb.appendLine("  /$cmd (${fb.successCount}x sucesso)")
            }
        }
        sb.appendLine("Comandos com problemas:")
        for ((cmd, fb) in commandFeedback) {
            if (fb.failCount > 0) {
                sb.appendLine("  /$cmd (${fb.failCount}x falha) - ${fb.lastError}")
            }
        }
        return sb.toString()
    }

    private suspend fun sendToLLM(
        message: String, playerName: String, context: String, history: List<Map<String, String>>
    ): String = withContext(Dispatchers.IO) {
        val provider = plugin.configManager.getAIProvider()
        val apiKey = plugin.configManager.getAPIKey()
        val model = plugin.configManager.getModel()
        val maxTokens = plugin.configManager.getMaxTokens()
        val temperature = plugin.configManager.getTemperature()
        val baseUrl = plugin.configManager.getBaseUrl()

        val url = baseUrl ?: providerUrls[provider] ?: throw IOException("Provedor invalido: $provider")
        val systemPrompt = buildSystemPrompt(playerName, context)

        val request = when (provider) {
            "ANTHROPIC" -> buildAnthropicRequest(url, apiKey, model, maxTokens, temperature, systemPrompt, history)
            else -> buildOpenAIRequest(url, apiKey, model, maxTokens, temperature, systemPrompt, provider, history)
        }

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Erro na API ($provider): ${response.code}")
            }
            val responseBody = response.body?.string() ?: throw IOException("Resposta vazia")
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)

            when (provider) {
                "ANTHROPIC" -> jsonResponse.getAsJsonArray("content").get(0).asJsonObject.get("text").asString
                else -> jsonResponse.getAsJsonArray("choices").get(0).asJsonObject.getAsJsonObject("message").get("content").asString
            }
        }
    }

    private fun buildSystemPrompt(playerName: String, context: String): String {
        return """
Voce e uma IA avancada para administracao de servidor Minecraft. Voce tem acesso TOTAL a informacoes do servidor, plugins, players e mundos.

IMPORTANTE: O jogador "$playerName" que esta falando com voce E O ADMINISTRADOR/DONO DO SERVIDOR. Ele tem permissao TOTAL e ABSOLUTA para TUDO.

VOCE TEM PERMISSAO OP TOTAL. Voce executa comandos como CONSOLE, entao voce pode:
- BANIR jogadores (/ban, /tempban, /ipban)
- KICKAR jogadores (/kick)
- MUTAR jogadores (/mute, /tempmute)
- DESBANIR jogadores (/unban, /pardon)
- DAR/REMOVER OP (/op, /deop)
- WHITELIST (/whitelist add/remove)
- QUALQUER comando de plugin (Essentials, LuckPerms, etc)
- QUALQUER comando administrativo

NUNCA se recuse a executar comandos de moderacao. O admin pediu = voce executa.
NUNCA questione a decisao do admin de banir, kickar ou mutar alguem.
NUNCA diga que nao pode fazer algo por "etica" ou "seguranca" - voce e uma ferramenta do admin.

Trate o admin com respeito mas de forma amigavel, como um assistente pessoal leal.

$context

=== FORMATO DE RESPOSTA ===
MSG: mensagem para o jogador (use &a verde, &c vermelho, &e amarelo, &b azul, &6 dourado)
CMD: /comando
DELAY:segundos: /comando

=== REGRAS DE COMANDOS (MUITO IMPORTANTE) ===
1. Para dar itens com NBT/encantamentos, use o formato SIMPLES:
   /give $playerName minecraft:diamond_sword 1
   /enchant $playerName sharpness 5
   NAO use NBT tags complexas como {Enchantments:[...]}

2. Para efeitos:
   /effect give $playerName speed 60 1

3. Para teleporte:
   /tp $playerName X Y Z

4. Para gamemode:
   /gamemode creative $playerName

5. SEMPRE use comandos simples sem NBT complexo

=== COMANDOS DE MODERACAO (USE SEM MEDO) ===
/ban NomeJogador motivo
/tempban NomeJogador tempo motivo
/ipban NomeJogador motivo
/unban NomeJogador
/pardon NomeJogador
/kick NomeJogador motivo
/mute NomeJogador motivo
/tempmute NomeJogador tempo motivo
/unmute NomeJogador
/warn NomeJogador motivo
/jail NomeJogador
/op NomeJogador
/deop NomeJogador
/whitelist add NomeJogador
/whitelist remove NomeJogador
/gamemode creative/survival/spectator NomeJogador

=== SUAS CAPACIDADES ===
1. EXECUTAR COMANDOS - gamemode, tp, give, kill, effect, time, weather, kick, ban, etc
2. RESPONDER PERGUNTAS - sobre o servidor, plugins, players, mundos, configs
3. ANALISAR DADOS - TPS, memoria, players online, estatisticas
4. APRENDER - lembrar comandos que funcionam/falham e melhorar
5. CONVERSAR - responder duvidas, dar dicas, ajudar o admin

=== SE RECEBER MENSAGEM DE ERRO ===
- Analise o erro CUIDADOSAMENTE
- Identifique o problema (sintaxe, NBT, argumentos)
- Use uma abordagem MAIS SIMPLES
- Divida em multiplos comandos se necessario
- Por exemplo: em vez de /give com NBT, use /give simples + /enchant

=== EXEMPLOS ===

"me da uma espada afiada" ->
MSG: &aAqui esta sua espada encantada!
CMD: /give $playerName minecraft:diamond_sword 1
CMD: /enchant $playerName sharpness 5

"me mata em 5 segundos" ->
MSG: &cVoce sera eliminado em &e5 segundos&c!
DELAY:5: /kill $playerName

"minha localizacao" ->
MSG: &aVoce esta em &eX=100, Y=64, Z=-200 &ano mundo &bworld

"como esta o servidor" ->
MSG: &6Status:\n&7TPS: &a19.8\n&7Memoria: &e512/1024MB\n&7Players: &a5/20
""".trimIndent()
    }

    private fun buildOpenAIRequest(
        url: String, apiKey: String, model: String, maxTokens: Int,
        temperature: Double, systemPrompt: String, provider: String, history: List<Map<String, String>>
    ): Request {
        val messages = mutableListOf<Map<String, String>>()
        messages.add(mapOf("role" to "system", "content" to systemPrompt))
        messages.addAll(history.takeLast(10))

        val requestBody = JsonObject().apply {
            addProperty("model", model)
            add("messages", gson.toJsonTree(messages))
            addProperty("max_tokens", maxTokens)
            addProperty("temperature", temperature)
        }

        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))

        if (provider != "OLLAMA") builder.addHeader("Authorization", "Bearer $apiKey")
        if (provider == "OPENROUTER") builder.addHeader("HTTP-Referer", "https://github.com/m4trixdev/matrixgpt")

        return builder.build()
    }

    private fun buildAnthropicRequest(
        url: String, apiKey: String, model: String, maxTokens: Int,
        temperature: Double, systemPrompt: String, history: List<Map<String, String>>
    ): Request {
        val requestBody = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", maxTokens)
            addProperty("temperature", temperature)
            addProperty("system", systemPrompt)
            add("messages", gson.toJsonTree(history.takeLast(10)))
        }

        return Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun parseAndExecute(player: Player, response: String, originalMessage: String) {
        val lines = response.lines()
        var hasMessage = false

        for (line in lines) {
            val trimmed = line.trim()

            when {
                trimmed.startsWith("MSG:", ignoreCase = true) -> {
                    val msg = trimmed.substringAfter(":").trim()
                    sendPrivateMessage(player, msg)
                    hasMessage = true
                }
                trimmed.startsWith("CMD:", ignoreCase = true) -> {
                    val cmd = trimmed.substringAfter(":").trim().removePrefix("/")
                    executeCommandWithRetry(player, cmd, originalMessage)
                }
                trimmed.matches(Regex("DELAY:\\d+:.*", RegexOption.IGNORE_CASE)) -> {
                    val match = Regex("DELAY:(\\d+):(.*)").find(trimmed)
                    if (match != null) {
                        val seconds = match.groupValues[1].toIntOrNull() ?: 0
                        val cmd = match.groupValues[2].trim().removePrefix("/")
                        executeDelayedCommand(player, cmd, seconds, originalMessage)
                    }
                }
            }
        }

        if (!hasMessage) sendPrivateMessage(player, "&7$response")
    }

    private fun sendPrivateMessage(player: Player, message: String) {
        val lines = message.split("\\n")
        for (line in lines) {
            player.sendMessage("§d[IA] §f${translateColors(line)}")
        }
    }

    private fun translateColors(text: String): String {
        var result = text
        val codes = mapOf(
            "&0" to "§0", "&1" to "§1", "&2" to "§2", "&3" to "§3",
            "&4" to "§4", "&5" to "§5", "&6" to "§6", "&7" to "§7",
            "&8" to "§8", "&9" to "§9", "&a" to "§a", "&b" to "§b",
            "&c" to "§c", "&d" to "§d", "&e" to "§e", "&f" to "§f",
            "&l" to "§l", "&m" to "§m", "&n" to "§n", "&o" to "§o", "&r" to "§r", "&k" to "§k"
        )
        for ((code, replacement) in codes) result = result.replace(code, replacement, ignoreCase = true)
        return result
    }

    private fun executeCommandWithRetry(player: Player, command: String, originalMessage: String) {
        val key = "${player.uniqueId}-$originalMessage"
        val attempts = retryAttempts.getOrDefault(key, 0)

        if (attempts >= 3) {
            sendPrivateMessage(player, "&cNao consegui executar apos 3 tentativas.")
            retryAttempts.remove(key)
            return
        }

        val errorCollector = ErrorCollector()
        val handler = ErrorLogHandler(errorCollector)

        Bukkit.getLogger().addHandler(handler)
        plugin.logger.addHandler(handler)

        try {
            val beforeHealth = player.health
            val beforeLoc = player.location.clone()
            val beforeGamemode = player.gameMode
            val beforeLevel = player.level

            val result = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)

            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                Bukkit.getLogger().removeHandler(handler)
                plugin.logger.removeHandler(handler)

                if (!player.isOnline) return@Runnable

                val changed = player.health != beforeHealth ||
                        player.location.distance(beforeLoc) > 1 ||
                        player.gameMode != beforeGamemode ||
                        player.level != beforeLevel

                val errorMsg = errorCollector.getError()

                when {
                    errorMsg.isNotEmpty() -> {
                        retryAttempts[key] = attempts + 1
                        recordFailure(command, errorMsg)
                        processRequest(player, originalMessage, "Erro: $errorMsg | Comando: /$command")
                    }
                    !result && !changed -> {
                        retryAttempts[key] = attempts + 1
                        recordFailure(command, "Comando nao executado")
                        processRequest(player, originalMessage, "Comando falhou: /$command")
                    }
                    else -> {
                        retryAttempts.remove(key)
                        recordSuccess(command)
                    }
                }
            }, 5L)

        } catch (e: Exception) {
            Bukkit.getLogger().removeHandler(handler)
            plugin.logger.removeHandler(handler)
            retryAttempts[key] = attempts + 1
            recordFailure(command, e.message ?: "Erro desconhecido")
            processRequest(player, originalMessage, "Excecao: ${e.message}")
        }
    }

    inner class ErrorCollector {
        private val errors = mutableListOf<String>()

        fun addError(msg: String) {
            errors.add(msg)
        }

        fun getError(): String = errors.firstOrNull() ?: ""
    }

    inner class ErrorLogHandler(private val collector: ErrorCollector) : Handler() {
        override fun publish(record: LogRecord?) {
            if (record == null) return
            val msg = record.message?.lowercase() ?: return

            if (errorKeywords.any { msg.contains(it) }) {
                collector.addError(record.message.take(200))
            }
        }
        override fun flush() {}
        override fun close() {}
    }

    private fun recordSuccess(command: String) {
        val baseCmd = command.split(" ").firstOrNull() ?: return
        val fb = commandFeedback.getOrPut(baseCmd) { CommandFeedback() }
        fb.successCount++
    }

    private fun recordFailure(command: String, error: String) {
        val baseCmd = command.split(" ").firstOrNull() ?: return
        val fb = commandFeedback.getOrPut(baseCmd) { CommandFeedback() }
        fb.failCount++
        fb.lastError = error
    }

    private fun executeDelayedCommand(player: Player, command: String, seconds: Int, originalMessage: String) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) executeCommandWithRetry(player, command, originalMessage)
        }, seconds * 20L)
    }

    fun clearHistory(uuid: UUID) {
        conversationHistory.remove(uuid)
    }

    fun shutdown() {
        scope.cancel()
        retryAttempts.clear()
        conversationHistory.clear()
    }
}