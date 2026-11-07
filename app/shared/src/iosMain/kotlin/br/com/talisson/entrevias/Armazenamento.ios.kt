package br.com.talisson.entrevias

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS: Keychain (item genérico), acessível depois do primeiro desbloqueio e só neste aparelho (não vai para backup
 * nem para outro iPhone). Qualquer falha vira "sem sessão"; nada aqui derruba o app.
 */
@OptIn(ExperimentalForeignApi::class)
private class ArmazenamentoIos : ArmazenamentoLocal {
    private val servico = "br.com.talisson.entrevias"

    private fun cf(s: String) = CFStringCreateWithCString(null, s, kCFStringEncodingUTF8)

    private fun dicionario(vararg pares: Pair<Any?, Any?>): CFDictionaryRef? = memScoped {
        val chaves = allocArrayOf(*pares.map { it.first as kotlinx.cinterop.CPointer<*>? }.toTypedArray())
        val valores = allocArrayOf(*pares.map { it.second as kotlinx.cinterop.CPointer<*>? }.toTypedArray())
        CFDictionaryCreate(null, chaves.reinterpret(), valores.reinterpret(), pares.size.convert(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    }

    private fun identidade(chave: String): Array<Pair<Any?, Any?>> =
        arrayOf(kSecClass to kSecClassGenericPassword, kSecAttrService to cf(servico), kSecAttrAccount to cf(chave))

    override fun ler(chave: String): String? = runCatching {
        memScoped {
            val consulta = dicionario(*identidade(chave), kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne)
            val resultado = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(consulta, resultado.ptr)
            CFRelease(consulta)
            if (status != errSecSuccess) return@memScoped null
            val dados = resultado.value as CFDataRef
            val n = CFDataGetLength(dados).toInt()
            val p = CFDataGetBytePtr(dados)!!
            val bytes = ByteArray(n) { p[it].toByte() }
            CFRelease(dados)
            bytes.decodeToString()
        }
    }.getOrNull()

    override fun gravar(chave: String, valor: String?) {
        runCatching {
            val consulta = dicionario(*identidade(chave))
            SecItemDelete(consulta)
            CFRelease(consulta)
            if (valor == null) return
            val bytes = valor.encodeToByteArray()
            val dados = bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.convert()) }
            val novo = dicionario(*identidade(chave), kSecValueData to dados, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            SecItemAdd(novo, null)
            CFRelease(novo); CFRelease(dados)
        }
    }
}

actual fun criarArmazenamento(): ArmazenamentoLocal = ArmazenamentoIos()
