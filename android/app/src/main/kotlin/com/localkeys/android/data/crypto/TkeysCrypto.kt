package com.localkeys.android.data.crypto

import com.localkeys.android.data.vault.Folder
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Vault
import com.localkeys.android.ffi.FfiException
import com.localkeys.android.ffi.FieldLabelsFfi
import com.localkeys.android.ffi.KdfParamsFfi
import com.localkeys.android.ffi.Session
import com.localkeys.android.ffi.changeItemKind as ffiChangeItemKind
import com.localkeys.android.ffi.createVault as ffiCreateVault
import com.localkeys.android.ffi.mergeOtherVault as ffiMergeOtherVault
import com.localkeys.android.ffi.moveItems as ffiMoveItems
import com.localkeys.android.ffi.openVault as ffiOpenVault
import com.localkeys.android.ffi.openVaultWithKey as ffiOpenVaultWithKey
import com.localkeys.android.ffi.subsetVault as ffiSubsetVault
import com.localkeys.android.ffi.trashItems as ffiTrashItems
import org.json.JSONArray
import org.json.JSONObject

/**
 * Erros do `.tkeys`, espelhando o `CryptoError` do desktop.
 * `Decrypt` é proposital: senha errada e arquivo adulterado caem no mesmo caso.
 */
sealed class TkeysError : Exception() {
    object BadFormat : TkeysError() {
        override val message: String get() = "arquivo não é um vault .tkeys válido"
    }
    class UnsupportedVersion(val version: Int) : TkeysError() {
        override val message: String get() = "versão de formato .tkeys não suportada: $version"
    }
    object Kdf : TkeysError() {
        override val message: String get() = "falha ao derivar a chave (KDF)"
    }
    object Decrypt : TkeysError() {
        override val message: String get() = "senha incorreta ou arquivo corrompido/adulterado"
    }
    object Corrupted : TkeysError() {
        override val message: String get() = "arquivo corrompido (estrutura inválida)"
    }
    /** O item não pode mudar para o tipo pedido (regra do núcleo). A [reason] é o diagnóstico em inglês do núcleo. */
    class NotConvertible(val reason: String) : TkeysError() {
        override val message: String get() = "item não pode ser convertido para o tipo pedido: $reason"
    }
    /**
     * O lote (lixeira/pasta) foi recusado inteiro pela regra do núcleo: um item inválido, um item já na lixeira ou
     * uma pasta que não existe. A [reason] é o diagnóstico em inglês do núcleo; nada foi alterado.
     */
    class InvalidItems(val reason: String) : TkeysError() {
        override val message: String get() = "itens não podem ser alterados: $reason"
    }
    /**
     * A seleção não pode virar um cofre novo: a regra do núcleo recusou (item sem `id`, entrada que não é lista ou
     * seleção vazia). A [reason] é o diagnóstico em inglês do núcleo; nada foi gerado.
     */
    class CannotExport(val reason: String) : TkeysError() {
        override val message: String get() = "itens não podem ser exportados: $reason"
    }
    /**
     * Os cofres não podem ser juntados: cofre sem `items`/`folders` válido, item/pasta que não é objeto ou sem `id`,
     * JSON inválido. A [reason] é o diagnóstico em inglês do núcleo; nada foi montado.
     */
    class CannotMerge(val reason: String) : TkeysError() {
        override val message: String get() = "cofres não podem ser juntados: $reason"
    }
}

/**
 * O `.tkeys` do app: formato, Argon2id e XChaCha20-Poly1305 são o MESMO código do desktop (`localkeys-core`, em
 * Rust), chamado por UniFFI (`crates/ffi`). Esta classe só traduz tipos e erros, para as telas e o repositório
 * continuarem como estavam.
 */
class TkeysCrypto {

