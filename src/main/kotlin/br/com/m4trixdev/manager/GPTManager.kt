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
                val description = cmdData["description"] as? String ?: "No description"
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
                    "PREVIOUS COMMAND ERROR: $errorContext\nORIGINAL REQUEST: $expandedMessage\nAnalyze the error and fix the command with correct syntax."
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
                    player.sendMessage("§c[MatrixGPT] §7Error: §f${e.message}")
                })
                plugin.logger.severe("GPT Error: ${e.message}")
            }
        }
    }

    private fun expandAbbreviations(message: String): String {
        var expanded = message
        val words = message.lowercase().split(" ")

        for (word in words) {
            if (abbreviations.containsKey(word)) {
                val possibleCommands = abbreviations[word]!!
                expanded = "$expanded [Abbreviation detected: '$word' could mean: ${possibleCommands.joinToString(", ")}]"
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
### SERVER INFORMATION
- **Name**: ${server.name}
- **MC Version**: $mcVersion
- **Full Version**: ${server.version}
- **MOTD**: ${server.motd}
- **Max Players**: ${server.maxPlayers}
- **Online Players**: ${server.onlinePlayers.size}
- **TPS**: ${"%.2f".format(tps)}
- **Memory**: ${usedMemory}MB / ${maxMemory}MB
- **Online Mode**: ${server.onlineMode}
- **Port**: ${server.port}
        """.trimIndent()
    }

    private fun buildPluginsInfo(): String {
        val plugins = Bukkit.getPluginManager().plugins
        val sb = StringBuilder("### INSTALLED PLUGINS LIST (${plugins.size} total)\n\n")
        sb.appendLine("**IMPORTANT**: Always verify if a plugin exists in this list before responding or executing commands!\n")

        sb.appendLine("**PLUGINS PRESENT ON SERVER**:")
        sb.appendLine(plugins.joinToString(", ") { it.name })
        sb.appendLine()
        sb.appendLine("**IF A PLUGIN IS NOT IN THE LIST ABOVE, IT DOES NOT EXIST ON THIS SERVER!**\n")

        for (p in plugins) {
            val status = if (p.isEnabled) "✅ ACTIVE" else "❌ DISABLED"
            sb.appendLine("#### $status ${p.name} v${p.description.version}")
            sb.appendLine("- **Authors**: ${p.description.authors.joinToString(", ")}")
            sb.appendLine("- **Main Class**: ${p.description.main}")

            if (pluginCache.containsKey(p.name.lowercase())) {
                val pluginInfo = pluginCache[p.name.lowercase()]!!
                if (pluginInfo.commands.isNotEmpty()) {
                    sb.appendLine("- **Registered Commands** (${pluginInfo.commands.size}):")
                    pluginInfo.commands.values.take(15).forEach { cmd ->
                        sb.append("  - `/${cmd.name}`")
                        if (cmd.aliases.isNotEmpty()) {
                            sb.append(" (aliases: ${cmd.aliases.joinToString(", ")})")
                        }
                        sb.appendLine()
                        sb.appendLine("    - Description: ${cmd.description}")
                        sb.appendLine("    - Usage: `${cmd.usage}`")
                        if (cmd.permission != null) {
                            sb.appendLine("    - Permission: `${cmd.permission}`")
                        }
                    }
                    if (pluginInfo.commands.size > 15) {
                        sb.appendLine("  - ... and ${pluginInfo.commands.size - 15} more commands")
                    }
                }
            }
            sb.appendLine()
        }

        sb.appendLine("**SUMMARY**:")
        sb.appendLine("- Total Plugins: ${plugins.size}")
        sb.appendLine("- Active: ${plugins.count { it.isEnabled }}")
        sb.appendLine("- Disabled: ${plugins.count { !it.isEnabled }}")

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
        ).joinToString(", ").ifEmpty { "None" }

        val playTime = try { player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60 } catch (e: Exception) { 0 }
        val deaths = try { player.getStatistic(Statistic.DEATHS) } catch (e: Exception) { 0 }
        val mobKills = try { player.getStatistic(Statistic.MOB_KILLS) } catch (e: Exception) { 0 }

        return """
### CURRENT PLAYER: ${player.name}
**Basic Info**:
- UUID: `${player.uniqueId}`
- Health: ${player.health.toInt()}/${player.maxHealth.toInt()}
- Hunger: ${player.foodLevel}/20
- XP Level: ${player.level}
- Gamemode: ${player.gameMode.name}
- OP Status: ${player.isOp}
- Flying: ${player.isFlying}
- Ping: ${player.ping}ms

**Location**:
- World: ${world.name} (${world.environment.name})
- Coordinates: X=${loc.blockX}, Y=${loc.blockY}, Z=${loc.blockZ}
- Biome: ${world.getBiome(loc.blockX, loc.blockY, loc.blockZ).key.key}

**Inventory**:
- Main Hand: ${mainHand.type.name} x${mainHand.amount}
- Armor: $armor

**Statistics**:
- Playtime: $playTime minutes
- Deaths: $deaths
- Mob Kills: $mobKills
        """.trimIndent()
    }

    private fun buildAllPlayersInfo(): String {
        val players = Bukkit.getOnlinePlayers()
        if (players.isEmpty()) return "### NO PLAYERS ONLINE"

        val sb = StringBuilder("### ALL ONLINE PLAYERS (${players.size})\n\n")
        for (p in players) {
            val loc = p.location
            sb.appendLine("- **${p.name}**: HP=${p.health.toInt()} | ${p.gameMode.name} | ${loc.world?.name} (${loc.blockX}, ${loc.blockY}, ${loc.blockZ}) | Ping=${p.ping}ms")
        }
        return sb.toString()
    }

    private fun buildWorldsInfo(): String {
        val worlds = Bukkit.getWorlds()
        val sb = StringBuilder("### WORLDS (${worlds.size})\n\n")
        for (w in worlds) {
            val isDay = w.time in 0..12000
            sb.appendLine("- **${w.name}**: ${w.environment.name} | ${if (isDay) "☀️ DAY" else "🌙 NIGHT"} | ${if (w.hasStorm()) "⛈️ STORM" else "☁️ CLEAR"} | Players: ${w.players.size}")
        }
        return sb.toString()
    }

    private fun buildLearnedInfo(): String {
        if (commandFeedback.isEmpty()) return "### COMMAND LEARNING DATA\nNo commands executed yet."

        val sb = StringBuilder("### COMMAND LEARNING DATA\n\n")
        sb.appendLine("**✅ SUCCESSFUL COMMANDS**:")
        for ((cmd, fb) in commandFeedback) {
            if (fb.successCount > fb.failCount && fb.successCount > 0) {
                sb.appendLine("- `/${fb.fullCommand ?: cmd}` - ${fb.successCount} successful executions")
            }
        }
        sb.appendLine()
        sb.appendLine("**❌ FAILED COMMANDS**:")
        for ((cmd, fb) in commandFeedback) {
            if (fb.failCount > 0) {
                sb.appendLine("- `/$cmd` - ${fb.failCount} failures - Last error: ${fb.lastError}")
            }
        }
        return sb.toString()
    }

    private fun buildAbbreviationsInfo(): String {
        val sb = StringBuilder("### KNOWN ABBREVIATIONS\n\n")
        sb.appendLine("When users use abbreviations, interpret them correctly:\n")
        for ((abbr, commands) in abbreviations.entries.take(20)) {
            sb.appendLine("- `$abbr` = ${commands.joinToString(" or ")}")
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
            else -> baseUrl ?: providerUrls[provider] ?: throw IOException("Invalid provider: $provider")
        }

        val systemPrompt = buildSystemPrompt(playerName, context)

        val request = when (provider) {
            "ANTHROPIC" -> buildAnthropicRequest(url, apiKey, model, maxTokens, temperature, systemPrompt, history)
            "GEMINI" -> buildGeminiRequest(url, maxTokens, temperature, systemPrompt, history)
            else -> buildOpenAIRequest(url, apiKey, model, maxTokens, temperature, systemPrompt, provider, history)
        }

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("API Error ($provider): ${response.code}")
            }
            val responseBody = response.body?.string() ?: throw IOException("Empty response")
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

You are an AI assistant specialized in **Minecraft server administration**. Your primary task is to **EXECUTE COMMANDS** based on administrator requests.

---

## 🎯 Your Role

**Current Administrator**: `$playerName`  
**Permission Level**: FULL ACCESS (OP with all permissions)  
**Authority**: Can execute ANY command including moderation, world management, and server configuration

---

## 📋 MANDATORY RESPONSE FORMAT

You **MUST** use these prefixes in **EVERY** response:

### Format Structure

```
MSG: <message to player with color codes>
CMD: <command to execute immediately>
DELAY:<seconds>: <command to execute after delay>
```

### Color Codes Available
- `&a` = Green (success)
- `&c` = Red (error/danger)
- `&e` = Yellow (warning/info)
- `&b` = Aqua (info)
- `&6` = Gold (highlight)
- `&7` = Gray (subtle)
- `&f` = White (normal)

---

## 💡 Practical Examples

### Example 1: Gamemode Change
**User says**: "gmc"

**Your response**:
```
MSG: &aCreative mode activated!
CMD: gamemode creative $playerName
```

### Example 2: OP Sword Request
**User says**: "give me op sword"

**Your response**:
```
MSG: &aHere's your supreme sword!
CMD: give $playerName netherite_sword 1
CMD: enchant $playerName sharpness 5
CMD: enchant $playerName unbreaking 3
CMD: enchant $playerName fire_aspect 2
```

### Example 3: Healing
**User says**: "heal me"

**Your response**:
```
MSG: &aHealing you now!
CMD: effect give $playerName instant_health 1 10
CMD: effect give $playerName regeneration 30 2
CMD: effect give $playerName saturation 1 10
```

### Example 4: World Management
**User says**: "make it day and clear weather"

**Your response**:
```
MSG: &eChanging time to day and clearing weather!
CMD: time set day
CMD: weather clear
```

### Example 5: Teleport
**User says**: "tp to spawn"

**Your response**:
```
MSG: &bTeleporting to spawn!
CMD: tp $playerName 0 100 0
```

### Example 6: Multiple Items
**User says**: "give me diamonds and gold"

**Your response**:
```
MSG: &6Giving you diamonds and gold!
CMD: give $playerName diamond 64
CMD: give $playerName gold_ingot 64
```

### Example 7: Delayed Action
**User says**: "give me strength in 5 seconds"

**Your response**:
```
MSG: &eYou'll receive strength in 5 seconds!
DELAY:5: effect give $playerName strength 60 2
```

---

## 🎮 Vanilla Minecraft Commands Reference

### Gamemode Commands
```
gamemode creative <player>
gamemode survival <player>
gamemode adventure <player>
gamemode spectator <player>
```

### Item Commands
```
give <player> <item> [amount]
clear <player> [item]
```

### Effect Commands
```
effect give <player> <effect> [duration] [amplifier]
effect clear <player> [effect]
```

**Common Effects**:
- `speed`, `slowness`, `haste`, `mining_fatigue`
- `strength`, `instant_health`, `instant_damage`
- `jump_boost`, `nausea`, `regeneration`, `resistance`
- `fire_resistance`, `water_breathing`, `invisibility`
- `night_vision`, `saturation`, `glowing`, `levitation`

### Enchantment Commands
```
enchant <player> <enchantment> [level]
```

**Common Enchantments**:
- `sharpness`, `smite`, `bane_of_arthropods`
- `knockback`, `fire_aspect`, `looting`
- `efficiency`, `fortune`, `silk_touch`
- `unbreaking`, `mending`, `protection`
- `feather_falling`, `thorns`, `respiration`

### Teleport Commands
```
tp <player> <x> <y> <z>
tp <player> <target_player>
tp <player> ~ ~10 ~
```

### World Commands
```
time set day
time set night
time set <value>
time add <value>
weather clear [duration]
weather rain [duration]
weather thunder [duration]
difficulty peaceful
difficulty easy
difficulty normal
difficulty hard
```

### XP Commands
```
xp add <player> <amount> [points|levels]
xp set <player> <amount> [points|levels]
```

### Other Useful Commands
```
kill <player>
spawnpoint <player> [x] [y] [z]
setworldspawn [x] [y] [z]
gamerule <rule> <value>
whitelist add <player>
whitelist remove <player>
ban <player> [reason]
pardon <player>
kick <player> [reason]
op <player>
deop <player>
```

---

## ⚡ Command Execution Rules

### Critical Rules
1. **NEVER** put `/` before commands in CMD: lines
2. **ALWAYS** use `$playerName` when referring to the current player
3. **ALWAYS** provide a MSG: line before executing commands
4. Use multiple CMD: lines for multiple commands
5. Use DELAY: for timed executions

### Good ✅
```
CMD: gamemode creative $playerName
CMD: give $playerName diamond 64
CMD: effect give $playerName speed 60 2
```

### Bad ❌
```
CMD: /gamemode creative $playerName
CMD: give playerName diamond 64
CMD: effect $playerName speed
```

---

## 🔍 Understanding Abbreviations

When users use abbreviations, understand them correctly:

- `gmc` → gamemode creative
- `gms` → gamemode survival
- `gma` → gamemode adventure
- `gmsp` → gamemode spectator
- `tp` → teleport
- `heal` → healing effects
- `feed` → saturation/food
- `fly` → toggle flight

---

## 📊 Server Context

$context

---

## 🎯 Response Strategy

### For Simple Requests
Respond with **1 MSG** and **1-3 CMD** lines.

### For Complex Requests
Respond with **1 MSG** and **multiple CMD** lines as needed.

### For Information Requests
Respond with **MSG only**, no CMD needed.

### For Plugin Commands
**ALWAYS** check the "INSTALLED PLUGINS LIST" first. If a plugin is NOT in the list, use vanilla alternatives or inform the user the plugin doesn't exist.

---

## ⚠️ Important Reminders

1. **BE CONCISE**: Don't write long explanations
2. **BE DIRECT**: Execute what is asked
3. **BE ACCURATE**: Use correct command syntax
4. **BE HELPFUL**: Provide feedback in MSG
5. **BE FAST**: Don't overthink simple requests

---

## 🚀 Now Execute!

Analyze the user's request and respond with the appropriate MSG: and CMD: format.

**Remember**: 
- No `/` before commands
- Use `$playerName` for current player
- Multiple commands = Multiple CMD: lines
- Always provide feedback via MSG:

Ready to execute commands!
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
            player.sendMessage("§d[AI] §f${translateColors(line)}")
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
            sendPrivateMessage(player, "&cFailed to execute after 3 attempts.")
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
                        processRequest(player, originalMessage, "Error: $errorMsg | Command: /$command")
                    }
                    !result && !changed -> {
                        retryAttempts[key] = attempts + 1
                        recordFailure(command, "Command not executed")
                        processRequest(player, originalMessage, "Command failed: /$command")
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
            recordFailure(command, e.message ?: "Unknown error")
            processRequest(player, originalMessage, "Exception: ${e.message}")
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