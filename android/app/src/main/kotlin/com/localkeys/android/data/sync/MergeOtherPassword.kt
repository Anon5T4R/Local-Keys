package com.localkeys.android.data.sync

import com.localkeys.android.data.crypto.TkeysError

/**
 * Política do erro ao abrir o outro cofre com a senha digitada: só [TkeysError.Decrypt] é "senha errada" e mantém o
 * diálogo para a pessoa tentar de novo. Arquivo corrompido, que não é um `.tkeys`, versão nova ou KDF inválido fecham
 * a junção com o texto traduzido — prender o usuário no diálogo de senha não resolveria nenhum deles.
 */
fun isWrongPassword(error: TkeysError): Boolean = error is TkeysError.Decrypt