    /**
     * Abre um `.tkeys` com a **chave bruta** (ex.: recuperada do cofre biométrico do SO). Devolve plaintext + sessão
     * viva (salt/params do arquivo) — o caminho do desbloqueio rápido, sem re-rodar o Argon2.
     */
    fun openVaultWithKey(key: ByteArray, file: ByteArray): OpenedVault {
        require(key.size == TkeysFormat.KEY_LEN) { "chave deve ter ${TkeysFormat.KEY_LEN} bytes" }
        val opened = translating { ffiOpenVaultWithKey(key, file) }
        return OpenedVault(opened.plaintext, SessionKey(opened.session))
    }

    /** Decifra um `.tkeys` com a **chave bruta** (sem re-rodar o Argon2). */
    fun openWithKey(key: ByteArray, file: ByteArray): ByteArray = openVaultWithKey(key, file).plaintext

    /**
     * Cria um vault novo: deriva a chave da `password` e cifra o `plaintext` inicial. Devolve o arquivo + a sessão
     * (já fica "destrancado").
     */
    fun createVault(
        password: String,
        plaintext: ByteArray,
        params: KdfParams = TkeysFormat.defaultParams(),
    ): CreatedVault {
        val created = translating { ffiCreateVault(password, plaintext, params.toFfi()) }
        return CreatedVault(created.file, SessionKey(created.session))
    }

    /**
     * Abre um `.tkeys`: valida o header, deriva a chave e decifra. Devolve o plaintext e a sessão para salvar depois.
     * Falha **genérica** em senha errada OU adulteração.
     */
    fun openVault(password: String, file: ByteArray): OpenedVault {
        val opened = translating { ffiOpenVault(password, file) }
        return OpenedVault(opened.plaintext, SessionKey(opened.session))
    }
}

/** Resultado de criar um vault: bytes do arquivo cifrado + sessão viva. */
data class CreatedVault(
    val file: ByteArray,
    val session: SessionKey,
)

/** Resultado de abrir um vault: plaintext decifrado + sessão viva. */
data class OpenedVault(
    val plaintext: ByteArray,
    val session: SessionKey,
)

/**
 * Chave de sessão (a sessão do núcleo Rust): a chave derivada + salt/params do vault aberto. Permite recifrar
 * (salvar) sem re-rodar o Argon2 nem guardar a master password — só sorteia um nonce novo a cada save
 * (exigência do XChaCha20-Poly1305).
 */
class SessionKey internal constructor(private val session: Session) {
    /** Recifra o `plaintext` com a chave da sessão e um nonce novo. */
    fun seal(plaintext: ByteArray): ByteArray = translating { session.seal(plaintext) }

    /** Cópia da chave bruta (32 bytes) — só para o cofre do SO no desbloqueio rápido. */
    fun keyBytes(): ByteArray = session.keyBytes()
}

private fun KdfParams.toFfi() = KdfParamsFfi(mCost = mCostKib.toUInt(), tCost = tCost.toUInt(), pCost = pCost.toUInt())

/**
 * Converte um item do cofre para outro `kind` pela regra do núcleo (`change_kind`), a mesma do desktop: todo
 * objeto tipado que não é o do tipo novo vira campo extra (senha, TOTP, número e código do cartão ocultos).
 * [labels] são os rótulos dos campos gerados no idioma do usuário — os mesmos textos do editor.
 */
fun changeItemKind(item: Item, newKind: ItemKind, labels: FieldLabelsFfi): Item =
    translating { Item.parse(JSONObject(ffiChangeItemKind(item.toJson().toString(), newKind.wire, labels))) }

/**
 * Manda para a lixeira os [items] SELECIONADOS (não o cofre inteiro: num cofre grande o JSON custa memória) pela
 * regra do núcleo, com o mesmo [now] em todos. Lote é tudo ou nada: um item inválido e nada muda
 * ([TkeysError.InvalidItems]). Devolve os itens atualizados, na mesma ordem.
 */
fun trashItems(items: List<Item>, now: Long): List<Item> =
    batchItems(items) { json -> ffiTrashItems(json, now.toULong()) }

