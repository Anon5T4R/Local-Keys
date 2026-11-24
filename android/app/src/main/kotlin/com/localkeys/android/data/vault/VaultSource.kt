package com.localkeys.android.data.vault

/**
 * As duas decisões de "qual arquivo é o cofre aberto" no ViewModel, puras para a JVM:
 * - quando o `vaultUri` observado chega, o conteúdo conhecido já é dele? (o cofre que acabou de ser criado manda o
 *   mesmo URI de volta; não pode esquecer nem reler);
 * - uma leitura atrasada ainda é do cofre atual? (troca A→B→A com dois `loadBlob` sobrepostos: a leitura velha de A
 *   não pode gravar o blob com o URI de outro).
 */
object VaultSource {

    /** O que o coletor do `vaultUri` faz ao ver [observed]. */
    enum class UriAction {
        /** O conhecido já é deste URI: só confirma o estado da tela, sem esquecer nem reler. */
        Keep,

        /** Outro cofre (ou nenhum): esquece o conteúdo e manda ler o arquivo. */
        Reload,
    }

    fun onUriObserved(knownUri: String?, observed: String?): UriAction =
        if (observed != null && knownUri == observed) UriAction.Keep else UriAction.Reload

    /** A leitura de [readUri] ainda vale? Só se o cofre atual continua sendo ele. */
    fun isCurrent(readUri: String, currentVaultUri: String?): Boolean = readUri == currentVaultUri
}
