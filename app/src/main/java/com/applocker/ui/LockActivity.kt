package com.applocker.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.applocker.R
import com.applocker.data.LockMethod
import com.applocker.data.PreferencesManager
import com.applocker.databinding.ActivityLockBinding
import com.applocker.service.AppMonitorService

class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var lockedPackage: String

    private val pinBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show over the lock screen without dismissing it (API-level safe)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager.getInstance(this)
        lockedPackage = intent.getStringExtra(EXTRA_LOCKED_PACKAGE) ?: run {
            finish()
            return
        }

        loadAppInfo()
        setupUiForMethod()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lockedPackage = intent.getStringExtra(EXTRA_LOCKED_PACKAGE) ?: lockedPackage
        loadAppInfo()
        setupUiForMethod()
    }

    private fun loadAppInfo() {
        try {
            val pm = packageManager
            val info = pm.getApplicationInfo(lockedPackage, 0)
            binding.tvAppName.text = pm.getApplicationLabel(info)
            binding.ivAppIcon.setImageDrawable(pm.getApplicationIcon(info))
        } catch (e: Exception) {
            binding.tvAppName.text = lockedPackage
        }
    }

    private fun setupUiForMethod() {
        // Reset UI
        binding.pinPad.visibility = View.GONE
        binding.passwordLayout.visibility = View.GONE
        binding.btnBiometric.visibility = View.GONE
        binding.pinDots.visibility = View.GONE
        clearPin()

        when (prefs.lockMethod) {
            LockMethod.PIN -> setupPin()
            LockMethod.PASSWORD -> setupPassword()
            LockMethod.BIOMETRIC -> setupBiometric()
        }

        // If biometrics enabled as fallback, show button alongside PIN/password
        if (prefs.lockMethod != LockMethod.BIOMETRIC && prefs.biometricEnabled) {
            binding.btnBiometric.visibility = View.VISIBLE
            binding.btnBiometric.setOnClickListener { launchBiometric() }
        }
    }

    // ── PIN ──────────────────────────────────────────────────────────────────

    private fun setupPin() {
        binding.pinPad.visibility = View.VISIBLE
        binding.pinDots.visibility = View.VISIBLE
        binding.tvPrompt.setText(R.string.enter_pin)
        setupNumpad()
    }

    private fun setupNumpad() {
        val digitButtons = listOf(
            binding.btn0, binding.btn1, binding.btn2, binding.btn3,
            binding.btn4, binding.btn5, binding.btn6, binding.btn7,
            binding.btn8, binding.btn9
        )
        digitButtons.forEachIndexed { index, btn ->
            btn.text = index.toString()
            btn.setOnClickListener { appendDigit(index.toString()) }
        }
        binding.btnBackspace.setOnClickListener { removeLastDigit() }
        binding.btnOkPin.setOnClickListener { submitPin() }
    }

    private fun appendDigit(d: String) {
        if (pinBuffer.length >= 8) return
        pinBuffer.append(d)
        updatePinDots()
    }

    private fun removeLastDigit() {
        if (pinBuffer.isNotEmpty()) {
            pinBuffer.deleteCharAt(pinBuffer.length - 1)
            updatePinDots()
        }
    }

    private fun updatePinDots() {
        val dots = listOf(
            binding.dot1, binding.dot2, binding.dot3,
            binding.dot4, binding.dot5, binding.dot6,
            binding.dot7, binding.dot8
        )
        dots.forEachIndexed { i, dot ->
            dot.isSelected = i < pinBuffer.length
        }
    }

    private fun submitPin() {
        if (prefs.verifyPin(pinBuffer.toString())) {
            onUnlockSuccess()
        } else {
            onUnlockFailed()
        }
    }

    private fun clearPin() {
        pinBuffer.clear()
        updatePinDots()
    }

    // ── Password ─────────────────────────────────────────────────────────────

    private fun setupPassword() {
        binding.passwordLayout.visibility = View.VISIBLE
        binding.tvPrompt.setText(R.string.enter_password)
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitPassword()
                true
            } else {
                false
            }
        }
    }

    private fun submitPassword() {
        val input = binding.etPassword.text?.toString() ?: ""
        if (prefs.verifyPassword(input)) {
            onUnlockSuccess()
        } else {
            onUnlockFailed()
        }
    }

    // ── Biometric ─────────────────────────────────────────────────────────────

    private fun setupBiometric() {
        binding.tvPrompt.setText(R.string.use_biometric)
        binding.btnBiometric.visibility = View.VISIBLE
        binding.btnBiometric.setOnClickListener { launchBiometric() }
        // Auto-launch
        launchBiometric()
    }

    private fun launchBiometric() {
        val biometricManager = BiometricManager.from(this)
        // BIOMETRIC_STRONG (fingerprint) or BIOMETRIC_WEAK (face on Samsung)
        // — no DEVICE_CREDENTIAL, so the phone unlock password is not accepted.
        val allowedAuthenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        val canAuth = biometricManager.canAuthenticate(allowedAuthenticators)

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, R.string.biometric_not_available, Toast.LENGTH_SHORT).show()
            // Fall back to PIN if set
            if (prefs.pinHash != null) {
                prefs.lockMethod = LockMethod.PIN
                setupUiForMethod()
            }
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlockSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        Toast.makeText(
                            this@LockActivity,
                            getString(R.string.biometric_error, errString),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onAuthenticationFailed() {
                    Toast.makeText(
                        this@LockActivity,
                        R.string.biometric_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_app))
            .setSubtitle(binding.tvAppName.text)
            // Fingerprint (STRONG) + Face (WEAK on Samsung) — no device password
            .setAllowedAuthenticators(allowedAuthenticators)
            // setNegativeButtonText is required when DEVICE_CREDENTIAL is not included
            .setNegativeButtonText(getString(R.string.cancel))
            .build()

        prompt.authenticate(info)
    }

    // ── Result ────────────────────────────────────────────────────────────────

    private fun onUnlockSuccess() {
        AppMonitorService.notifyUnlocked(this, lockedPackage)
        finish()
    }

    private fun onUnlockFailed() {
        clearPin()
        binding.etPassword.text?.clear()
        Toast.makeText(this, R.string.wrong_credentials, Toast.LENGTH_SHORT).show()
        binding.root.animate().translationX(-20f).setDuration(50)
            .withEndAction {
                binding.root.animate().translationX(20f).setDuration(50)
                    .withEndAction {
                        binding.root.animate().translationX(0f).setDuration(50).start()
                    }.start()
            }.start()
    }

    override fun onBackPressed() {
        // Send user back to home instead of unlocking
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(home)
    }

    companion object {
        const val EXTRA_LOCKED_PACKAGE = "locked_package"
    }
}
