package com.localkeys.android.data.vault

import com.localkeys.android.data.crypto.attachmentBase64Size
import com.localkeys.android.data.crypto.exceedsVaultLimit
import com.localkeys.android.data.crypto.vaultSizeAfterAttachments

/**
 * Os tetos de tamanho do cofre no Android, num lugar só (unidade 8).
 *
 * O anexo viaja em base64 dentro do JSON e o cofre INTEIRO é regravado (e recifrado) a cada save: o custo de anexar
 * cresce com o **cofre**, não com o anexo. A medição (JVM host, heap de 256 MB) mostrou que um cofre de 50 MB abre em
 * 1,5 s e **não salva** (estoura na releitura), 100 MB estoura no parse e 200 MB estoura na decifra. Por isso 20 MB é
 * o teto tanto para juntar cofres quanto para o aviso ao anexar; o limite POR anexo (10 MiB) continua sendo o que
 * impede um arquivo gigante de entrar. O desktop tem o teto dele em `src/size_limit.rs` (100 MB), pela medição de
 * lá; não é regra do cofre, é capacidade de cada plataforma.
 *
 * A CONTA (base64 + soma + comparação) vem do núcleo pela ponte (`localkeys_core::size`), a mesma do desktop: aqui só
 * ficam os números desta plataforma.
 */
object VaultSizeLimit {
    /** Soma máxima dos dois arquivos `.tkeys` na junção (o mesmo teto do aviso ao anexar). */
    const val MAX_BYTES: Long = 20L * 1024 * 1024

    /** Limite por anexo, o mesmo do desktop (`jobs.rs`): o blob inteiro é recifrado a cada gravação. */
    const val MAX_ATTACHMENT_BYTES: Long = 10L * 1024 * 1024

    /** O que o anexo ocupa em base64 — a fórmula do núcleo. */
    fun base64Bytes(bytes: Long): Long = attachmentBase64Size(bytes)

    /**
     * Tamanho previsto do `.tkeys` depois dos anexos: o arquivo aberto (`0` para cofre novo/sem arquivo) + o base64
     * de cada anexo novo (em bytes crus). A conta é a do núcleo.
     */
    fun predictedVaultBytes(vaultBytes: Long, rawAttachmentBytes: Iterable<Long>): Long =
        vaultSizeAfterAttachments(vaultBytes, rawAttachmentBytes.toList())

    /** O aviso aparece? Exatamente no teto ainda não; um byte acima sim. */
    fun exceedsWarnLimit(predictedBytes: Long): Boolean = exceedsVaultLimit(predictedBytes, MAX_BYTES)

    /**
     * O aviso antes de guardar um anexo: o tamanho previsto quando ele passa do teto, ou `null` quando pode entrar.
     * [pendingRawBytes] são os anexos que ainda não estão no arquivo (o item NOVO no editor); num item existente os
     * anexos dele já estão dentro do [vaultBytes].
     */
    fun attachWarning(vaultBytes: Long, pendingRawBytes: List<Long>, newRawBytes: Long): Long? =
        predictedVaultBytes(vaultBytes, pendingRawBytes + newRawBytes).takeIf(::exceedsWarnLimit)
}

/** O que fazer com um anexo recém-lido no editor (unidade 8). */
sealed interface PendingAttachment {
    /** Cabe no teto: entra no item na hora. */
    data class Ready(val attachment: Attachment) : PendingAttachment

    /** Passa do teto: espera o "Continuar" (ou é descartado no "Cancelar"); [predictedBytes] é o que o aviso mostra. */
    data class Confirm(val attachment: Attachment, val predictedBytes: Long) : PendingAttachment
}

/**
 * A decisão do anexo recém-lido: entra direto ou espera o aviso de tamanho. [pendingRawBytes] são os anexos que ainda
 * não estão no arquivo (o item NOVO no editor); num item existente, os anexos dele já estão dentro do tamanho aberto.
 */
fun gateAttachment(vaultBytes: Long, pendingRawBytes: List<Long>, attachment: Attachment): PendingAttachment {
    val predicted = VaultSizeLimit.attachWarning(vaultBytes, pendingRawBytes, attachment.size)
    return if (predicted == null) PendingAttachment.Ready(attachment) else PendingAttachment.Confirm(attachment, predicted)
}
