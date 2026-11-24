package com.localkeys.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.localkeys.android.data.crypto.MergeOtherResult

/**
 * As contagens que o resumo mostra, tiradas do resultado da ponte (os mesmos números do desktop). A tela lê daqui;
 * nenhuma regra de junção é recalculada em Kotlin.
 */
data class MergeOtherCounts(
    /** Itens do outro cofre que entraram com o id próprio. */
    val added: Int = 0,
    /** Mesmo id e conteúdo diferente: entraram com um id novo (ficaram os dois). */
    val collisions: Int = 0,
    /** Itens que os dois cofres já tinham com o mesmo conteúdo e o mesmo id. */
    val alreadyPresent: Int = 0,
    /** Itens cujo conteúdo já estava no aberto sob OUTRO id (a duplicata de uma junção anterior, ou uma edição que
     * ficou idêntica a outro item): não entram de novo. */
    val contentAlreadyPresent: Int = 0,
    /** Pastas do outro que entraram inteiras. */
    val foldersAdded: Int = 0,
    /** Pastas unidas a uma pasta do aberto com o mesmo nome, com item reapontado ou chave copiada da do outro. */
    val foldersMerged: Int = 0,
) {
    companion object {
        fun of(result: MergeOtherResult) = MergeOtherCounts(
            added = result.added,
            collisions = result.collisions,
            alreadyPresent = result.alreadyPresent,
            contentAlreadyPresent = result.contentAlreadyPresent,
            foldersAdded = result.foldersAdded,
            foldersMerged = result.foldersMerged,
        )
    }
}

/** Em que pé está a junção de outro cofre; a tela mostra um diálogo por etapa. */
sealed class MergeOtherStage {
    /** O outro arquivo não abriu com a chave do cofre aberto: falta a senha dele (o erro fica até a nova tentativa). */
    data class NeedsPassword(val name: String, val error: String? = null) : MergeOtherStage()

    /** A junção foi calculada: o resumo espera confirmar; cancelar não grava nada. */
    data class Ready(val name: String, val counts: MergeOtherCounts, val nothingChanges: Boolean) : MergeOtherStage()
}

/**
 * O estado da junção de outro cofre (unidade 7, peça 3), sem tela: o `VaultViewModel` lê o arquivo, abre pela ponte
 * e calcula a junção, e vai empurrando as etapas por aqui. Um trabalho por vez ([begin]/[startPassword]/[startApply]
 * recusam enquanto o anterior roda), como o "Sincronizar com outra cópia". Testável na JVM, como o
 * [ListSelectionMode].
 *
 * O outro arquivo é somente leitura: nada nesta classe (nem no ViewModel) grava nele. O cofre aberto só é gravado
 * quando a pessoa confirma o resumo, uma vez só.
 */
class MergeOtherFlow {
    /** A etapa visível; null = nada em andamento. */
    var stage: MergeOtherStage? by mutableStateOf(null)
        private set

    /** Há um passo rodando (ler o arquivo, rodar o Argon2id da senha do outro, calcular a junção, gravar). */
    var working: Boolean by mutableStateOf(false)
        private set

    /** A ação do menu só começa quando não há nada pendente nem rodando. */
    fun begin(): Boolean {
        if (working || stage != null) return false
        working = true
        return true
    }

    /** Não abriu com a chave da sessão: pede a senha do outro cofre (os bytes ficam guardados no ViewModel). */
    fun askPassword(name: String) {
        working = false
        stage = MergeOtherStage.NeedsPassword(name)
    }

    /** A senha foi enviada: trava o botão enquanto o Argon2id e a junção rodam. False = já há um passo rodando. */
    fun startPassword(): Boolean {
        if (working) return false
        val current = stage as? MergeOtherStage.NeedsPassword ?: return false
        working = true
        stage = current.copy(error = null)
        return true
    }

    /** Confirmou o resumo: trava o botão enquanto o cofre aberto é gravado. False = já há um passo rodando. */
    fun startApply(): Boolean {
        if (working || stage !is MergeOtherStage.Ready) return false
        working = true
        return true
    }

    /** Senha errada: o diálogo continua aberto com o motivo e a pessoa tenta de novo. */
    fun passwordRejected(message: String) {
        working = false
        val current = stage as? MergeOtherStage.NeedsPassword ?: return
        stage = current.copy(error = message)
    }

    /** Junção calculada: o resumo espera a confirmação (nada foi gravado). */
    fun ready(name: String, counts: MergeOtherCounts, nothingChanges: Boolean) {
        working = false
        stage = MergeOtherStage.Ready(name, counts, nothingChanges)
    }

    /** Fim de qualquer caminho (aplicado, cancelado ou um passo que falhou): nada fica pendente. */
    fun close() {
        working = false
        stage = null
    }
}
