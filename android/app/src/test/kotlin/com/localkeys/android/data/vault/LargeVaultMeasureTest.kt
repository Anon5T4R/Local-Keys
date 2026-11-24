package com.localkeys.android.data.vault

import com.localkeys.android.data.crypto.TkeysCrypto
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Medição — **fora do gate** (pulado sem a system property): quanto custa **abrir** e **salvar** um cofre
 * `.tkeys` grande no caminho do app (`VaultRepository` sobre a lib do host, a mesma que o teste usa por JNA).
 *
 * Os `.tkeys` não são gerados aqui: com heap de 256 MB (o limite de um app Android sem `largeHeap`) não dá para
 * montar 200 MB de JSON em Kotlin. Quem gera é o teste do desktop, que os deixa numa pasta:
 * ```text
 * $env:LOCALKEYS_MEASURE_FIXTURES="C:\Temp\localkeys-measure"
 * cargo test --release --locked -p localkeys-core --test measure_large_vault -- --ignored --nocapture
 * .\gradlew testDebugUnitTest --console=plain "-Plocalkeys.measure=true" "-Plocalkeys.measure.dir=C:/Temp/localkeys-measure"
 * ```
 * (a senha dos cofres é `measure-large-vault`; o build só aplica o heap de 256 MB, a property e a lib do host em
 * **release** com `-Plocalkeys.measure=true` — sem ela o teste se pula e o gate não muda de tempo).
 *
 * O "abrir" é o do `VaultViewModel.unlock`: ler o arquivo, `repository.unlock` (decifra + `Vault.parse`). O
 * "salvar" é o do `persistAndCommit`: `repository.save()` (toJson + cifra), `verifySaved` (decifra e compara),
 * gravar, reler o arquivo e `verifySaved` de novo.
 *
 * O pico é o do heap **usado** da JVM (`Runtime`), amostrado a cada 5 ms — não é o pico de um aparelho.
 */
class LargeVaultMeasureTest {

    private val sizes = listOf(50, 100, 200)

    @Before
    fun onlyWhenAsked() {
        assumeTrue(
            "medição de cofre grande desligada (rode com -Plocalkeys.measure)",
            System.getProperty("localkeys.measure") == "true",
        )
    }

    @Test
    fun abrir_e_salvar_cofre_grande() {
        val dir = fixturesDir()
        println("fixtures: ${dir.absolutePath}")
        println("heap máximo desta JVM: ${mb(Runtime.getRuntime().maxMemory())} MiB")

        for (label in sizes) {
            val file = File(dir, "vault-$label.tkeys")
            if (!file.isFile) {
                println()
                println("~$label MB: fixture ausente (${file.absolutePath}) — rode o teste do desktop com LOCALKEYS_MEASURE_FIXTURES")
                continue
            }
            measureSize(label, file)
        }
    }

    /**
     * O "abrir" por dentro: decifrar (a ponte Rust) e `Vault.parse` (org.json) separados, para saber quem pesa.
     * Sem o repositório: aqui só se mede o custo das duas fases.
     */
    @Test
    fun decifrar_e_parsear_separados() {
        val dir = fixturesDir()
        println("fixtures: ${dir.absolutePath}")
        println("heap máximo desta JVM: ${mb(Runtime.getRuntime().maxMemory())} MiB")
        val crypto = TkeysCrypto()

        for (label in sizes) {
            val file = File(dir, "vault-$label.tkeys")
            if (!file.isFile) {
                println()
                println("~$label MB: fixture ausente (${file.absolutePath})")
                continue
            }
            println()
            println("── cofre ~$label MB · ${file.length()} bytes no disco")
            var blob: ByteArray? = null
            var plaintext: ByteArray? = null
            var readMs = 0.0
            var decryptMs = 0.0
            var decryptPeak = 0L
            try {
                decryptPeak = measurePeak {
                    var t = System.nanoTime()
                    blob = file.readBytes()
                    readMs = elapsedMs(t)
                    t = System.nanoTime()
                    plaintext = crypto.openVault(PASSWORD, blob!!).plaintext
                    decryptMs = elapsedMs(t)
                }
                println("  ler ${fmt(readMs)} ms · decifrar ${fmt(decryptMs)} ms · pico de heap ${mb(decryptPeak)} MiB")
            } catch (e: OutOfMemoryError) {
                println("  decifrar: OOM com heap de ${mb(Runtime.getRuntime().maxMemory())} MiB")
                System.gc()
                continue
            }

            var parseMs = 0.0
            var parsePeak = 0L
            try {
                parsePeak = measurePeak {
                    val t = System.nanoTime()
                    val vault = Vault.parse(String(plaintext!!))
                    parseMs = elapsedMs(t)
                    assertEquals(1, vault.items.size)
                }
                println("  parse (org.json) ${fmt(parseMs)} ms · pico de heap ${mb(parsePeak)} MiB")
            } catch (e: OutOfMemoryError) {
                println("  parse (org.json): OOM com heap de ${mb(Runtime.getRuntime().maxMemory())} MiB")
                System.gc()
            }
            blob = null
            plaintext = null
            System.gc()
        }
    }

