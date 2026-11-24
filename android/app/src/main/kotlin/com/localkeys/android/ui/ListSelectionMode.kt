package com.localkeys.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A seleção múltipla da lista principal (unidade 5, peça 3): o modo de seleção, os ids marcados e as regras do
 * toque. Sem tela: a lista lê [active], [len] e [contains], e chama [onLongPress]/[onTap]/[selectAll]/[clear];
 * quem grava é o `VaultViewModel`, pela regra do núcleo. Espelho do `src/selection_ui.rs` do desktop, com o toque
 * longo no lugar do Ctrl/Shift.
 *
 * O estado é `mutableStateOf` (como no `ListFilterState`): a lista recompõe sozinha quando a seleção muda, e a
 * classe continua testável na JVM, sem Compose de tela.
 */
class ListSelectionMode {
    /** Modo de seleção ligado? Toque longo entra; esvaziar a seleção, o "fechar" ou trocar a lista saem. */
    var active by mutableStateOf(false)
        private set

    private val selection = ListSelection()

    /** Assinatura da lista (busca + filtro + pasta) vista no último desenho. */
    private var filterSignature: String? = null

    val len: Int get() = selection.len

    val isEmpty: Boolean get() = selection.isEmpty

    fun contains(id: String): Boolean = selection.contains(id)

    fun ids(): List<String> = selection.ids()

    /** Toque longo numa linha: entra no modo já com este item; no modo, é o mesmo que o toque. */
    fun onLongPress(id: String) {
        if (!active) {
            active = true
            selection.selectOnly(id)
        } else {
            toggle(id)
        }
    }

    /**
     * Toque numa linha: no modo alterna o item (e sai do modo quando o último sai); fora do modo devolve true —
     * quem chamou abre o item.
     */
    fun onTap(id: String): Boolean {
        if (!active) return true
        toggle(id)
        return false
    }

    /** "Selecionar tudo": marca os visíveis (a lista já chega filtrada). Lista vazia não entra no modo. */
    fun selectAll(visible: List<String>) {
        selection.clear()
        for (id in visible) selection.toggle(id)
        active = selection.len > 0
    }

    /** Fecha o modo e limpa a seleção. */
    fun clear() {
        selection.clear()
        active = false
    }

    /**
     * Trocar a busca, o filtro ou a pasta zera a seleção: os ids marcados podem nem estar na lista nova. A mesma
     * lista (uma recomposição, a volta da rotação) não mexe em nada.
     */
    fun syncFilter(signature: String) {
        val previous = filterSignature
        filterSignature = signature
        if (previous == null || previous == signature) return
        clear()
    }

    /** Alterna um item; o modo cai junto quando o último sai. */
    private fun toggle(id: String) {
        selection.toggle(id)
        if (selection.isEmpty) active = false
    }
}
