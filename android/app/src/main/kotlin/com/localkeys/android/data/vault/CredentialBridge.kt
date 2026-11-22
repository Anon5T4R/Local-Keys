package com.localkeys.android.data.vault

/**
 * Ponte do provedor de credenciais (e da importação por Credential Exchange) para a persistência do cofre.
 *
 * Quem detém a sessão para recifrar o `.tkeys` é o `VaultViewModel`; enquanto o cofre está aberto, ele registra
 * aqui como gravar itens novos. A gravação é esperada: uma passkey só é entregue ao site depois de estar no
 * disco (senão o site guardaria uma credencial que o cofre não tem).
 */
object CredentialBridge {

    @Volatile
    private var handler: (suspend (List<Item>) -> Boolean)? = null

    val canSave: Boolean get() = handler != null

    fun register(onSave: suspend (List<Item>) -> Boolean) {
        handler = onSave
    }

    fun unregister() {
        handler = null
    }

    /** Grava os itens no cofre aberto. False se está trancado ou se a gravação falhou. */
    suspend fun save(items: List<Item>): Boolean = handler?.invoke(items) ?: false
}
