package com.localkeys.android.data.vault

import com.localkeys.android.data.crypto.ReferenceTkeysCrypto
import com.localkeys.android.data.crypto.TkeysCrypto
import com.localkeys.android.data.crypto.TkeysError
import com.localkeys.android.data.crypto.TkeysFormat
import com.localkeys.android.data.crypto.refParseHeader
import com.localkeys.android.data.crypto.TkeysCryptoTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * O repositório é a ponte entre o `.tkeys` cifrado e o `Vault` em memória.
 * Estes testes rodam na JVM (núcleo Rust por UniFFI), sem Android — exatamente como
 * a suíte de crypto.
 */
class VaultRepositoryTest {

    private lateinit var crypto: TkeysCrypto
    private lateinit var repository: VaultRepository

    @Before
    fun setUp() {
        crypto = TkeysCrypto()
        repository = VaultRepository(crypto)
    }

    // ── Abrir ────────────────────────────────────────────────────────────

    @Test
    fun desbloqueia_o_vault_do_desktop_e_parseia_os_itens() {
        val vault = repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        assertTrue(repository.isUnlocked)
        assertEquals(1, vault.version)
        assertEquals(1, vault.folders.size)
        assertEquals(2, vault.items.size)
        assertEquals("gmail", vault.items[0].name)
        assertEquals(ItemKind.LOGIN, vault.items[0].kind)
        assertEquals("joao@gmail.com", vault.items[0].login?.username)
        assertEquals("JBSWY3DPEHPK3PXP", vault.items[0].login?.totp)
        assertEquals(ItemKind.NOTE, vault.items[1].kind)
    }

    @Test
    fun senha_errada_falha_e_nao_destrava() {
        assertThrows(TkeysError.Decrypt::class.java) {
            repository.unlock(TkeysCryptoTest.fixtureBytes(), "senha-errada")
        }
        assertFalse(repository.isUnlocked)
    }

    // ── Desbloqueio rápido (chave bruta do cofre biométrico) ─────────────

    @Test
    fun desbloqueia_com_a_chave_bruta_derivada() {
        val file = TkeysCryptoTest.fixtureBytes()
        val header = refParseHeader(file)
        val key = ReferenceTkeysCrypto().deriveKey(TkeysCryptoTest.FIXTURE_PASSWORD, header.salt, header.params)
        val vault = repository.unlockWithKey(key, file)
        assertEquals("gmail", vault.items[0].name)
        assertTrue(repository.isUnlocked)
    }

    // ── Criar e salvar ──────────────────────────────────────────────────

    @Test
    fun cria_vault_novo_e_o_arquivo_reabre_na_maquina_de_fora() {
        val vault = Vault(
            version = 1,
            folders = emptyList(),
            items = listOf(
                Item(
                    id = "n1", kind = ItemKind.NOTE, name = "Wi-Fi", favorite = false,
                    folderId = null, notes = "rede123", createdAt = 1, updatedAt = 1,
                    deletedAt = null, login = null, card = null, identity = null,
                    passwordHistory = emptyList(), customFields = emptyList(), attachments = emptyList(),
                ),
            ),
        )
        val file = repository.create(vault, "senha-nova")

        // O arquivo gerado é um .tkeys válido e abre fora (senha correta).
        val opened = crypto.openVault("senha-nova", file)
        val reloaded = Vault.parse(String(opened.plaintext))
        assertEquals(1, reloaded.items.size)
        assertEquals("Wi-Fi", reloaded.items[0].name)
    }

    @Test
    fun salvar_recifra_com_mesmo_salt_params_e_nonce_novo() {
        val vault = repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val before = refParseHeader(TkeysCryptoTest.fixtureBytes())

        val saved = repository.save()
        val after = refParseHeader(saved)

        // Salt e params do KDF preservados (a chave da sessão continua válida)…
        assertEquals(before.salt.toList(), after.salt.toList())
        assertEquals(before.params, after.params)
        // …mas o nonce gira a cada save (XChaCha20-Poly1305 exige nonce único).
        assertNotEquals(before.nonce.toList(), after.nonce.toList())

        // O arquivo salvo reabre e reflete o estado atual (2 itens do fixture).
        val reopened = crypto.openVault(TkeysCryptoTest.FIXTURE_PASSWORD, saved)
        assertEquals(2, Vault.parse(String(reopened.plaintext)).items.size)
    }

