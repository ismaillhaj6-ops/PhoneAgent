package com.khaled.phoneagent

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope

class MainActivity : AppCompatActivity(), AgentUi {

    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var micBtn: Button
    private lateinit var confirmCard: View
    private lateinit var confirmText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var speakBox: CheckBox
    private lateinit var agent: Agent

    private var tts: TextToSpeech? = null
    private var busy = false
    private var decisionShowing = false

    // Microphone: uses Android's built-in speech recognition screen.
    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                input.setText("")
                agent.onUserText(spoken)
            }
        }
    }

    private val contactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            addMessage(
                Role.SYSTEM,
                "Contacts permission denied. Messaging by contact name will not work until you allow it in Android settings."
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chat = findViewById(R.id.chat)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)
        sendBtn = findViewById(R.id.sendBtn)
        micBtn = findViewById(R.id.micBtn)
        confirmCard = findViewById(R.id.confirmCard)
        confirmText = findViewById(R.id.confirmText)
        progress = findViewById(R.id.progress)
        speakBox = findViewById(R.id.speakBox)

        agent = Agent(this, lifecycleScope, this)
        tts = TextToSpeech(this) { /* ready when status == SUCCESS; default language is used */ }

        sendBtn.setOnClickListener { sendFromInput() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendFromInput()
                true
            } else false
        }
        micBtn.setOnClickListener { startListening() }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            tts?.stop()
            agent.emergencyStop()
        }
        findViewById<Button>(R.id.confirmBtn).setOnClickListener { agent.confirmPending() }
        findViewById<Button>(R.id.cancelBtn).setOnClickListener { agent.cancelPending() }

        addMessage(
            Role.ASSISTANT,
            "Hi! I'm your phone agent. Try:\n• Open WhatsApp\n• Send Ahmed a WhatsApp message saying I'll arrive at 5\n• Search YouTube for a networking tutorial"
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
        if (SecureStore.getApiKey(this).isBlank()) showSettings()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- Input ----------

    private fun sendFromInput() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        agent.onUserText(text)
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your command")
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "No speech recognition found. Install/enable the Google app (Speech Services by Google).",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------- Settings (API key) ----------

    private fun showSettings() {
        val hasKey = SecureStore.getApiKey(this).isNotBlank()
        val box = EditText(this).apply {
            hint = "sk-ant-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Anthropic API key")
            .setMessage(
                if (hasKey) "A key is saved (encrypted on this phone). Enter a new one to replace it."
                else "Paste your API key from console.anthropic.com. It is stored encrypted on this phone only."
            )
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val k = box.text.toString().trim()
                if (k.isNotEmpty()) {
                    SecureStore.setApiKey(this, k)
                    Toast.makeText(this, "Key saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Delete key") { _, _ ->
                SecureStore.deleteApiKey(this)
                Toast.makeText(this, "Key deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- AgentUi ----------

    override fun addMessage(role: Role, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(36, 22, 36, 22)
            setTextIsSelectable(true)
            val bg = GradientDrawable().apply { cornerRadius = 36f }
            when (role) {
                Role.USER -> {
                    bg.setColor(Color.parseColor("#1565C0"))
                    setTextColor(Color.WHITE)
                }
                Role.ASSISTANT -> {
                    bg.setColor(Color.parseColor("#E0E0E0"))
                    setTextColor(Color.parseColor("#212121"))
                }
                Role.SYSTEM -> {
                    bg.setColor(Color.parseColor("#FFF3CD"))
                    setTextColor(Color.parseColor("#5D4037"))
                    setTypeface(typeface, Typeface.ITALIC)
                    textSize = 14f
                }
            }
            background = bg
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 10, 0, 10)
            gravity = if (role == Role.USER) Gravity.END else Gravity.START
        }
        chat.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        if (role == Role.ASSISTANT && speakBox.isChecked) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "agent_reply")
        }
    }

    override fun setBusy(busy: Boolean) {
        this.busy = busy
        refreshInput()
    }

    override fun askDecision(p: PendingMessage) {
        decisionShowing = true
        refreshInput()

        // Several contacts match: let the USER choose. The agent never guesses.
        if (p.app != "telegram" && p.chosen == null) {
            val labels = p.candidates.map { "${it.name} — ${it.number}" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Which contact?")
                .setItems(labels) { _, i -> agent.selectCandidate(i) }
                .setOnCancelListener { agent.cancelPending() }
                .show()
            return
        }

        val appName = when (p.app) {
            "whatsapp" -> "WhatsApp"
            "sms" -> "SMS"
            else -> "Telegram"
        }
        val to = p.chosen?.name ?: "(you choose the chat in Telegram)"
        confirmText.text = "Send via $appName?\n\nTo: $to\nNumber: ${Actions.displayNumber(p)}\n\nMessage:\n“${p.message}”"
        confirmCard.visibility = View.VISIBLE
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun clearDecision() {
        decisionShowing = false
        confirmCard.visibility = View.GONE
        refreshInput()
    }

    private fun refreshInput() {
        val enabled = !busy && !decisionShowing
        input.isEnabled = enabled
        sendBtn.isEnabled = enabled
        micBtn.isEnabled = enabled
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }
}
