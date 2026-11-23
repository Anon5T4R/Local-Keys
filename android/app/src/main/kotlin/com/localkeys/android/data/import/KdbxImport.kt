package com.localkeys.android.data.import

import com.localkeys.android.R
import com.localkeys.android.data.vault.CustomField
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.engines.Salsa20Engine
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Import de cofres KeePass (`.kdbx`) — port do `kdbx.rs` do desktop com o
 * formato decifrado na mão (o desktop usa o crate `keepass`; aqui o Android
 * não tem crate, então o KDBX 4.0 é decifrado com primitivas do JCE +
 * BouncyCastle para Argon2/ChaCha20/Salsa20).
 *
 * Formato 4.0: cabeçalho com VariantDictionary (KDF/cifra), payload em
 * HMAC-block-stream cifrado com AES-256-CBC, e os campos `Protected="True"`
 * do XML com stream ChaCha20/Salsa20. Só as entradas da RAIZ são importadas
 * (grupos aninhados ficam de fora — mesmo comportamento do desktop).
 */
object KdbxImport {

    private val KDBX_SIG = byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte())

    private val AES256_UUID = hex("31c1f2e6bf714350be5805216afc5aff")
    private val KDF_AES_UUID = hex("7c02bb8279a74ac0927d114a00648238")
    private val KDF_AES_KDBX3_UUID = hex("c9d9f39a628a4460bf740d08c18a4fea")
    private val KDF_ARGON2_UUID = hex("ef636ddf8c29444b91f7a9a403e30a0c")
    private val KDF_ARGON2ID_UUID = hex("9e298b1956db4773b23dfc3ec6f0a1e6")

    private const val INNER_PLAIN = 0
    private const val INNER_SALSA20 = 2
    private const val INNER_CHACHA20 = 3

    // Campos padrão do KeePass que viram campos próprios do item.
    private val STANDARD_FIELDS = setOf("Title", "UserName", "Password", "URL", "Notes", "otp")
    private val SECRET_RE = Regex("pass|senha|secret|otp|totp|2fa|key|cvv|pin|seed", RegexOption.IGNORE_CASE)

    /** Abre o `.kdbx` com a senha e devolve os itens de login (só raiz). */
    fun parse(bytes: ByteArray, password: String): List<Item> {
        val decrypted = decryptToXml(bytes, password)
        return parseXml(decrypted)
    }

    // ── Decifra o arquivo → XML claro ───────────────────────────────────

    private class DecryptedXml(val xml: ByteArray, val streamId: Int, val streamKey: ByteArray)

    private fun decryptToXml(bytes: ByteArray, password: String): DecryptedXml {
        if (bytes.size < 12 || !bytes.copyOfRange(0, 4).contentEquals(KDBX_SIG)) {
            throw ImportError(R.string.import_err_not_kdbx)
        }
        val major = readU16LE(bytes, 10) // bytes 8-9 = menor, 10-11 = maior
        if (major != 4) {
            throw ImportError(R.string.import_err_kdbx_old, major)
        }

        // ── Cabeçalho externo ───────────────────────────────────────────
        val header = HashMap<Int, ByteArray>()
        var pos = 12
        while (true) {
            if (pos + 5 > bytes.size) throw ImportError(R.string.import_err_kdbx_header)
            val type = bytes[pos].toInt() and 0xFF
            val len = readU32LE(bytes, pos + 1)
            if (pos + 5 + len > bytes.size) throw ImportError(R.string.import_err_kdbx_header)
            header[type] = bytes.copyOfRange(pos + 5, pos + 5 + len)
            pos += 5 + len
            if (type == 0) break // END
        }
        val headerData = bytes.copyOfRange(0, pos)

        if (pos + 64 > bytes.size) throw ImportError(R.string.import_err_kdbx_incomplete)
        val headerSha256 = bytes.copyOfRange(pos, pos + 32)
        val headerHmac = bytes.copyOfRange(pos + 32, pos + 64)
        val streamStart = pos + 64

        val headerSha = sha256(headerData)
        if (!headerSha.contentEquals(headerSha256)) {
            throw ImportError(R.string.import_err_header_integrity)
        }

        val cipherUuid = header[2] ?: throw ImportError(R.string.import_err_no_cipher)
        if (!cipherUuid.contentEquals(AES256_UUID)) {
            throw ImportError(R.string.import_err_outer_cipher)
        }
        val compression = readU32LE(header[3] ?: byteArrayOf(0, 0, 0, 0))
        val masterSeed = header[4] ?: throw ImportError(R.string.import_err_no_seed)
        val outerIv = header[7] ?: throw ImportError(R.string.import_err_no_iv)
        val kdfParams = parseVariantDictionary(header[11] ?: throw ImportError(R.string.import_err_no_kdf_params))

        // ── Derivação da chave ──────────────────────────────────────────
        val composite = sha256(sha256(password.toByteArray(Charsets.UTF_8)))
        val transformed = kdfTransform(kdfParams, composite)
        val masterKey = sha256(masterSeed + transformed)
        val hmacKey = sha512(masterSeed + transformed + byteArrayOf(0x01))

        // ── Autenticação do cabeçalho (valida a senha) ─────────────────
        val headerHmacKey = sha512(le64(-1L) + hmacKey) // u64::MAX (índice do HMAC do cabeçalho)
        val expectedHmac = hmacSha256(headerData, headerHmacKey)
        if (!expectedHmac.contentEquals(headerHmac)) {
            throw ImportError(R.string.import_err_wrong_password_hmac)
        }

        // ── HMAC block stream → payload cifrado → decrypt + gunzip ─────
        val encrypted = readHmacBlockStream(bytes.copyOfRange(streamStart, bytes.size), hmacKey)
        val compressed = aesCbcDecrypt(masterKey, outerIv, encrypted)
        val payload = if (compression == 1) gunzip(compressed) else compressed

        // ── Cabeçalho interno → XML ─────────────────────────────────────
        var p = 0
        var streamId = INNER_CHACHA20
        var streamKey = ByteArray(0)
        var hasStream = false
        while (true) {
            if (p + 5 > payload.size) throw ImportError(R.string.import_err_inner_header)
            val type = payload[p].toInt() and 0xFF
            val len = readU32LE(payload, p + 1)
            if (p + 5 + len > payload.size) throw ImportError(R.string.import_err_inner_header)
            val data = payload.copyOfRange(p + 5, p + 5 + len)
            p += 5 + len
            when (type) {
                0 -> break // END
                1 -> {
                    if (data.size >= 4) {
                        streamId = readU32LE(data)
                        hasStream = true
                    }
                }
                2 -> streamKey = data
                3 -> Unit // anexo binário (ignorado por enquanto)
            }
        }
        if (!hasStream) throw ImportError(R.string.import_err_inner_no_cipher)
        return DecryptedXml(payload.copyOfRange(p, payload.size), streamId, streamKey)
    }

    // ── KDF ────────────────────────────────────────────────────────────

    private fun kdfTransform(vd: Map<String, ByteArray>, composite: ByteArray): ByteArray {
        val uuid = vd["\$UUID"] ?: throw ImportError(R.string.import_err_no_kdf_id)
        return when {
            uuid.contentEquals(KDF_AES_UUID) || uuid.contentEquals(KDF_AES_KDBX3_UUID) -> {
                val seed = vd["S"] ?: throw ImportError(R.string.import_err_aeskdf_seed)
                val rounds = readU64LE(vd["R"] ?: byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
                aesKdf(seed, rounds, composite)
            }
            uuid.contentEquals(KDF_ARGON2_UUID) || uuid.contentEquals(KDF_ARGON2ID_UUID) -> {
                argon2(
                    id = uuid.contentEquals(KDF_ARGON2ID_UUID),
                    salt = vd["S"] ?: throw ImportError(R.string.import_err_argon_salt),
                    memoryBytes = readU64LE(vd["M"] ?: throw ImportError(R.string.import_err_argon_memory)),
                    iterations = readU64LE(vd["I"] ?: throw ImportError(R.string.import_err_argon_iterations)),
                    parallelism = readU32LE(vd["P"] ?: throw ImportError(R.string.import_err_argon_parallelism)),
                    version = readU32LE(vd["V"] ?: throw ImportError(R.string.import_err_argon_version_missing)),
                    password = composite,
                )
            }
            else -> throw ImportError(R.string.import_err_kdf_unsupported)
        }
    }

    private fun aesKdf(seed: ByteArray, rounds: Long, composite: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        var b1 = composite.copyOfRange(0, 16)
        var b2 = composite.copyOfRange(16, 32)
        repeat(rounds.toInt()) {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(seed, "AES"))
            b1 = cipher.doFinal(b1)
            b2 = cipher.doFinal(b2)
        }
        return sha256(b1 + b2)
    }

    private fun argon2(
        id: Boolean,
        salt: ByteArray,
        memoryBytes: Long,
        iterations: Long,
        parallelism: Int,
        version: Int,
        password: ByteArray,
    ): ByteArray {
        if (iterations < 1 || memoryBytes < 8 * 1024 || parallelism < 1) {
            throw ImportError(R.string.import_err_argon_params_file)
        }
        val v = when (version) {
            0x10 -> Argon2Parameters.ARGON2_VERSION_10
            0x13 -> Argon2Parameters.ARGON2_VERSION_13
            else -> throw ImportError(R.string.import_err_argon_version)
        }
        return try {
            val params = Argon2Parameters.Builder(if (id) Argon2Parameters.ARGON2_id else Argon2Parameters.ARGON2_d)
                .withVersion(v)
                .withIterations(iterations.toInt())
                .withMemoryAsKB((memoryBytes / 1024).toInt())
                .withParallelism(parallelism)
                .withSalt(salt)
                .build()
            val gen = Argon2BytesGenerator()
            gen.init(params)
            val out = ByteArray(32)
            gen.generateBytes(password, out)
            out
        } catch (e: Exception) {
            throw ImportError(R.string.import_err_argon_failed, e.message.orEmpty())
        }
    }

    // ── VariantDictionary ──────────────────────────────────────────────

    private fun parseVariantDictionary(data: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        if (data.size < 2) throw ImportError(R.string.import_err_variant_corrupt)
        if (readU16LE(data, 0) != 0x0100) throw ImportError(R.string.import_err_variant_version)
        var pos = 2
        while (pos < data.size) {
            val type = data[pos].toInt() and 0xFF
            pos += 1
            if (type == 0) return out // END
            if (pos + 8 > data.size) throw ImportError(R.string.import_err_variant_corrupt)
            val keyLen = readU32LE(data, pos)
            pos += 4
            if (pos + keyLen + 4 > data.size) throw ImportError(R.string.import_err_variant_corrupt)
            val key = String(data, pos, keyLen, Charsets.UTF_8)
            pos += keyLen
            val valLen = readU32LE(data, pos)
            pos += 4
            if (pos + valLen > data.size) throw ImportError(R.string.import_err_variant_corrupt)
            out[key] = data.copyOfRange(pos, pos + valLen)
            pos += valLen
        }
        return out
    }

    // ── HMAC block stream ──────────────────────────────────────────────

    private fun readHmacBlockStream(data: ByteArray, hmacKey: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var pos = 0
        var index = 0L
        while (pos < data.size) {
            if (pos + 36 > data.size) throw ImportError(R.string.import_err_hmac_block)
            val hmac = data.copyOfRange(pos, pos + 32)
            val size = readU32LE(data, pos + 32)
            if (pos + 36 + size > data.size) throw ImportError(R.string.import_err_hmac_block)
            val block = data.copyOfRange(pos + 36, pos + 36 + size)
            val blockKey = sha512(le64(index) + hmacKey)
            val idx = le64(index)
            val sizeBytes = data.copyOfRange(pos + 32, pos + 36)
            if (!hmacSha256(idx + sizeBytes + block, blockKey).contentEquals(hmac)) {
                throw ImportError(R.string.import_err_payload_integrity, index)
            }
            pos += 36 + size
            index++
            if (size == 0) break
            out.write(block)
        }
        return out.toByteArray()
    }

    // ── XML → itens ────────────────────────────────────────────────────

    private fun parseXml(decrypted: DecryptedXml): List<Item> {
        val doc = parseDom(decrypted.xml)
        val inner = innerCipher(decrypted.streamId, decrypted.streamKey)
        val rootGroup = doc.documentElement?.child("Root")?.child("Group")
            ?: throw ImportError(R.string.import_err_xml_root)
        val items = mutableListOf<Item>()
        for (el in rootGroup.children("Entry")) {
            val fields = LinkedHashMap<String, String>()
            val protectedFields = HashSet<String>()
            for (s in el.children("String")) {
                val key = s.child("Key")?.text() ?: continue
                val value = s.child("Value")
                if (value != null) {
                    val protected = value.getAttribute("Protected").equals("True", ignoreCase = true)
                    val raw = value.text()
                    fields[key] = if (protected) {
                        protectedFields.add(key)
                        val buf = base64Decode(raw)
                        String(inner.decrypt(buf), Charsets.UTF_8)
                    } else {
                        raw
                    }
                }
            }
            val name = fields["Title"].orEmpty()
            val username = fields["UserName"].orEmpty()
            val password = fields["Password"].orEmpty()
            if (name.isEmpty() && username.isEmpty() && password.isEmpty()) continue
            val url = fields["URL"].orEmpty()
            val now = System.currentTimeMillis()
            val customFields = fields.filterKeys { it !in STANDARD_FIELDS }.map { (k, v) ->
                CustomField(
                    id = UUID.randomUUID().toString(),
                    name = k,
                    value = v,
                    hidden = k in protectedFields || SECRET_RE.containsMatchIn(k),
                )
            }
            items.add(
                Item(
                    id = UUID.randomUUID().toString(),
                    kind = ItemKind.LOGIN,
                    name = name.ifEmpty { url.ifEmpty { username.ifEmpty { "(sem nome)" } } },
                    favorite = false,
                    folderId = null,
                    notes = fields["Notes"].orEmpty(),
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                    login = Login(
                        username = username,
                        password = password,
                        uris = if (url.isNotEmpty()) listOf(url) else listOf(""),
                        totp = Importers.extractSecret(fields["otp"].orEmpty()),
                    ),
                    card = null,
                    identity = null,
                    passwordHistory = null,
                    customFields = customFields.takeIf { it.isNotEmpty() },
                    attachments = null,
                )
            )
        }
        return items
    }

    // ── DOM helpers ────────────────────────────────────────────────────

    private fun parseDom(xml: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (e: Exception) {
            // versões de Android sem essa feature seguem sem ela
        }
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        } catch (e: Exception) {
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }

    private fun Element.child(tag: String): Element? {
        val nodes = getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.parentNode === this && n is Element) return n
        }
        return null
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = childNodes
        val out = mutableListOf<Element>()
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.tagName == tag) out.add(n)
        }
        return out
    }

    private fun Element.text(): String = textContent ?: ""

    // ── Primitivas ─────────────────────────────────────────────────────

    /** Stream interno dos campos protegidos (um só keystream contínuo no XML). */
    private class InnerCipher(
        private val engine: org.bouncycastle.crypto.StreamCipher,
    ) {
        fun decrypt(data: ByteArray): ByteArray {
            val out = ByteArray(data.size)
            engine.processBytes(data, 0, data.size, out, 0)
            return out
        }
    }

    private fun innerCipher(streamId: Int, streamKey: ByteArray): InnerCipher {
        val params: org.bouncycastle.crypto.CipherParameters = when (streamId) {
            INNER_CHACHA20 -> {
                val iv = sha512(streamKey)
                ParametersWithIV(KeyParameter(iv.copyOfRange(0, 32)), iv.copyOfRange(32, 44))
            }
            INNER_SALSA20 -> {
                ParametersWithIV(
                    KeyParameter(streamKey),
                    byteArrayOf(0xE8.toByte(), 0x30, 0x09, 0x4B.toByte(), 0x97.toByte(), 0x20, 0x5D, 0x2A.toByte()),
                )
            }
            INNER_PLAIN -> return InnerCipher(object : org.bouncycastle.crypto.StreamCipher {
                override fun getAlgorithmName() = "Plain"
                override fun init(forEncryption: Boolean, params: org.bouncycastle.crypto.CipherParameters) {}
                override fun returnByte(input: Byte): Byte = input
                override fun processBytes(input: ByteArray, inOff: Int, len: Int, out: ByteArray, outOff: Int): Int {
                    System.arraycopy(input, inOff, out, outOff, len)
                    return len
                }
                override fun reset() {}
            })
            else -> throw ImportError(R.string.import_err_field_cipher, streamId)
        }
        val engine = if (streamId == INNER_CHACHA20) ChaCha7539Engine() else Salsa20Engine()
        engine.init(false, params)
        return InnerCipher(engine)
    }

    private fun base64Decode(s: String): ByteArray = try {
        Base64.getDecoder().decode(s.trim())
    } catch (e: IllegalArgumentException) {
        throw ImportError(R.string.import_err_b64_protected)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)

    private fun hmacSha256(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

    private fun readU16LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readU32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readU32LE(b: ByteArray): Int = readU32LE(b, 0)

    private fun readU64LE(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        }
        return v
    }

    private fun readU64LE(b: ByteArray): Long = readU64LE(b, 0)

    private fun le64(v: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = ((v shr (8 * i)) and 0xFF).toByte()
        return out
    }

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }
}