/**
 * Põe os [items] SELECIONADOS na pasta [folderId] (`null` = sem pasta) pela regra do núcleo, com os [folderIds] que
 * o cofre conhece (pasta de destino fora deles é recusa: o núcleo valida, o Kotlin não reescreve a regra). Lote é
 * tudo ou nada ([TkeysError.InvalidItems]); devolve os itens atualizados, na mesma ordem.
 */
fun moveItems(items: List<Item>, folderId: String?, folderIds: List<String>, now: Long): List<Item> =
    batchItems(items) { json -> ffiMoveItems(json, folderId, folderIds, now.toULong()) }

/**
 * Gera o JSON de um cofre NOVO com os [items] SELECIONADOS e só as [folders] que eles referenciam, pela regra do
 * núcleo: os itens vão inteiros (id, anexos, histórico, passkeys, chaves desconhecidas). O cofre inteiro não
 * atravessa — só a seleção e as pastas (pequenas): num cofre grande o JSON completo custa memória. Quem cifra é o
 * [TkeysCrypto.createVault], com o nome do arquivo e a senha escolhidos pelo usuário; uma recusa da regra
 * ([TkeysError.CannotExport]) não gera nada.
 */
fun subsetVault(folders: List<Folder>, items: List<Item>): String {
    val foldersJson = JSONArray(folders.map { it.toJson() }).toString()
    val itemsJson = JSONArray(items.map { it.toJson() }).toString()
    return translating { ffiSubsetVault(foldersJson, itemsJson) }
}

/**
 * Junta o cofre [other] (somente leitura) no cofre [base] pela regra do núcleo: devolve o cofre MESCLADO (novo, com
 * os itens/pastas que entraram) e o resumo que a tela mostra antes de confirmar. Em colisão de id, o que entra ganha
 * um id novo; o outro cofre não é alterado e nada é gravado aqui ([TkeysError.CannotMerge] quando a regra recusa).
 */
fun mergeOtherVault(base: Vault, other: Vault): MergeOtherResult {
    val out = translating { ffiMergeOtherVault(base.toJson().toString(), other.toJson().toString()) }
    return MergeOtherResult(
        merged = Vault.parse(out.mergedJson),
        added = out.added.toInt(),
        collisions = out.collisions.toInt(),
        alreadyPresent = out.alreadyPresent.toInt(),
        foldersAdded = out.foldersAdded.toInt(),
        foldersMerged = out.foldersMerged.toInt(),
    )
}

/** O que a junção de outro cofre fez: o cofre resultante + as contagens para a tela confirmar. */
data class MergeOtherResult(
    val merged: Vault,
    val added: Int,
    val collisions: Int,
    val alreadyPresent: Int,
    val foldersAdded: Int,
    val foldersMerged: Int,
)

/** O lote só atravessa a ponte com os itens selecionados; o resultado volta como lista de [Item]. */
private inline fun batchItems(items: List<Item>, bridge: (itemsJson: String) -> String): List<Item> {
    val json = JSONArray(items.map { it.toJson() }).toString()
    val out = translating { JSONArray(bridge(json)) }
    return (0 until out.length()).map { Item.parse(out.getJSONObject(it)) }
}

/** O erro do núcleo vira o [TkeysError] que o resto do app já trata. */
private inline fun <T> translating(block: () -> T): T = try {
    block()
} catch (e: FfiException) {
    throw when (e) {
        is FfiException.BadFormat -> TkeysError.BadFormat
        is FfiException.UnsupportedVersion -> TkeysError.UnsupportedVersion(e.version.toInt())
        is FfiException.Kdf -> TkeysError.Kdf
        is FfiException.Decrypt -> TkeysError.Decrypt
        is FfiException.Corrupted -> TkeysError.Corrupted
        is FfiException.NotConvertible -> TkeysError.NotConvertible(e.reason)
        is FfiException.InvalidItems -> TkeysError.InvalidItems(e.reason)
        is FfiException.CannotExport -> TkeysError.CannotExport(e.reason)
        is FfiException.CannotMerge -> TkeysError.CannotMerge(e.reason)
    }
}