    private fun fixturesDir(): File = System.getProperty("localkeys.measure.dir")
        ?.let(::File)
        ?: File(System.getProperty("java.io.tmpdir"), "localkeys-measure")

    private fun measureSize(label: Int, file: File) {
        println()
        println("── cofre ~$label MB · ${file.length()} bytes no disco · heap usada ${usedHeapMb()} MiB")
        val repository = VaultRepository(TkeysCrypto())

        var readMs = 0.0
        var unlockMs = 0.0
        var openMs = 0.0
        var items = 0
        var openPeak = 0L
        try {
            openPeak = measurePeak {
                val started = System.nanoTime()
                var t = System.nanoTime()
                val blob = file.readBytes()
                readMs = elapsedMs(t)
                t = System.nanoTime()
                val vault = repository.unlock(blob, PASSWORD)
                unlockMs = elapsedMs(t)
                openMs = elapsedMs(started)
                items = vault.items.size
            }
            println(
                "  abrir  ${fmt(openMs)} ms (ler ${fmt(readMs)} + decifrar/parsear ${fmt(unlockMs)})" +
                    " · itens $items · pico de heap ${mb(openPeak)} MiB",
            )
        } catch (e: OutOfMemoryError) {
            println("  abrir: OOM com heap de ${mb(Runtime.getRuntime().maxMemory())} MiB (o app sem largeHeap não passaria)")
            System.gc()
            return
        }

        var sealMs = 0.0
        var verifyMs = 0.0
        var writeMs = 0.0
        var readBackMs = 0.0
        var verifyBackMs = 0.0
        var saveMs = 0.0
        var out: File? = null
        try {
            val savePeak = measurePeak {
                val started = System.nanoTime()
                var t = System.nanoTime()
                val sealed = repository.save()
                sealMs = elapsedMs(t)
                t = System.nanoTime()
                repository.verifySaved(sealed)
                verifyMs = elapsedMs(t)
                out = File(file.parentFile, "vault-$label.out.tkeys")
                t = System.nanoTime()
                out!!.writeBytes(sealed)
                writeMs = elapsedMs(t)
                t = System.nanoTime()
                val back = out!!.readBytes()
                readBackMs = elapsedMs(t)
                t = System.nanoTime()
                repository.verifySaved(back)
                verifyBackMs = elapsedMs(t)
                saveMs = elapsedMs(started)
            }
            println(
                "  salvar ${fmt(saveMs)} ms (cifrar ${fmt(sealMs)} + validar ${fmt(verifyMs)} + gravar ${fmt(writeMs)}" +
                    " + reler ${fmt(readBackMs)} + conferir ${fmt(verifyBackMs)}) · pico de heap ${mb(savePeak)} MiB",
            )
        } catch (e: OutOfMemoryError) {
            println(
                "  salvar: OOM com heap de ${mb(Runtime.getRuntime().maxMemory())} MiB" +
                    " (até aqui: cifrar ${fmt(sealMs)} ms, validar ${fmt(verifyMs)} ms, gravar ${fmt(writeMs)} ms)",
            )
            System.gc()
        } finally {
            out?.delete()
        }
    }

    /** Roda [block] com um amostrador de heap usado a cada 5 ms; devolve o pico (bytes). */
    private fun measurePeak(block: () -> Unit): Long {
        val stop = AtomicBoolean(false)
        val peak = AtomicLong(usedHeap())
        val sampler = Thread {
            while (!stop.get()) {
                peak.accumulateAndGet(usedHeap(), ::maxOf)
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        sampler.isDaemon = true
        sampler.start()
        try {
            block()
            return peak.get()
        } finally {
            stop.set(true)
            sampler.interrupt()
            sampler.join()
        }
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun usedHeapMb(): Long = mb(usedHeap())

    private fun elapsedMs(since: Long): Double = (System.nanoTime() - since) / 1_000_000.0

    private fun fmt(value: Double): String = String.format("%.1f", value)

    private fun mb(bytes: Long): Long = bytes / (1024 * 1024)

    private companion object {
        /** A mesma senha que o teste do desktop usa nos cofres de medição. */
        const val PASSWORD = "measure-large-vault"
    }
}
