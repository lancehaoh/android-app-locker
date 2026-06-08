package com.applocker.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import com.applocker.R
import com.applocker.data.LockMethod
import com.applocker.data.PreferencesManager
import com.applocker.databinding.ActivitySetupLockBinding

class SetupLockActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupLockBinding
    private lateinit var prefs: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { finish() }

        prefs = PreferencesManager.getInstance(this)

        // Chip group: select lock method
        binding.chipGroup.setOnCheckedStateChangeListener { _, ids ->
            when (ids.firstOrNull()) {
                R.id.chip_pin -> showPinSetup()
                R.id.chip_password -> showPasswordSetup()
                R.id.chip_biometric -> showBiometricSetup()
            }
        }

        // Check biometric availability
        val bm = BiometricManager.from(this)
        val canBio = bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
        binding.chipBiometric.isEnabled = canBio

        // Pre-select current method
        when (prefs.lockMethod) {
            LockMethod.PIN -> { binding.chipPin.isChecked = true; showPinSetup() }
            LockMethod.PASSWORD -> { binding.chipPassword.isChecked = true; showPasswordSetup() }
            LockMethod.BIOMETRIC -> { binding.chipBiometric.isChecked = true; showBiometricSetup() }
        }

        binding.switchBiometricFallback.isChecked = prefs.biometricEnabled
        binding.switchBiometricFallback.isEnabled = canBio
        binding.switchBiometricFallback.setOnCheckedChangeListener { _, checked ->
            prefs.biometricEnabled = checked
        }
    }

    // ── PIN setup ─────────────────────────────────────────────────────────────

    private fun showPinSetup() {
        binding.pinSetupLayout.visibility = View.VISIBLE
        binding.passwordSetupLayout.visibility = View.GONE
        binding.biometricSetupLayout.visibility = View.GONE

        binding.btnSavePin.setOnClickListener {
            val pin = binding.etPin.text?.toString() ?: ""
            val confirm = binding.etPinConfirm.text?.toString() ?: ""
            when {
                pin.length < 4 -> toast(R.string.pin_too_short)
                pin != confirm -> toast(R.string.pin_mismatch)
                else -> {
                    prefs.setPin(pin)
                    prefs.lockMethod = LockMethod.PIN
                    toast(R.string.pin_saved)
                    finish()
                }
            }
        }
    }

    // ── Password setup ────────────────────────────────────────────────────────

    private fun showPasswordSetup() {
        binding.pinSetupLayout.visibility = View.GONE
        binding.passwordSetupLayout.visibility = View.VISIBLE
        binding.biometricSetupLayout.visibility = View.GONE

        binding.btnSavePassword.setOnClickListener {
            val pw = binding.etNewPassword.text?.toString() ?: ""
            val confirm = binding.etNewPasswordConfirm.text?.toString() ?: ""
            when {
                pw.length < 6 -> toast(R.string.password_too_short)
                pw != confirm -> toast(R.string.password_mismatch)
                else -> {
                    prefs.setPassword(pw)
                    prefs.lockMethod = LockMethod.PASSWORD
                    toast(R.string.password_saved)
                    finish()
                }
            }
        }
    }

    // ── Biometric setup ───────────────────────────────────────────────────────

    private fun showBiometricSetup() {
        binding.pinSetupLayout.visibility = View.GONE
        binding.passwordSetupLayout.visibility = View.GONE
        binding.biometricSetupLayout.visibility = View.VISIBLE

        binding.btnConfirmBiometric.setOnClickListener {
            prefs.biometricEnabled = true
            prefs.lockMethod = LockMethod.BIOMETRIC
            toast(R.string.biometric_saved)
            finish()
        }
    }

    private fun toast(resId: Int) =
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

}
