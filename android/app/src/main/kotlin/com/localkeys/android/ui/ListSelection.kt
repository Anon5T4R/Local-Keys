package com.localkeys.android.ui

/**
 * A seleção de itens da lista (Android): um conjunto ORDENADO de ids, sem Compose e sem cofre.
 *
 * A tela chama [toggle]/[selectOnly] pelo toque, via [ListSelectionMode] (o modo de seleção, as regras do toque e a
 * limpeza quando a lista muda); [range] marca o intervalo entre dois ids na ordem visível. Espelho do
 * `src/selection.rs` do desktop.
 */
class ListSelection {

    /** Os ids na ordem em que entraram. */
    private val order = mutableListOf<String>()

    /** Espelho de [order] para o [contains], que a lista chama uma vez por linha visível. */
    private val present = mutableSetOf<String>()

    /** Marca o id, ou desmarca se já estava marcado (Ctrl+clique). */
    fun toggle(id: String) {
        if (present.remove(id)) {
            order.remove(id)
        } else {
            present.add(id)
            order.add(id)
        }
    }

    /** Deixa só este id marcado (clique sem modificador). */
    fun selectOnly(id: String) {
        clear()
        toggle(id)
    }

    /**
     * Marca o intervalo entre dois ids na [visible] (Shift+clique), inclusive as pontas e em qualquer direção. Id que
     * não está na lista visível (a lista mudou no meio) não mexe na seleção.
     */
    fun range(from: String, to: String, visible: List<String>) {
        val start = visible.indexOf(from)
        val end = visible.indexOf(to)
        if (start < 0 || end < 0) return
        clear()
        val (first, last) = if (start <= end) start to end else end to start
        for (id in visible.subList(first, last + 1)) toggle(id)
    }

    fun clear() {
        order.clear()
        present.clear()
    }

    /** Trocar filtro, busca ou pasta zera a seleção: os ids marcados podem nem estar na lista nova. */
    fun resetForFilterChange() {
        clear()
    }

    fun contains(id: String): Boolean = present.contains(id)

    val len: Int get() = order.size

    val isEmpty: Boolean get() = order.isEmpty()

    fun ids(): List<String> = order.toList()
}
