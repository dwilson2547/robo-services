package com.robo.phonecompanion.data.git

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "git_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var repoUrl: String?
        get() = prefs.getString(KEY_REPO_URL, null)
        set(value) = prefs.edit().putString(KEY_REPO_URL, value).apply()

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    val isConfigured: Boolean
        get() = !repoUrl.isNullOrBlank() && !token.isNullOrBlank()

    fun clear() {
        prefs.edit().remove(KEY_REPO_URL).remove(KEY_TOKEN).apply()
    }

    companion object {
        private const val KEY_REPO_URL = "repo_url"
        private const val KEY_TOKEN = "token"
    }
}
