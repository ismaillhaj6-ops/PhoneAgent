package com.khaled.phoneagent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Stores the API key encrypted on the phone (Android Keystore). It never leaves the device except in API calls. */
object SecureStore {
    private const val KEY_API = "anthropic_api_key"

    private fun prefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getApiKey(ctx: Context): String = prefs(ctx).getString(KEY_API, "") ?: ""

    fun setApiKey(ctx: Context, key: String) {
        prefs(ctx).edit().putString(KEY_API, key.trim()).apply()
    }

    fun deleteApiKey(ctx: Context) {
        prefs(ctx).edit().remove(KEY_API).apply()
    }
}
