//! Medição — **fora do gate** (`#[ignore]`): quanto custa **abrir** e **salvar** um cofre `.tkeys` grande.
//!
//! Cofre sintético: um login com anexos de 10 MiB (o cap do app, `jobs.rs`) de bytes pseudo-aleatórios com
//! semente fixa — incompressíveis, então o tamanho no disco é o real. Três tamanhos de arquivo cifrado:
//! ~50, ~100 e ~200 MB (4, 7 e 14 anexos).
//!
//! Rodar (release; debug não representa o app):
//! ```text
//! cargo test --release --locked -p localkeys-core --test measure_large_vault -- --ignored --nocapture
//! ```
//! Para deixar os `.tkeys` em disco para o teste de JVM do Android (que não consegue gerar 200 MB com heap de
//! 256 MB), rode com a pasta de fixtures no ambiente:
//! ```text
//! $env:LOCALKEYS_MEASURE_FIXTURES="C:\Temp\localkeys-measure"
//! cargo test --release --locked -p localkeys-core --test measure_large_vault -- --ignored --nocapture
//! ```
//! O teste imprime pasta e senha, apaga os temporários que criou e deixa só as cópias da pasta de fixtures.
//!
//! O "abrir" segue o caminho do app (`jobs::unlock` + `adopt`): ler o arquivo, `open_vault` (Argon2id + AEAD),
//! `serde_json::from_slice` e `items_view` (a montagem da lista). O "salvar" segue o `persist`: clonar o JSON
//! bruto (o app clona a cada edição), `to_vec`, `SessionKey::seal` e `atomic_write` (que ainda copia o arquivo
//! anterior para o `.bak`).
//!
//! O pico de memória é o do **heap Rust** (alocador global contando), não o RSS do processo.

use std::alloc::{GlobalAlloc, Layout, System};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use base64::Engine as _;
use localkeys_core::{crypto, vault};
use serde_json::Value;

// ── contador de heap (sem dependência nova) ──────────────────────────────

struct Tracking;

static CURRENT: AtomicUsize = AtomicUsize::new(0);
static PEAK: AtomicUsize = AtomicUsize::new(0);

unsafe impl GlobalAlloc for Tracking {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let ptr = unsafe { System.alloc(layout) };
        if !ptr.is_null() {
            let now = CURRENT.fetch_add(layout.size(), Ordering::Relaxed) + layout.size();
            PEAK.fetch_max(now, Ordering::Relaxed);
        }
        ptr
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        CURRENT.fetch_sub(layout.size(), Ordering::Relaxed);
        unsafe { System.dealloc(ptr, layout) }
    }
}

#[global_allocator]
static ALLOC: Tracking = Tracking;

/// Abre uma janela de medição: o pico passa a ser o que está vivo agora.
fn reset_peak() {
    PEAK.store(CURRENT.load(Ordering::Relaxed), Ordering::Relaxed);
}

fn peak_bytes() -> usize {
    PEAK.load(Ordering::Relaxed)
}

// ── cofre sintético ──────────────────────────────────────────────────────

const PASSWORD: &str = "measure-large-vault";
/// O cap de anexo dos dois lados (`jobs.rs` / `Vault.kt`).
const ATTACHMENT_BYTES: usize = 10 * 1024 * 1024;
/// (rótulo em MB, nº de anexos de 10 MiB → arquivo cifrado ≈ 14 MB cada)
const SIZES: [(u32, usize); 3] = [(50, 4), (100, 7), (200, 14)];

/// xorshift64* com semente fixa: reprodutível e incompressível (nenhum anexo se comprime a nada).
fn pseudo_random(seed: u64, len: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(len + 8);
    let mut x = seed | 1;
    while out.len() < len {
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        out.extend_from_slice(&x.wrapping_mul(0x2545_F491_4F6C_DD1D).to_le_bytes());
    }
    out.truncate(len);
    out
}

/// JSON do cofre com `attachments` anexos de 10 MiB no mesmo login (escrita conservadora do núcleo).
fn build_vault(attachments: usize) -> Value {
    let mut raw: Value = serde_json::from_str(vault::EMPTY_VAULT).expect("semente do cofre");
    vault::add_login(&mut raw, vault::LoginForm { name: "Cofre grande", username: "qa", password: "pw", totp: "", url: "", notes: "" });
    let item_id = vault::items_view(&raw).first().expect("login recém-criado").id.clone();
    for i in 0..attachments {
        let bytes = pseudo_random(0xA5A5_0000 + i as u64, ATTACHMENT_BYTES);
        let b64 = base64::engine::general_purpose::STANDARD.encode(&bytes);
        let name = format!("anexo-{i}.bin");
        assert!(vault::add_attachment(&mut raw, &item_id, &name, "application/octet-stream", &b64), "anexo {i} recusado");
    }
    raw
}

/// Cifra com o KDF real (default do app) e grava — o `.tkeys` que o app abriria.
fn write_vault(path: &Path, raw: &Value) -> u64 {
    let plaintext = serde_json::to_vec(raw).expect("serializa o cofre");
    let (file, _session) = crypto::create_vault(PASSWORD, &plaintext).expect("cria o .tkeys");
    vault::atomic_write(path, &file).expect("grava o .tkeys");
    file.len() as u64
}

// ── medições ─────────────────────────────────────────────────────────────

struct Open {
    read: Duration,
    decrypt: Duration,
    parse: Duration,
    view: Duration,
    peak: usize,
    raw: Value,
    session: crypto::SessionKey,
}

