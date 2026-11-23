package com.localkeys.android.data.import

import androidx.annotation.StringRes

/**
 * Erro de importação exibível ao usuário. Carrega o **recurso de string** (e os argumentos) em vez do texto, para
 * a mensagem sair no idioma escolhido nas Configurações; a tela resolve com `str(error.resId, *error.args)`.
 * Import falha sem tocar no cofre — pior caso, nada é gravado.
 */
class ImportError(@StringRes val resId: Int, vararg val args: Any) : Exception("import error (string res $resId)")
