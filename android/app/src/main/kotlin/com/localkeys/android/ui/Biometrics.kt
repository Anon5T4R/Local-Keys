package com.localkeys.android.ui

import android.os.Build
import android.widget.Toast
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.localkeys.android.R
import javax.crypto.Cipher

/**
 * Sobe o BiometricPrompt com o Cipher do cofre do SO (ligar a biometria ou destrancar com ela). O `doFinal`
 * (wrap/unwrap) roda no ViewModel só depois de `onAuthenticationSucceeded`.
 */
fun FragmentActivity.launchVaultBiometric(viewModel: VaultViewModel, request: BiometricRequest) {
    val cipher: Cipher = try {
        when (request) {
            BiometricRequest.Wrap -> viewModel.newWrapCipher()
            BiometricRequest.Unlock -> viewModel.biometricDecryptCipher() ?: return
        }
    } catch (e: Exception) {
        Toast.makeText(this, getString(R.string.biometric_unavailable, e.message), Toast.LENGTH_LONG).show()
        return
    }
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(getString(R.string.biometric_prompt_title))
        .setSubtitle(getString(R.string.biometric_prompt_subtitle))
        .setNegativeButtonText(getString(R.string.biometric_prompt_cancel))
        .build()
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                when (request) {
                    BiometricRequest.Wrap -> viewModel.enableBiometricWithCipher(cipher)
                    BiometricRequest.Unlock -> viewModel.unlockWithBiometricCipher(cipher)
                }
            }

            // Sensor não reconheceu: o usuário pode tentar de novo. Cancelou: segue pelo caminho da senha.
        },
    )
    prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
}

/**
 * Confirma que é o dono do aparelho antes de usar ou criar uma passkey (a "verificação do usuário" do WebAuthn):
 * digital, rosto ou o bloqueio de tela. [onResult] recebe true só se confirmou.
 */
fun FragmentActivity.verifyUser(title: String, onResult: (Boolean) -> Unit) {
    val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
    } else {
        Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
    }
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setAllowedAuthenticators(allowed)
        .build()
    BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        },
    ).authenticate(info)
}