impl Open {
    fn total(&self) -> Duration {
        self.read + self.decrypt + self.parse + self.view
    }
}

/// O caminho do app ao destrancar: `fs::read` → `open_vault` → parse → `items_view`.
fn measure_open(path: &Path) -> Open {
    reset_peak();

    let t = Instant::now();
    let file = std::fs::read(path).expect("lê o .tkeys");
    let read = t.elapsed();

    let t = Instant::now();
    let (plaintext, session) = crypto::open_vault(PASSWORD, &file).expect("decifra");
    let decrypt = t.elapsed();

    let t = Instant::now();
    let raw: Value = serde_json::from_slice(&plaintext).expect("parse do cofre");
    let parse = t.elapsed();

    let t = Instant::now();
    let items = vault::items_view(&raw);
    let view = t.elapsed();

    let peak = peak_bytes();
    assert_eq!(items.len(), 1, "a lista do app teria o login");
    Open { read, decrypt, parse, view, peak, raw, session }
}

struct Save {
    clone: Duration,
    serialize: Duration,
    seal: Duration,
    write: Duration,
    peak: usize,
}

impl Save {
    fn total(&self) -> Duration {
        self.clone + self.serialize + self.seal + self.write
    }
}

/// O caminho do app ao salvar (`persist`): clonar o bruto, `to_vec`, `seal`, `atomic_write` (com o `.bak`).
fn measure_save(path: &Path, raw: &Value, session: &crypto::SessionKey) -> Save {
    reset_peak();

    let t = Instant::now();
    let next = raw.clone();
    let clone = t.elapsed();

    let t = Instant::now();
    let bytes = serde_json::to_vec(&next).expect("serializa o cofre");
    let serialize = t.elapsed();

    let t = Instant::now();
    let sealed = session.seal(&bytes).expect("cifra");
    let seal = t.elapsed();

    let t = Instant::now();
    vault::atomic_write(path, &sealed).expect("grava o .tkeys");
    let write = t.elapsed();

    let peak = peak_bytes();
    assert_eq!(sealed.len() as u64, bytes.len() as u64 + 60 + 16, "header + tag do formato");
    Save { clone, serialize, seal, write, peak }
}

fn mb(bytes: usize) -> f64 {
    bytes as f64 / (1024.0 * 1024.0)
}

fn ms(d: Duration) -> f64 {
    d.as_secs_f64() * 1000.0
}

fn print_row(label: u32, attachments: usize, file_bytes: u64, open: &Open, save: &Save) {
    println!("cofre ~{label} MB | {attachments} anexos | arquivo {:.1} MiB ({file_bytes} bytes)", mb(file_bytes as usize));
    println!(
        "  abrir  {:8.1} ms  (ler {:6.1} + decifrar {:6.1} + parse {:6.1} + lista {:6.1})  pico {:7.1} MiB",
        ms(open.total()),
        ms(open.read),
        ms(open.decrypt),
        ms(open.parse),
        ms(open.view),
        mb(open.peak)
    );
    println!(
        "  salvar {:8.1} ms  (clonar {:6.1} + serializar {:6.1} + cifrar {:6.1} + gravar {:6.1})  pico {:7.1} MiB",
        ms(save.total()),
        ms(save.clone),
        ms(save.serialize),
        ms(save.seal),
        ms(save.write),
        mb(save.peak)
    );
}

fn fixtures_dir() -> Option<PathBuf> {
    let dir = std::env::var_os("LOCALKEYS_MEASURE_FIXTURES")?;
    let dir = PathBuf::from(dir);
    std::fs::create_dir_all(&dir).expect("cria a pasta de fixtures");
    Some(dir)
}

fn cleanup(path: &Path) {
    let _ = std::fs::remove_file(path);
    let _ = std::fs::remove_file(format!("{}.bak", path.display()));
    let _ = std::fs::remove_file(format!("{}.tmp", path.display()));
}

#[test]
#[ignore = "medição de cofre grande: roda só com --ignored (ver o doc do arquivo)"]
fn abrir_e_salvar_cofre_grande() {
    let dir = std::env::temp_dir().join("localkeys-measure");
    std::fs::create_dir_all(&dir).expect("cria a pasta temporária");
    let fixtures = fixtures_dir();
    println!("pasta temporária: {}", dir.display());
    println!("senha dos cofres: {PASSWORD}");
    if let Some(f) = &fixtures {
        println!("fixtures para o Android: {}", f.display());
    }

    for (label, attachments) in SIZES {
        let path = dir.join(format!("vault-{label}.tkeys"));

        let t = Instant::now();
        let raw = build_vault(attachments);
        let generated = t.elapsed();
        let file_bytes = write_vault(&path, &raw);
        println!("\n[~{label} MB] montado em {:.1} s ({attachments} anexos de {} MiB)", generated.as_secs_f64(), ATTACHMENT_BYTES / (1024 * 1024));
        assert!(file_bytes as f64 >= label as f64 * 1_000_000.0 * 0.85, "arquivo menor que o alvo: {file_bytes} bytes para ~{label} MB");
        drop(raw);

        let open = measure_open(&path);
        let save = measure_save(&path, &open.raw, &open.session);
        print_row(label, attachments, file_bytes, &open, &save);

        if let Some(f) = &fixtures {
            let copy = f.join(format!("vault-{label}.tkeys"));
            std::fs::copy(&path, &copy).expect("copia a fixture");
            println!("  fixture: {}", copy.display());
        }

        drop(open);
        cleanup(&path);
    }

    println!("\n(medição terminada; temporários apagados)");
}
