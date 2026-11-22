package com.localkeys.android.data.vault

/**
 * Preferências que o `LocalKeysAutofillService` precisa ler na hora (sem esperar o DataStore): o
 * `VaultViewModel` espelha aqui o que está gravado. O serviço só preenche com o cofre aberto, e com o cofre
 * aberto o ViewModel está vivo e o espelho em dia.
 */
object AutofillPrefs {
    /** Copiar o TOTP para a área de transferência depois de preencher um login que tem TOTP. */
    @Volatile
    var copyTotp: Boolean = true
}
