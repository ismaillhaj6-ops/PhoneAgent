package com.khaled.phoneagent

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

enum class Role { USER, ASSISTANT, SYSTEM }

/** What the agent needs from the screen. MainActivity implements this. */
interface AgentUi {
    fun addMessage(role: Role, text: String)
    fun setBusy(busy: Boolean)
    fun askDecision(p: PendingMessage)   // show contact picker and/or the Confirm / Cancel card
    fun clearDecision()
}

/** The tools Claude is allowed to call. Add new abilities here (phase 2+). */
object Tools {
    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

    private fun enumStr(desc: String, vararg values: String) = JSONObject()
        .put("type", "string")
        .put("description", desc)
        .put("enum", JSONArray(values.toList()))

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String>) = JSONObject()
        .put("name", name)
        .put("description", desc)
        .put(
            "input_schema",
            JSONObject()
                .put("type", "object")
                .put("properties", props)
                .put("required", JSONArray(required))
        )

    fun definitions(): JSONArray = JSONArray()
        .put(
            tool(
                "open_app",
                "Open an installed app on the phone by name, e.g. WhatsApp, Telegram, Chrome, YouTube.",
                JSONObject().put("app_name", str("Name of the app to open")),
                listOf("app_name")
            )
        )
        .put(
            tool(
                "web_search",
                "Open a web search (Google or YouTube) for the given text.",
                JSONObject()
                    .put("query", str("What to search for"))
                    .put("site", enumStr("Where to search", "google", "youtube")),
                listOf("query", "site")
            )
        )
        .put(
            tool(
                "prepare_message",
                "Prepare a message to a contact on WhatsApp, SMS or Telegram. This does NOT send anything: " +
                    "it shows the recipient and text to the user, who must confirm. Always use this tool " +
                    "for any request to send a message.",
                JSONObject()
                    .put("app", enumStr("Messaging app", "whatsapp", "sms", "telegram"))
                    .put("contact_name", str("Name of the contact as the user said it"))
                    .put("message", str("Exact text of the message to send")),
                listOf("app", "contact_name", "message")
            )
        )
}

