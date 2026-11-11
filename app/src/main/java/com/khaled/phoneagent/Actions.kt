package com.khaled.phoneagent

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** A phone contact found by name. */
data class ContactMatch(val name: String, val number: String)

/** A message the agent has prepared but NOT sent. It waits for the user's confirmation. */
data class PendingMessage(
    val app: String,                 // "whatsapp", "sms" or "telegram"
    val contactQuery: String,
    val message: String,
    val candidates: List<ContactMatch>,
    var chosen: ContactMatch? = null
)

/** Everything the agent can actually DO on the phone. Uses official Android intents only. */
object Actions {

    /** Country code added to local numbers that have no "+" (Tunisia = 216). Change it if needed. */
    const val DEFAULT_COUNTRY_CODE = "216"
    private const val LOCAL_NUMBER_LENGTH = 8

    private val KNOWN_PACKAGES = mapOf(
        "whatsapp" to "com.whatsapp",
        "telegram" to "org.telegram.messenger",
        "chrome" to "com.android.chrome",
        "youtube" to "com.google.android.youtube",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps"
    )

    // ---------- Open an app ----------

    fun openApp(ctx: Context, appName: String): String {
        val pm = ctx.packageManager
        val key = appName.trim().lowercase()
        if (key.isEmpty()) return "Error: no app name given."

        var intent: Intent? = KNOWN_PACKAGES[key]?.let { pm.getLaunchIntentForPackage(it) }

        if (intent == null) {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val apps = pm.queryIntentActivities(main, 0)
            val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == key }
                ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(key) }
            intent = match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        }

        if (intent == null) return "Error: no installed app matches '$appName'."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            "Opened $appName."
        } catch (e: Exception) {
            "Error: could not open $appName (${e.message})."
        }
    }

    // ---------- Web search ----------

    fun webSearch(ctx: Context, query: String, site: String): String {
        if (query.isBlank()) return "Error: empty search query."
        val q = Uri.encode(query)
        val url = if (site == "youtube") {
            "https://www.youtube.com/results?search_query=$q"
        } else {
            "https://www.google.com/search?q=$q"
        }
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened a $site search for: $query"
        } catch (e: Exception) {
            "Error: could not open the search (${e.message})."
        }
    }

    // ---------- Contacts ----------

    /**
     * Finds contacts whose name contains [query]. Blocking: call on a background thread.
     * Throws SecurityException if the contacts permission was not granted.
     */
    fun findContacts(ctx: Context, query: String): List<ContactMatch> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("READ_CONTACTS not granted")

        val clean = query.trim().replace("%", "").replace("_", "")
        if (clean.isEmpty()) return emptyList()

        val found = LinkedHashMap<String, ContactMatch>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$clean%"),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val digits = number.filter { it.isDigit() }
                found.putIfAbsent("$name|$digits", ContactMatch(name, number.trim()))
            }
        }

        var list = found.values.toList()
        // If exactly one contact name matches the query exactly, prefer it.
        val exact = list.filter { it.name.equals(clean, ignoreCase = true) }
        if (exact.isNotEmpty()) list = exact
        return list.take(10)
    }

    /** Converts a stored number to the international digits WhatsApp needs (no "+"). */
    fun toInternational(raw: String): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (trimmed.startsWith("+")) return digits
        if (digits.startsWith("00")) return digits.substring(2)
        if (digits.length == LOCAL_NUMBER_LENGTH) return DEFAULT_COUNTRY_CODE + digits
        return digits
    }

    /** The number shown to the user in the confirmation card. */
    fun displayNumber(p: PendingMessage): String {
        val c = p.chosen ?: return "(you will pick the chat in the app)"
        return if (p.app == "whatsapp") "+" + toInternational(c.number) else c.number
    }

    // ---------- Send (only called AFTER the user taps Confirm) ----------

    /**
     * Opens the messaging app with the recipient and text already filled in.
     * Android has no official way to press "Send" inside another app, so the final tap
     * on Send happens in that app. (An optional Accessibility step can be added in a later phase.)
     */
    fun sendMessage(ctx: Context, p: PendingMessage): String {
        return try {
            when (p.app) {
                "whatsapp" -> {
                    val c = p.chosen ?: return "Error: no contact selected."
                    val uri = Uri.parse(
                        "https://wa.me/${toInternational(c.number)}?text=${Uri.encode(p.message)}"
                    )
                    launchWithFallback(ctx, Intent(Intent.ACTION_VIEW, uri), "com.whatsapp")
                    "WhatsApp opened with the message for ${c.name}. Press Send in WhatsApp to finish."
                }
                "sms" -> {
                    val c = p.chosen ?: return "Error: no contact selected."
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${c.number}"))
                        .putExtra("sms_body", p.message)
                    launchWithFallback(ctx, intent, null)
                    "SMS app opened with the message for ${c.name}. Press Send to finish."
                }
                "telegram" -> {
                    val intent = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, p.message)
                    launchWithFallback(ctx, intent, "org.telegram.messenger")
                    "Telegram opened with the message. Choose the chat and press Send."
                }
                else -> "Error: unsupported app '${p.app}'."
            }
        } catch (e: Exception) {
            "Error: could not open the messaging app (${e.message})."
        }
    }

    private fun launchWithFallback(ctx: Context, intent: Intent, preferredPackage: String?) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (preferredPackage != null) {
            try {
                ctx.startActivity(Intent(intent).setPackage(preferredPackage))
                return
            } catch (_: ActivityNotFoundException) {
                // fall through to the generic intent below
            }
        }
        ctx.startActivity(intent)
    }
}