    @Test
    fun salvar_apos_edicao_grava_as_mudancas() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val vault = repository.vault
        repository.lock()

        // "Edita" offline: muda o nome do primeiro item.
        val edited = vault.copy(
            items = vault.items.mapIndexed { i, item ->
                if (i == 0) item.copy(name = "gmail-renomeado") else item
            },
        )
        // Reabre com a senha e substitui o estado (mesmo fluxo de criar).
        val file = repository.create(edited, TkeysCryptoTest.FIXTURE_PASSWORD)
        val saved = repository.save()
        val reopened = Vault.parse(String(crypto.openVault(TkeysCryptoTest.FIXTURE_PASSWORD, saved).plaintext))
        assertEquals("gmail-renomeado", reopened.items[0].name)
    }

    @Test
    fun chave_da_sessao_fica_disponivel_para_o_cofre_biometrico() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val key = repository.keyBytes()
        assertNotNull(key)
        assertEquals(TkeysFormat.KEY_LEN, key!!.size)
        repository.lock()
        assertNull(repository.keyBytes())
        assertFalse(repository.isUnlocked)
    }

    @Test
    fun travar_descarta_a_sessao() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        repository.lock()
        assertFalse(repository.isUnlocked)
        assertThrows(IllegalStateException::class.java) { repository.vault }
        assertThrows(IllegalStateException::class.java) { repository.save() }
    }

    @Test
    fun append_items_importados_entram_no_vault_atual() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val imported = Item(
            id = "imp1", kind = ItemKind.LOGIN, name = "Importado", favorite = false,
            folderId = null, notes = "", createdAt = 1, updatedAt = 1, deletedAt = null,
            login = Login(username = "u", password = "p", uris = listOf("https://x"), totp = ""),
            card = null, identity = null, passwordHistory = emptyList(),
            customFields = emptyList(), attachments = emptyList(),
        )
        val merged = repository.appendItems(listOf(imported))
        assertEquals(3, merged.items.size)
        assertEquals("Importado", repository.vault.items[2].name)

        // O append não muda o arquivo: salvar recifra o estado novo.
        val saved = repository.save()
        val reopened = Vault.parse(String(crypto.openVault(TkeysCryptoTest.FIXTURE_PASSWORD, saved).plaintext))
        assertEquals(3, reopened.items.size)
        assertEquals("imp1", reopened.items[2].id)
    }

    // ── CRUD manual ──────────────────────────────────────────────────────

    private fun login(id: String, name: String): Item = Item(
        id = id, kind = ItemKind.LOGIN, name = name, favorite = false,
        folderId = null, notes = "", createdAt = 1, updatedAt = 1, deletedAt = null,
        login = Login(username = "", password = "", uris = emptyList(), totp = ""),
        card = null, identity = null, passwordHistory = emptyList(),
        customFields = emptyList(), attachments = emptyList(),
    )

    @Test
    fun add_item_entra_no_fim_e_persiste_no_arquivo() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val novo = login("add1", "Manual")
        val updated = repository.addItem(novo)
        assertEquals(3, updated.items.size)
        assertEquals("Manual", repository.vault.items[2].name)

        val saved = repository.save()
        val reopened = Vault.parse(String(crypto.openVault(TkeysCryptoTest.FIXTURE_PASSWORD, saved).plaintext))
        assertEquals(3, reopened.items.size)
        assertEquals("add1", reopened.items[2].id)
    }

    @Test
    fun update_item_substitui_pelo_mesmo_id_sem_duplicar() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val gmail = repository.vault.items.first { it.name == "gmail" }
        val edited = gmail.copy(name = "gmail-renomeado", login = gmail.login?.copy(username = "novo@email.com"))
        val updated = repository.updateItem(edited)
        assertEquals(2, updated.items.size)
        assertEquals("gmail-renomeado", repository.vault.items.first { it.id == gmail.id }.name)
        assertEquals("novo@email.com", repository.vault.items.first { it.id == gmail.id }.login?.username)
    }

    @Test
    fun update_item_id_inexistente_nao_duplica_estado() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        repository.updateItem(login("nao-existe", "Fantasma"))
        assertEquals(2, repository.vault.items.size)
    }

    // ── Histórico de senhas ──────────────────────────────────────────────

    @Test
    fun update_item_grava_a_senha_anterior_quando_a_senha_muda() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val gmail = repository.vault.items.first { it.name == "gmail" }
        val edited = gmail.copy(login = gmail.login?.copy(password = "nova-senha"))
        val updated = repository.updateItem(edited)

        val hist = updated.items.first { it.id == gmail.id }.passwordHistory
        assertEquals(1, hist?.size)
        assertEquals("hunter2", hist?.first()?.password)
        assertTrue((hist?.first()?.at ?: 0) > 0)
    }

    @Test
    fun update_item_sem_mudanca_de_senha_nao_grava_historico() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val gmail = repository.vault.items.first { it.name == "gmail" }
        val edited = gmail.copy(login = gmail.login?.copy(username = "novo@email.com"))
        val updated = repository.updateItem(edited)

        val hist = updated.items.first { it.id == gmail.id }.passwordHistory
        assertTrue(hist.isNullOrEmpty())
    }

    @Test
    fun historico_nao_registra_quando_a_senha_anterior_era_vazia() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val semSenha = repository.addItem(login("s1", "Sem senha"))
        val item = semSenha.items.first { it.id == "s1" }
        val edited = item.copy(login = item.login?.copy(password = "nova"))
        val updated = repository.updateItem(edited)

        val hist = updated.items.first { it.id == "s1" }.passwordHistory
        assertTrue(hist.isNullOrEmpty())
    }

    @Test
    fun historico_entradas_mais_recentes_primeiro_com_cap_de_20() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val gmail = repository.vault.items.first { it.name == "gmail" }

        // 19 entradas antigas (mais antiga = "antiga-1").
        val comHistorico = gmail.copy(
            passwordHistory = (1..19).map { PasswordHistoryEntry("antiga-$it", it.toLong()) },
        )
        // Troca 1 → 20 entradas, a mais recente "hunter2".
        val aposPrimeira = repository.updateItem(
            comHistorico.copy(login = comHistorico.login?.copy(password = "v20")),
        ).items.first { it.id == gmail.id }
        assertEquals(20, aposPrimeira.passwordHistory?.size)
        assertEquals("hunter2", aposPrimeira.passwordHistory?.first()?.password)

        // Troca 2 → estoura o cap; cai a mais velha (antiga-19).
        val aposSegunda = repository.updateItem(
            aposPrimeira.copy(login = aposPrimeira.login?.copy(password = "v21")),
        ).items.first { it.id == gmail.id }
        assertEquals(20, aposSegunda.passwordHistory?.size)
        assertEquals("v20", aposSegunda.passwordHistory?.first()?.password)
        assertTrue(aposSegunda.passwordHistory?.none { it.password == "antiga-19" } == true)
        assertTrue(aposSegunda.passwordHistory?.any { it.password == "antiga-1" } == true)
    }

    @Test
    fun delete_item_remove_do_vault_e_do_arquivo() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val gmail = repository.vault.items.first { it.name == "gmail" }
        val updated = repository.deleteItem(gmail.id)
        assertEquals(1, updated.items.size)
        assertEquals(ItemKind.NOTE, updated.items[0].kind)

        val saved = repository.save()
        val reopened = Vault.parse(String(crypto.openVault(TkeysCryptoTest.FIXTURE_PASSWORD, saved).plaintext))
        assertEquals(1, reopened.items.size)
    }

    @Test
    fun toggle_favorite_alterna_a_flag_do_item() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val gmail = repository.vault.items.first { it.name == "gmail" }
        assertFalse(gmail.favorite)
        repository.toggleFavorite(gmail.id)
        assertTrue(repository.vault.items.first { it.id == gmail.id }.favorite)
        repository.toggleFavorite(gmail.id)
        assertFalse(repository.vault.items.first { it.id == gmail.id }.favorite)
    }

    @Test
    fun crud_lança_se_o_vault_estiver_trancado() {
        assertThrows(IllegalStateException::class.java) { repository.addItem(login("x", "X")) }
        assertThrows(IllegalStateException::class.java) { repository.updateItem(login("x", "X")) }
        assertThrows(IllegalStateException::class.java) { repository.deleteItem("x") }
        assertThrows(IllegalStateException::class.java) { repository.toggleFavorite("x") }
    }

    // ── Pastas ────────────────────────────────────────────────────────────

    @Test
    fun add_folder_entra_no_vault_e_persiste_no_arquivo() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val updated = repository.addFolder(Folder("f2", "Trabalho"))
        assertEquals(2, updated.folders.size)
        assertEquals("Trabalho", repository.vault.folders.first { it.id == "f2" }.name)

        val saved = repository.save()
        val reopened = Vault.parse(String(crypto.openVault(TkeysCryptoTest.FIXTURE_PASSWORD, saved).plaintext))
        assertEquals(2, reopened.folders.size)
        assertTrue(reopened.folders.any { it.id == "f2" && it.name == "Trabalho" })
    }

    @Test
    fun rename_folder_muda_o_nome_pelo_id() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val original = repository.vault.folders.first()
        val updated = repository.renameFolder(original.id, "Pessoal")
        assertEquals(1, updated.folders.size)
        assertEquals("Pessoal", repository.vault.folders.first().name)
    }

    @Test
    fun delete_folder_remove_e_desvincula_os_itens() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val folder = repository.vault.folders.first()
        val comPasta = repository.addItem(login("em-pasta", "No folder").copy(folderId = folder.id))
        assertTrue(comPasta.items.first { it.id == "em-pasta" }.folderId == folder.id)

        val updated = repository.deleteFolder(folder.id)
        assertEquals(0, updated.folders.size)
        // O item continua no vault, mas sem pasta.
        val item = updated.items.first { it.id == "em-pasta" }
        assertNull(item.folderId)
    }

    @Test
    fun folder_crud_lança_se_trancado() {
        assertThrows(IllegalStateException::class.java) { repository.addFolder(Folder("x", "X")) }
        assertThrows(IllegalStateException::class.java) { repository.renameFolder("x", "Y") }
        assertThrows(IllegalStateException::class.java) { repository.deleteFolder("x") }
    }

    // ── Verificação de gravação (blob só é gravado se reabre) ─────────────

    @Test
    fun verify_aceita_o_blob_que_o_save_gerou() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val withItem = repository.addItem(login("v1", "verificado"))
        val blob = repository.save()
        val verified = repository.verifySaved(blob)
        assertEquals(withItem.items.size, verified.items.size)
        assertEquals("verificado", verified.items.first { it.id == "v1" }.name)
        assertTrue(repository.isUnlocked)
    }

    @Test
    fun verify_detecta_blob_adulterado() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        repository.addItem(login("v2", "tamper"))
        val blob = repository.save()
        val tampered = blob.copyOf()
        tampered[tampered.size - 1] = (tampered.last().toInt() xor 0x01).toByte()
        assertThrows(TkeysError.Corrupted::class.java) { repository.verifySaved(tampered) }
    }

    @Test
    fun arquivo_truncado_e_corrompido_nao_senha_errada() {
        val file = TkeysCryptoTest.fixtureBytes()
        val truncated = file.copyOfRange(0, TkeysFormat.HEADER_LEN + 8)
        assertThrows(TkeysError.Corrupted::class.java) {
            repository.unlock(truncated, TkeysCryptoTest.FIXTURE_PASSWORD)
        }
        assertFalse(repository.isUnlocked)
    }

    @Test
    fun blob_que_decifra_mas_eh_lixo_e_corrompido() {
        // Cifra um plaintext que abre com a senha certa, mas não é JSON de vault.
        val created = crypto.createVault("senha-do-lixo", "isto nao eh um vault".toByteArray())
        val repo = VaultRepository(crypto)
        assertThrows(TkeysError.Corrupted::class.java) { repo.unlock(created.file, "senha-do-lixo") }
    }

    // ── Reload (mudança externa: outro dispositivo sincronizou) ──────────

    @Test
    fun reload_adota_o_vault_gravado_por_outro_dispositivo() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)

        // "Outro dispositivo": um segundo repositório abre o MESMO arquivo
        // (mesmo salt → mesma chave derivada) e grava o vault editado.
        val other = VaultRepository(crypto)
        other.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        other.addItem(login("ext1", "Do desktop"))
        val externalBlob = other.save()

        val reloaded = repository.reload(externalBlob)
        assertEquals(3, reloaded.items.size)
        assertEquals("Do desktop", repository.vault.items.first { it.id == "ext1" }.name)

        // A sessão sobrevive ao reload: salvar em seguida funciona normal.
        val saved = repository.save()
        val reopened = Vault.parse(String(crypto.openVault(TkeysCryptoTest.FIXTURE_PASSWORD, saved).plaintext))
        assertEquals(3, reopened.items.size)
    }

    @Test
    fun reload_de_arquivo_com_outra_senha_falha_com_decrypt() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        // Arquivo recriado com outra senha (outro salt → outra chave): a chave
        // da sessão atual já não casa.
        val external = crypto.createVault("outra-senha", Vault.empty().toJson().toByteArray())
        assertThrows(TkeysError.Decrypt::class.java) { repository.reload(external.file) }
    }

    @Test
    fun reload_de_blob_truncado_falha_com_corrupted() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val truncated = TkeysCryptoTest.fixtureBytes().copyOfRange(0, TkeysFormat.HEADER_LEN + 8)
        assertThrows(TkeysError.Corrupted::class.java) { repository.reload(truncated) }
    }

    @Test
    fun reload_lança_se_o_vault_estiver_trancado() {
        assertThrows(IllegalStateException::class.java) {
            repository.reload(TkeysCryptoTest.fixtureBytes())
        }
    }

    // ── Lixeira de itens e de anexos (semântica do desktop) ──────────────

    @Test
    fun lixeira_marca_so_o_deletedAt_e_restaurar_zera() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val gmail = repository.vault.items.first { it.name == "gmail" }
        val trashed = repository.trashItem(gmail.id, now = 1_700_000_000_000).items.first { it.id == gmail.id }
        assertEquals(1_700_000_000_000, trashed.deletedAt)
        assertTrue(trashed.inTrash)
        // Só o deletedAt muda (o trashItem do oficial não toca em updatedAt nem no resto).
        assertEquals(gmail.copy(deletedAt = 1_700_000_000_000), trashed)
        assertEquals(listOf(gmail.id), repository.trashedItems().map { it.id })

        // Continua no arquivo, na lixeira.
        val reopened = Vault.parse(String(crypto.openVault(TkeysCryptoTest.FIXTURE_PASSWORD, repository.save()).plaintext))
        assertTrue(reopened.items.first { it.id == gmail.id }.inTrash)

        val restored = repository.restoreItem(gmail.id).items.first { it.id == gmail.id }
        assertEquals(gmail, restored)
        assertTrue(repository.trashedItems().isEmpty())
    }

    @Test
    fun excluir_de_vez_tira_o_item_da_lixeira_e_do_arquivo() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val gmail = repository.vault.items.first { it.name == "gmail" }
        repository.trashItem(gmail.id)
        repository.deleteItem(gmail.id)
        assertTrue(repository.vault.items.none { it.id == gmail.id })
        assertTrue(repository.trashedItems().isEmpty())
    }

    @Test
    fun anexo_vai_para_a_lixeira_volta_e_sai_de_vez() {
        repository.unlock(TkeysCryptoTest.fixtureBytes(), TkeysCryptoTest.FIXTURE_PASSWORD)
        val a1 = Attachment("a1", "rg.pdf", 3, "application/pdf", "AAAA")
        val a2 = Attachment("a2", "cpf.png", 3, "image/png", "BBBB")
        repository.addItem(login("doc", "Documentos").copy(attachments = listOf(a1, a2)))

        var item = repository.trashAttachment("doc", "a1", now = 42).items.first { it.id == "doc" }
        assertEquals(listOf("a2"), item.liveAttachments.map { it.id })
        assertEquals(listOf("a1"), item.trashedAttachments.map { it.id })
        assertEquals(42L, item.trashedAttachments.single().deletedAt)

        item = repository.restoreAttachment("doc", "a1").items.first { it.id == "doc" }
        assertEquals(listOf(a1, a2), item.attachments)

        repository.trashAttachment("doc", "a2")
        item = repository.deleteAttachmentForever("doc", "a2").items.first { it.id == "doc" }
        assertEquals(listOf(a1), item.attachments)

        // O resto do cofre não muda.
        assertEquals(3, repository.vault.items.size)
    }

    @Test
    fun lixeira_lança_se_o_vault_estiver_trancado() {
        assertThrows(IllegalStateException::class.java) { repository.trashItem("x") }
        assertThrows(IllegalStateException::class.java) { repository.restoreItem("x") }
        assertThrows(IllegalStateException::class.java) { repository.trashAttachment("x", "y") }
    }
}
