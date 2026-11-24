package com.localkeys.android.data.store

/**
 * A pasta lembrada no seletor da lista, guardada nas preferências (nunca no cofre). A forma salva é a mesma do
 * `FolderFilter` da lista: `null` = todas, `""` = sem pasta, o id da pasta nos outros casos.
 */
object ListFolder {
    /**
     * A chave com que a lista abre: a lembrada quando ela ainda existe no cofre aberto. Pasta apagada (aqui ou em
     * outro aparelho) cai em todas.
     */
    fun openKey(saved: String?, folderIds: Collection<String>): String? =
        if (saved != null && (saved.isEmpty() || saved in folderIds)) saved else null
}