class Agent(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ui: AgentUi
) {
    companion object {
        /** Change this to use another Claude model. */
        const val MODEL = "claude-haiku-5-5"
        private const val MAX_STEPS = 6
    }

    private val history = JSONArray()
    private var job: Job? = null

    /** A message waiting for the user's decision. Nothing is sent while this is set. */
    var pending: PendingMessage? = null
        private set
    private var pendingToolUseId: String? = null

    private fun systemPrompt() = """
        You are PhoneAgent, a personal assistant that runs on the user's Android phone and can perform actions with tools.
        Today's date is ${LocalDate.now()}.
        Rules:
        - Use tools to actually do things. Do not pretend to have done something you did not do.
        - To send a message you MUST call prepare_message. It never sends by itself: the user confirms first.
        - If the contact, app or message text is unclear, ask a short question instead of guessing.
        - You can only use your tools. You cannot read the screen or other apps yet. Say so if asked.
        - Never ask for or handle passwords, bank details or verification codes.
        - Reply in the same language as the user (English, French or Arabic). Keep replies short and clear.
    """.trimIndent()

    // ---------- Entry points from the UI ----------

    fun onUserText(text: String) {
        val t = text.trim()
        if (t.isEmpty() || pending != null || job?.isActive == true) return
        ui.addMessage(Role.USER, t)
        history.put(JSONObject().put("role", "user").put("content", t))
        runLoop()
    }

    fun selectCandidate(index: Int) {
        val p = pending ?: return
        p.chosen = p.candidates.getOrNull(index) ?: return
        ui.askDecision(p)
    }

    fun confirmPending() {
        val p = pending ?: return
        val id = pendingToolUseId ?: return
        pending = null
        pendingToolUseId = null
        ui.clearDecision()
        val result = Actions.sendMessage(context, p)
        ui.addMessage(Role.SYSTEM, (if (result.startsWith("Error")) "❌ " else "✅ ") + result)
        addToolResult(id, result, result.startsWith("Error"))
        runLoop()
    }

    fun cancelPending() {
        val p = pending ?: return
        val id = pendingToolUseId ?: return
        pending = null
        pendingToolUseId = null
        ui.clearDecision()
        ui.addMessage(Role.SYSTEM, "🚫 Cancelled. Nothing was sent to ${p.chosen?.name ?: p.contactQuery}.")
        addToolResult(id, "The user cancelled. The message was NOT sent.", false)
        runLoop()
    }

    /** Emergency stop: cancels the request, drops any pending action. */
    fun emergencyStop() {
        job?.cancel()
        ClaudeClient.cancel()
        pending = null
        pendingToolUseId = null
        ui.clearDecision()
        repairHistory("Cancelled by the user (emergency stop).")
        ui.setBusy(false)
        ui.addMessage(Role.SYSTEM, "⛔ Stopped. Nothing further will be executed.")
    }

    // ---------- The agent loop ----------

    private fun runLoop() {
        val apiKey = SecureStore.getApiKey(context)
        if (apiKey.isBlank()) {
            ui.addMessage(Role.SYSTEM, "Please add your API key first (⚙ button).")
            return
        }
        job = scope.launch {
            ui.setBusy(true)
            try {
                for (step in 0 until MAX_STEPS) {
                    val resp = withContext(Dispatchers.IO) {
                        ClaudeClient.send(apiKey, MODEL, systemPrompt(), history, Tools.definitions())
                    }
                    val content = resp.getJSONArray("content")
                    if (content.length() == 0) return@launch
                    history.put(JSONObject().put("role", "assistant").put("content", content))

                    var toolUse: JSONObject? = null
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        when (block.optString("type")) {
                            "text" -> {
                                val t = block.optString("text").trim()
                                if (t.isNotEmpty()) ui.addMessage(Role.ASSISTANT, t)
                            }
                            "tool_use" -> toolUse = block
                        }
                    }
                    if (toolUse == null) return@launch          // Claude is done
                    val pausedForUser = handleTool(toolUse)
                    if (pausedForUser) return@launch
                }
                ui.addMessage(Role.SYSTEM, "Stopped: too many steps in a row.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repairHistory("Failed: ${e.message}")
                ui.addMessage(Role.SYSTEM, "Error: ${e.message}")
            } finally {
                ui.setBusy(false)
            }
        }
    }

    /** Runs one tool call. Returns true if we must wait for the user (confirmation). */
    private suspend fun handleTool(block: JSONObject): Boolean {
        val id = block.getString("id")
        val name = block.getString("name")
        val input = block.optJSONObject("input") ?: JSONObject()

        when (name) {
            "open_app" -> {
                val app = input.optString("app_name")
                ui.addMessage(Role.SYSTEM, "▶ Opening app: $app")
                val r = Actions.openApp(context, app)
                addToolResult(id, r, r.startsWith("Error"))
            }

            "web_search" -> {
                val query = input.optString("query")
                val site = input.optString("site", "google")
                ui.addMessage(Role.SYSTEM, "▶ Searching $site: $query")
                val r = Actions.webSearch(context, query, site)
                addToolResult(id, r, r.startsWith("Error"))
            }

            "prepare_message" -> {
                val app = input.optString("app").lowercase()
                val contact = input.optString("contact_name").trim()
                val message = input.optString("message")
                if (app !in listOf("whatsapp", "sms", "telegram") || message.isBlank()) {
                    addToolResult(id, "Error: invalid app or empty message.", true)
                    return false
                }

                val p: PendingMessage
                if (app == "telegram") {
                    p = PendingMessage(app, contact, message, emptyList(), null)
                } else {
                    if (contact.isBlank()) {
                        addToolResult(id, "Error: contact name is missing. Ask the user who it is for.", true)
                        return false
                    }
                    val found = try {
                        withContext(Dispatchers.IO) { Actions.findContacts(context, contact) }
                    } catch (e: SecurityException) {
                        addToolResult(
                            id,
                            "Error: the app has no permission to read contacts. Tell the user to allow Contacts for PhoneAgent in Android settings.",
                            true
                        )
                        return false
                    }
                    if (found.isEmpty()) {
                        addToolResult(id, "No contact matching '$contact' exists in the phone. Ask the user for the exact name.", true)
                        return false
                    }
                    p = PendingMessage(app, contact, message, found, if (found.size == 1) found[0] else null)
                }

                pending = p
                pendingToolUseId = id
                ui.addMessage(Role.SYSTEM, "📝 Message prepared. Waiting for your confirmation.")
                ui.askDecision(p)
                return true
            }

            else -> addToolResult(id, "Error: unknown tool '$name'.", true)
        }
        return false
    }

    // ---------- History helpers ----------

    private fun toolResult(id: String, text: String, isError: Boolean) = JSONObject()
        .put("type", "tool_result")
        .put("tool_use_id", id)
        .put("content", text)
        .put("is_error", isError)

    private fun addToolResult(id: String, text: String, isError: Boolean) {
        history.put(
            JSONObject().put("role", "user").put("content", JSONArray().put(toolResult(id, text, isError)))
        )
    }

    /** If the last assistant turn asked for a tool that never ran, close it so the next API call stays valid. */
    private fun repairHistory(reason: String) {
        if (history.length() == 0) return
        val last = history.getJSONObject(history.length() - 1)
        if (last.optString("role") != "assistant") return
        val content = last.optJSONArray("content") ?: return
        val results = JSONArray()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "tool_use") results.put(toolResult(b.getString("id"), reason, true))
        }
        if (results.length() > 0) history.put(JSONObject().put("role", "user").put("content", results))
    }
}
