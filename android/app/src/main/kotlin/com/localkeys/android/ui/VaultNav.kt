package com.localkeys.android.ui

/**
 * Telas dentro do cofre aberto. A lista é a base (pilha vazia); o resto empilha por cima. A forma de texto
 * ([encode]/[decode]) é o que o `rememberSaveable` guarda.
 */
sealed interface Route {
    data class Detail(val id: String) : Route
    /** [id] null = item novo, do tipo [preset] (o que foi escolhido no "+"). */
    data class Edit(val id: String?, val preset: NewItemPreset? = null) : Route
    data object Trash : Route
    data object Folders : Route
    data object Settings : Route

    /** Detalhe e editor moram no painel da direita do tablet; o resto ocupa a tela toda. */
    val isPane: Boolean get() = this is Detail || this is Edit

    fun encode(): String = when (this) {
        is Detail -> "detail:$id"
        is Edit -> if (id == null && preset != null) "new:${preset.name}" else "edit:${id.orEmpty()}"
        Trash -> "trash"
        Folders -> "folders"
        Settings -> "settings"
    }

    companion object {
        fun decode(s: String): Route? = when {
            s.startsWith("detail:") -> Detail(s.removePrefix("detail:"))
            s.startsWith("edit:") -> Edit(s.removePrefix("edit:").ifEmpty { null })
            s.startsWith("new:") -> Edit(null, NewItemPreset.entries.firstOrNull { it.name == s.removePrefix("new:") })
            s == "trash" -> Trash
            s == "folders" -> Folders
            s == "settings" -> Settings
            else -> null
        }
    }
}

/** Operações puras da pilha de navegação (testadas na JVM). */
object NavStack {

    /** Abre um item: no tablet troca o que está no painel; no celular empilha. */
    fun openItem(stack: List<Route>, id: String, twoPane: Boolean): List<Route> =
        if (twoPane) stack.filterNot { it.isPane } + Route.Detail(id) else stack + Route.Detail(id)

    /** Depois de salvar no editor: o editor sai e fica o detalhe do item salvo (sem duplicar). */
    fun afterSave(stack: List<Route>, id: String): List<Route> {
        val base = stack.dropLastWhile { it is Route.Edit }
        return if (base.lastOrNull() == Route.Detail(id)) base else base + Route.Detail(id)
    }

    /** O item sumiu (foi para a lixeira): tira da pilha tudo o que aponta para ele. */
    fun removeItem(stack: List<Route>, id: String): List<Route> =
        stack.filterNot { (it is Route.Detail && it.id == id) || (it is Route.Edit && it.id == id) }

    /** O que vai no painel da direita do tablet: a rota de painel no topo, se houver. */
    fun paneRoute(stack: List<Route>): Route? = stack.lastOrNull()?.takeIf { it.isPane }

    /** A tela cheia por cima de tudo no tablet (configurações, lixeira, pastas), se houver. */
    fun fullScreenRoute(stack: List<Route>): Route? = stack.lastOrNull()?.takeIf { !it.isPane }
}
