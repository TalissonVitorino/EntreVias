package br.com.talisson.entrevias

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A MainActivity entrega o contexto da aplicação antes de montar a tela. */
object ContextoAndroid { lateinit var app: Context }

/**
 * Android: o texto é cifrado com AES-256-GCM, com uma chave que fica no Android Keystore (não sai do aparelho,
 * não entra em backup) e só o texto cifrado vai para as preferências privadas do app.
 * Se a chave for perdida (restauração, troca de lock screen), a leitura falha e vira "sem sessão".
 */
private class ArmazenamentoAndroid : ArmazenamentoLocal {
    private val prefs get() = ContextoAndroid.app.getSharedPreferences("entrevias_seguro", Context.MODE_PRIVATE)

    private fun chave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build(),
        )
        return g.generateKey()
    }

    override fun ler(chave: String): String? = runCatching {
        val bruto = prefs.getString(chave, null) ?: return null
        val (iv, cifrado) = bruto.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(128, iv)) }
        String(c.doFinal(cifrado), Charsets.UTF_8)
    }.getOrElse { prefs.edit().remove(chave).apply(); null }   // ilegível: some, em vez de travar o login

    override fun gravar(chave: String, valor: String?) {
        if (valor == null) { prefs.edit().remove(chave).apply(); return }
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, chave()) }
            val cifrado = c.doFinal(valor.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(chave, Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cifrado, Base64.NO_WRAP)).apply()
        }
    }

    private companion object { const val ALIAS = "entrevias_sessao" }
}

actual fun criarArmazenamento(): ArmazenamentoLocal = ArmazenamentoAndroid()
