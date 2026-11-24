package com.localkeys.android.data.export

import com.localkeys.android.data.crypto.KdfParams
import com.localkeys.android.data.crypto.TkeysCrypto
import com.localkeys.android.data.crypto.TkeysError
import com.localkeys.android.data.crypto.TkeysFormat
import com.localkeys.android.data.crypto.subsetVault
import com.localkeys.android.data.store.DocumentIo
import com.localkeys.android.data.store.DocumentWriter
import com.localkeys.android.data.vault.Vault

/**
 * O cofre NOVO a partir da seleção (unidade 6): a regra do núcleo escolhe os itens e as pastas usadas
 * ([subsetVault]), o [createVault] cifra com a senha escolhida e o arquivo é gravado no documento do "criar".
 *
 * Sem Android aqui: entram o cofre, os ids, a senha e o [DocumentIo]; a JVM dos testes roda o caminho inteiro. A
 * exportação só LÊ o cofre aberto — ele não é adotado nem gravado.
 */
class VaultExport(private val crypto: TkeysCrypto = TkeysCrypto()) {

    /** Falha depois da regra do núcleo (a gravação no documento escolhido). */
    sealed class Failure(cause: Throwable?) : Exception(cause) {
        class Save(cause: Throwable?) : Failure(cause)
    }

    /**
     * O `.tkeys` cifrado do cofre novo, com [ids] (sem repetir) na ordem da seleção, e a senha [password]. Pasta só
     * entra se algum item escolhido a usa.
     *
     * @throws TkeysError.CannotExport seleção vazia ou id que não existe mais no cofre.
     */
    fun build(vault: Vault, ids: List<String>, password: String, params: KdfParams = TkeysFormat.defaultParams()): ByteArray {
        // Id repetido é a mesma seleção (como no desktop); id que sumiu do cofre é recusa, não item inventado.
        val selected = ids.distinct().map { id ->
            vault.items.firstOrNull { it.id == id } ?: throw TkeysError.CannotExport("item `$id` does not exist")
        }
        val json = subsetVault(vault.folders, selected)
        return crypto.createVault(password, json.toByteArray(Charsets.UTF_8), params).file
    }

    /**
     * Grava o arquivo no documento escolhido no "criar" do sistema (ele já perguntou sobre sobrescrever). Qualquer
     * falha do provedor vira [Failure.Save], com a causa para a mensagem da tela.
     */
    fun write(document: DocumentIo, file: ByteArray) {
        try {
            DocumentWriter.write(document, file)
        } catch (e: Exception) {
            throw Failure.Save(e)
        }
    }
}

/** Por que a senha do cofre novo não serve (a tela escolhe o texto). `null` = pronta. */
enum class ExportPasswordProblem { Empty, Mismatch }

/** A senha precisa ser não vazia e igual à confirmação — a mesma regra do desktop, sem medidor de força. */
fun exportPasswordProblem(password: String, confirmation: String): ExportPasswordProblem? = when {
    password.isEmpty() -> ExportPasswordProblem.Empty
    password != confirmation -> ExportPasswordProblem.Mismatch
    else -> null
}
