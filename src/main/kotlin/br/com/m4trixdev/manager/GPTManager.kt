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
import org.bukkit.plugin.Plugin
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.LogRecord

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
    private val abbreviations = ConcurrentHashMap<String, MutableList<String>>()
    private val pluginCache = ConcurrentHashMap<String, PluginInfo>()

    data class CommandFeedback(
        var successCount: Int = 0,
        var failCount: Int = 0,
        var lastError: String? = null,
        var fullCommand: String? = null
    )

    data class PluginInfo(
        val name: String,
        val version: String,
        val enabled: Boolean,
        val commands: Map<String, CommandInfo>
    )

    data class CommandInfo(
        val name: String,
        val aliases: List<String>,
        val description: String,
        val usage: String,
        val permission: String?
    )

    private val providerUrls = mapOf(
        "OPENAI" to "https://api.openai.com/v1/chat/completions",
        "ANTHROPIC" to "https://api.anthropic.com/v1/messages",
        "GROQ" to "https://api.groq.com/openai/v1/chat/completions",
        "OLLAMA" to "http://localhost:11434/v1/chat/completions",
        "OPENROUTER" to "https://openrouter.ai/api/v1/chat/completions",
        "GEMINI" to "https://generativelanguage.googleapis.com/v1beta/models/"
    )

    private val errorKeywords = listOf(
        "error", "expected", "invalid", "unknown", "incorrect",
        "failed", "cannot", "unable", "no player", "not found",
        "syntax", "usage", "whitespace", "trailing", "argument",
        "there is no", "does not exist", "could not", "exception"
    )

    private val commonAbbreviations = mapOf(
        "gm" to listOf("gamemode", "gm"),
        "gmc" to listOf("gamemode creative", "gm creative", "gm c"),
        "gms" to listOf("gamemode survival", "gm survival", "gm s"),
        "gma" to listOf("gamemode adventure", "gm adventure", "gm a"),
        "gmsp" to listOf("gamemode spectator", "gm spectator", "gm sp"),
        "tp" to listOf("teleport", "tp"),
        "tpa" to listOf("tpa", "teleport accept"),
        "tphere" to listOf("tphere", "tp here"),
        "ban" to listOf("ban", "tempban"),
        "unban" to listOf("unban", "pardon"),
        "wl" to listOf("whitelist", "wl"),
        "inv" to listOf("inventory", "invsee"),
        "ec" to listOf("enderchest", "ec"),
        "heal" to listOf("heal", "health"),
        "feed" to listOf("feed", "food"),
        "fly" to listOf("fly", "flight"),
        "speed" to listOf("speed", "walkspeed", "flyspeed"),
        "invsee" to listOf("invsee", "inventory see"),
        "ci" to listOf("clear inventory", "clear inv"),
        "wb" to listOf("worldborder", "wb"),
        "rg" to listOf("region", "rg", "worldguard"),
        "co" to listOf("coreprotect", "co"),
        "lp" to listOf("luckperms", "lp"),
        "perm" to listOf("permission", "perm"),
        "eff" to listOf("effect", "potion"),
        "ench" to listOf("enchant", "enchantment")
    )

    init {
        initializeAbbreviations()
        updatePluginCache()
    }

    private fun initializeAbbreviations() {
        for ((abbr, commands) in commonAbbreviations) {
            abbreviations[abbr] = commands.toMutableList()
        }
    }

    private fun updatePluginCache() {
        pluginCache.clear()
        for (plugin in Bukkit.getPluginManager().plugins) {
            val commands = mutableMapOf<String, CommandInfo>()

            for ((cmdName, cmdData) in plugin.description.commands) {
                val aliases = (cmdData["aliases"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                val description = cmdData["description"] as? String ?: "Sem descricao"
                val usage = cmdData["usage"] as? String ?: "/$cmdName"
                val permission = cmdData["permission"] as? String

                commands[cmdName] = CommandInfo(
                    name = cmdName,
                    aliases = aliases,
                    description = description,
                    usage = usage,
                    permission = permission
                )
            }

            pluginCache[plugin.name.lowercase()] = PluginInfo(
                name = plugin.name,
                version = plugin.description.version,
                enabled = plugin.isEnabled,
                commands = commands
            )
        }
    }

    fun processRequest(player: Player, message: String, errorContext: String? = null) {
        scope.launch {
            try {
                updatePluginCache()

                val context = buildFullContext(player)
                val history = conversationHistory.getOrPut(player.uniqueId) { mutableListOf() }

                val expandedMessage = expandAbbreviations(message)

                val finalMessage = if (errorContext != null) {
                    "ERRO NO COMANDO ANTERIOR: $errorContext\nPEDIDO ORIGINAL: $expandedMessage\nAnalise o erro, entenda o problema e corrija o comando. Use sintaxe diferente se necessario."
                } else {
                    expandedMessage
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

    private fun expandAbbreviations(message: String): String {
        var expanded = message
        val words = message.lowercase().split(" ")

        for (word in words) {
            if (abbreviations.containsKey(word)) {
                val possibleCommands = abbreviations[word]!!
                expanded = "$expanded [Abreviacao detectada: '$word' pode ser: ${possibleCommands.joinToString(", ")}]"
            }
        }

        return expanded
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
        sb.appendLine()
        sb.appendLine(buildAbbreviationsInfo())
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
        val sb = StringBuilder("=== LISTA COMPLETA DE PLUGINS INSTALADOS (${plugins.size}) ===\n")
        sb.appendLine("IMPORTANTE: SEMPRE verifique se o plugin existe nesta lista antes de responder ou executar comandos!")
        sb.appendLine()

        val pluginNames = plugins.map { it.name.lowercase() }.toSet()
        sb.appendLine("PLUGINS PRESENTES NO SERVIDOR:")
        sb.appendLine(plugins.joinToString(", ") { it.name })
        sb.appendLine()
        sb.appendLine("PLUGINS NAO PRESENTES: Se perguntar sobre EssentialsX, Vault, WorldGuard, LuckPerms, etc")
        sb.appendLine("e NAO estiver na lista acima, responda que NAO esta instalado!")
        sb.appendLine()

        for (p in plugins) {
            val status = if (p.isEnabled) "[ATIVO]" else "[DESATIVADO]"
            sb.appendLine("$status ${p.name} v${p.description.version}")
            sb.appendLine("  Autor: ${p.description.authors.joinToString(", ")}")
            sb.appendLine("  Main: ${p.description.main}")

            if (pluginCache.containsKey(p.name.lowercase())) {
                val pluginInfo = pluginCache[p.name.lowercase()]!!
                if (pluginInfo.commands.isNotEmpty()) {
                    sb.appendLine("  Comandos registrados (${pluginInfo.commands.size}):")
                    pluginInfo.commands.values.take(20).forEach { cmd ->
                        sb.append("    /${cmd.name}")
                        if (cmd.aliases.isNotEmpty()) {
                            sb.append(" [aliases: ${cmd.aliases.joinToString(", ")}]")
                        }
                        sb.appendLine()
                        sb.appendLine("      Descricao: ${cmd.description}")
                        sb.appendLine("      Uso: ${cmd.usage}")
                        if (cmd.permission != null) {
                            sb.appendLine("      Permissao: ${cmd.permission}")
                        }
                    }
                    if (pluginInfo.commands.size > 20) {
                        sb.appendLine("    ... e mais ${pluginInfo.commands.size - 20} comandos")
                    }
                }
            }
            sb.appendLine()
        }

        sb.appendLine("TOTAL DE PLUGINS: ${plugins.size}")
        sb.appendLine("PLUGINS ATIVOS: ${plugins.count { it.isEnabled }}")
        sb.appendLine("PLUGINS DESATIVADOS: ${plugins.count { !it.isEnabled }}")

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
        if (commandFeedback.isEmpty()) return "=== APRENDIZADO ===\nNenhum comando registrado ainda."

        val sb = StringBuilder("=== APRENDIZADO DE COMANDOS ===\n")
        sb.appendLine("Comandos que FUNCIONARAM corretamente:")
        for ((cmd, fb) in commandFeedback) {
            if (fb.successCount > fb.failCount && fb.successCount > 0) {
                sb.appendLine("  /${fb.fullCommand ?: cmd} - ${fb.successCount} execucoes bem-sucedidas")
            }
        }
        sb.appendLine()
        sb.appendLine("Comandos que FALHARAM:")
        for ((cmd, fb) in commandFeedback) {
            if (fb.failCount > 0) {
                sb.appendLine("  /$cmd - ${fb.failCount} falhas - Ultimo erro: ${fb.lastError}")
            }
        }
        return sb.toString()
    }

    private fun buildAbbreviationsInfo(): String {
        val sb = StringBuilder("=== ABREVIACOES CONHECIDAS ===\n")
        sb.appendLine("Quando o usuario usar abreviacoes, interprete corretamente:")
        for ((abbr, commands) in abbreviations.entries.take(20)) {
            sb.appendLine("$abbr = ${commands.joinToString(" ou ")}")
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

        val url = when (provider) {
            "GEMINI" -> "${providerUrls[provider]}${model}:generateContent?key=$apiKey"
            else -> baseUrl ?: providerUrls[provider] ?: throw IOException("Provedor invalido: $provider")
        }

        val systemPrompt = buildSystemPrompt(playerName, context)

        val request = when (provider) {
            "ANTHROPIC" -> buildAnthropicRequest(url, apiKey, model, maxTokens, temperature, systemPrompt, history)
            "GEMINI" -> buildGeminiRequest(url, maxTokens, temperature, systemPrompt, history)
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
                "GEMINI" -> jsonResponse.getAsJsonArray("candidates").get(0).asJsonObject
                    .getAsJsonObject("content").getAsJsonArray("parts").get(0).asJsonObject
                    .get("text").asString
                else -> jsonResponse.getAsJsonArray("choices").get(0).asJsonObject
                    .getAsJsonObject("message").get("content").asString
            }
        }
    }

    private fun buildSystemPrompt(playerName: String, context: String): String {
        return """
# Minecraft Server AI Administrator

You are a **PRECISE** and **FACTUAL** AI assistant for Minecraft server administration.

## ⚠️ CRITICAL VERIFICATION RULES

**BEFORE every response or command execution:**

1. **ALWAYS** read the "LISTA COMPLETA DE PLUGINS INSTALADOS" section in the context
2. **IF** a plugin is NOT in the list, it does NOT exist on the server
3. **NEVER** assume or invent that a plugin is installed
4. **NEVER** execute commands from plugins that are NOT in the list
5. **IF** asked about a plugin, VERIFY the list before responding
6. **ONLY** respond based on the actual plugin list provided

---

## 👤 Current Administrator

**Player**: `$playerName`  
**Role**: Server Administrator with FULL permissions  
**Authority**: Can execute ANY command including moderation, configuration, and management

---

## 📋 Server Context

$context

---

## 🔍 How to Answer About Plugins

### Example Question: "Do you have EssentialsX?" or "Is Vault installed?"

**Process:**
1. Search in the "PLUGINS PRESENTES NO SERVIDOR" section
2. If **NOT found**: Respond "No, the plugin [name] is NOT installed on this server"
3. If **found**: Respond "Yes, plugin [name] version [X.X] is installed and active"

### ✅ CORRECT Example:
```
User: "do you have essentials?"
You search the list → EssentialsX NOT found
Response:
MSG: &cNo, the EssentialsX plugin is NOT installed on this server. Installed plugins are: [list from section]
```

### ❌ WRONG Example:
```
User: "do you have essentials?"
Response: "Yes, to use Essentials commands..." ← WRONG! Did not verify the list!
```

---

## 🎮 How to Choose Commands

### Command Selection Process:

1. **Verify** which plugins ACTUALLY exist in the list
2. **Use ONLY** commands from installed plugins
3. **If plugin missing**: Use vanilla Minecraft commands
4. **If command requires missing plugin**: Inform the user

### Example: "heal me"

```
IF EssentialsX is installed:
  MSG: &aHealing you!
  CMD: /essentials:heal $playerName

IF EssentialsX is NOT installed:
  MSG: &aNo heal plugin installed, but I can use vanilla commands!
  CMD: /effect give $playerName instant_health 1 10
```

---

## 📤 Response Format

**Format your responses using these prefixes:**

- `MSG:` - Message to the player (use color codes: &a green, &c red, &e yellow, &b blue, &6 gold)
- `CMD:` - Command to execute immediately
- `DELAY:seconds:` - Command to execute after delay

### Example:
```
MSG: &aGiving you an OP sword!
CMD: /give $playerName minecraft:netherite_sword 1
CMD: /enchant $playerName sharpness 5
DELAY:2: /enchant $playerName unbreaking 3
```

---

## 🛠️ Vanilla Minecraft Commands

**Use these when NO plugins are available:**

### Player Management:
- `/gamemode creative|survival|spectator|adventure [player]`
- `/tp [player] <x> <y> <z>`
- `/kill [player]`
- `/clear [player]`
- `/xp add [player] [amount]`

### Items & Effects:
- `/give [player] <item> [amount]`
- `/enchant [player] <enchantment> [level]`
- `/effect give [player] <effect> [seconds] [amplifier]`

### World Management:
- `/time set day|night`
- `/weather clear|rain|thunder`
- `/difficulty peaceful|easy|normal|hard`
- `/gamerule [rule] [value]`
- `/setworldspawn`
- `/spawnpoint [player]`

---

## 💡 Practical Examples

### Example 1: Gamemode Change
```
User: "gmc" (abbreviation for gamemode creative)

Check plugin list:
- IF Essentials installed:
  MSG: &aCreative mode activated!
  CMD: /essentials:gmc $playerName

- IF Essentials NOT installed:
  MSG: &aCreative mode activated!
  CMD: /gamemode creative $playerName
```

### Example 2: Plugin Inquiry
```
User: "do you have Vault plugin?"

Check plugin list:
- IF Vault found:
  MSG: &aYes! Vault version X.X is installed and active.

- IF Vault NOT found:
  MSG: &cNo, the Vault plugin is NOT installed on this server.
```

### Example 3: OP Item Request
```
User: "give me an op sword"

MSG: &aHere's your supreme sword!
CMD: /give $playerName minecraft:netherite_sword 1
CMD: /enchant $playerName sharpness 5
CMD: /enchant $playerName unbreaking 3
CMD: /enchant $playerName fire_aspect 2
```

### Example 4: Plugin List
```
User: "what plugins are installed?"

MSG: &eInstalled plugins:\n&7[exact list from PLUGINS PRESENTES section]
```

---

## 🎯 Abbreviations Recognition

**Common abbreviations you should understand:**

- `gmc` = gamemode creative
- `gms` = gamemode survival
- `gma` = gamemode adventure
- `gmsp` = gamemode spectator
- `tp` = teleport
- `inv` = inventory/invsee
- `heal` = heal player
- `feed` = feed player
- `fly` = toggle flight
- `wl` = whitelist

**When user uses abbreviations, interpret them correctly and use appropriate commands.**

---

## ✅ Final Checklist (Before Every Response)

- [ ] Read the installed plugins list
- [ ] Verify if required plugin exists
- [ ] Use vanilla alternative if plugin is missing
- [ ] Never lie or invent information
- [ ] Be PRECISE, FACTUAL, and HONEST

---

**Remember:** You are a trusted tool for server administration. Accuracy and honesty are paramount.
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

    private fun buildGeminiRequest(
        url: String, maxTokens: Int, temperature: Double, systemPrompt: String, history: List<Map<String, String>>
    ): Request {
        val contents = mutableListOf<Map<String, Any>>()

        contents.add(mapOf(
            "role" to "user",
            "parts" to listOf(mapOf("text" to systemPrompt))
        ))

        for (msg in history.takeLast(10)) {
            val role = when (msg["role"]) {
                "assistant" -> "model"
                else -> "user"
            }
            contents.add(mapOf(
                "role" to role,
                "parts" to listOf(mapOf("text" to msg["content"]!!))
            ))
        }

        val requestBody = JsonObject().apply {
            add("contents", gson.toJsonTree(contents))
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", temperature)
                addProperty("maxOutputTokens", maxTokens)
            })
        }

        return Request.Builder()
            .url(url)
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
        fb.fullCommand = command
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
        pluginCache.clear()
    }
}