package com.applocker.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PreferencesManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    // Encrypted prefs for sensitive data (PIN / password hash)
    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "app_locker_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Plain prefs for non-sensitive settings
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_locker_settings", Context.MODE_PRIVATE)

    // ── Lock method ──────────────────────────────────────────────────────────

    var lockMethod: LockMethod
        get() = LockMethod.valueOf(prefs.getString(KEY_LOCK_METHOD, LockMethod.PIN.name)!!)
        set(v) = prefs.edit().putString(KEY_LOCK_METHOD, v.name).apply()

    // ── PIN (stored as SHA-256 hex) ──────────────────────────────────────────

    var pinHash: String?
        get() = securePrefs.getString(KEY_PIN_HASH, null)
        set(v) = securePrefs.edit().putString(KEY_PIN_HASH, v).apply()

    fun verifyPin(input: String): Boolean = pinHash == sha256(input)
    fun setPin(pin: String) { pinHash = sha256(pin) }

    // ── Password ─────────────────────────────────────────────────────────────

    var passwordHash: String?
        get() = securePrefs.getString(KEY_PASSWORD_HASH, null)
        set(v) = securePrefs.edit().putString(KEY_PASSWORD_HASH, v).apply()

    fun verifyPassword(input: String): Boolean = passwordHash == sha256(input)
    fun setPassword(pw: String) { passwordHash = sha256(pw) }

    // ── Biometrics ────────────────────────────────────────────────────────────

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(v) = prefs.edit().putBoolean(KEY_BIOMETRIC, v).apply()

    // ── Service state ─────────────────────────────────────────────────────────

    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE, false)
        set(v) = prefs.edit().putBoolean(KEY_SERVICE, v).apply()

    // ── Unlock grace period (ms) ──────────────────────────────────────────────

    var gracePeriodMs: Long
        get() = prefs.getLong(KEY_GRACE_PERIOD, 3_000L)
        set(v) = prefs.edit().putLong(KEY_GRACE_PERIOD, v).apply()

    fun isSetUp(): Boolean = when (lockMethod) {
        LockMethod.PIN -> pinHash != null
        LockMethod.PASSWORD -> passwordHash != null
        LockMethod.BIOMETRIC -> biometricEnabled
    }

    private fun sha256(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_LOCK_METHOD = "lock_method"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PASSWORD_HASH = "password_hash"
        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val KEY_SERVICE = "service_enabled"
        private const val KEY_GRACE_PERIOD = "grace_period_ms"

        @Volatile
        private var INSTANCE: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}

enum class LockMethod { PIN, PASSWORD, BIOMETRIC }
