package com.localkeys.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A seleção de itens da lista (Android): um conjunto ORDENADO de ids, sem cofre.
 *
 * A tela chama [toggle]/[selectOnly] pelo toque, via [ListSelectionMode] (o modo de seleção, as regras do toque e a
 * limpeza quando a lista muda); [range] marca o intervalo entre dois ids na ordem visível. Espelho do
 * `src/selection.rs` do desktop.
 *
 * O conjunto é **estado observável** (`mutableStateOf`): `contains`/`len`/`ids` são lidos pela lista e pela barra
 * de seleção em cada desenho, e marcar/desmarcar uma linha, "selecionar tudo" ou limpar SUBSTITUI o valor — a tela
 * recompõe sozinha. Um `mutableSetOf` comum deixaria a contagem e as marcas presas no primeiro toque.
 */
class ListSelection {

    /** O conjunto marcado: os ids na ordem em que entraram e o espelho para o `contains` (uma leitura por linha). */
    private data class Marked(val order: List<String>, val present: Set<String>)

    private var marked by mutableStateOf(Marked(order = emptyList(), present = emptySet()))

    /** Marca o id, ou desmarca se já estava marcado (Ctrl+clique). */
    fun toggle(id: String) {
        marked = if (id in marked.present) {
            Marked(marked.order - id, marked.present - id)
        } else {
            Marked(marked.order + id, marked.present + id)
        }
    }

    /** Deixa só este id marcado (clique sem modificador). */
    fun selectOnly(id: String) {
        marked = Marked(listOf(id), setOf(id))
    }

    /**
     * Marca o intervalo entre dois ids na [visible] (Shift+clique), inclusive as pontas e em qualquer direção. Id que
     * não está na lista visível (a lista mudou no meio) não mexe na seleção.
     */
    fun range(from: String, to: String, visible: List<String>) {
        val start = visible.indexOf(from)
        val end = visible.indexOf(to)
        if (start < 0 || end < 0) return
        val (first, last) = if (start <= end) start to end else end to start
        val picked = visible.subList(first, last + 1).distinct()
        marked = Marked(picked, picked.toSet())
    }

    fun clear() {
        marked = Marked(emptyList(), emptySet())
    }

    /** Trocar filtro, busca ou pasta zera a seleção: os ids marcados podem nem estar na lista nova. */
    fun resetForFilterChange() {
        clear()
    }

    fun contains(id: String): Boolean = id in marked.present

    val len: Int get() = marked.order.size

    val isEmpty: Boolean get() = marked.order.isEmpty()

    fun ids(): List<String> = marked.order
}
