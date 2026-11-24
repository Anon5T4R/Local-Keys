//! Local Keys — gerenciador de senhas 100% local e offline da suíte Local (Rust + egui forkado).
//!
//! - **Cofre `.tkeys`**: cifra e formato em `crypto.rs` (Argon2id + XChaCha20-Poly1305, primitivas prontas,
//!   nada feito em casa), TOTP em `totp.rs`, o JSON do cofre e a gravação atômica em `vault.rs`.
//! - **Nada pesado na thread da UI**: Argon2id, PBKDF2 do Bitwarden, `.kdbx` e leitura de arquivo rodam num job
//!   (`jobs.rs`), um por vez; o resultado volta por canal e a thread acorda a UI com `request_repaint()`.
//! - **Desbloqueio rápido** (Windows, opt-in): a chave derivada fica no cofre do SO (keyring).
//! - **Bandeja** (Windows): o X esconde; "Sair" fecha de verdade. Os handlers vivem numa thread própria porque a
//!   janela oculta congela o `update` (`winctl.rs`).
//! - **Importa** KeePass `.kdbx`, Bitwarden (cifrado ou não) e CSV; **exporta** JSON/CSV em claro com aviso.
//!   Gerador de senha/passphrase com medidor zxcvbn, anexos, pastas, favoritos ★, lixeira.
//! - **Casca da suíte** em `shell.rs` (tema, Configurações, paleta), idiomas em `i18n.rs`, preferências em
//!   `prefs.rs`, cores em `theme.rs`.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod clipboard;
mod i18n;
mod jobs;
mod pickers;
mod prefs;
mod qr;
// O modelo de seleção da lista (unidade 5, peça 2): a tela usa o estado e a barra em `selection_ui.rs`.
mod selection;
mod selection_ui;
mod shell;
mod theme;
#[cfg(any(windows, test))]
mod tray;

#[cfg(windows)]
mod winctl;

#[cfg(test)]
mod ui_tests;

use localkeys_core::{
    bwdecrypt, card, change_kind, cpf, crypto, export, filters, generator, import, kdbx, merge, merge_other, otpauth, totp, vault, vault_export,
};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Receiver;

use craft_icons::Icon;
use eframe::egui;
use i18n::{tr, tr_fmt, tr_n, trc};
use jobs::{AttachmentRead, Created, Job, JobError, JobResult, Renewed, Unlocked};
use prefs::{Prefs, config_dir};
use zeroize::Zeroizing;

#[cfg(windows)]
use tray::TrayCmd;
#[cfg(windows)]
use tray_icon::menu::{Menu, MenuItem};
#[cfg(windows)]
use tray_icon::{TrayIcon, TrayIconBuilder};

const APP_ID: &str = "local-keys";
#[cfg(windows)]
const KEYRING_SERVICE: &str = "LocalKeys";

/// Título da janela: o `winctl` acha a janela por ele para escondê-la e trazê-la de volta pela bandeja.
const WINDOW_TITLE: &str = "Local Keys";

/// Alvo dos anexos escolhidos no formulário de item: não é um id de item (os ids são UUIDs).
const FORM_ATTACH: &str = "\u{0}form";
const CURRENT_VERSION: &str = env!("CARGO_PKG_VERSION");

/// Carrega o ícone da janela (title bar + taskbar) do `icon.ico` embutido.
fn load_window_icon() -> egui::IconData {
    let icon_bytes = include_bytes!("../icon.ico");
    match image::load_from_memory(icon_bytes) {
        Ok(img) => {
            let rgba = img.to_rgba8();
            let (w, h) = rgba.dimensions();
            egui::IconData { rgba: rgba.into_raw(), width: w, height: h }
        }
        Err(e) => {
            eprintln!("ícone: falha ao decodificar icon.ico: {e}");
            egui::IconData::default()
        }
    }
}

fn main() -> eframe::Result<()> {
    // Windows: o helper de update é uma CÓPIA deste programa iniciada com
    // `--finish-update` (craft_update). Roda ANTES de tudo — inclusive da
    // guarda de instância única abaixo, que o helper não pode bloquear (ele
    // espera o app sair e instala por conta própria).
    #[cfg(windows)]
    if let Some(result) = craft_update::self_update::finish(&craft_update::suite::LOCALKEYS, &std::env::args_os().skip(1).collect::<Vec<_>>()) {
        std::process::exit(i32::from(result.is_err()));
    }

    // ── single-instance (só Windows — é lá que o X esconde pra bandeja) ──
    // Mesmo desenho do lab-clip (onda 9): lock de ARQUIVO (`File::try_lock` —
    // o kernel libera sozinho em crash) + flag "mostra-te" consumida pela
    // thread da bandeja. Sem isso, abrir pelo ícone de novo cria uma segunda
    // instância (e segunda bandeja) em vez de acordar a que já existe.
    #[cfg(windows)]
    {
        let dir = config_dir(APP_ID);
        let _ = std::fs::create_dir_all(&dir);
        let lock = std::fs::OpenOptions::new().create(true).write(true).truncate(false).open(dir.join("instance.lock"));
        let segunda = match &lock {
            Ok(f) => f.try_lock().is_err(),
            // Sem conseguir abrir o lock: segue SEM guarda — melhor rodar
            // sem single-instance do que não rodar.
            Err(_) => false,
        };
        if segunda {
            let _ = std::fs::write(dir.join("show.flag"), b"1");
            return Ok(()); // a bandeja do primeiro atende a flag e mostra
        }
        if let Ok(f) = lock {
            std::mem::forget(f); // lock vive até o processo morrer
        }
    }

    let prefs = Prefs::load_from(&config_dir(APP_ID).join("config.json"));
    i18n::set_language(&prefs.language);
    // "Abrir com" / duplo clique num `.tkeys`: o Explorer e o `%F` do .desktop mandam o caminho no argv.
    let initial_vault = vault_from_args(std::env::args_os().skip(1));
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title(WINDOW_TITLE)
            .with_inner_size([480.0, 660.0])
            .with_min_inner_size([340.0, 640.0])
            .with_icon(load_window_icon()),
        ..Default::default()
    };
    eframe::run_native(
        APP_ID,
        options,
        Box::new(move |cc| {
            // O carregador de SVG dos ícones da suíte (craft-icons).
            egui_extras::install_image_loaders(&cc.egui_ctx);
            theme::apply(&cc.egui_ctx, prefs.mode());
            // Linux: a escolha clara/escura do desktop, que o winit não informa.
            craft_desktop_theme::follow(&cc.egui_ctx);
            let mut app = KeysApp::new(prefs, initial_vault);
            // Check de update no arranque: achou release nova, o prompt do
            // updater abre sozinho (craft_update::dialog).
            app.updater.check_at_start(&cc.egui_ctx);
            Ok(Box::new(app))
        }),
    )
}

/// O cofre pedido na linha de comando: o primeiro argumento com extensão `.tkeys` (sem diferenciar
/// maiúsculas). Flags como `--finish-update` e arquivos de outro tipo não contam; um caminho que
/// não é UTF-8 também não (o campo de caminho da tela de desbloqueio é `String`).
fn vault_from_args<I>(args: I) -> Option<String>
where
    I: IntoIterator,
    I::Item: Into<std::ffi::OsString>,
{
    args.into_iter().map(Into::into).find_map(|arg: std::ffi::OsString| {
        let path = std::path::Path::new(&arg);
        let is_vault = path.extension().and_then(|e| e.to_str()).is_some_and(|e| e.eq_ignore_ascii_case("tkeys"));
        if is_vault { arg.into_string().ok() } else { None }
    })
}

/// Abre a página da release no navegador — ação "Download" do prompt do
/// updater, para instalações que não se auto-atualizam (zip solto, dev build).
fn open_url(url: &str) {
    #[cfg(windows)]
    {
        use windows::Win32::UI::Shell::ShellExecuteW;
        use windows::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;
        use windows::core::{HSTRING, PCWSTR};
        let verb = HSTRING::from("open");
        let file = HSTRING::from(url);
        // > 32 = lançou (mesma convenção do antigo autoupdate com "runas").
        let res = unsafe { ShellExecuteW(None, PCWSTR(verb.as_ptr()), PCWSTR(file.as_ptr()), PCWSTR::null(), PCWSTR::null(), SW_SHOWNORMAL) };
        if res.0 as usize <= 32 {
            eprintln!("update: falha ao abrir {url} (código {})", res.0 as usize);
        }
    }
    #[cfg(not(windows))]
    {
        if std::process::Command::new("xdg-open").arg(url).spawn().is_err() {
            eprintln!("update: falha ao abrir {url}");
        }
    }
}

/// Formato escolhido na janela de importação (a senha só é pedida nos dois
/// primeiros; CSV é texto claro).
#[derive(Clone, Copy, PartialEq, Eq)]
enum ImportKind {
    Kdbx,
    Bitwarden,
    Csv,
}

/// Linux: os caminhos digitados no campo vêm separados por `;` — separa,
/// tira os espaços das pontas e descarta os vazios. (No Windows sobrevive
/// como fallback do campo — que lá não é renderizado.)
fn split_attachment_paths(text: &str) -> Vec<String> {
    text.split(';').map(str::trim).filter(|s| !s.is_empty()).map(str::to_string).collect()
}

/// Formato do export em claro — o oficial só tem estes dois (`export.ts`).
#[derive(Clone, Copy, PartialEq, Eq)]
enum ExportKind {
    Json,
    Csv,
}

/// O que o "+" oferece antes de abrir o formulário (o `NewItemSheet` do Android). **Item livre** é uma
/// nota que já abre com um campo personalizado vazio — sem formato pronto.
#[derive(Clone, Copy, PartialEq, Eq)]
enum NewKind {
    Login,
    Card,
    Identity,
    Note,
    FreeItem,
}

impl NewKind {
    /// O tipo que vai no cofre (item livre é uma nota).
    fn kind(self) -> vault::ItemKind {
        match self {
            Self::Login => vault::ItemKind::Login,
            Self::Card => vault::ItemKind::Card,
            Self::Identity => vault::ItemKind::Identity,
            Self::Note | Self::FreeItem => vault::ItemKind::Note,
        }
    }

    fn icon(self) -> &'static str {
        if self == Self::FreeItem { "📄" } else { kind_icon(self.kind()) }
    }

    fn label(self) -> &'static str {
        if self == Self::FreeItem { tr("Free item") } else { kind_label(self.kind()) }
    }
}

/// Ícone de cada tipo (lista, seletor e crachá do form usam o mesmo) — os glifos existem nas fontes
/// embutidas (Noto Emoji), o guarda de glyphs dos testes confirma.
fn kind_icon(kind: vault::ItemKind) -> &'static str {
    match kind {
        vault::ItemKind::Login => "🔑",
        vault::ItemKind::Note => "📝",
        vault::ItemKind::Card => "💳",
        vault::ItemKind::Identity => "👤",
    }
}

/// Nome de cada tipo no idioma da interface.
fn kind_label(kind: vault::ItemKind) -> &'static str {
    match kind {
        vault::ItemKind::Login => tr("Login"),
        vault::ItemKind::Note => tr("Note"),
        vault::ItemKind::Card => tr("Card"),
        vault::ItemKind::Identity => tr("Identity"),
    }
}

/// O rótulo de um tipo no popup Filtrar e no chip (plural, como o Android; "All" é "Todos" no
/// contexto `filter`, que não é o "Todas" das pastas).
fn filter_kind_label(kind: filters::KindFilter) -> &'static str {
    match kind {
        filters::KindFilter::All => trc("filter", "All"),
        filters::KindFilter::Logins => tr("Logins"),
        filters::KindFilter::Notes => tr("Notes"),
        filters::KindFilter::Cards => tr("Cards"),
        filters::KindFilter::Identities => tr("Identities"),
    }
}

/// Os rótulos dos campos que a mudança de tipo gera (a regra do núcleo): as MESMAS chaves que o formulário
/// já mostra para esses campos, para o campo extra nascer com o nome que a pessoa conhece — com uma exceção:
/// o número do cartão vira "Card number", embora o formulário o rotule "Number"; sozinho, fora do cartão, o
/// rótulo curto não diz o que é.
fn field_labels() -> change_kind::FieldLabels {
    change_kind::FieldLabels {
        username: tr("Username").into(),
        password: tr("Password").into(),
        website: tr("URL").into(),
        totp: tr("TOTP").into(),
        cardholder: tr("Cardholder").into(),
        brand: tr("Brand").into(),
        card_number: tr("Card number").into(),
        expiry: tr("Expiry (MM/YY)").into(),
        security_code: tr("Security code").into(),
        first_name: tr("First name").into(),
        last_name: tr("Last name").into(),
        email: tr("E-mail").into(),
        phone: tr("Phone").into(),
        address: tr("Address").into(),
    }
}

/// O motivo do export da SELEÇÃO recusado, no idioma da interface. O `Display` do núcleo é diagnóstico em inglês e
/// não chega à tela; o id cru do item não aparece.
fn export_vault_error_text(error: &vault_export::ExportVaultError) -> String {
    match error {
        vault_export::ExportVaultError::NoItems => tr("Select at least one item to export."),
        vault_export::ExportVaultError::NotAnObject | vault_export::ExportVaultError::MissingId => {
            tr("The selection has an item that is not valid; nothing was exported.")
        }
        vault_export::ExportVaultError::UnknownId { .. } => tr("One of the selected items no longer exists in the vault; nothing was exported."),
        vault_export::ExportVaultError::MalformedVault => tr("The vault has no valid item list; nothing was exported."),
        vault_export::ExportVaultError::InvalidJson { .. } | vault_export::ExportVaultError::Serialize(_) => {
            tr("The export could not be completed; nothing was exported.")
        }
    }
    .to_string()
}

/// O motivo da junção de outro cofre recusada, no idioma da interface. O `Display` do núcleo é diagnóstico em
/// inglês e não chega à tela; o id cru do item não aparece.
fn merge_other_error_text(error: &merge_other::MergeOtherError) -> String {
    match error {
        merge_other::MergeOtherError::MalformedVault { .. } => tr("one of the vaults has no valid item list; nothing was merged"),
        merge_other::MergeOtherError::ItemNotObject { .. } => tr("an item in one of the vaults is not valid; nothing was merged"),
        merge_other::MergeOtherError::ItemWithoutId { .. } => tr("an item in one of the vaults has no id; nothing was merged"),
        merge_other::MergeOtherError::FolderNotObject { .. } => tr("a folder in one of the vaults is not valid; nothing was merged"),
        merge_other::MergeOtherError::FolderWithoutId { .. } => tr("a folder in one of the vaults has no id; nothing was merged"),
        merge_other::MergeOtherError::InvalidJson { .. } | merge_other::MergeOtherError::Serialize(_) => {
            tr("the vaults could not be merged; nothing was merged")
        }
    }
    .to_string()
}

/// O motivo da conversão recusada no idioma da interface. O `Display` do núcleo é diagnóstico em inglês e
/// não chega à tela; o nome cru do campo/objeto também não (o usuário vê só a explicação).
fn change_kind_error_text(error: &change_kind::ChangeKindError) -> String {
    match error {
        change_kind::ChangeKindError::NotAnObject => tr("This item cannot change type: it is not a valid vault item."),
        change_kind::ChangeKindError::UnknownKind => tr("This item cannot change type: its current type is unknown."),
        change_kind::ChangeKindError::UnknownField { .. } => tr("This item cannot change type: it has a field this app cannot convert."),
        change_kind::ChangeKindError::UnexpectedValue { .. } => tr("This item cannot change type: one of its fields has an unexpected value."),
        change_kind::ChangeKindError::MalformedTypedObject { .. } => tr("This item cannot change type: one of its parts is not a valid object."),
        change_kind::ChangeKindError::MalformedCustomFields => tr("This item cannot change type: its custom fields are not a valid list."),
        change_kind::ChangeKindError::MalformedVault => tr("This item cannot change type: the vault has no valid item list."),
        change_kind::ChangeKindError::InvalidJson(_) => tr("This item cannot change type: its data is not valid JSON."),
        change_kind::ChangeKindError::Serialize(_) => tr("This item cannot change type: its data could not be written."),
    }
    .to_string()
}

/// O texto do aviso de parâmetros fora do padrão (por exemplo "8 dígitos, 60 s, SHA-256").
fn unsupported_text(u: &otpauth::Unsupported) -> String {
    let mut parts: Vec<String> = Vec::new();
    if let Some(digits) = u.digits {
        parts.push(tr_fmt("{n} digits", &[("n", &digits.to_string())]));
    }
    if let Some(period) = u.period {
        parts.push(format!("{} {}", period, tr("s")));
    }
    if let Some(algorithm) = &u.algorithm {
        parts.push(algorithm.clone());
    }
    tr_fmt(
        "key saved, but the QR asks for {params}. Local Keys makes 6-digit codes every 30 s (SHA-1), so the codes may not match.",
        &[("params", &parts.join(", "))],
    )
}

/// O motivo do erro de TOTP no idioma da interface (`None` = campo vazio, sem texto).
fn totp_error_text(reason: otpauth::Reason) -> Option<&'static str> {
    match reason {
        otpauth::Reason::NotTotp => Some(tr("this is not a TOTP link (HOTP is not supported)")),
        otpauth::Reason::NoSecret => Some(tr("the link has no secret key")),
        otpauth::Reason::BadKey => Some(tr("invalid key: it must be base32 (letters A-Z and digits 2-7)")),
        otpauth::Reason::Empty => None,
    }
}

/// O pedaço de `text` a partir do `otpauth://` (sem diferenciar maiúsculas), se houver — o link colado
/// pode entrar no fim de uma chave que já estava no campo.
fn otpauth_tail(text: &str) -> Option<&str> {
    let at = text.to_lowercase().find("otpauth://")?;
    Some(&text[at..])
}

/// Reposiciona o cursor de um `TextEdit` formatado-enquanto-digita: quem digitava no FIM do texto cru
/// (o caso comum) continua no fim do texto formatado; quem edita no meio fica como o egui deixar
/// (clampado). Sem isso, o formato que insere separadores deixa o cursor atrás deles e o próximo
/// dígito entra no meio.
fn cursor_do_fim(ctx: &egui::Context, id: egui::Id, cru_len: usize, novo_len: usize) {
    if let Some(mut st) = egui::text_edit::TextEditState::load(ctx, id)
        && let Some(range) = st.cursor.char_range()
        && range.primary.index.0 >= cru_len
    {
        st.cursor.set_char_range(Some(egui::text_selection::CCursorRange::one(egui::epaint::text::cursor::CCursor::new(novo_len))));
        st.store(ctx, id);
    }
}

/// Lê o `.tkeys` do caminho e confere que abre com a chave da sessão e parseia como cofre — o "conferir
/// que a cópia abre" do pedido 022 fase D.
fn copia_abre(session: &crypto::SessionKey, path: &std::path::Path) -> bool {
    std::fs::read(path).is_ok_and(|file| {
        crypto::open_with_key(&session.key_bytes(), &file).is_ok_and(|(plain, _)| serde_json::from_slice::<serde_json::Value>(&plain).is_ok())
    })
}

/// O JSON decifrado tem cara de cofre: objeto, e `items`/`folders` (quando presentes) são arrays.
/// Um JSON qualquer que decifrasse viraria "cofre vazio" no merge — e a outra cópia seria regravada.
fn shape_de_cofre(v: &serde_json::Value) -> bool {
    v.is_object() && v.get("items").is_none_or(serde_json::Value::is_array) && v.get("folders").is_none_or(serde_json::Value::is_array)
}

/// Os dois caminhos apontam para o MESMO arquivo? Compara canonicalizado quando os dois existem; senão, o texto
/// normalizado (barras do SO e, no Windows, sem diferenciar maiúsculas). Usado para recusar exportar por cima do
/// cofre aberto e para recusar juntar o cofre com ele mesmo.
fn same_path(first: &str, second: &str) -> bool {
    let first = std::path::Path::new(first.trim());
    let second = std::path::Path::new(second.trim());
    if first.as_os_str().is_empty() || second.as_os_str().is_empty() {
        return false;
    }
    match (first.canonicalize(), second.canonicalize()) {
        (Ok(first), Ok(second)) => first == second,
        _ => {
            let normalize = |path: &std::path::Path| {
                let text = path.to_string_lossy().replace('/', std::path::MAIN_SEPARATOR_STR);
                #[cfg(windows)]
                let text = text.to_lowercase();
                text
            };
            normalize(first) == normalize(second)
        }
    }
}

/// Um campo personalizado no formulário: `id` vazio = campo novo (o `set_custom_fields` gera o id ao
/// gravar). `revealed` é estado só da UI (o 👁 do campo Oculto).
struct FormCustomField {
    id: String,
    name: String,
    value: String,
    hidden: bool,
    revealed: bool,
}

/// A sincronização calculada, esperando a confirmação (o resumo aparece ANTES de gravar).
#[derive(Clone)]
struct SyncPlan {
    /// O caminho da outra cópia (para gravar nela também).
    other_path: String,
    /// O cofre juntado (`merge::merge` do core).
    merged: serde_json::Value,
    /// O resumo que a tela mostra.
    summary: merge::MergeSummary,
}

/// A junção de um cofre DIFERENTE calculada, esperando a confirmação (o resumo aparece ANTES de gravar).
#[derive(Clone)]
struct MergePlan {
    /// O caminho do outro cofre (só leitura: nunca é gravado).
    other_path: String,
    /// O cofre juntado (`merge_other::merge_other` do core).
    merged: serde_json::Value,
    /// O resumo que a tela mostra.
    summary: merge_other::MergeSummary,
}

impl ExportKind {
    fn ext(self) -> &'static str {
        match self {
            ExportKind::Json => "json",
            ExportKind::Csv => "csv",
        }
    }
}

/// MIME mínimo por extensão (o anexo vai pro JSON do vault; sem adivinhar
/// demais, o resto cai em `application/octet-stream`).
fn mime_by_ext(path: &str) -> &'static str {
    let ext = std::path::Path::new(path).extension().and_then(|e| e.to_str()).unwrap_or("").to_ascii_lowercase();
    match ext.as_str() {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "pdf" => "application/pdf",
        "txt" => "text/plain",
        "zip" => "application/zip",
        "json" => "application/json",
        _ => "application/octet-stream",
    }
}

/// Tamanho legível pra lista de anexos (o JSON guarda bytes crus).
fn human_size(bytes: usize) -> String {
    const KB: f64 = 1024.0;
    const MB: f64 = 1024.0 * 1024.0;
    let b = bytes as f64;
    if b < KB {
        format!("{bytes} B")
    } else if b < MB {
        format!("{:.1} KB", b / KB)
    } else {
        format!("{:.1} MB", b / MB)
    }
}

/// Nome do item pelo id — só pro feedback ("fulano — restaurado"). Vazio se
/// o id não existir mais.
fn item_name(raw: &serde_json::Value, id: &str) -> String {
    raw.get("items")
        .and_then(serde_json::Value::as_array)
        .and_then(|arr| arr.iter().find(|it| it.get("id").and_then(serde_json::Value::as_str) == Some(id)))
        .and_then(|it| it.get("name"))
        .and_then(serde_json::Value::as_str)
        .unwrap_or("")
        .to_string()
}

/// O `kind` CRU do item no cofre, sem o mapeamento para nota do `item_full`: `None` quando falta ou é
/// desconhecido — nesse caso o formulário não oferece o seletor de tipo (o tipo fica fixo, como antes).
fn item_wire_kind(raw: &serde_json::Value, id: &str) -> Option<vault::ItemKind> {
    raw.get("items")
        .and_then(serde_json::Value::as_array)
        .and_then(|arr| arr.iter().find(|it| it.get("id").and_then(serde_json::Value::as_str) == Some(id)))
        .and_then(|it| it.get("kind"))
        .and_then(serde_json::Value::as_str)
        .and_then(vault::ItemKind::from_wire)
}

/// Ids (String) de TODOS os itens do vault — a lista "antes" do add.
fn item_ids(raw: &serde_json::Value) -> Vec<String> {
    raw.get("items")
        .and_then(serde_json::Value::as_array)
        .map(|arr| arr.iter().filter_map(|it| it.get("id").and_then(serde_json::Value::as_str).map(str::to_string)).collect())
        .unwrap_or_default()
}

/// O id do item ACABADO de criar: o primeiro id que não existia antes
/// (`add_login` gera o id lá dentro — o diff é como a UI o encontra).
fn novo_item_id(raw: &serde_json::Value, antes: &[String]) -> Option<String> {
    item_ids(raw).into_iter().find(|id| !antes.contains(id))
}

/// Rótulo da escala zxcvbn (0–4), no idioma da interface.
fn strength_label(score: u8) -> &'static str {
    match score {
        0 => tr("terrible"),
        1 => tr("weak"),
        2 => tr("fair"),
        3 => tr("strong"),
        _ => tr("excellent"),
    }
}

/// Vermelho → verde, um degrau por score.
fn strength_color(score: u8) -> egui::Color32 {
    match score {
        0 => egui::Color32::from_rgb(220, 60, 60),
        1 => egui::Color32::from_rgb(230, 130, 40),
        2 => egui::Color32::from_rgb(215, 190, 60),
        3 => egui::Color32::from_rgb(120, 190, 80),
        _ => egui::Color32::from_rgb(60, 170, 90),
    }
}

// ── kit de UI (onda 23) ────────────────────────────────────────────────────
// Versão SIMPLIFICADA do `widgets.rs` do local-pdf: mesmo desenho (modal com
// cartão, campo alto, pill, ghost, toast no rodapé), sem o sistema de tokens/
// motion/bidi de lá. As cores saem do tema aplicado (`theme::apply`), então o
// mesmo desenho funciona nos dois temas.

/// Recuo interno de um campo — molde do `FIELD_MARGIN` do local-pdf.
const FIELD_MARGIN: egui::Margin = egui::Margin::symmetric(10, 6);
/// Altura externa mínima de um campo de uma linha.
const FIELD_MIN_HEIGHT: f32 = 30.0;
/// Quanto tempo o toast fica na tela (o mesmo do local-pdf).
const TOAST_LIFETIME: std::time::Duration = std::time::Duration::from_millis(3500);

/// Cores derivadas do tema vigente (nada fixo: claro e escuro).
#[derive(Clone, Copy)]
struct Skin {
    /// Fundo do cartão do diálogo (o "sunken" da paleta).
    card: egui::Color32,
    /// Fundo dos campos DENTRO do diálogo (o "base", o inverso do cartão).
    field: egui::Color32,
    accent: egui::Color32,
    /// Texto sobre `accent` (pill primário).
    on_accent: egui::Color32,
    text: egui::Color32,
    muted: egui::Color32,
    border: egui::Color32,
    /// Lavagem de hover (ghost buttons).
    hover: egui::Color32,
    /// Cartão invertido do toast/progresso (claro no tema escuro e vice-versa).
    inverted_fill: egui::Color32,
    inverted_text: egui::Color32,
    /// Aviso em texto (o tipo do formulário mudou).
    warn: egui::Color32,
    /// Erro em texto (gravação recusada).
    danger: egui::Color32,
}

impl Skin {
    fn of(ui: &egui::Ui) -> Self {
        Self::from_visuals(ui.visuals())
    }

    fn from_visuals(v: &egui::Visuals) -> Self {
        let text = v.override_text_color.unwrap_or(v.widgets.noninteractive.fg_stroke.color);
        // O accent cheio (o da seleção é translúcido e sumia no fundo: chip "Todas", ★, filtros e o pill primário).
        let accent = v.hyperlink_color;
        Self {
            card: v.extreme_bg_color,
            field: v.panel_fill,
            accent,
            on_accent: if luminance(accent) > 150.0 { egui::Color32::BLACK } else { egui::Color32::WHITE },
            text,
            muted: text.gamma_multiply(0.6),
            border: if v.dark_mode { egui::Color32::from_white_alpha(30) } else { egui::Color32::from_black_alpha(30) },
            hover: if v.dark_mode { egui::Color32::from_white_alpha(18) } else { egui::Color32::from_black_alpha(14) },
            inverted_fill: if v.dark_mode { egui::Color32::from_rgb(0xEC, 0xEC, 0xEF) } else { egui::Color32::from_rgb(0x2A, 0x2A, 0x2F) },
            inverted_text: if v.dark_mode { egui::Color32::from_rgb(0x22, 0x22, 0x26) } else { egui::Color32::WHITE },
            warn: v.warn_fg_color,
            danger: v.error_fg_color,
        }
    }
}

/// Luminância perceptual (0–255) — decide texto claro ou escuro sobre o accent.
fn luminance(c: egui::Color32) -> f32 {
    0.299 * f32::from(c.r()) + 0.587 * f32::from(c.g()) + 0.114 * f32::from(c.b())
}

/// Campo de uma linha com altura de clique decente.
fn line(text: &mut dyn egui::TextBuffer) -> egui::TextEdit<'_> {
    egui::TextEdit::singleline(text).margin(FIELD_MARGIN).min_size(egui::vec2(0.0, FIELD_MIN_HEIGHT))
}

/// Campo multilinha (notas) com o mesmo recuo horizontal.
fn block(text: &mut dyn egui::TextBuffer) -> egui::TextEdit<'_> {
    egui::TextEdit::multiline(text).margin(FIELD_MARGIN)
}

/// Moldura do diálogo: cartão com canto de 10, borda sutil e a sombra do tema.
fn modal_frame(ctx: &egui::Context) -> egui::Frame {
    let style = ctx.style_of(ctx.theme());
    let skin = Skin::from_visuals(&style.visuals);
    egui::Frame::NONE
        .fill(skin.card)
        .stroke(egui::Stroke::new(1.0_f32, skin.border))
        .corner_radius(egui::CornerRadius::same(10))
        .inner_margin(egui::Margin::symmetric(20, 16))
        .shadow(style.visuals.window_shadow)
}

/// Largura de um diálogo: a pedida, mas nunca mais larga que a janela menos a moldura e uma margem de cada lado
/// (na largura mínima de 340 pt o diálogo não encosta nas bordas).
fn dialog_width(ui: &mut egui::Ui, wanted: f32) {
    let room = ui.ctx().content_rect().width() - 80.0;
    ui.set_width(wanted.min(room).max(160.0));
}

/// Visuals de dentro do diálogo: campos com contorno e no tom "base" (o cartão
/// é o "sunken" — sem isso o campo some no fundo do modal).
fn begin_dialog(ui: &mut egui::Ui) {
    let skin = Skin::of(ui);
    let v = ui.visuals_mut();
    v.extreme_bg_color = skin.field;
    v.selection.stroke = egui::Stroke::new(1.5_f32, skin.accent);
    let w = &mut v.widgets;
    w.inactive.bg_stroke = egui::Stroke::new(1.0_f32, skin.border);
    w.inactive.weak_bg_fill = skin.field;
    w.hovered.bg_stroke = egui::Stroke::new(1.0_f32, skin.muted);
    for state in [&mut w.inactive, &mut w.hovered, &mut w.active, &mut w.open] {
        state.corner_radius = egui::CornerRadius::same(4);
    }
    ui.spacing_mut().interact_size.y = FIELD_MIN_HEIGHT;
}

/// Botão pill: `primary` preenche com o accent, o secundário fica no contorno.
/// Desenhado à mão porque o `egui::Button` não tem raio/altura própria.
fn pill(ui: &mut egui::Ui, label: &str, primary: bool) -> egui::Response {
    let skin = Skin::of(ui);
    if primary {
        pill_colored(ui, label, skin.accent, skin.on_accent, false)
    } else {
        pill_colored(ui, label, egui::Color32::TRANSPARENT, skin.text, true)
    }
}

/// Pill de ação destrutiva (vermelho fixo: legível nos dois temas).
fn pill_danger(ui: &mut egui::Ui, label: &str) -> egui::Response {
    pill_colored(ui, label, egui::Color32::from_rgb(0xC0, 0x3A, 0x3A), egui::Color32::WHITE, false)
}

/// Pill com cores explícitas; `outline` desenha só a borda.
fn pill_colored(ui: &mut egui::Ui, label: &str, fill: egui::Color32, ink: egui::Color32, outline: bool) -> egui::Response {
    let skin = Skin::of(ui);
    let enabled = ui.is_enabled();
    let ink = if enabled { ink } else { skin.muted };
    let font = egui::TextStyle::Button.resolve(ui.style());
    let galley = ui.painter().layout_no_wrap(label.to_owned(), font, ink);
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(galley.size().x + 28.0, 28.0), egui::Sense::click());
    resp.widget_info(|| egui::WidgetInfo::labeled(egui::Role::Button, enabled, label));
    let hovered = resp.hovered() && enabled;
    let fill = if !enabled {
        fill.gamma_multiply(0.45)
    } else if hovered {
        fill.gamma_multiply(1.12)
    } else {
        fill.gamma_multiply(0.9)
    };
    let fill = if outline { if hovered { skin.hover } else { egui::Color32::TRANSPARENT } } else { fill };
    let stroke = if outline { egui::Stroke::new(1.0_f32, skin.border) } else { egui::Stroke::NONE };
    ui.painter().rect(rect, egui::CornerRadius::same(14), fill, stroke, egui::StrokeKind::Inside);
    let pos = rect.center() - galley.size() / 2.0;
    ui.painter().galley(pos, galley, ink);
    resp
}

/// Largura ocupada por um texto num estilo (para reservar espaço antes de desenhar).
fn text_width(ui: &egui::Ui, text: &str, style: egui::TextStyle) -> f32 {
    let font = style.resolve(ui.style());
    ui.painter().layout_no_wrap(text.to_owned(), font, egui::Color32::PLACEHOLDER).size().x
}

/// Uma linha de anexo: o nome à esquerda e, ao lado, o tamanho e os botões ([actions], que ocupam
/// [actions_width]). Enquanto o nome cabe ao lado deles, fica numa linha só; quando não cabe, o nome
/// quebra dentro do espaço que sobra — em vez de alargar o diálogo para fora da janela.
fn attachment_row(ui: &mut egui::Ui, name: &str, weak: bool, actions_width: f32, actions: impl FnOnce(&mut egui::Ui)) {
    ui.horizontal(|ui| {
        let wanted = text_width(ui, name, egui::TextStyle::Body) + 1.0;
        let width = wanted.min((ui.available_width() - actions_width).max(80.0));
        let text = if weak { egui::RichText::new(name).weak() } else { egui::RichText::new(name) };
        ui.allocate_ui_with_layout(egui::vec2(width, 0.0), egui::Layout::left_to_right(egui::Align::Center), |ui| {
            ui.add(egui::Label::new(text).wrap());
        });
        actions(ui);
    });
}

/// Ação de linha: transparente até o hover (o "ghost button" do local-pdf).
fn ghost(ui: &mut egui::Ui, label: &str) -> egui::Response {
    ghost_ink(ui, label, Skin::of(ui).muted)
}

/// [`ghost`] com tinta própria (o ★ favorito usa o accent quando ativo).
fn ghost_ink(ui: &mut egui::Ui, label: &str, ink: egui::Color32) -> egui::Response {
    ghost_ink_named(ui, label, label, ink)
}

/// Como `ghost_ink`, mas com o nome acessível separado do texto visível (o chip "Cartões ×" se chama
/// "Remover filtro Cartões" para leitor de tela).
fn ghost_ink_named(ui: &mut egui::Ui, label: &str, accessible: &str, ink: egui::Color32) -> egui::Response {
    let skin = Skin::of(ui);
    let font = egui::TextStyle::Button.resolve(ui.style());
    let galley = ui.painter().layout_no_wrap(label.to_owned(), font, ink);
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(galley.size().x + 14.0, 24.0), egui::Sense::click());
    resp.widget_info(|| egui::WidgetInfo::labeled(egui::Role::Button, ui.is_enabled(), accessible));
    if resp.hovered() {
        ui.painter().rect_filled(rect, egui::CornerRadius::same(6), skin.hover);
    }
    let pos = rect.center() - galley.size() / 2.0;
    ui.painter().galley(pos, galley, ink);
    resp
}

/// `dd/mm/aaaa` a partir de epoch-ms — o app não tem crate de data, e o único
/// uso é mostrar quando o item foi pra lixeira.
fn date_from_ms(ms: i64) -> String {
    let days = ms.div_euclid(86_400_000);
    // Algoritmo civil-from-days (Hinnant), o mesmo que o chrono usa.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{d:02}/{m:02}/{y:04}")
}

/// Export cifrado do Bitwarden traz `passwordProtected` (ou `encrypted`) true.
/// Checamos ANTES de decifrar pra não chamar o decrypt num JSON claro.
fn bitwarden_encrypted(text: &str) -> bool {
    serde_json::from_str::<serde_json::Value>(text)
        .map(|v| {
            v.get("passwordProtected").and_then(serde_json::Value::as_bool) == Some(true)
                || v.get("encrypted").and_then(serde_json::Value::as_bool) == Some(true)
        })
        .unwrap_or(false)
}

/// Último cofre aberto (pra pré-preencher o caminho e alimentar o quick mode).
fn state_path() -> std::path::PathBuf {
    config_dir(APP_ID).join("state.json")
}

fn remember_last_path(path: &str) {
    let p = state_path();
    if let Some(dir) = p.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    let _ = std::fs::write(&p, serde_json::json!({ "lastPath": path }).to_string());
}

fn last_path() -> Option<String> {
    let v: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(state_path()).ok()?).ok()?;
    v.get("lastPath")?.as_str().map(str::to_string)
}

/// Invalida a entrada do desbloqueio rápido no keyring: a chave guardada foi
/// derivada do salt ANTIGO (a troca de senha recifra com salt novo), então ela
/// não abre mais o cofre.
#[cfg(windows)]
fn invalidate_quick_key(path: &str) {
    if path.is_empty() {
        return;
    }
    if let Ok(entry) = keyring::Entry::new(KEYRING_SERVICE, path) {
        let _ = entry.delete_credential();
    }
}

#[cfg(not(windows))]
fn invalidate_quick_key(_path: &str) {}

// ── bandeja (Windows) ──────────────────────────────────────────────────────

/// Sobe a bandeja + a thread que atende os comandos dela. Devolve o `TrayIcon`
/// (que o app segura vivo) e a flag de quit lida no `update`.
///
/// A thread é obrigatória: com a janela oculta o eframe congela (sem WM_PAINT
/// não há frame), então os handlers do tray-icon não podem depender do
/// `update` — mostram/escondem via `winctl` (SO direto). Encerrar é sempre
/// "mostrar + flag de quit": o update, com a janela visível, faz o
/// `ViewportCommand::Close` — caminho limpo do winit 0.30.
#[cfg(windows)]
fn spawn_tray() -> (Option<TrayIcon>, Arc<AtomicBool>) {
    let (tx, rx) = std::sync::mpsc::channel::<TrayCmd>();
    let quit = Arc::new(AtomicBool::new(false));

    let show = MenuItem::with_id("show", tr("Show/Hide"), true, None);
    let quit_item = MenuItem::with_id("quit", tr("Exit"), true, None);
    let menu = Menu::new();
    let _ = menu.append(&show);
    let _ = menu.append(&quit_item);
    let tray = tray::configure(TrayIconBuilder::new())
        .with_menu(Box::new(menu))
        .with_tooltip("Local Keys")
        .with_icon(tray_icon().expect("ícone rgba"))
        .build();

    let tray = match tray {
        Ok(t) => {
            {
                use tray_icon::{MouseButtonState, TrayIconEvent};
                let tx = tx.clone();
                tray_icon::TrayIconEvent::set_event_handler(Some(move |ev: TrayIconEvent| {
                    if let TrayIconEvent::Click { button, button_state, .. } = ev {
                        let released = button_state == MouseButtonState::Up;
                        if let Some(cmd) = tray::click_cmd(button.into(), released) {
                            let _ = tx.send(cmd);
                        }
                    }
                }));
            }
            {
                use tray_icon::menu::MenuEvent;
                let tx = tx.clone();
                MenuEvent::set_event_handler(Some(move |ev: MenuEvent| {
                    if let Some(cmd) = tray::menu_cmd(ev.id.0.as_str()) {
                        let _ = tx.send(cmd);
                    }
                }));
            }
            Some(t)
        }
        Err(e) => {
            // Sem bandeja o app segue abrindo normal (o X fecha de verdade).
            eprintln!("bandeja: não deu para criar: {e}");
            None
        }
    };

    let quit_flag = quit.clone();
    std::thread::Builder::new()
        .name("keys-tray".into())
        .spawn(move || {
            // Flag "mostra-te" da segunda instância (single-instance): a
            // thread da bandeja é quem a consome — o update não roda com a
            // janela oculta, então não pode depender dele.
            let show_flag = config_dir(APP_ID).join("show.flag");
            loop {
                match rx.recv_timeout(std::time::Duration::from_millis(200)) {
                    Ok(cmd) => match cmd {
                        TrayCmd::ShowHide => {
                            if winctl::is_visible() {
                                winctl::hide();
                            } else {
                                winctl::show();
                            }
                        }
                        TrayCmd::Quit => {
                            quit_flag.store(true, Ordering::Relaxed);
                            // Janela VISÍVEL primeiro — o update precisa rodar pra
                            // encerrar pelo caminho limpo (Close).
                            winctl::show();
                        }
                    },
                    Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => break,
                    Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
                }
                if show_flag.exists() {
                    let _ = std::fs::remove_file(&show_flag);
                    winctl::show();
                }
            }
        })
        .expect("spawn keys-tray");

    (tray, quit)
}

/// Ícone da bandeja: carregado do `icon.ico` embutido (o mesmo do exe/janela).
#[cfg(windows)]
fn tray_icon() -> Option<tray_icon::Icon> {
    let icon_bytes = include_bytes!("../icon.ico");
    let img = image::load_from_memory(icon_bytes).ok()?;
    let rgba = img.to_rgba8();
    let (w, h) = rgba.dimensions();
    tray_icon::Icon::from_rgba(rgba.into_raw(), w, h).ok()
}

struct KeysApp {
    prefs: Prefs,
    /// Seletor nativo async (src/pickers.rs): a resposta chega num frame
    /// posterior, como closure — cada fluxo tem a sua continuação.
    pickers: pickers::Pickers,
    // trancado
    path: String,
    master: String,
    confirm: String,
    err: Option<String>,
    quick_unlock: bool,
    quick_ativa: bool, // há chave guardada no cofre do SO pra este path
    // destrancado
    session: Option<crypto::SessionKey>,
    raw: Option<serde_json::Value>,
    items: Vec<vault::ItemView>,
    search: String,
    /// Filtros do popup ao lado da busca (tipo + "mostrar só"), combinados com E — a lógica pura mora
    /// no `filters.rs` do core, espelho do `VaultFilters.kt` do Android. Estado de sessão, como a busca.
    filters: filters::Filters,
    /// Pasta selecionada nos chips da toolbar (None = todas).
    folder_filter: Option<String>,
    /// Itens marcados na lista principal (Ctrl+clique alterna, Shift+clique faz o intervalo, Ctrl+A pega os
    /// visíveis). Trocar filtro/pasta/busca, trancar ou fechar zera.
    selection: selection::Selection,
    /// Âncora do Shift+clique: o último item marcado (por Ctrl+clique) na ordem visível.
    selection_anchor: Option<String>,
    /// Apagar a seleção esperando confirmação (quantos itens); `None` = sem confirmação na tela.
    batch_delete_ask: Option<usize>,
    // formulário de adição/edição
    form_open: bool,
    /// O tipo do item no formulário — escolhido no seletor do "+" (novo) ou o do próprio item (edição); é
    /// ele que decide os campos mostrados e o `edit_*` da gravação. A troca de tipo na edição passa por
    /// [`Self::form_target_kind`].
    form_kind: vault::ItemKind,
    /// Na edição de um item de tipo CONHECIDO, o tipo escolhido no seletor (começa no atual); a conversão
    /// acontece ao salvar. `None` = sem seletor: item novo (o tipo veio do "+") ou `kind` desconhecido no
    /// arquivo, que fica fixo como antes.
    form_target_kind: Option<vault::ItemKind>,
    /// Erro da última gravação do formulário (conversão de tipo recusada pelo núcleo ou falha do `persist`):
    /// aparece DENTRO do modal, que continua aberto — a tela de trás fica coberta por ele.
    form_err: Option<String>,
    /// Seletor de tipo do "+" (Login, Cartão, Identidade, Nota, Item livre) antes de abrir o form.
    new_kind_open: bool,
    /// Anexos escolhidos no formulário, gravados junto com o item ao salvar (o item novo ainda
    /// não tem id quando o arquivo é escolhido).
    form_attachments: Vec<AttachmentRead>,
    form_edit_id: Option<String>,
    add_name: String,
    add_user: String,
    add_pass: String,
    add_totp: String,
    /// Primeira URI do item (o form edita só ela, como o oficial).
    add_url: String,
    add_notes: String,
    /// Campos do cartão no form (número/validade/código normalizados ao vivo, como são gravados).
    add_cardholder: String,
    add_brand: String,
    add_number: String,
    add_exp: String,
    add_code: String,
    /// A bandeira atual foi preenchida pela DETECÇÃO do número (então o número pode trocá-la);
    /// digitada pela pessoa, fica (o `brandAuto` do Android).
    brand_auto: bool,
    /// 👁 do código de segurança do cartão (CVV oculto, como a senha).
    reveal_code: bool,
    /// Campos da identidade no form.
    add_first: String,
    add_last: String,
    add_email: String,
    add_phone: String,
    add_address: String,
    /// Campos personalizados do form (todos os tipos têm).
    form_custom: Vec<FormCustomField>,
    /// Pasta escolhida no form (None = sem pasta; `set_item_folder` aplica).
    add_folder_id: Option<String>,
    /// 👁 do campo de senha do form (volta a ocultar ao fechar).
    reveal_form: bool,
    /// Aviso de parâmetros fora do padrão (digits/period/algorithm ≠ 6/30 s/SHA-1) do último link/QR
    /// TOTP aplicado no form. Some quando o campo muda — a chave é gravada do mesmo jeito.
    totp_warn: Option<String>,
    /// 👁 da chave TOTP do form — a chave base32 é segredo de longo prazo, o campo nasce mascarado.
    reveal_totp: bool,
    /// Link `otpauth://` colado no campo, esperando o próximo quadro para virar chave: aplicar dentro
    /// do `changed` trocaria o buffer sob o TextEdit e quebraria o cursor (o paste seguinte entraria
    /// no meio da chave). Fora do widget, o cursor só clampa — comportamento normal.
    totp_pending: Option<String>,
    /// 👁 da senha do arquivo de import.
    reveal_import: bool,
    /// Exclusão esperando confirmação (um clique sem querer não apaga).
    delete_ask: Option<(String, String)>,
    // onda 23 — lixeira
    trash_open: bool,
    /// "Excluir de vez" esperando o segundo clique (o `confirm()` do oficial).
    purge_ask: Option<String>,
    // onda 23 — export em claro
    export_open: bool,
    export_kind: ExportKind,
    export_path: String,
    // unidade 6 — export da seleção para um cofre NOVO (nome = o do arquivo; senha pedida duas vezes)
    export_vault_open: bool,
    /// Caminho escolhido no "Salvar como" (o nome do cofre novo é o nome do arquivo).
    export_vault_path: String,
    export_vault_pass: String,
    export_vault_confirm: String,
    /// 👁 das senhas do cofre novo (volta a ocultar ao fechar/criar).
    export_vault_reveal: bool,
    /// Erro de validação do diálogo (senha vazia/diferente), mostrado DENTRO dele.
    export_vault_err: Option<String>,
    /// Parâmetros do Argon2id do cofre exportado: o padrão do app; os testes usam um leve.
    export_vault_kdf: crypto::KdfParams,
    /// Verificação e instalação de atualização (craft_update): check no
    /// arranque/pedido, prompt egui próprio e self-update em thread própria.
    updater: craft_update::updater::Updater,
    // onda 6 — importação
    import_open: bool,
    import_kind: ImportKind,
    import_path: String,
    import_password: String,
    /// `(importados, pulados card/identity)` — Some = janela de relatório.
    import_report: Option<(usize, usize)>,
    // onda 6 — gerador (defaults do oficial)
    gen_open: bool,
    gen_passphrase: bool,
    gen_pw: generator::PasswordOptions,
    gen_pp: generator::PassphraseOptions,
    gen_result: String,
    // onda 6 — troca de senha mestra
    change_open: bool,
    chg_old: String,
    chg_new: String,
    chg_confirm: String,
    // onda 6 — anexos (id do item com a janela aberta + caminho digitável)
    attach_item: Option<String>,
    attach_path: String,
    /// Anexos restantes do multi-pick: `(id do item, caminho)`. O mecanismo de
    /// jobs é UM por vez: o primeiro arquivo roda já, o resto espera aqui e
    /// é consumido a cada resultado que chega (`apply_job`).
    attach_queue: Vec<(String, String)>,
    // onda 24 — pastas (gerência e criação, ambos os modais usam `folder_new`)
    folders_open: bool,
    folder_new_open: bool,
    folder_new: String,
    /// Pasta em renomeação inline (`folders_modal`) + buffer do nome.
    folder_edit_id: Option<String>,
    folder_edit_name: String,
    /// Toast do rodapé: `(mensagem, quando apareceu)`. Um só canal de
    /// feedback — "copiado", "senha trocada", "exportado" caem todos aqui.
    toast: Option<(String, std::time::Instant)>,
    /// Canal do job pesado em andamento (`jobs.rs`). Um por vez: enquanto
    /// `job_label` é Some, os gatilhos de job ficam desabilitados.
    job_rx: Option<Receiver<JobResult>>,
    /// O que está rodando (chave i18n do spinner).
    job_label: Option<&'static str>,
    /// Viva enquanto o app viver: derruba a bandeja no drop.
    #[cfg(windows)]
    _tray: Option<TrayIcon>,
    /// "Sair" da bandeja: o update responde com `ViewportCommand::Close`
    /// (o único caminho que encerra de verdade).
    quit: Arc<AtomicBool>,
    /// Janela de Configurações (idioma, tema, Sobre) — a engrenagem e a paleta abrem (`shell.rs`).
    settings_open: bool,
    /// Sincronização com outra cópia (pedido 022, fase D): o plano calculado esperando o "Gravar" do
    /// modal de resumo — o resumo aparece ANTES de gravar qualquer coisa.
    sync_plan: Option<SyncPlan>,
    /// O modal de resumo da sincronização está aberto.
    sync_open: bool,
    // unidade 7 — juntar outro cofre (o outro é só leitura; o resultado é o cofre aberto + o que entrou)
    /// O diálogo da senha do outro cofre está aberto (a chave da sessão não abriu).
    merge_open: bool,
    /// Caminho do outro cofre escolhido (só leitura).
    merge_path: String,
    merge_password: String,
    /// 👁 da senha do outro cofre (volta a ocultar ao fechar).
    merge_reveal: bool,
    /// Erro do diálogo da senha (senha errada/vazia) — aparece DENTRO dele.
    merge_error: Option<String>,
    /// A junção calculada, esperando a confirmação do resumo.
    merge_plan: Option<MergePlan>,
    /// Paleta de comandos da suíte (lupa e Ctrl+K).
    palette: craft_palette::Palette,
    /// Onde o config.json é gravado (os testes apontam para uma pasta temporária).
    config_file: std::path::PathBuf,
    /// Quantas gravações deram certo (só nos testes: afirma que o lote grava UMA vez).
    #[cfg(test)]
    pub(crate) persist_count: usize,
}

impl KeysApp {
    /// O estado inicial, sem tocar no disco, no cofre do SO nem na bandeja (os testes de UI partem daqui).
    fn bare(prefs: Prefs) -> Self {
        Self {
            prefs,
            pickers: pickers::Pickers::default(),
            path: String::new(),
            master: String::new(),
            confirm: String::new(),
            err: None,
            quick_unlock: false,
            quick_ativa: false,
            session: None,
            raw: None,
            items: Vec::new(),
            search: String::new(),
            filters: filters::Filters::default(),
            folder_filter: None,
            selection: selection::Selection::new(),
            selection_anchor: None,
            batch_delete_ask: None,
            form_open: false,
            form_kind: vault::ItemKind::Login,
            form_target_kind: None,
            form_err: None,
            new_kind_open: false,
            form_edit_id: None,
            add_name: String::new(),
            add_user: String::new(),
            add_pass: String::new(),
            add_totp: String::new(),
            add_url: String::new(),
            add_notes: String::new(),
            add_cardholder: String::new(),
            add_brand: String::new(),
            add_number: String::new(),
            add_exp: String::new(),
            add_code: String::new(),
            brand_auto: true,
            reveal_code: false,
            add_first: String::new(),
            add_last: String::new(),
            add_email: String::new(),
            add_phone: String::new(),
            add_address: String::new(),
            form_custom: Vec::new(),
            add_folder_id: None,
            form_attachments: Vec::new(),
            reveal_form: false,
            totp_warn: None,
            reveal_totp: false,
            totp_pending: None,
            reveal_import: false,
            delete_ask: None,
            trash_open: false,
            purge_ask: None,
            export_open: false,
            export_kind: ExportKind::Json,
            export_path: String::new(),
            export_vault_open: false,
            export_vault_path: String::new(),
            export_vault_pass: String::new(),
            export_vault_confirm: String::new(),
            export_vault_reveal: false,
            export_vault_err: None,
            export_vault_kdf: crypto::KdfParams::default(),
            updater: craft_update::updater::Updater::new(&craft_update::suite::LOCALKEYS, CURRENT_VERSION),
            import_open: false,
            import_kind: ImportKind::Kdbx,
            import_path: String::new(),
            import_password: String::new(),
            import_report: None,
            gen_open: false,
            gen_passphrase: false,
            gen_pw: generator::PasswordOptions::default(),
            gen_pp: generator::PassphraseOptions::default(),
            gen_result: String::new(),
            change_open: false,
            chg_old: String::new(),
            chg_new: String::new(),
            chg_confirm: String::new(),
            attach_item: None,
            attach_path: String::new(),
            attach_queue: Vec::new(),
            folders_open: false,
            folder_new_open: false,
            folder_new: String::new(),
            folder_edit_id: None,
            folder_edit_name: String::new(),
            toast: None,
            job_rx: None,
            job_label: None,
            #[cfg(windows)]
            _tray: None,
            quit: Arc::new(AtomicBool::new(false)),
            settings_open: false,
            sync_plan: None,
            sync_open: false,
            merge_open: false,
            merge_path: String::new(),
            merge_password: String::new(),
            merge_reveal: false,
            merge_error: None,
            merge_plan: None,
            palette: craft_palette::Palette::default(),
            config_file: config_dir(APP_ID).join("config.json"),
            #[cfg(test)]
            persist_count: 0,
        }
    }

    /// `initial_vault`: o `.tkeys` recebido no argv. Vira o caminho da tela de desbloqueio no lugar do
    /// último cofre usado; sem ele, vale o último.
    fn new(prefs: Prefs, initial_vault: Option<String>) -> Self {
        let mut app = Self::bare(prefs);
        #[cfg(windows)]
        {
            let (tray, quit) = spawn_tray();
            app._tray = tray;
            app.quit = quit;
        }
        app.path = initial_vault.or_else(last_path).unwrap_or_default();
        app.quick_ativa = app.path_has_stored_key();
        app.try_quick_unlock();
        app
    }

    fn locked(&self) -> bool {
        self.session.is_none()
    }

    /// Tranca: apaga a sessão (Drop → Zeroizing limpa a chave) e o vault em
    /// claro da memória. Ordem session → raw, como no oficial.
    fn lock(&mut self) {
        self.session = None;
        self.raw = None;
        self.items.clear();
        self.master.clear();
        self.confirm.clear();
        self.add_pass.clear();
        self.form_open = false;
        self.form_edit_id = None;
        self.form_target_kind = None;
        self.form_err = None;
        self.new_kind_open = false;
        self.add_url.clear();
        self.add_notes.clear();
        self.add_cardholder.clear();
        self.add_brand.clear();
        self.add_number.clear();
        self.add_exp.clear();
        self.add_code.clear();
        self.brand_auto = true;
        self.reveal_code = false;
        self.add_first.clear();
        self.add_last.clear();
        self.add_email.clear();
        self.add_phone.clear();
        self.add_address.clear();
        self.form_custom.clear();
        self.form_attachments.clear();
        self.reveal_form = false;
        self.totp_warn = None;
        self.reveal_totp = false;
        self.totp_pending = None;
        self.delete_ask = None;
        self.trash_open = false;
        self.purge_ask = None;
        self.export_open = false;
        self.export_path.clear();
        self.export_vault_open = false;
        self.export_vault_path.clear();
        drop(Zeroizing::new(std::mem::take(&mut self.export_vault_pass)));
        drop(Zeroizing::new(std::mem::take(&mut self.export_vault_confirm)));
        self.export_vault_reveal = false;
        self.export_vault_err = None;
        // Onda 6: nenhum segredo de import/gerador/senha mestra sobrevive ao
        // trancar, e as janelas fecham junto.
        self.import_open = false;
        self.import_path.clear();
        self.import_password.clear();
        self.reveal_import = false;
        self.import_report = None;
        self.gen_open = false;
        self.gen_result.clear();
        self.change_open = false;
        self.chg_old.clear();
        self.chg_new.clear();
        self.chg_confirm.clear();
        self.attach_item = None;
        self.attach_path.clear();
        self.attach_queue.clear();
        // onda 24: gerência/criação de pastas fecha junto, e a fila de anexos
        // multi-selecionados não sobrevive ao trancar (o raw já era).
        self.folders_open = false;
        self.folder_new_open = false;
        self.folder_new.clear();
        self.folder_edit_id = None;
        self.folder_edit_name.clear();
        self.sync_plan = None;
        self.sync_open = false;
        self.merge_plan = None;
        self.merge_open = false;
        self.merge_path.clear();
        drop(Zeroizing::new(std::mem::take(&mut self.merge_password)));
        self.merge_reveal = false;
        self.merge_error = None;
        // O cofre seguinte abre sem filtro nenhum ligado (como a pasta, zerada no `adopt`).
        self.filters.clear();
        self.clear_selection();
        self.toast = None;
    }

    fn adopt(&mut self, session: crypto::SessionKey, raw: serde_json::Value) {
        self.clear_selection();
        self.items = vault::items_view(&raw);
        // O cofre pode ser OUTRO (destrancar depois de trancar): a pasta lembrada só vale se este cofre ainda
        // tem essa pasta; senão a lista começa em "todas" (a preferência fica como está).
        self.folder_filter = self.prefs.folder.clone().filter(|id| vault::folders(&raw).iter().any(|folder| &folder.id == id));
        self.raw = Some(raw);
        self.session = Some(session);
        self.err = None;
        self.master.clear();
        self.confirm.clear();
    }

    /// Troca a pasta do filtro da lista e lembra a escolha no config.json (o próximo cofre abre nela). O cofre
    /// não é tocado: a lembrança é estado de UI.
    fn set_folder_filter(&mut self, folder: Option<String>) {
        self.prefs.folder = folder.clone();
        self.folder_filter = folder;
        // A lista muda de conteúdo: a seleção da lista antiga não vale para a nova.
        self.reset_selection_for_filter_change();
        self.save_prefs();
    }

    // ── desbloqueio rápido (keyring do SO — desenho do oficial) ────────
    // Só no Windows: secret-service no Linux puxaria libdbus pro build
    // (runner/AppImage sem dbus-1). Fora do Windows os stubs desligam tudo.

    #[cfg(windows)]
    fn path_has_stored_key(&self) -> bool {
        let path = self.path.trim();
        !path.is_empty() && keyring::Entry::new(KEYRING_SERVICE, path).and_then(|e| e.get_password()).is_ok()
    }

    #[cfg(not(windows))]
    fn path_has_stored_key(&self) -> bool {
        false
    }

    /// Tenta abrir o último cofre com a chave guardada no cofre do SO, sem
    /// pedir a master. Falha silenciosa (chave inválida → apaga a entrada:
    /// senha mudou em outro lugar, a chave velha não serve mais).
    ///
    /// Fica SÍNCRONO de propósito: a chave já está derivada, então
    /// `open_with_key` é só o AEAD de poucos KB (sem Argon2) — não há KDF pra
    /// tirar da thread da UI.
    #[cfg(windows)]
    fn try_quick_unlock(&mut self) {
        use base64::Engine as _;

        // O caminho da tela de desbloqueio (o último cofre ou o `.tkeys` do argv): a chave guardada é
        // por caminho, então um cofre aberto pelo Explorer só destranca sozinho se tiver a dele.
        let path = self.path.trim().to_string();
        if path.is_empty() {
            return;
        }
        let Ok(b64) = keyring::Entry::new(KEYRING_SERVICE, &path).and_then(|e| e.get_password()) else {
            return;
        };
        let Ok(key) = base64::engine::general_purpose::STANDARD.decode(b64) else {
            return;
        };
        let Ok(mut key) = <[u8; 32]>::try_from(key) else {
            return;
        };
        let Ok(file) = std::fs::read(&path) else {
            return;
        };
        match crypto::open_with_key(&key, &file) {
            Ok((plaintext, session)) => match serde_json::from_slice::<serde_json::Value>(&plaintext) {
                Ok(raw) => {
                    self.path = path.clone();
                    self.adopt(session, raw);
                    remember_last_path(&path);
                }
                Err(_) => self.err = Some(tr("the vault is not valid JSON").into()),
            },
            Err(_) => {
                // chave guardada não serve mais (senha trocada / arquivo outro)
                if let Ok(entry) = keyring::Entry::new(KEYRING_SERVICE, &path) {
                    let _ = entry.delete_credential();
                }
            }
        }
        key.fill(0);
        self.quick_ativa = self.path_has_stored_key();
    }

    #[cfg(not(windows))]
    fn try_quick_unlock(&mut self) {}

    #[cfg(windows)]
    fn store_quick_key(&self, session: &crypto::SessionKey) {
        use base64::Engine as _;

        let path = self.path.trim();
        if path.is_empty() {
            return;
        }
        let b64 = base64::engine::general_purpose::STANDARD.encode(session.key_bytes());
        if let Ok(entry) = keyring::Entry::new(KEYRING_SERVICE, path) {
            let _ = entry.set_password(&b64);
        }
    }

    #[cfg(not(windows))]
    fn store_quick_key(&self, _session: &crypto::SessionKey) {}

    #[cfg(windows)]
    fn forget_quick_key(&mut self) {
        let path = self.path.trim();
        if !path.is_empty()
            && let Ok(entry) = keyring::Entry::new(KEYRING_SERVICE, path)
        {
            let _ = entry.delete_credential();
        }
        self.quick_unlock = false;
        self.quick_ativa = false;
    }

    #[cfg(not(windows))]
    fn forget_quick_key(&mut self) {
        self.quick_unlock = false;
        self.quick_ativa = false;
    }

    // ── abrir / criar / salvar ──────────────────────────────────────────

    /// Destranca: a validação é barata (fica aqui); ler o arquivo e rodar o
    /// Argon2id vão pra thread. A master sai do campo como `Zeroizing` inteiro
    /// (a thread é dona dela e a apaga ao terminar).
    fn unlock(&mut self, ctx: &egui::Context) {
        let path = self.path.trim().to_string();
        if path.is_empty() || self.master.is_empty() {
            return;
        }
        let password = Zeroizing::new(std::mem::take(&mut self.master));
        let quick = self.quick_unlock;
        self.start_job(ctx, tr("unlocking…"), Job::Unlock { path, password, quick });
    }

    fn create(&mut self, ctx: &egui::Context) {
        if self.path.trim().is_empty() {
            return;
        }
        if self.master.is_empty() {
            self.err = Some(tr("the master password cannot be empty").into());
            return;
        }
        if self.master != self.confirm {
            self.err = Some(tr("the passwords do not match").into());
            return;
        }
        let password = Zeroizing::new(std::mem::take(&mut self.master));
        self.confirm.clear();
        let quick = self.quick_unlock;
        let path = self.path.trim().to_string();
        self.start_job(ctx, tr("working…"), Job::Create { path, password, quick });
    }

    /// Cifra + grava um novo estado do vault. Se o salvamento falhar, o raw em
    /// memória NÃO muda — memória e disco nunca divergem. Devolve ok/falha.
    fn persist(&mut self, next: serde_json::Value) -> bool {
        let bytes = match serde_json::to_vec(&next) {
            Ok(b) => b,
            Err(e) => {
                self.err = Some(e.to_string());
                return false;
            }
        };
        let sealed = match self.session.as_ref().map(|s| s.seal(&bytes)) {
            Some(Ok(file)) => file,
            Some(Err(e)) => {
                self.err = Some(e.to_string());
                return false;
            }
            None => return false,
        };
        match vault::atomic_write(std::path::Path::new(self.path.trim()), &sealed) {
            Ok(()) => {
                self.err = None;
                self.items = vault::items_view(&next);
                // A lista pode ter mudado (o ★ com o filtro "Favoritos" ligado, pasta/tipo trocados): a seleção é da
                // lista VISÍVEL, então item marcado que saiu da tela sai junto.
                self.prune_selection();
                self.raw = Some(next);
                #[cfg(test)]
                {
                    self.persist_count += 1;
                }
                true
            }
            Err(e) => {
                self.err = Some(e);
                false
            }
        }
    }

    /// O item aparece na lista com a busca, os filtros e a pasta ATUAIS? A mesma regra vale para o desenho da lista
    /// e para a poda da seleção depois de um `persist` — uma linha marcada que sai da tela sai da seleção.
    fn in_view(&self, item: &vault::ItemView) -> bool {
        let needle = self.search.trim().to_lowercase();
        filters::matches(&self.filters, item)
            && (needle.is_empty() || item.name.to_lowercase().contains(&needle))
            && (self.folder_filter.is_none() || item.folder_id.as_deref() == self.folder_filter.as_deref())
    }

    /// Os ids da lista visível (busca, filtros e pasta aplicados), na ordem do cofre.
    fn visible_ids(&self) -> Vec<String> {
        self.items.iter().filter(|item| self.in_view(item)).map(|item| item.id.clone()).collect()
    }

    /// Salva o item do formulário (novo ou edição), de qualquer tipo, e persiste.
    fn save_form(&mut self) {
        let name = self.add_name.trim().to_string();
        if name.is_empty() {
            return;
        }
        // Owned antes: os campos do `form_modal` emprestam estes buffers e o
        // `persist` precisa de `&mut self`.
        //
        // Trim como o `ItemEditorState.build` do Android: texto de formulário chega limpo ao cofre
        // (titular, bandeira, usuário, os cinco da identidade). A senha NUNCA é trimada (espaço pode
        // ser parte dela) e as notas só perdem o fim (`trimEnd`).
        let username = self.add_user.trim().to_string();
        let password = self.add_pass.clone();
        let totp = self.add_totp.trim().to_string();
        let url = self.add_url.trim().to_string();
        let notes = self.add_notes.trim_end().to_string();
        let cardholder = self.add_cardholder.trim().to_string();
        let brand = self.add_brand.trim().to_string();
        let number = self.add_number.clone();
        let exp = self.add_exp.clone();
        let code = self.add_code.clone();
        let (first, last, email, phone, address) = (
            self.add_first.trim().to_string(),
            self.add_last.trim().to_string(),
            self.add_email.trim().to_string(),
            self.add_phone.trim().to_string(),
            self.add_address.trim().to_string(),
        );
        let custom: Vec<vault::CustomFieldView> = self
            .form_custom
            .iter()
            .map(|f| vault::CustomFieldView { id: f.id.clone(), name: f.name.clone(), value: f.value.clone(), hidden: f.hidden })
            .collect();
        let Some(mut next) = self.raw.clone() else {
            return;
        };
        // "antes" do add — com ele achamos o id gerado pelo `add_*` depois.
        let antes = item_ids(&next);
        let edit_id = self.form_edit_id.clone();
        let ok = match (self.form_kind, edit_id.as_deref()) {
            (vault::ItemKind::Login, Some(id)) => vault::edit_login(
                &mut next,
                id,
                vault::LoginForm { name: &name, username: &username, password: &password, totp: &totp, url: &url, notes: &notes },
            ),
            (vault::ItemKind::Login, None) => {
                vault::add_login(
                    &mut next,
                    vault::LoginForm { name: &name, username: &username, password: &password, totp: &totp, url: &url, notes: &notes },
                );
                true
            }
            (vault::ItemKind::Card, Some(id)) => vault::edit_card(
                &mut next,
                id,
                vault::CardForm { name: &name, cardholder: &cardholder, brand: &brand, number: &number, exp: &exp, code: &code, notes: &notes },
            ),
            (vault::ItemKind::Card, None) => {
                vault::add_card(
                    &mut next,
                    vault::CardForm { name: &name, cardholder: &cardholder, brand: &brand, number: &number, exp: &exp, code: &code, notes: &notes },
                );
                true
            }
            (vault::ItemKind::Identity, Some(id)) => vault::edit_identity(
                &mut next,
                id,
                vault::IdentityForm {
                    name: &name,
                    first_name: &first,
                    last_name: &last,
                    email: &email,
                    phone: &phone,
                    address: &address,
                    notes: &notes,
                },
            ),
            (vault::ItemKind::Identity, None) => {
                vault::add_identity(
                    &mut next,
                    vault::IdentityForm {
                        name: &name,
                        first_name: &first,
                        last_name: &last,
                        email: &email,
                        phone: &phone,
                        address: &address,
                        notes: &notes,
                    },
                );
                true
            }
            (vault::ItemKind::Note, Some(id)) => vault::edit_note(&mut next, id, vault::NoteForm { name: &name, notes: &notes }),
            (vault::ItemKind::Note, None) => {
                vault::add_note(&mut next, vault::NoteForm { name: &name, notes: &notes });
                true
            }
        };
        if !ok {
            self.err = Some(tr("item not found").into());
            return;
        }
        // onda 24 — pasta do item: aplicar DEPOIS do add/edit (o `add_*`
        // gera o id interno, então o item novo é o diff de ids).
        let alvo = match self.form_edit_id.clone() {
            Some(id) => Some(id),
            None => novo_item_id(&next, &antes),
        };
        if let Some(id) = alvo.as_deref() {
            for a in &self.form_attachments {
                if !vault::add_attachment(&mut next, id, &a.name, a.mime, &a.data_b64) {
                    self.err = Some(tr("could not attach to the item").into());
                    return;
                }
            }
            // Campos personalizados (todos os tipos): a lista do form substitui a do item.
            if !vault::set_custom_fields(&mut next, id, &custom) {
                self.err = Some(tr("item not found").into());
                return;
            }
        }
        if let Some(id) = alvo {
            // `set_item_folder` grava `updatedAt` — só tocar se de fato mudou
            // (no item novo, sempre aplica).
            let igual = self.form_edit_id.is_some()
                && self.items.iter().find(|i| i.id == id).and_then(|i| i.folder_id.clone()).as_deref() == self.add_folder_id.as_deref();
            if !igual {
                vault::set_item_folder(&mut next, &id, self.add_folder_id.as_deref());
            }
        }
        // Mudança de tipo (unidade 4, peça 2): as edições acima já estão no clone `next`; a regra do núcleo
        // converte o item por cima. `persist` é a ÚNICA gravação — se a conversão recusar, `next` é
        // descartado (memória e disco intactos) e o formulário continua aberto no tipo original.
        if let (Some(id), Some(target)) = (edit_id.as_deref(), self.form_target_kind)
            && target != self.form_kind
        {
            match change_kind::change_item_kind(&mut next, id, target, &field_labels()) {
                Ok(true) => {}
                Ok(false) => {
                    self.form_err = Some(tr("item not found").into());
                    return;
                }
                Err(error) => {
                    self.form_err = Some(change_kind_error_text(&error));
                    self.form_target_kind = Some(self.form_kind);
                    return;
                }
            }
        }
        if self.persist(next) {
            self.close_form_buffers();
            self.form_open = false;
            self.form_edit_id = None;
        } else {
            // Qualquer gravação que falhe com o formulário aberto mostra o motivo DENTRO do modal: a linha de erro
            // da tela de trás fica coberta por ele (edição comum ou conversão de tipo pendente).
            self.form_err = self.err.clone();
        }
    }

    /// Zera os buffers do formulário (sucesso, cancelamento ou trancar).
    fn close_form_buffers(&mut self) {
        self.add_name.clear();
        self.add_user.clear();
        self.add_pass.clear();
        self.add_totp.clear();
        self.add_url.clear();
        self.add_notes.clear();
        self.add_cardholder.clear();
        self.add_brand.clear();
        self.add_number.clear();
        self.add_exp.clear();
        self.add_code.clear();
        self.brand_auto = true;
        self.reveal_code = false;
        self.add_first.clear();
        self.add_last.clear();
        self.add_email.clear();
        self.add_phone.clear();
        self.add_address.clear();
        self.add_folder_id = None;
        self.form_attachments.clear();
        self.form_custom.clear();
        self.reveal_form = false;
        self.totp_warn = None;
        self.reveal_totp = false;
        self.totp_pending = None;
        self.form_target_kind = None;
        self.form_err = None;
    }

    // ── TOTP por link/QR (pedido 022, fase B) ─────────────────────────

    /// Aplica no formulário o texto lido de um QR ou colado: link `otpauth://totp/…` ou chave crua —
    /// o `apply` do `TotpEditorField` do Android. Texto inválido fica no campo (o motivo do erro
    /// aparece abaixo dele, derivado a cada quadro); válido grava só a chave base32.
    fn apply_totp_raw(&mut self, raw: &str) {
        let text = raw.trim();
        if text.is_empty() {
            return;
        }
        match otpauth::parse(text) {
            otpauth::ParseResult::Ok(p) => self.apply_parsed_totp(p),
            otpauth::ParseResult::Invalid(_) => {
                self.add_totp = text.to_string();
                self.totp_warn = None;
            }
        }
    }

    /// O `applyTotp` do Android: grava só a chave base32, preenche nome e usuário quando estão vazios
    /// (nunca sobrescreve) e guarda o aviso de parâmetros fora do padrão.
    fn apply_parsed_totp(&mut self, p: otpauth::Parsed) {
        self.totp_warn = p.unsupported.as_ref().map(unsupported_text);
        self.add_totp = p.secret;
        if self.add_name.trim().is_empty()
            && let Some(nome) = p.issuer.clone().or(p.account.clone())
        {
            self.add_name = nome;
        }
        if self.form_kind == vault::ItemKind::Login
            && self.add_user.trim().is_empty()
            && let Some(conta) = &p.account
        {
            self.add_user = conta.clone();
        }
    }

    /// QR decodificado de bytes de imagem (arquivo do seletor ou imagem colada): o texto vai pro campo
    /// pelo mesmo caminho de um link colado.
    fn apply_qr_bytes(&mut self, bytes: &[u8]) {
        match qr::decode(bytes) {
            Ok(text) => self.apply_totp_raw(&text),
            Err(qr::QrError::NotAnImage) => self.notice(tr("could not read the image file").to_string()),
            Err(qr::QrError::NoQr) => self.notice(tr("no QR code found in the image").to_string()),
            Err(qr::QrError::TooBig) => self.notice(tr("the image is too large (over 4096×4096)").to_string()),
        }
    }

    /// "Ler QR de imagem…": seletor nativo de arquivo de imagem (a leitura e o decode correm no callback).
    fn add_totp_from_file(&mut self, ctx: &egui::Context) {
        let dialog = rfd::AsyncFileDialog::new().add_filter("PNG/JPEG/BMP", &["png", "jpg", "jpeg", "bmp"]);
        let _ = self.pickers.ask(
            ctx,
            pickers::Ask::File(dialog),
            Box::new(|app, paths| {
                let Some(path) = paths.first() else { return };
                match std::fs::read(path) {
                    Ok(bytes) => app.apply_qr_bytes(&bytes),
                    Err(_) => app.notice(tr("could not read the image file").to_string()),
                }
            }),
        );
    }

    /// "Colar imagem": lê a imagem da área de transferência e decodifica o QR dela.
    fn paste_totp_image(&mut self) {
        let image = arboard::Clipboard::new().and_then(|mut c| c.get_image());
        let Ok(image) = image else {
            self.notice(tr("the clipboard has no image").to_string());
            return;
        };
        // O arboard entrega RGBA; um tamanho que não bate com os bytes não é imagem.
        let Some(rgba) = u32::try_from(image.width)
            .ok()
            .zip(u32::try_from(image.height).ok())
            .and_then(|(w, h)| image::RgbaImage::from_raw(w, h, image.bytes.into_owned()))
        else {
            self.notice(tr("the clipboard has no image").to_string());
            return;
        };
        match qr::decode_gray(&image::imageops::grayscale(&rgba)) {
            Ok(text) => self.apply_totp_raw(&text),
            Err(qr::QrError::NoQr) => self.notice(tr("no QR code found in the image").to_string()),
            Err(qr::QrError::NotAnImage) => self.notice(tr("could not read the image file").to_string()),
            Err(qr::QrError::TooBig) => self.notice(tr("the image is too large (over 4096×4096)").to_string()),
        }
    }

    // ── sincronização (pedido 022, fase D) — sempre manual, nada automático ──

    /// "Salvar cópia em pasta sincronizada…": grava uma cópia CIFRADA do cofre em `destino` (OneDrive,
    /// Drive, rclone, Syncthing… — a pasta que a pessoa escolher), CONFIRMA que a cópia abre com a
    /// mesma senha e passa a usar o arquivo novo; o antigo fica no disco como backup.
    fn carry_vault_to(&mut self, destino: &std::path::Path) {
        let Some(session) = self.session.as_ref() else { return };
        let Ok(bytes) = serde_json::to_vec(self.raw.as_ref().expect("cofre aberto")) else {
            self.err = Some(tr("could not save the vault").into());
            return;
        };
        let sealed = match session.seal(&bytes) {
            Ok(sealed) => sealed,
            Err(e) => {
                self.err = Some(format!("{}: {e}", tr("could not write the copy")));
                return;
            }
        };
        if let Err(e) = vault::atomic_write(destino, &sealed) {
            self.err = Some(format!("{}: {e}", tr("could not write the copy")));
            return;
        }
        // Confere que a cópia abre ANTES de trocar o caminho — quem usa pasta de rede agradece.
        if !copia_abre(session, destino) {
            self.err = Some(tr("could not write the copy").to_string());
            return;
        }
        self.path = destino.display().to_string();
        remember_last_path(&self.path);
        self.err = None;
        self.notice(tr("copy checked — this file is the vault now").to_string());
    }

    /// "Sincronizar com outra cópia…": abre a outra cópia COM A SENHA deste cofre, junta item a item
    /// (`merge::merge` do core, as regras do `docs/sync-merge.md`) e mostra o resumo ANTES de gravar.
    fn sync_with_other(&mut self, caminho: &str) {
        let Some(session) = self.session.as_ref() else { return };
        if caminho == self.path.trim() {
            self.err = Some(tr("the other copy is the vault in use").to_string());
            return;
        }
        let file = match std::fs::read(caminho) {
            Ok(file) => file,
            Err(_) => {
                self.err = Some(tr("could not read the other copy").to_string());
                return;
            }
        };
        let plain = match crypto::open_with_key(&session.key_bytes(), &file) {
            Ok((plain, _)) => plain,
            // O `CryptoError` diz o que foi (sem separar senha errada de adulterado — de propósito).
            Err(crypto::CryptoError::BadFormat) => {
                self.err = Some(tr("the other copy is not a .tkeys vault").to_string());
                return;
            }
            Err(crypto::CryptoError::UnsupportedVersion(_)) => {
                self.err = Some(tr("the other copy uses a newer format version").to_string());
                return;
            }
            Err(crypto::CryptoError::Decrypt) => {
                self.err = Some(tr("the other copy does not open with this vault's password").to_string());
                return;
            }
            Err(_) => {
                self.err = Some(tr("could not read the other copy").to_string());
                return;
            }
        };
        let other: serde_json::Value = match serde_json::from_slice(&plain) {
            Ok(v) => v,
            Err(_) => {
                self.err = Some(tr("the other copy is not a valid vault").to_string());
                return;
            }
        };
        // Shape de cofre ANTES do merge: sem isso, um JSON qualquer decifrado viraria "cofre vazio" e
        // a sincronização regravaria a outra cópia com o conteúdo daqui.
        if !shape_de_cofre(&other) {
            self.err = Some(tr("the other copy is not a valid vault").to_string());
            return;
        }
        let result = merge::merge(self.raw.as_ref().expect("cofre aberto"), &other);
        self.sync_plan = Some(SyncPlan { other_path: caminho.to_string(), merged: result.vault, summary: result.summary });
        self.err = None;
        self.sync_open = true;
    }

    /// Grava o resultado da sincronização NAS DUAS cópias (o mesmo conteúdo, selado uma vez) e confere
    /// que as duas abrem. A OUTRA cópia é gravada primeiro: se ela falhar (sem permissão, bloqueada),
    /// nada mudou; se o cofre atual falhar depois, a outra já mudou — nada é adotado e o erro diz o
    /// que aconteceu (repetir a sincronização converge as duas de novo).
    fn sync_apply(&mut self) {
        let Some(plan) = self.sync_plan.clone() else { return };
        let Some(session) = self.session.as_ref() else { return };
        let Ok(bytes) = serde_json::to_vec(&plan.merged) else {
            self.err = Some(tr("could not save the vault").into());
            return;
        };
        let sealed = match session.seal(&bytes) {
            Ok(sealed) => sealed,
            Err(e) => {
                self.err = Some(format!("{}: {e}", tr("could not write the copy")));
                return;
            }
        };
        if let Err(e) = vault::atomic_write(std::path::Path::new(&plan.other_path), &sealed) {
            self.err = Some(format!("{}: {e}", tr("could not write to the other copy")));
            return;
        }
        if let Err(e) = vault::atomic_write(std::path::Path::new(self.path.trim()), &sealed) {
            self.err = Some(format!("{}: {e}", tr("could not write the copy")));
            return;
        }
        // Confere que os dois arquivos abrem antes de adotar o resultado.
        let atual = std::path::Path::new(self.path.trim()).to_path_buf();
        if !copia_abre(session, &atual) || !copia_abre(session, std::path::Path::new(&plan.other_path)) {
            self.err = Some(tr("could not write the copy").to_string());
            return;
        }
        self.err = None;
        self.items = vault::items_view(&plan.merged);
        self.raw = Some(plan.merged);
        // A mescla pode apagar/mover itens: a seleção da lista antiga não vale para o resultado.
        self.clear_selection();
        // O cofre mesclado pode não ter mais a pasta do filtro (apagada na outra cópia): a lista cai em "Todas" e a
        // preferência acompanha — como no Android —, em vez de ficar apontando para uma pasta que não existe.
        let gone = self
            .folder_filter
            .as_deref()
            .is_some_and(|id| !self.raw.as_ref().is_some_and(|raw| vault::folders(raw).iter().any(|folder| folder.id == id)));
        if gone {
            self.set_folder_filter(None);
        }
        self.sync_plan = None;
        self.sync_open = false;
        self.notice(tr("synced: both copies identical").to_string());
    }

    // ── unidade 7: juntar outro cofre ───────────────────────────────────

    /// Seletor nativo do outro `.tkeys` para juntar ao aberto.
    fn pick_merge_other(&mut self, ctx: &egui::Context) {
        if self.job_label.is_some() {
            return;
        }
        let dialog = rfd::AsyncFileDialog::new().add_filter("Cofre LocalKeys (.tkeys)", &["tkeys"]);
        let ctx2 = ctx.clone();
        let _ = self.pickers.ask(
            ctx,
            pickers::Ask::File(dialog),
            Box::new(move |app, paths| {
                if let Some(path) = paths.first() {
                    app.merge_other_chosen(&ctx2, &path.display().to_string());
                }
            }),
        );
    }

    /// Outro cofre escolhido: tenta abrir com a chave da sessão (mesma senha do aberto). Se a chave não servir, o
    /// diálogo pede a senha do outro cofre. O arquivo é só LIDO: nada é gravado nele, nem no aberto.
    fn merge_other_chosen(&mut self, ctx: &egui::Context, path: &str) {
        if self.job_label.is_some() {
            return;
        }
        if same_path(path, self.path.trim()) {
            self.err = Some(tr("the other vault is the vault in use").to_string());
            return;
        }
        let Some(session) = self.session.as_ref() else {
            return;
        };
        let file = match std::fs::read(path) {
            Ok(file) => file,
            Err(_) => {
                self.err = Some(tr("could not read the other vault").to_string());
                return;
            }
        };
        match crypto::open_with_key(&session.key_bytes(), &file) {
            Ok((plain, _)) => match serde_json::from_slice::<serde_json::Value>(&plain) {
                Ok(other) => self.start_merge(ctx, path, other),
                Err(_) => self.err = Some(tr("the other vault is not a valid vault").to_string()),
            },
            // Pode ser outra senha (ou arquivo adulterado — indistinguíveis de propósito): o diálogo decide.
            Err(crypto::CryptoError::Decrypt) => {
                self.merge_path = path.to_string();
                drop(Zeroizing::new(std::mem::take(&mut self.merge_password)));
                self.merge_reveal = false;
                self.merge_error = None;
                self.merge_open = true;
            }
            Err(crypto::CryptoError::BadFormat) => self.err = Some(tr("the other vault is not a .tkeys vault").to_string()),
            Err(crypto::CryptoError::UnsupportedVersion(_)) => self.err = Some(tr("the other vault uses a newer format version").to_string()),
            Err(crypto::CryptoError::Kdf) => self.err = Some(tr("could not read the other vault").to_string()),
        }
    }

    /// Abre o outro cofre com a senha digitada (Argon2id) fora da UI; a senha sai do estado e morre com o job.
    fn merge_other_open(&mut self, ctx: &egui::Context) {
        if self.merge_password.is_empty() {
            self.merge_error = Some(tr("enter the other vault's password").into());
            return;
        }
        let path = self.merge_path.trim().to_string();
        let password = Zeroizing::new(std::mem::take(&mut self.merge_password));
        self.merge_error = None;
        self.merge_open = false;
        self.start_job(ctx, tr("opening the other vault…"), Job::MergeOtherOpen { path, password });
    }

    /// Calcula a junção fora da UI: o cofre aberto vai clonado e o outro só para leitura. Nada é adotado até a
    /// confirmação do resumo.
    fn start_merge(&mut self, ctx: &egui::Context, path: &str, other: serde_json::Value) {
        let Some(base) = self.raw.clone() else {
            return;
        };
        self.start_job(ctx, tr("merging the other vault…"), Job::MergeOther { path: path.to_string(), base, other });
    }

    /// Aplica o plano no cofre ABERTO com UMA gravação; a seleção é limpa (a lista muda). O outro arquivo não é
    /// tocado.
    fn merge_apply(&mut self) {
        let Some(plan) = self.merge_plan.clone() else {
            return;
        };
        if self.persist(plan.merged) {
            self.clear_selection();
            self.merge_plan = None;
            self.notice(tr("merged: the other vault's items are in this vault").to_string());
        } else {
            // `persist` já pôs o motivo em `err`; o plano não fica pendurado.
            self.merge_plan = None;
        }
    }

    /// Abre o form em modo "novo item" já com o tipo escolhido no seletor do "+". Se há pasta
    /// selecionada nos chips, o item já nasce nela (como o oficial faz no filtro atual). O **item
    /// livre** é uma nota que abre com um campo personalizado vazio.
    fn open_add_form(&mut self, pick: NewKind) {
        self.form_edit_id = None;
        self.form_kind = pick.kind();
        self.close_form_buffers();
        if pick == NewKind::FreeItem {
            self.form_custom.push(FormCustomField { id: String::new(), name: String::new(), value: String::new(), hidden: false, revealed: false });
        }
        self.add_folder_id = self.folder_filter.clone();
        self.form_open = true;
    }

    /// Abre o form carregando o item do tipo que ele tem. O tipo vira um seletor quando o `kind` do arquivo
    /// é conhecido (a conversão acontece ao salvar); com `kind` desconhecido fica o crachá fixo, como antes.
    fn open_edit_form(&mut self, id: &str) {
        let Some(full) = self.raw.as_ref().and_then(|r| vault::item_full(r, id)) else {
            return;
        };
        self.form_kind = full.kind;
        self.form_edit_id = Some(id.to_string());
        self.close_form_buffers();
        // O `item_full` mapeia `kind` desconhecido para nota; o tipo CRU decide se há seletor (e é o alvo
        // inicial dele).
        self.form_target_kind = self.raw.as_ref().and_then(|raw| item_wire_kind(raw, id));
        self.add_folder_id = self.items.iter().find(|i| i.id == id).and_then(|i| i.folder_id.clone());
        self.add_name = self.items.iter().find(|i| i.id == id).map(|i| i.name.clone()).unwrap_or_default();
        self.add_notes = full.notes;
        // Item sem login (nota/cartão/identidade): os campos do login abrem vazios.
        let vazio = vault::LoginFull { username: String::new(), password: String::new(), totp: String::new(), url: String::new() };
        let login = full.login.as_ref().unwrap_or(&vazio);
        self.add_user = login.username.clone();
        self.add_pass = login.password.clone();
        self.add_totp = login.totp.clone();
        self.add_url = login.url.clone();
        if let Some(c) = full.card {
            self.add_cardholder = c.cardholder;
            self.add_brand = c.brand.clone();
            // O número abre JÁ em blocos (o Android formata sempre — o pedido C.1 "aparece em
            // blocos" vale para a edição também; o cofre guarda só os dígitos e o save normaliza).
            self.add_number = card::group(&c.number);
            self.add_exp = c.exp;
            self.add_code = c.code;
            // A bandeira é "automática" se veio vazia ou se é a que a detecção daria (o número pode
            // trocá-la); digitada de outro jeito, fica (o `brandAuto` do Android no load).
            let detectada = card::brand_of(&c.number).map(|b| b.label());
            self.brand_auto = c.brand.trim().is_empty() || detectada == Some(c.brand.trim());
        }
        if let Some(i) = full.identity {
            self.add_first = i.first_name;
            self.add_last = i.last_name;
            self.add_email = i.email;
            self.add_phone = i.phone;
            self.add_address = i.address;
        }
        self.form_custom = full
            .custom_fields
            .into_iter()
            .map(|f| FormCustomField { id: f.id, name: f.name, value: f.value, hidden: f.hidden, revealed: false })
            .collect();
        self.form_open = true;
    }

    fn close_form(&mut self) {
        self.form_open = false;
        self.form_edit_id = None;
        self.close_form_buffers();
    }

    /// Exclusão lógica (lixeira do oficial).
    fn delete_item(&mut self, id: &str) {
        let Some(mut next) = self.raw.clone() else {
            return;
        };
        if vault::delete_login(&mut next, id) && self.persist(next) {
            // O item apagado pela linha pode estar marcado: a seleção não guarda id que saiu da lista.
            if self.selection.contains(id) {
                self.selection.toggle(id);
                self.selection_anchor = None;
                if self.selection.is_empty() {
                    self.batch_delete_ask = None;
                }
            }
        }
    }

    // ── onda 23: favorito, lixeira, export ──────────────────────────────

    /// Liga/desliga o ★ do item. O oficial não tem `toggleFavorite`: o ★ do
    /// editor passa por `updateItem`, que grava `updatedAt` — `set_favorite`
    /// faz o mesmo (ver `vault.rs`).
    fn toggle_favorite(&mut self, id: &str) {
        let Some(current) = self.items.iter().find(|i| i.id == id).map(|i| i.favorite) else {
            return;
        };
        let Some(mut next) = self.raw.clone() else {
            return;
        };
        if vault::set_favorite(&mut next, id, !current) {
            self.persist(next);
        }
    }

    /// Restaura um item da lixeira (o `restoreItem` do oficial).
    fn restore_item(&mut self, id: &str) {
        let Some(mut next) = self.raw.clone() else {
            return;
        };
        let name = item_name(&next, id);
        if vault::restore_item(&mut next, id) && self.persist(next) {
            let msg = format!("{name} — {}", tr("restored"));
            self.notice(msg);
        }
    }

    /// Exclui de vez (o `deleteForever` do oficial, que pede confirmação).
    fn purge_item(&mut self, id: &str) {
        let Some(mut next) = self.raw.clone() else {
            return;
        };
        if vault::delete_forever(&mut next, id) && self.persist(next) {
            self.purge_ask = None;
            let msg = tr("deleted forever").to_string();
            self.notice(msg);
        }
    }

    /// Export em CLARO: monta o texto aqui (barato) e manda a GRAVAÇÃO pra
    /// thread — destino em rede/OneDrive trava como qualquer escrita.
    fn run_export(&mut self, ctx: &egui::Context) {
        let target = self.export_path.trim().to_string();
        if target.is_empty() {
            self.err = Some(tr("choose where to save the export").into());
            return;
        }
        if same_path(&target, self.path.trim()) {
            self.err = Some(tr("Do not save over the open vault; choose another file.").into());
            return;
        }
        let Some(raw) = self.raw.as_ref() else {
            return;
        };
        let text = match self.export_kind {
            ExportKind::Json => export::export_json(raw),
            ExportKind::Csv => export::export_csv(raw),
        };
        self.export_open = false;
        self.start_job(ctx, tr("working…"), Job::WriteExport { target, text });
    }

    // ── unidade 6: export da seleção para um cofre NOVO ─────────────────

    /// "Export to vault…": abre o "Salvar como" nativo. O diálogo das senhas só abre com um caminho escolhido
    /// (cancelar não faz nada). Um job ou um seletor em andamento bloqueiam o clique.
    fn export_vault_ask(&mut self, ctx: &egui::Context) {
        if self.job_label.is_some() || self.pickers.busy() {
            return;
        }
        let dialog = rfd::AsyncFileDialog::new().add_filter("Cofre LocalKeys (.tkeys)", &["tkeys"]).set_file_name("exported-vault.tkeys");
        let _ = self.pickers.ask(
            ctx,
            pickers::Ask::Save(dialog),
            Box::new(|app, paths| {
                if let Some(path) = paths.first() {
                    app.export_vault_chosen(path.display().to_string());
                }
            }),
        );
    }

    /// Caminho escolhido no "Salvar como" (o nome do cofre novo é o do arquivo): abre o diálogo das senhas com o
    /// destino à mostra. Caminho vazio (seletor cancelado) não faz nada.
    fn export_vault_chosen(&mut self, path: String) {
        if path.trim().is_empty() {
            return;
        }
        self.export_vault_path = path;
        drop(Zeroizing::new(std::mem::take(&mut self.export_vault_pass)));
        drop(Zeroizing::new(std::mem::take(&mut self.export_vault_confirm)));
        self.export_vault_reveal = false;
        self.export_vault_err = None;
        self.export_vault_open = true;
    }

    /// Valida as duas senhas e manda o trabalho pesado pra thread: a regra do núcleo (`vault_from_items`), a cifra
    /// com a senha nova e a gravação. A seleção é lida AGORA; o cofre aberto não é tocado.
    fn run_export_vault(&mut self, ctx: &egui::Context) {
        if self.export_vault_pass.is_empty() {
            self.export_vault_err = Some(tr("the password cannot be empty").into());
            return;
        }
        if self.export_vault_pass != self.export_vault_confirm {
            self.export_vault_err = Some(tr("the passwords do not match").into());
            return;
        }
        let target = self.export_vault_path.trim().to_string();
        if target.is_empty() {
            self.export_vault_err = Some(tr("choose where to save the new vault").into());
            return;
        }
        if same_path(&target, self.path.trim()) {
            self.export_vault_err = Some(tr("Do not save over the open vault; choose another file.").into());
            return;
        }
        let Some(raw) = self.raw.clone() else {
            return;
        };
        let ids: Vec<String> = self.selection.ids().map(str::to_string).collect();
        // A senha sai do estado já: o job recebe uma cópia que se apaga sozinha quando ele termina. A confirmação
        // também sai do heap (o `clear` mantinha os bytes no buffer).
        let password = Zeroizing::new(std::mem::take(&mut self.export_vault_pass));
        let _confirmation = Zeroizing::new(std::mem::take(&mut self.export_vault_confirm));
        self.export_vault_reveal = false;
        self.export_vault_open = false;
        self.export_vault_err = None;
        self.start_job(ctx, tr("creating the new vault…"), Job::ExportVault { target, raw, ids, password, params: self.export_vault_kdf });
    }

    // ── onda 6: importação ──────────────────────────────────────────────

    /// Manda ler/decifrar/parsear o arquivo escolhido pra thread (PBKDF2 600k e
    /// o keepass levam SEGUNDOS). O `push_items` + `persist` ficam no retorno —
    /// o relatório (importados/pulados) abre numa janela própria.
    fn run_import(&mut self, ctx: &egui::Context) {
        let path = self.import_path.trim().to_string();
        if path.is_empty() {
            self.err = Some(tr("choose the file to import").into());
            return;
        }
        // A senha do arquivo FICA no campo (se o import falhar por caminho
        // errado, o usuário não redigita): vai pra thread uma cópia que se
        // apaga sozinha.
        let password = Zeroizing::new(self.import_password.clone());
        self.start_job(ctx, tr("importing…"), Job::Import { kind: self.import_kind, path, password });
    }

    // ── onda 6: troca de senha mestra ───────────────────────────────────

    fn change_master(&mut self, ctx: &egui::Context) {
        if self.chg_new.is_empty() {
            self.err = Some(tr("the new master password cannot be empty").into());
            return;
        }
        if self.chg_new != self.chg_confirm {
            self.err = Some(tr("the passwords do not match").into());
            return;
        }
        // Os campos ficam preenchidos (erro de digitação na senha atual é
        // comum); as cópias vão pra thread sob `Zeroizing`.
        self.start_job(
            ctx,
            tr("changing the master password…"),
            Job::ChangeMaster {
                path: self.path.trim().to_string(),
                old: Zeroizing::new(self.chg_old.clone()),
                new: Zeroizing::new(self.chg_new.clone()),
            },
        );
    }

    // ── onda 6: anexos ──────────────────────────────────────────────────

    /// Lê os arquivos de origem e codifica em base64 — NA THREAD: `fs::read`
    /// de arquivo grande/rede trava. O cap de `MAX_ATTACHMENT_BYTES` é checado
    /// lá antes de ler. O blob do cofre já cifra tudo.
    ///
    /// onda 24 — MULTI-arquivos: no Windows o `pick_files` abre o picker com
    /// multi-seleção; no Linux o campo accepta caminhos separados por `;`.
    /// Como o mecanismo de jobs é UM por vez, o primeiro arquivo abre o job e
    /// o resto espera na fila (`attach_queue`), consumida a cada resultado.
    fn add_attachment_from(&mut self, item_id: &str, ctx: &egui::Context) {
        // Seletor nativo multi-seleção (src/pickers.rs): a resposta chega num
        // frame posterior. Cancelou (paths vazios) → cai no campo de caminhos
        // digitados (`;`-separados), o fallback que sempre existiu.
        let item = item_id.to_string();
        let ctx2 = ctx.clone();
        let _ = self.pickers.ask(
            ctx,
            pickers::Ask::Files(rfd::AsyncFileDialog::new()),
            Box::new(move |app, paths| app.attachments_chosen(&item, paths.into_iter().map(|p| p.display().to_string()).collect(), &ctx2)),
        );
    }

    /// Sequência comum dos anexos: os caminhos do seletor, ou — vazios — os
    /// digitados no campo. O primeiro abre o job; o resto espera na fila
    /// (`attach_queue`), consumida a cada resultado.
    fn attachments_chosen(&mut self, item_id: &str, mut sources: Vec<String>, ctx: &egui::Context) {
        if sources.is_empty() && item_id == FORM_ATTACH {
            // O formulário não tem campo de caminho: cancelar o seletor só não anexa nada.
            return;
        }
        if sources.is_empty() {
            sources = split_attachment_paths(&self.attach_path);
        }
        if sources.is_empty() {
            self.err = Some(tr("choose the attachment file").into());
            return;
        }
        let primeiro = sources.remove(0);
        self.attach_queue = sources.into_iter().map(|source| (item_id.to_string(), source)).collect();
        self.start_job(ctx, tr("reading file…"), Job::ReadAttachment { item_id: item_id.to_string(), source: primeiro });
    }

    /// Continua a fila de anexos multi-selecionados (um job por vez) — chamada
    /// depois de cada `AttachmentRead` aplicado ou recusado, dentro do frame
    /// em que o resultado chegou.
    fn continua_fila_anexos(&mut self, ctx: &egui::Context) {
        if self.attach_queue.is_empty() {
            return;
        }
        let (item_id, source) = self.attach_queue.remove(0);
        self.start_job(ctx, tr("reading file…"), Job::ReadAttachment { item_id, source });
    }

    /// Decodifica o base64 do anexo e grava no destino escolhido — na thread
    /// (`fs::write` em pasta de rede tem a mesma lentidão da leitura).
    fn save_attachment(&mut self, item_id: &str, attachment_id: &str, ctx: &egui::Context) {
        let Some((name, b64)) = self.raw.as_ref().and_then(|r| vault::attachment_data(r, item_id, attachment_id)) else {
            self.err = Some(tr("attachment not found").into());
            return;
        };
        // Seletor nativo "salvar como" com o nome do anexo (src/pickers.rs);
        // cancelou → cai no campo de destino digitado, o fallback de sempre.
        let ctx2 = ctx.clone();
        let _ = self.pickers.ask(
            ctx,
            pickers::Ask::Save(rfd::AsyncFileDialog::new().set_file_name(name)),
            Box::new(move |app, paths| {
                let target = paths.first().map(|p| p.display().to_string()).unwrap_or_else(|| app.attach_path.trim().to_string());
                if target.is_empty() {
                    app.err = Some(tr("choose where to save the attachment").into());
                    return;
                }
                app.start_job(&ctx2, tr("working…"), Job::SaveAttachment { target, data_b64: b64 });
            }),
        );
    }

    // ── feedback ────────────────────────────────────────────────────────

    /// "copiado" — o toast mostra `✓ Copiar — <o quê>`.
    fn copied(&mut self, what: &str) {
        let msg = format!("{} — {what}", tr("Copy"));
        self.notice(msg);
    }

    /// Toast do rodapé (3,5 s): um canal só pra todo feedback.
    fn notice(&mut self, msg: String) {
        self.toast = Some((msg, std::time::Instant::now()));
    }

    /// Rodapé: card do job em andamento (spinner + rótulo) e o toast, ambos
    /// centrados embaixo — o desenho do `toast`/`progress_notice` do local-pdf,
    /// com um cartão invertido em relação ao tema.
    fn footer(&mut self, ctx: &egui::Context) {
        let skin = Skin::from_visuals(&ctx.style_of(ctx.theme()).visuals);
        let screen = ctx.viewport_rect();
        let mut lift = 18.0;
        if let Some(label) = self.job_label {
            let area = egui::Area::new(egui::Id::unique("job-notice"))
                .order(egui::Order::Tooltip)
                .pivot(egui::Align2::CENTER_BOTTOM)
                .fixed_pos(screen.center_bottom() - egui::vec2(0.0, lift))
                .show(ctx, |ui| {
                    egui::Frame::NONE
                        .fill(skin.inverted_fill)
                        .corner_radius(egui::CornerRadius::same(10))
                        .inner_margin(egui::Margin::symmetric(16, 10))
                        .show(ui, |ui| {
                            ui.horizontal(|ui| {
                                ui.add(egui::Spinner::new().color(skin.inverted_text));
                                ui.label(egui::RichText::new(label).color(skin.inverted_text));
                            });
                        });
                });
            lift += area.response.rect.height() + 8.0;
            ctx.request_repaint_after(std::time::Duration::from_millis(100));
        }
        let Some((msg, at)) = self.toast.clone() else {
            return;
        };
        if at.elapsed() > TOAST_LIFETIME {
            self.toast = None;
            return;
        }
        egui::Area::new(egui::Id::unique("toast"))
            .order(egui::Order::Tooltip)
            .pivot(egui::Align2::CENTER_BOTTOM)
            .fixed_pos(screen.center_bottom() - egui::vec2(0.0, lift))
            .show(ctx, |ui| {
                egui::Frame::NONE
                    .fill(skin.inverted_fill)
                    .corner_radius(egui::CornerRadius::same(8))
                    .inner_margin(egui::Margin::symmetric(16, 10))
                    .show(ui, |ui| {
                        // Um toast curto não pode impor largura ao próximo.
                        ui.set_max_width((screen.width() - 64.0).clamp(1.0, 560.0));
                        // O ✓ é o ícone da suíte (o glyph não existe nas fontes embutidas).
                        ui.horizontal(|ui| {
                            ui.add(craft_icons::ui::image(ui, craft_icons::Icon::Check).tint(skin.inverted_text));
                            ui.add(egui::Label::new(egui::RichText::new(msg).color(skin.inverted_text)).wrap());
                        });
                    });
            });
        ctx.request_repaint_after(std::time::Duration::from_millis(100));
    }

    // ── jobs (onda 17) ──────────────────────────────────────────────────

    /// Manda o job pra thread e fica devendo o resultado. Um job por vez: os
    /// gatilhos só chamam isto com `job_label` vazio.
    fn start_job(&mut self, ctx: &egui::Context, label: &'static str, job: Job) {
        let (tx, rx) = std::sync::mpsc::channel();
        // O `Context` é Send+Sync: a thread chama `request_repaint()` no fim e
        // o resultado aparece sem o usuário mexer no mouse.
        jobs::spawn(job, tx, ctx.clone());
        self.job_rx = Some(rx);
        self.job_label = Some(label);
        self.err = None;
        ctx.request_repaint();
    }

    /// Drena o canal no início do frame e aplica o que chegou. O canal é
    /// `take`-ado pra soltar o borrow antes de mexer no estado.
    fn poll_jobs(&mut self, ctx: &egui::Context) {
        let Some(rx) = self.job_rx.take() else {
            return;
        };
        let (results, closed) = jobs::drain(&rx);
        for result in results {
            self.apply_job(result, ctx);
        }
        if closed {
            // Thread terminou sem resultado (spawn falhou / panic): não deixa o
            // spinner girando pra sempre. Um resultado que já disparou OUTRO job
            // (fila de anexos, junção) deixou um canal novo — aí não há "sem resultado".
            if self.job_rx.is_none() && self.job_label.take().is_some() {
                self.err = Some(tr("the operation ended without a result").into());
            }
        } else if self.job_rx.is_none() {
            // Um `apply_job` pode ter começado o próximo job: o canal novo é dele, não este.
            self.job_rx = Some(rx);
        }
    }

    /// Aplica o resultado do job — aqui (e só aqui) o estado do app muda.
    /// `ctx` entra pra fila de anexos multi-selecionados poder disparar o
    /// job seguinte no mesmo frame (`start_job` repinta).
    fn apply_job(&mut self, result: JobResult, ctx: &egui::Context) {
        self.job_label = None;
        match result {
            JobResult::Unlocked(Ok(unlocked)) => {
                let Unlocked { path, raw, session, quick } = unlocked;
                if quick {
                    self.store_quick_key(&session);
                }
                self.path = path.clone();
                self.adopt(session, raw);
                remember_last_path(&path);
                self.quick_ativa = self.path_has_stored_key();
            }
            JobResult::Unlocked(Err(e)) => self.err = Some(self.job_error(e)),
            JobResult::Created(Ok(created)) => {
                let Created { path, file, raw, session, quick } = created;
                // Gravação atômica aqui: poucos KB e é ela que decide se o
                // cofre novo é adotado (disco e memória não divergem).
                match vault::atomic_write(std::path::Path::new(&path), &file) {
                    Ok(()) => {
                        if quick {
                            self.store_quick_key(&session);
                        }
                        self.path = path.clone();
                        self.adopt(session, raw);
                        remember_last_path(&path);
                    }
                    Err(e) => self.err = Some(e),
                }
            }
            JobResult::Created(Err(e)) => self.err = Some(self.job_error(e)),
            JobResult::MasterChanged(Ok(renewed)) => {
                let Renewed { path, file } = renewed;
                match vault::atomic_write(std::path::Path::new(&path), &file) {
                    Ok(()) => {
                        // A sessão em memória foi derivada do salt ANTIGO: um
                        // save reescreveria o arquivo com a senha velha.
                        // Trancar força o destrancar com a nova (o "vault
                        // fechado no disco" que o oficial assume).
                        self.lock();
                        // A chave do keyring foi derivada do salt antigo.
                        invalidate_quick_key(&path);
                        self.quick_ativa = false;
                        self.err = None;
                        let msg = tr("master password changed").to_string();
                        self.notice(msg);
                    }
                    Err(e) => self.err = Some(e),
                }
            }
            JobResult::MasterChanged(Err(e)) => self.err = Some(self.job_error(e)),
            JobResult::Imported(Ok(imported)) => {
                let Some(mut next) = self.raw.clone() else {
                    return;
                };
                let count = imported.items.len();
                vault::push_items(&mut next, imported.items);
                if self.persist(next) {
                    self.import_report = Some((count, imported.skipped));
                    self.import_open = false;
                    self.import_path.clear();
                    self.import_password.clear();
                }
            }
            JobResult::Imported(Err(e)) => self.err = Some(self.job_error(e)),
            JobResult::AttachmentRead(Ok(read)) => {
                self.apply_attachment(read);
                // Próximo da fila (se houver) — aplica e recusa consomem a fila.
                self.continua_fila_anexos(ctx);
            }
            JobResult::AttachmentRead(Err(e)) => {
                self.err = Some(self.job_error(e));
                // Um arquivo ruim não para a fila — os próximos seguem.
                self.continua_fila_anexos(ctx);
            }
            JobResult::AttachmentSaved(Ok(target)) => {
                self.err = None;
                let msg = format!("{}: {target}", tr("Save"));
                self.notice(msg);
            }
            JobResult::AttachmentSaved(Err(e)) => self.err = Some(self.job_error(e)),
            JobResult::Exported(Ok(target)) => {
                self.err = None;
                let msg = format!("{}: {target}", tr("exported"));
                self.notice(msg);
            }
            JobResult::Exported(Err(e)) => self.err = Some(self.job_error(e)),
            JobResult::VaultExported(Ok(target)) => {
                self.err = None;
                let msg = format!("{}: {target}", tr("new vault saved"));
                self.notice(msg);
            }
            JobResult::VaultExported(Err(e)) => self.err = Some(self.job_error(e)),
            JobResult::MergeOtherOpened(Ok(opened)) => {
                if self.raw.is_none() {
                    return;
                }
                self.start_merge(ctx, &opened.path, opened.raw);
            }
            // A chave da sessão não serviu e a senha digitada também não: o diálogo continua com o motivo.
            JobResult::MergeOtherOpened(Err(JobError::WrongPassword)) => {
                if self.raw.is_none() {
                    return;
                }
                drop(Zeroizing::new(std::mem::take(&mut self.merge_password)));
                self.merge_error = Some(tr("wrong password or corrupted/tampered file").to_string());
                self.merge_open = true;
            }
            JobResult::MergeOtherOpened(Err(e)) => self.err = Some(self.job_error(e)),
            JobResult::MergeOtherReady(Ok(ready)) => {
                // O cofre pode ter trancado enquanto a junção rodava: aí o plano não vale mais.
                if self.raw.is_none() {
                    return;
                }
                self.err = None;
                self.merge_plan = Some(MergePlan { other_path: ready.path, merged: ready.merged, summary: ready.summary });
            }
            JobResult::MergeOtherReady(Err(e)) => self.err = Some(self.job_error(e)),
        }
    }

    fn apply_attachment(&mut self, read: AttachmentRead) {
        if read.item_id == FORM_ATTACH {
            self.form_attachments.push(read);
            return;
        }
        let AttachmentRead { item_id, name, mime, size, data_b64 } = read;
        let Some(mut next) = self.raw.clone() else {
            return;
        };
        if vault::add_attachment(&mut next, &item_id, &name, mime, &data_b64) {
            if self.persist(next) {
                let msg = format!("{name} — {}", human_size(size));
                self.notice(msg);
            }
        } else {
            self.err = Some(tr("could not attach to the item").into());
        }
    }

    /// Erro de job → texto pro `self.err` (o que é traduzível volta como
    /// variante porque a thread não conhece o idioma).
    fn job_error(&self, e: JobError) -> String {
        match e {
            JobError::Msg(msg) => msg,
            JobError::WrongPassword => tr("wrong password or corrupted/tampered file").to_string(),
            JobError::AttachmentTooBig { name } => {
                format!("{name}: {}", tr("attachment too large (max 10 MiB — the whole vault is rewritten on every save)"))
            }
            JobError::VaultExport(error) => export_vault_error_text(&error),
            JobError::OtherNotVault => tr("the other vault is not a .tkeys vault").to_string(),
            JobError::OtherUnsupportedVersion => tr("the other vault uses a newer format version").to_string(),
            JobError::OtherInvalid => tr("the other vault is not a valid vault").to_string(),
            JobError::MergeOther(error) => merge_other_error_text(&error),
        }
    }
}

/// Linha da lista (owned antes do UI: nenhum borrow de `self.items` nas
/// closures) — com a ordenação de renderização aplicada em cima.
struct Row {
    id: String,
    name: String,
    kind: vault::ItemKind,
    /// Resumo da linha por tipo (usuário, bandeira e final, nome da pessoa, começo da nota).
    summary: Option<String>,
    username: Option<String>,
    totp: Option<String>,
    favorite: bool,
}

impl KeysApp {
    // ── onda 23: diálogos ───────────────────────────────────────────────

    /// Form de adição/edição por tipo: campos do tipo, notas, pasta, anexos e campos personalizados. O tipo
    /// do item novo vem do seletor do "+" (crachá fixo). Na edição de um item de tipo conhecido ele vira um
    /// seletor — os campos mostrados continuam os do item e a conversão acontece ao salvar; com `kind`
    /// desconhecido no arquivo, crachá fixo como antes.
    fn form_modal(&mut self, ctx: &egui::Context) {
        if !self.form_open {
            return;
        }
        // Colagem de otpauth:// aplicada FORA do TextEdit (trocar o buffer dentro do `changed` do
        // campo quebra o cursor — o paste seguinte entraria no meio da chave).
        if let Some(raw) = self.totp_pending.take()
            && let otpauth::ParseResult::Ok(p) = otpauth::parse(&raw)
        {
            self.apply_parsed_totp(p);
        }
        let kind = self.form_kind;
        let title = if self.form_edit_id.is_some() { tr("Edit") } else { tr("Add") };
        let mut cancel = false;
        let mut add_file = false;
        let mut ask_qr_file = false;
        let mut paste_qr_image = false;
        let mut add_field = false;
        let mut add_doc: Option<String> = None;
        let mut remove_field: Option<usize> = None;
        let mut copy_value: Option<(String, String)> = None;
        let resp = egui::Modal::new(egui::Id::unique("form")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            dialog_width(ui, 400.0);
            ui.heading(title);
            ui.add_space(4.0);
            match self.form_target_kind {
                // Item existente de tipo conhecido: o tipo pode ser trocado. Trocar aqui só marca o alvo —
                // a conversão (regra do núcleo) acontece no Salvar, junto com as edições, numa gravação só.
                Some(target) => {
                    let mut type_changed = false;
                    ui.horizontal(|ui| {
                        let label = ui.label(tr("Type"));
                        egui::ComboBox::from_id_salt("form-tipo")
                            .width(220.0)
                            .selected_text(format!("{} {}", kind_icon(target), kind_label(target)))
                            .show_ui(ui, |ui| {
                                for option in [vault::ItemKind::Login, vault::ItemKind::Note, vault::ItemKind::Card, vault::ItemKind::Identity] {
                                    type_changed |= ui
                                        .selectable_value(
                                            &mut self.form_target_kind,
                                            Some(option),
                                            format!("{} {}", kind_icon(option), kind_label(option)),
                                        )
                                        .changed();
                                }
                            })
                            .response
                            .labelled_by(label.id);
                    });
                    // A mensagem da última gravação recusada é do tipo que a pessoa tinha escolhido: trocar de tipo
                    // a torna obsoleta (ela some junto).
                    if type_changed {
                        self.form_err = None;
                    }
                    // Nada some em silêncio: os campos que o tipo novo não tem viram campos personalizados.
                    if target != kind {
                        let skin = Skin::of(ui);
                        ui.label(
                            egui::RichText::new(tr(
                                "Fields that do not exist in the new type become custom fields when you save; hidden fields stay hidden. The fields of the new type only appear after you save and reopen the item.",
                            ))
                            .small()
                            .color(skin.warn),
                        );
                        // O TOTP não dá para "levar" como campo oculto e continuar gerando código: o item deixa de
                        // gerar. Só avisa quando é o caso do item aberto.
                        if kind == vault::ItemKind::Login && target != vault::ItemKind::Login && !self.add_totp.trim().is_empty() {
                            ui.label(
                                egui::RichText::new(tr("The TOTP secret becomes a hidden field, and this item stops generating codes."))
                                    .small()
                                    .color(skin.warn),
                            );
                        }
                    }
                }
                // Item novo (o tipo veio do "+") ou `kind` desconhecido no arquivo: crachá fixo, como antes.
                None => {
                    ui.label(
                        egui::RichText::new(format!("{} {}", kind_icon(kind), tr_fmt("Type: {kind}", &[("kind", kind_label(kind))]))).small().weak(),
                    );
                }
            }
            ui.add_space(8.0);
            // Com os campos por tipo + campos personalizados o diálogo passa da altura mínima da
            // janela: a área central rola (os botões ficam fora dela, sempre visíveis).
            egui::ScrollArea::vertical().max_height(420.0).show(ui, |ui| {
                // Os campos encolhem com o diálogo (a coluna dos rótulos fica com ~110).
                let field = (ui.available_width() - 110.0).clamp(140.0, 280.0);
                egui::Grid::new("form-grid").num_columns(2).spacing([10.0, 8.0]).show(ui, |ui| {
                    let label = ui.label(tr("Title"));
                    ui.add(line(&mut self.add_name).desired_width(field)).labelled_by(label.id);
                    ui.end_row();

                    match kind {
                        vault::ItemKind::Login => {
                            let label = ui.label(tr("Username"));
                            ui.add(line(&mut self.add_user).desired_width(field)).labelled_by(label.id);
                            ui.end_row();

                            let label = ui.label(tr("Password"));
                            ui.horizontal(|ui| {
                                ui.add(line(&mut self.add_pass).password(!self.reveal_form).desired_width(field - 28.0)).labelled_by(label.id);
                                // 👁 reveal: o campo volta a ocultar ao fechar.
                                if ghost(ui, if self.reveal_form { "🙈" } else { "👁" }).on_hover_text(tr("Show/hide")).clicked() {
                                    self.reveal_form = !self.reveal_form;
                                }
                            });
                            ui.end_row();

                            let label = ui.label(tr("URL"));
                            ui.add(line(&mut self.add_url).hint_text("https://…").desired_width(field)).labelled_by(label.id);
                            ui.end_row();

                            let label = ui.label(tr("TOTP"));
                            ui.vertical(|ui| {
                                // A chave base32 é segredo de longo prazo: o campo nasce mascarado.
                                ui.horizontal(|ui| {
                                    let edit = ui
                                        .add(
                                            line(&mut self.add_totp)
                                                .hint_text("base32 (opcional)")
                                                .password(!self.reveal_totp)
                                                .desired_width(field - 28.0),
                                        )
                                        .labelled_by(label.id);
                                    if ghost(ui, if self.reveal_totp { "🙈" } else { "👁" }).on_hover_text(tr("Show/hide")).clicked() {
                                        self.reveal_totp = !self.reveal_totp;
                                    }
                                    // Colar um otpauth:// aplica no próximo quadro (fora do widget):
                                    // grava só a chave e preenche nome e usuário VAZIOS (nunca
                                    // sobrescreve) — o applyTotp do Android. O link vale de qualquer
                                    // posição do campo: colar sobre uma chave antiga troca a chave.
                                    if edit.changed() {
                                        self.totp_warn = None;
                                        self.totp_pending = None;
                                        if let Some(link) = otpauth_tail(&self.add_totp) {
                                            self.totp_pending = Some(link.to_string());
                                        }
                                    }
                                });
                                // Abaixo do campo: o motivo do erro, ou o código atual com contagem.
                                let texto = self.add_totp.trim().to_string();
                                if !texto.is_empty() {
                                    match otpauth::parse(&texto) {
                                        otpauth::ParseResult::Ok(p) => {
                                            if let Ok(code) = totp::now(&p.secret) {
                                                let (a, b) = code.code.split_at(3);
                                                let preview = tr_fmt("current code: {code}", &[("code", &format!("{a} {b}"))]);
                                                ui.label(
                                                    egui::RichText::new(format!("{} · {}{}", preview, code.seconds_remaining, tr("s")))
                                                        .monospace()
                                                        .strong(),
                                                );
                                                // A contagem precisa de quadros para andar.
                                                ui.ctx().request_repaint_after(std::time::Duration::from_millis(500));
                                            }
                                            if let Some(warn) = &self.totp_warn {
                                                ui.label(egui::RichText::new(warn).small().color(egui::Color32::from_rgb(230, 130, 40)));
                                            }
                                        }
                                        otpauth::ParseResult::Invalid(reason) => {
                                            if let Some(msg) = totp_error_text(reason) {
                                                ui.label(egui::RichText::new(msg).small().color(egui::Color32::LIGHT_RED));
                                            }
                                        }
                                    }
                                }
                                // QR: de um arquivo escolhido ou da imagem na área de transferência.
                                ui.horizontal_wrapped(|ui| {
                                    if ghost(ui, tr("Read QR from image…")).clicked() {
                                        ask_qr_file = true;
                                    }
                                    if ghost(ui, tr("Paste image")).clicked() {
                                        paste_qr_image = true;
                                    }
                                });
                            });
                            ui.end_row();
                        }
                        vault::ItemKind::Card => {
                            let label = ui.label(tr("Cardholder"));
                            ui.add(line(&mut self.add_cardholder).desired_width(field)).labelled_by(label.id);
                            ui.end_row();

                            // Bandeira: a detecção pelo número só preenche campo vazio ou preenchido
                            // por ela mesma; digitada pela pessoa, fica.
                            let label = ui.label(tr("Brand"));
                            let edit = ui
                                .add(line(&mut self.add_brand).hint_text(tr("Detected from the number; type to change it.")).desired_width(field))
                                .labelled_by(label.id);
                            if edit.changed() {
                                self.brand_auto = self.add_brand.trim().is_empty();
                            }
                            ui.end_row();

                            // Número: o campo mostra blocos de 4 (Amex 4-6-5) — só exibição, o cofre
                            // guarda só os dígitos (o core normaliza na gravação). O aviso de Luhn
                            // aparece com 13+ dígitos e NÃO bloqueia o salvar.
                            let label = ui.label(tr("Number"));
                            ui.vertical(|ui| {
                                let edit = ui.add(line(&mut self.add_number).desired_width(field)).labelled_by(label.id);
                                if edit.changed() {
                                    let cru = self.add_number.chars().count();
                                    self.add_number = card::group(&self.add_number);
                                    cursor_do_fim(ui.ctx(), edit.id, cru, self.add_number.chars().count());
                                    if self.brand_auto || self.add_brand.trim().is_empty() {
                                        self.add_brand = card::brand_of(&self.add_number).map_or_else(String::new, |b| b.label().to_string());
                                        self.brand_auto = true;
                                    }
                                }
                                if card::luhn_warning(&self.add_number) {
                                    ui.label(
                                        egui::RichText::new(tr("This number fails the card check (Luhn). Check for a typo; you can still save."))
                                            .small()
                                            .color(egui::Color32::from_rgb(230, 130, 40)),
                                    );
                                }
                            });
                            ui.end_row();

                            let label = ui.label(tr("Expiry (MM/YY)"));
                            let edit = ui.add(line(&mut self.add_exp).desired_width(field)).labelled_by(label.id);
                            if edit.changed() {
                                let cru = self.add_exp.chars().count();
                                self.add_exp = card::expiry(&self.add_exp);
                                cursor_do_fim(ui.ctx(), edit.id, cru, self.add_exp.chars().count());
                            }
                            ui.end_row();

                            // CVV oculto, como a senha (👈 revela; o campo volta a ocultar ao fechar).
                            let label = ui.label(tr("Security code"));
                            ui.horizontal(|ui| {
                                let edit =
                                    ui.add(line(&mut self.add_code).password(!self.reveal_code).desired_width(field - 28.0)).labelled_by(label.id);
                                if edit.changed() {
                                    self.add_code = card::cvv(&self.add_code);
                                }
                                if ghost(ui, if self.reveal_code { "🙈" } else { "👁" }).on_hover_text(tr("Show/hide")).clicked() {
                                    self.reveal_code = !self.reveal_code;
                                }
                            });
                            ui.end_row();
                        }
                        vault::ItemKind::Identity => {
                            let label = ui.label(tr("First name"));
                            ui.add(line(&mut self.add_first).desired_width(field)).labelled_by(label.id);
                            ui.end_row();

                            let label = ui.label(tr("Last name"));
                            ui.add(line(&mut self.add_last).desired_width(field)).labelled_by(label.id);
                            ui.end_row();

                            let label = ui.label(tr("E-mail"));
                            ui.add(line(&mut self.add_email).desired_width(field)).labelled_by(label.id);
                            ui.end_row();

                            let label = ui.label(tr("Phone"));
                            ui.add(line(&mut self.add_phone).desired_width(field)).labelled_by(label.id);
                            ui.end_row();

                            let label = ui.label(tr("Address"));
                            ui.add(block(&mut self.add_address).desired_width(field).desired_rows(2)).labelled_by(label.id);
                            ui.end_row();
                        }
                        vault::ItemKind::Note => {}
                    }

                    // Nota: o texto É o item (label e altura próprios); nos outros tipos é complemento.
                    let notes_label = if kind == vault::ItemKind::Note { tr("Note") } else { tr("Notes") };
                    let notes_rows = if kind == vault::ItemKind::Note { 6 } else { 3 };
                    let label = ui.label(notes_label);
                    ui.add(block(&mut self.add_notes).desired_width(field).desired_rows(notes_rows)).labelled_by(label.id);
                    ui.end_row();

                    // onda 24 — pasta do item: "Sem pasta" + existentes.
                    // Pastas owned antes da closure (nenhum borrow de raw).
                    let label = ui.label(tr("Folder"));
                    let pastas = self.raw.as_ref().map(vault::folders).unwrap_or_default();
                    let pasta_sel = pastas
                        .iter()
                        .find(|p| Some(&p.id) == self.add_folder_id.as_ref())
                        .map(|p| p.name.clone())
                        .unwrap_or_else(|| tr("No folder").to_string());
                    egui::ComboBox::from_id_salt("form-pasta")
                        .width(field)
                        .selected_text(pasta_sel)
                        .show_ui(ui, |ui| {
                            ui.selectable_value(&mut self.add_folder_id, None, tr("No folder"));
                            for p in &pastas {
                                ui.selectable_value(&mut self.add_folder_id, Some(p.id.clone()), &p.name);
                            }
                        })
                        .response
                        .labelled_by(label.id);
                    ui.end_row();

                    // Anexos já na criação: os arquivos ficam na lista e entram no item ao salvar.
                    ui.label(tr("Attachments"));
                    ui.vertical(|ui| {
                        let mut remove = None;
                        for (i, a) in self.form_attachments.iter().enumerate() {
                            ui.horizontal(|ui| {
                                ui.label(format!("{} — {}", a.name, human_size(a.size)));
                                if craft_icons::ui::button(ui, Icon::Delete, tr("Delete attachment")).clicked() {
                                    remove = Some(i);
                                }
                            });
                        }
                        if let Some(i) = remove {
                            self.form_attachments.remove(i);
                        }
                        if pill(ui, tr("Add attachment"), false).clicked() {
                            add_file = true;
                        }
                    });
                    ui.end_row();
                });
                // Atalhos de documento da identidade (abaixo dos campos fixos): cada um cria um campo
                // personalizado com o nome já posto e o valor livre; "Documento (outro)" deixa o nome
                // vazio para a pessoa escrever (os chips do `DocumentShortcuts` do Android).
                if kind == vault::ItemKind::Identity {
                    ui.add_space(8.0);
                    ui.label(egui::RichText::new(tr("Add a document")).small().weak());
                    ui.add_space(4.0);
                    ui.horizontal_wrapped(|ui| {
                        for nome in [tr("CPF"), tr("RG"), tr("CNH"), tr("Date of birth"), tr("Passport")] {
                            if ghost(ui, nome).clicked() {
                                add_doc = Some(nome.to_string());
                            }
                        }
                        if ghost(ui, tr("Document (other)")).clicked() {
                            add_doc = Some(String::new());
                        }
                    });
                }
                // Força ao vivo (zxcvbn) — só no login (o único tipo com senha) e quando há uma.
                if kind == vault::ItemKind::Login && !self.add_pass.is_empty() {
                    let strength = generator::password_strength(&self.add_pass, &[self.add_name.as_str()]);
                    ui.add_space(8.0);
                    ui.add(
                        egui::ProgressBar::new((f32::from(strength.score) + 1.0) / 5.0)
                            .desired_width(280.0)
                            .fill(strength_color(strength.score))
                            .text(strength_label(strength.score)),
                    )
                    .on_hover_text(tr_fmt("zxcvbn: 10^{guesses} guesses", &[("guesses", &format!("{:.1}", strength.guesses_log10))]));
                }

                // ── campos personalizados (todos os tipos) ─────────────────────
                ui.add_space(8.0);
                ui.separator();
                ui.add_space(6.0);
                ui.label(egui::RichText::new(tr("Custom fields")).small().weak());
                ui.add_space(4.0);
                let skin = Skin::of(ui);
                for (i, f) in self.form_custom.iter_mut().enumerate() {
                    egui::Frame::NONE
                        .fill(skin.field)
                        .stroke(egui::Stroke::new(1.0_f32, skin.border))
                        .corner_radius(egui::CornerRadius::same(6))
                        .inner_margin(egui::Margin::symmetric(10, 8))
                        .show(ui, |ui| {
                            ui.set_width(ui.available_width());
                            let label = ui.label(tr("Name"));
                            ui.add(line(&mut f.name).desired_width(field)).labelled_by(label.id);
                            ui.add_space(4.0);
                            let label = ui.label(tr("Value"));
                            ui.horizontal(|ui| {
                                // Oculto: o valor sai mascarado, com 👁 para revelar e cópia direta.
                                // CPF (campo com esse nome): formata 000.000.000-00 ao digitar — outro
                                // formato colado fica como veio (`cpf::format` devolve o cru).
                                let edit = ui
                                    .add(line(&mut f.value).password(f.hidden && !f.revealed).desired_width(if f.hidden {
                                        field - 84.0
                                    } else {
                                        field
                                    }))
                                    .labelled_by(label.id);
                                if edit.changed() && !f.hidden && cpf::is_cpf_field(&f.name) {
                                    let cru = f.value.chars().count();
                                    f.value = cpf::format(&f.value);
                                    cursor_do_fim(ui.ctx(), edit.id, cru, f.value.chars().count());
                                }
                                if f.hidden {
                                    if ghost(ui, if f.revealed { "🙈" } else { "👁" }).on_hover_text(tr("Show/hide")).clicked() {
                                        f.revealed = !f.revealed;
                                    }
                                    if ghost(ui, tr("Copy")).on_hover_text(tr("Copy")).clicked() {
                                        copy_value = Some((f.name.clone(), f.value.clone()));
                                    }
                                }
                            });
                            if !f.hidden && cpf::is_cpf_field(&f.name) && cpf::warning(&f.value) {
                                ui.label(
                                    egui::RichText::new(tr("The CPF check digits don't match. Check for a typo; you can still save."))
                                        .small()
                                        .color(egui::Color32::from_rgb(230, 130, 40)),
                                );
                            }
                            ui.add_space(2.0);
                            ui.horizontal(|ui| {
                                ui.checkbox(&mut f.hidden, tr("Hidden"));
                                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                    if craft_icons::ui::button(ui, Icon::Delete, tr("Remove field")).clicked() {
                                        remove_field = Some(i);
                                    }
                                });
                            });
                        });
                    ui.add_space(6.0);
                }
                if pill(ui, tr("Add field"), false).clicked() {
                    add_field = true;
                }
            });
            // A conversão recusada aparece aqui, no próprio modal — a linha de erro da tela de trás fica
            // coberta enquanto o formulário está aberto.
            if let Some(error) = &self.form_err {
                ui.add_space(6.0);
                ui.label(egui::RichText::new(format!("{}: {error}", tr("Error"))).color(Skin::of(ui).danger));
            }
            ui.add_space(12.0);
            ui.horizontal(|ui| {
                // Atalho pro gerador (abre por cima deste modal) — só no login, que tem senha.
                if kind == vault::ItemKind::Login && pill(ui, tr("Generate"), false).clicked() {
                    self.gen_open = true;
                }
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let can_save = !self.add_name.trim().is_empty();
                    let saved = ui.add_enabled_ui(can_save, |ui| pill(ui, tr("Save"), true)).inner.clicked();
                    if saved {
                        self.save_form();
                    }
                    if pill(ui, tr("Cancel"), false).clicked() {
                        cancel = true;
                    }
                });
            });
        });
        if add_file {
            self.add_attachment_from(FORM_ATTACH, ctx);
        }
        if ask_qr_file {
            self.add_totp_from_file(ctx);
        }
        if paste_qr_image {
            self.paste_totp_image();
        }
        if let Some(i) = remove_field {
            self.form_custom.remove(i);
        }
        if add_field {
            self.form_custom.push(FormCustomField { id: String::new(), name: String::new(), value: String::new(), hidden: false, revealed: false });
        }
        if let Some(nome) = add_doc {
            self.form_custom.push(FormCustomField { id: String::new(), name: nome, value: String::new(), hidden: false, revealed: false });
        }
        if let Some((nome, valor)) = copy_value
            && clipboard::copy_secret(valor).is_ok()
        {
            self.copied(&nome);
        }
        if cancel || resp.should_close() {
            self.close_form();
        }
    }

    /// Seletor de tipo do "+": Login, Cartão, Identidade, Nota e Item livre (uma nota que abre com um
    /// campo personalizado vazio). O formulário abre já com o tipo escolhido.
    fn new_kind_modal(&mut self, ctx: &egui::Context) {
        if !self.new_kind_open {
            return;
        }
        let mut pick: Option<NewKind> = None;
        let resp = egui::Modal::new(egui::Id::unique("novo-tipo")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            dialog_width(ui, 300.0);
            ui.heading(tr("New item"));
            ui.add_space(10.0);
            for pick_kind in [NewKind::Login, NewKind::Card, NewKind::Identity, NewKind::Note, NewKind::FreeItem] {
                if ui.button(format!("{}  {}", pick_kind.icon(), pick_kind.label())).clicked() {
                    pick = Some(pick_kind);
                }
                ui.add_space(2.0);
            }
        });
        if let Some(pick) = pick {
            self.new_kind_open = false;
            self.open_add_form(pick);
        } else if resp.should_close() {
            self.new_kind_open = false;
        }
    }

    /// Confirmação da exclusão (vai pra lixeira, mas não sem querer).
    fn delete_modal(&mut self, ctx: &egui::Context) {
        let Some((id, name)) = self.delete_ask.clone() else {
            return;
        };
        let mut confirm = false;
        let mut cancel = false;
        let resp = egui::Modal::new(egui::Id::unique("delete")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            dialog_width(ui, 320.0);
            ui.heading(tr("Delete"));
            ui.add_space(8.0);
            ui.strong(&name);
            ui.label(egui::RichText::new(tr("The item goes to the Trash, where you can restore it.")).weak());
            ui.add_space(12.0);
            ui.horizontal(|ui| {
                if pill_danger(ui, tr("Confirm")).clicked() {
                    confirm = true;
                }
                if pill(ui, tr("Cancel"), false).clicked() {
                    cancel = true;
                }
            });
        });
        if confirm {
            self.delete_item(&id);
            self.delete_ask = None;
        } else if cancel || resp.should_close() {
            self.delete_ask = None;
        }
    }

    /// Importação (KeePass/Bitwarden/CSV) — senha do arquivo com 👁.
    fn import_modal(&mut self, ctx: &egui::Context) {
        if !self.import_open {
            return;
        }
        let busy = self.job_label.is_some();
        let mut cancel = false;
        let mut run = false;
        let resp = egui::Modal::new(egui::Id::unique("import")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            dialog_width(ui, 380.0);
            ui.heading(tr("Import"));
            ui.add_space(8.0);
            // Duas linhas: os três rótulos juntos estouram a largura.
            ui.horizontal(|ui| {
                ui.selectable_value(&mut self.import_kind, ImportKind::Kdbx, tr("KeePass (.kdbx)"));
                ui.selectable_value(&mut self.import_kind, ImportKind::Bitwarden, tr("Bitwarden (encrypted export)"));
            });
            ui.horizontal(|ui| {
                ui.selectable_value(&mut self.import_kind, ImportKind::Csv, tr("CSV (Chrome/Edge/LastPass/1Password)"));
            });
            ui.add_space(10.0);
            let field = (ui.available_width() - 150.0).clamp(120.0, 214.0);
            egui::Grid::new("import-grid").num_columns(2).spacing([10.0, 8.0]).show(ui, |ui| {
                let label = ui.label(tr("File"));
                ui.horizontal(|ui| {
                    ui.add(line(&mut self.import_path).hint_text("C:\\...\\export").desired_width(field)).labelled_by(label.id);
                    if ui.button("…").on_hover_text(tr("Choose file…")).clicked() {
                        // Seletor nativo (src/pickers.rs); resposta num frame posterior.
                        let kind = self.import_kind;
                        let dialog = match kind {
                            ImportKind::Kdbx => rfd::AsyncFileDialog::new().add_filter("KeePass (.kdbx)", &["kdbx"]),
                            ImportKind::Bitwarden => rfd::AsyncFileDialog::new().add_filter("Bitwarden (.json)", &["json"]),
                            ImportKind::Csv => rfd::AsyncFileDialog::new().add_filter("CSV (.csv)", &["csv"]),
                        };
                        let _ = self.pickers.ask(
                            ui.ctx(),
                            pickers::Ask::File(dialog),
                            Box::new(|app, paths| {
                                if let Some(p) = paths.first() {
                                    app.import_path = p.display().to_string();
                                }
                            }),
                        );
                    }
                });
                ui.end_row();
                // CSV é texto claro; kdbx e Bitwarden cifrado
                // precisam da senha do arquivo.
                if self.import_kind != ImportKind::Csv {
                    let label = ui.label(tr("File password"));
                    ui.horizontal(|ui| {
                        ui.add(line(&mut self.import_password).password(!self.reveal_import).desired_width(field)).labelled_by(label.id);
                        if ghost(ui, if self.reveal_import { "🙈" } else { "👁" }).on_hover_text(tr("Show/hide")).clicked() {
                            self.reveal_import = !self.reveal_import;
                        }
                    });
                    ui.end_row();
                }
            });
            ui.add_space(12.0);
            ui.horizontal(|ui| {
                let ready = !busy && !self.import_path.trim().is_empty();
                if ui.add_enabled_ui(ready, |ui| pill(ui, tr("Import"), true)).inner.clicked() {
                    run = true;
                }
                if pill(ui, tr("Cancel"), false).clicked() {
                    cancel = true;
                }
            });
        });
        if run {
            self.run_import(ctx);
        } else if cancel || resp.should_close() {
            self.import_open = false;
            self.import_path.clear();
            self.import_password.clear();
            self.reveal_import = false;
        }
    }

    /// Relatório da importação (importados / pulados card-identity).
    fn report_modal(&mut self, ctx: &egui::Context) {
        let Some((imported, skipped)) = self.import_report else {
            return;
        };
        let mut close = false;
        let resp = egui::Modal::new(egui::Id::unique("import-report")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            dialog_width(ui, 300.0);
            ui.heading(tr("Import"));
            ui.add_space(8.0);
            ui.label(tr_n(imported as u64, "{n} item imported", "{n} items imported"));
            if skipped > 0 {
                ui.label(
                    egui::RichText::new(tr_n(
                        skipped as u64,
                        "{n} card/identity skipped — Local Keys keeps logins and notes",
                        "{n} cards/identities skipped — Local Keys keeps logins and notes",
                    ))
                    .small()
                    .weak(),
                );
            }
            ui.add_space(12.0);
            if pill(ui, tr("Close"), true).clicked() {
                close = true;
            }
        });
        if close || resp.should_close() {
            self.import_report = None;
        }
    }

    /// Gerador de senha/passphrase + medidor de força.
    fn generator_modal(&mut self, ctx: &egui::Context) {
        if !self.gen_open {
            return;
        }
        let mut close = false;
        let mut generate = false;
        let resp = egui::Modal::new(egui::Id::unique("generator")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            dialog_width(ui, 360.0);
            ui.heading(tr("Generator"));
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                ui.selectable_value(&mut self.gen_passphrase, false, tr("Password"));
                ui.selectable_value(&mut self.gen_passphrase, true, tr("Passphrase"));
            });
            ui.add_space(8.0);
            if self.gen_passphrase {
                ui.add(egui::Slider::new(&mut self.gen_pp.words, 3..=12).text(tr("Words")));
                ui.horizontal(|ui| {
                    let label = ui.label(tr("Separator"));
                    ui.add(line(&mut self.gen_pp.separator).desired_width(56.0)).labelled_by(label.id);
                });
                ui.checkbox(&mut self.gen_pp.capitalize, tr("Capitalize"));
                ui.checkbox(&mut self.gen_pp.include_number, tr("Include number"));
            } else {
                ui.add(egui::Slider::new(&mut self.gen_pw.length, 4..=128).text(tr("Length")));
                ui.checkbox(&mut self.gen_pw.lowercase, tr("Lowercase"));
                ui.checkbox(&mut self.gen_pw.uppercase, tr("Uppercase"));
                ui.checkbox(&mut self.gen_pw.digits, tr("Digits"));
                ui.checkbox(&mut self.gen_pw.symbols, tr("Symbols"));
            }
            ui.add_space(10.0);
            if pill(ui, tr("Generate"), true).clicked() {
                generate = true;
            }
            if !self.gen_result.is_empty() {
                ui.add_space(10.0);
                ui.add(line(&mut self.gen_result).desired_width(320.0).font(egui::TextStyle::Monospace));
                ui.horizontal(|ui| {
                    if ghost(ui, tr("Copy")).clicked() && clipboard::copy_secret(self.gen_result.clone()).is_ok() {
                        self.copied(tr("Generator"));
                    }
                    // "Usar" só faz sentido com o form aberto — é ele que
                    // recebe a senha gerada.
                    if self.form_open && ghost(ui, tr("Use")).clicked() {
                        self.add_pass = self.gen_result.clone();
                        self.gen_result.clear();
                        close = true;
                    }
                });
            }
            ui.add_space(12.0);
            if pill(ui, tr("Close"), false).clicked() {
                close = true;
            }
        });
        if generate {
            let made = if self.gen_passphrase { generator::generate_passphrase(&self.gen_pp) } else { generator::generate_password(&self.gen_pw) };
            match made {
                Ok(s) => {
                    self.gen_result = s;
                    self.err = None;
                }
                Err(e) => self.err = Some(e),
            }
        }
        if close || resp.should_close() {
            self.gen_open = false;
            self.gen_result.clear();
        }
    }

    /// Troca de senha mestra (Argon2id 2× — roda na thread do job).
    fn change_modal(&mut self, ctx: &egui::Context) {
        if !self.change_open {
            return;
        }
        let busy = self.job_label.is_some();
        let mut cancel = false;
        let mut confirm = false;
        let resp = egui::Modal::new(egui::Id::unique("change-master")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            dialog_width(ui, 360.0);
            ui.heading(tr("Change master password"));
            ui.add_space(10.0);
            let field = (ui.available_width() - 130.0).clamp(120.0, 220.0);
            egui::Grid::new("change-grid").num_columns(2).spacing([10.0, 8.0]).show(ui, |ui| {
                let label = ui.label(tr("Current password"));
                ui.add(line(&mut self.chg_old).password(true).desired_width(field)).labelled_by(label.id);
                ui.end_row();
                let label = ui.label(tr("New password"));
                ui.add(line(&mut self.chg_new).password(true).desired_width(field)).labelled_by(label.id);
                ui.end_row();
                let label = ui.label(tr("Confirm new password"));
                ui.add(line(&mut self.chg_confirm).password(true).desired_width(field)).labelled_by(label.id);
                ui.end_row();
            });
            ui.add_space(12.0);
            ui.horizontal(|ui| {
                let ready = !busy && !self.chg_new.is_empty();
                if ui.add_enabled_ui(ready, |ui| pill(ui, tr("Confirm"), true)).inner.clicked() {
                    confirm = true;
                }
                if pill(ui, tr("Cancel"), false).clicked() {
                    cancel = true;
                }
            });
        });
        if confirm {
            self.change_master(ctx);
        } else if cancel || resp.should_close() {
            self.change_open = false;
            self.chg_old.clear();
            self.chg_new.clear();
            self.chg_confirm.clear();
        }
    }

    /// Anexos do item: listar/salvar os existentes e anexar um novo.
    fn attachments_modal(&mut self, ctx: &egui::Context) {
        let Some(item_id) = self.attach_item.clone() else {
            return;
        };
        let ready = self.job_label.is_none();
        let item_name = self.items.iter().find(|i| i.id == item_id).map(|i| i.name.clone()).unwrap_or_default();
        // Owned antes do UI: nenhum borrow de self.raw na closure.
        // Separa vivos (para exibir) de deletados (lixeira).
        let mut atts: Vec<(String, String, u64)> = Vec::new();
        let mut trashed: Vec<(String, String, u64)> = Vec::new();
        if let Some(list) = self
            .raw
            .as_ref()
            .and_then(|r| r.get("items"))
            .and_then(|i| i.as_array())
            .and_then(|arr| arr.iter().find(|it| it.get("id").and_then(|v| v.as_str()) == Some(item_id.as_str())))
            .and_then(|item| item.get("attachments"))
            .and_then(|a| a.as_array())
        {
            for a in list {
                let id = a.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string();
                let name = a.get("name").and_then(|v| v.as_str()).unwrap_or("anexo").to_string();
                let size = a.get("size").and_then(|v| v.as_u64()).unwrap_or(0);
                let deleted = a.get("deletedAt").map(|v| !v.is_null()).unwrap_or(false);
                if deleted {
                    trashed.push((id, name, size));
                } else {
                    atts.push((id, name, size));
                }
            }
        }
        let mut close = false;
        let mut add = false;
        let mut save: Option<String> = None;
        let mut del: Option<String> = None;
        let mut restore: Option<String> = None;
        let mut del_forever: Option<String> = None;
        let resp = egui::Modal::new(egui::Id::unique("attachments")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            dialog_width(ui, 380.0);
            // Título e nomes quebram linha: sem isso o diálogo fica mais largo que a janela e
            // desloca para a esquerda, cortando a borda.
            ui.add(egui::Label::new(egui::RichText::new(format!("{} — {item_name}", tr("Attachments"))).text_style(egui::TextStyle::Heading)).wrap());
            ui.add_space(8.0);
            if atts.is_empty() {
                ui.label(egui::RichText::new(tr("no attachments")).weak());
            } else {
                for (att_id, name, size) in &atts {
                    let size_text = human_size(*size as usize);
                    let save_label = tr("Save");
                    // Tamanho + Salvar + excluir, com o espaço entre eles e uma folga para o botão de ícone.
                    let actions_width = text_width(ui, &size_text, egui::TextStyle::Small)
                        + text_width(ui, save_label, egui::TextStyle::Button)
                        + 28.0
                        + ui.spacing().interact_size.x
                        + ui.spacing().item_spacing.x * 3.0
                        + 8.0;
                    attachment_row(ui, name, false, actions_width, |ui| {
                        ui.label(egui::RichText::new(size_text).small().weak());
                        if ui.add_enabled_ui(ready, |ui| pill(ui, save_label, false)).inner.clicked() {
                            save = Some(att_id.clone());
                        }
                        // onda 24 — excluir anexo: sem confirmação (o
                        // arquivo original continua no disco), mas com o
                        // mesmo gate do resto (nenhum persist durante job).
                        if ui.add_enabled_ui(ready, |ui| craft_icons::ui::button(ui, Icon::Delete, tr("Delete attachment"))).inner.clicked() {
                            del = Some(att_id.clone());
                        }
                    });
                    ui.add_space(4.0);
                }
            }
            // Lixeira de anexos — anexos excluídos ficam aqui até serem
            // restaurados ou excluídos de vez (padrão do item, onda 23).
            if !trashed.is_empty() {
                ui.add_space(8.0);
                ui.separator();
                ui.add_space(4.0);
                ui.label(egui::RichText::new(format!("🗑 {}", tr("Trash"))).small().weak());
                ui.add_space(4.0);
                for (att_id, name, size) in &trashed {
                    let size_text = human_size(*size as usize);
                    let restore_label = tr("Restore");
                    // Tamanho + Restaurar + excluir para sempre, com o espaço entre eles e uma folga.
                    let actions_width = text_width(ui, &size_text, egui::TextStyle::Small)
                        + text_width(ui, restore_label, egui::TextStyle::Body)
                        + ui.spacing().interact_size.x * 2.0
                        + ui.spacing().item_spacing.x * 3.0
                        + 8.0;
                    attachment_row(ui, name, true, actions_width, |ui| {
                        ui.label(egui::RichText::new(size_text).small().weak());
                        if ui.add_enabled_ui(ready, |ui| ghost(ui, restore_label)).inner.clicked() {
                            restore = Some(att_id.clone());
                        }
                        if ui.add_enabled_ui(ready, |ui| craft_icons::ui::button(ui, Icon::Delete, tr("Delete forever"))).inner.clicked() {
                            del_forever = Some(att_id.clone());
                        }
                    });
                }
            }
            ui.add_space(10.0);
            ui.separator();
            ui.add_space(6.0);
            // No Linux o(s) caminho(s) são digitados (sem gtk no AppImage),
            // MULTI: separados por `;` — o Windows tem picker nativo.
            #[cfg(not(windows))]
            ui.horizontal(|ui| {
                ui.label(tr("File"));
                ui.add(line(&mut self.attach_path).hint_text("C:\\...\\arquivo1;arquivo2").desired_width(ui.available_width().max(120.0)));
            });
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                if ui.add_enabled_ui(ready, |ui| pill(ui, tr("Add attachment"), true)).inner.clicked() {
                    add = true;
                }
                if pill(ui, tr("Close"), false).clicked() {
                    close = true;
                }
            });
        });
        if let Some(att_id) = save {
            self.save_attachment(&item_id, &att_id, ctx);
        } else if let Some(att_id) = del {
            // onda 24 — excluir anexo (padrão clone → mutação → persist).
            let Some(mut next) = self.raw.clone() else {
                return;
            };
            if vault::delete_attachment(&mut next, &item_id, &att_id) {
                self.persist(next);
            }
        } else if let Some(att_id) = restore {
            let Some(mut next) = self.raw.clone() else {
                return;
            };
            if vault::restore_attachment(&mut next, &item_id, &att_id) {
                self.persist(next);
            }
        } else if let Some(att_id) = del_forever {
            let Some(mut next) = self.raw.clone() else {
                return;
            };
            if vault::delete_attachment_forever(&mut next, &item_id, &att_id) {
                self.persist(next);
            }
        } else if add {
            self.add_attachment_from(&item_id, ctx);
        } else if close || resp.should_close() {
            self.attach_item = None;
            self.attach_path.clear();
        }
    }

    /// "Nova pasta": mini modal com campo de nome + criar (`vault::add_folder`).
    fn new_folder_modal(&mut self, ctx: &egui::Context) {
        if !self.folder_new_open {
            return;
        }
        let busy = self.job_label.is_some();
        let mut cria = false;
        let mut cancela = false;
        let resp = egui::Modal::new(egui::Id::unique("nova-pasta")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            dialog_width(ui, 320.0);
            ui.heading(tr("New folder"));
            ui.add_space(8.0);
            ui.add(line(&mut self.folder_new).hint_text(tr("Folder name")).desired_width(290.0));
            ui.add_space(12.0);
            ui.horizontal(|ui| {
                let pronto = !busy && !self.folder_new.trim().is_empty();
                if ui.add_enabled_ui(pronto, |ui| pill(ui, tr("Add"), true)).inner.clicked() {
                    cria = true;
                }
                if pill(ui, tr("Cancel"), false).clicked() {
                    cancela = true;
                }
            });
        });
        if cria {
            self.create_folder();
            self.folder_new_open = false;
        } else if cancela || resp.should_close() {
            self.folder_new_open = false;
            self.folder_new.clear();
        }
    }

    /// Cria a pasta do buffer `folder_new` (padrão clone → mutação → persist).
    fn create_folder(&mut self) {
        let nome = self.folder_new.trim().to_string();
        if nome.is_empty() {
            return;
        }
        let Some(mut next) = self.raw.clone() else {
            return;
        };
        vault::add_folder(&mut next, &nome);
        if self.persist(next) {
            self.folder_new.clear();
        }
    }

    /// Gerenciar pastas: criar, renomear (inline) e excluir. Excluir NÃO pede
    /// confirmação — os itens ficam no cofre, só perdem a pasta (o
    /// `delete_folder` limpa o `folderId` deles).
    fn folders_modal(&mut self, ctx: &egui::Context) {
        if !self.folders_open {
            return;
        }
        let busy = self.job_label.is_some();
        // Owned antes do UI — nenhuma closure pega emprestado o `self.raw`.
        let pastas = self.raw.as_ref().map(vault::folders).unwrap_or_default();
        let mut cria = false;
        let mut inicia_rename: Option<String> = None;
        let mut confirma_rename: Option<String> = None;
        let mut cancela_rename = false;
        let mut deleta: Option<String> = None;
        let mut fecha = false;
        let resp = egui::Modal::new(egui::Id::unique("pastas")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            dialog_width(ui, 340.0);
            ui.heading(tr("Folders"));
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                ui.add(line(&mut self.folder_new).hint_text(tr("Folder name")).desired_width(234.0));
                if ui.add_enabled_ui(!busy && !self.folder_new.trim().is_empty(), |ui| pill(ui, tr("Add"), true)).inner.clicked() {
                    cria = true;
                }
            });
            ui.add_space(6.0);
            if pastas.is_empty() {
                ui.label(egui::RichText::new(tr("No folder")).weak());
            } else {
                egui::ScrollArea::vertical().max_height(240.0).show(ui, |ui| {
                    for p in &pastas {
                        ui.horizontal(|ui| {
                            if Some(&p.id) == self.folder_edit_id.as_ref() {
                                // Renomear: campo inline + confirmar/cancelar.
                                ui.add(line(&mut self.folder_edit_name).desired_width(196.0));
                                if ui
                                    .add_enabled_ui(!busy && !self.folder_edit_name.trim().is_empty(), |ui| pill(ui, tr("Save"), true))
                                    .inner
                                    .clicked()
                                {
                                    confirma_rename = Some(p.id.clone());
                                }
                                if craft_icons::ui::close_button(ui, tr("Cancel")).clicked() {
                                    cancela_rename = true;
                                }
                            } else {
                                ui.label(&p.name);
                                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                    if ui.add_enabled_ui(!busy, |ui| craft_icons::ui::button(ui, Icon::Delete, tr("Delete folder"))).inner.clicked() {
                                        deleta = Some(p.id.clone());
                                    }
                                    if ui.add_enabled_ui(!busy, |ui| ghost(ui, "✏")).inner.on_hover_text(tr("Rename folder")).clicked() {
                                        inicia_rename = Some(p.id.clone());
                                    }
                                });
                            }
                        });
                        ui.separator();
                    }
                });
            }
            ui.add_space(10.0);
            if pill(ui, tr("Close"), false).clicked() {
                fecha = true;
            }
        });
        // Ações fora da closure, uma por vez (o usuário clicou num só).
        if cria {
            self.create_folder();
        }
        if let Some(id) = inicia_rename {
            self.folder_edit_name = pastas.iter().find(|p| p.id == id).map(|p| p.name.clone()).unwrap_or_default();
            self.folder_edit_id = Some(id);
        }
        if cancela_rename {
            self.folder_edit_id = None;
            self.folder_edit_name.clear();
        }
        if let Some(id) = confirma_rename {
            let nome = self.folder_edit_name.trim().to_string();
            let Some(mut next) = self.raw.clone() else {
                return;
            };
            if vault::rename_folder(&mut next, &id, &nome) && self.persist(next) {
                self.folder_edit_id = None;
                self.folder_edit_name.clear();
            }
        }
        if let Some(id) = deleta {
            let Some(mut next) = self.raw.clone() else {
                return;
            };
            if vault::delete_folder(&mut next, &id) {
                // Se a pasta excluída era a do filtro, volta pra "todas" (e a escolha é lembrada).
                if self.folder_filter.as_deref() == Some(id.as_str()) {
                    self.set_folder_filter(None);
                }
                self.folder_edit_id = None;
                self.folder_edit_name.clear();
                self.persist(next);
            }
        }
        if fecha || resp.should_close() {
            self.folders_open = false;
            self.folder_edit_id = None;
            self.folder_edit_name.clear();
        }
    }

    /// Lixeira: lista os itens com `deletedAt`, com Restaurar e Excluir de vez.
    ///
    /// O oficial não tem "esvaziar lixeira": o que existe é `deleteForever`
    /// por item (com `confirm()`), então é isso que o app oferece — sem
    /// inventar ação em massa.
    fn trash_modal(&mut self, ctx: &egui::Context) {
        if !self.trash_open {
            return;
        }
        let rows = self.raw.as_ref().map(vault::trashed_items).unwrap_or_default();
        let mut close = false;
        let mut restore: Option<String> = None;
        let mut purge: Option<String> = None;
        let mut ask: Option<String> = None;
        let keep_ask = self.purge_ask.clone();
        let resp = egui::Modal::new(egui::Id::unique("trash")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            dialog_width(ui, 420.0);
            ui.heading(tr("Trash"));
            ui.add_space(8.0);
            if rows.is_empty() {
                ui.label(egui::RichText::new(tr("trash is empty")).weak());
            } else {
                egui::ScrollArea::vertical().max_height(280.0).show(ui, |ui| {
                    for row in &rows {
                        ui.horizontal(|ui| {
                            ui.vertical(|ui| {
                                ui.strong(&row.name);
                                ui.label(egui::RichText::new(date_from_ms(row.deleted_at)).small().weak());
                            });
                            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                if keep_ask.as_deref() == Some(row.id.as_str()) {
                                    if pill_danger(ui, tr("Confirm")).clicked() {
                                        purge = Some(row.id.clone());
                                    }
                                    if pill(ui, tr("Cancel"), false).clicked() {
                                        ask = Some(String::new());
                                    }
                                } else {
                                    if ghost(ui, tr("Delete forever")).clicked() {
                                        ask = Some(row.id.clone());
                                    }
                                    if ghost(ui, tr("Restore")).clicked() {
                                        restore = Some(row.id.clone());
                                    }
                                }
                            });
                        });
                        ui.separator();
                    }
                });
            }
            ui.add_space(10.0);
            if pill(ui, tr("Close"), false).clicked() {
                close = true;
            }
        });
        if let Some(id) = restore {
            self.restore_item(&id);
        } else if let Some(id) = purge {
            self.purge_item(&id);
        } else if let Some(id) = ask {
            // "" = cancelar o "excluir de vez" deste item.
            self.purge_ask = if id.is_empty() { None } else { Some(id) };
        }
        if close || resp.should_close() {
            self.trash_open = false;
            self.purge_ask = None;
        }
    }

    /// Seletor nativo "salvar como" para a cópia sincronizada (nome do cofre atual já sugerido).
    fn pick_sync_copy_target(&mut self, ctx: &egui::Context) {
        let nome =
            std::path::Path::new(self.path.trim()).file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| "cofre.tkeys".into());
        let dialog = rfd::AsyncFileDialog::new().add_filter("Cofre LocalKeys (.tkeys)", &["tkeys"]).set_file_name(nome);
        let _ = self.pickers.ask(
            ctx,
            pickers::Ask::Save(dialog),
            Box::new(|app, paths| {
                if let Some(p) = paths.first() {
                    app.carry_vault_to(p);
                }
            }),
        );
    }

    /// Seletor nativo da outra cópia para a sincronização.
    fn pick_sync_other(&mut self, ctx: &egui::Context) {
        let dialog = rfd::AsyncFileDialog::new().add_filter("Cofre LocalKeys (.tkeys)", &["tkeys"]);
        let _ = self.pickers.ask(
            ctx,
            pickers::Ask::File(dialog),
            Box::new(|app, paths| {
                if let Some(p) = paths.first() {
                    app.sync_with_other(&p.display().to_string());
                }
            }),
        );
    }

    /// Resumo da sincronização ANTES de gravar (adicionados, atualizados, lixeira, restaurados,
    /// empates, pastas — e o que vai para a outra cópia). Nada é gravado sem o botão.
    fn sync_modal(&mut self, ctx: &egui::Context) {
        if !self.sync_open {
            return;
        }
        let Some(plan) = self.sync_plan.clone() else {
            self.sync_open = false;
            return;
        };
        let s = plan.summary;
        let mut grava = false;
        let mut fecha = false;
        let resp = egui::Modal::new(egui::Id::unique("sync")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            dialog_width(ui, 400.0);
            ui.heading(tr("Sync with another copy"));
            ui.add_space(4.0);
            ui.add(egui::Label::new(egui::RichText::new(&plan.other_path).small().weak()).truncate());
            ui.add_space(8.0);
            if s.nothing_changes() {
                ui.label(egui::RichText::new(tr("Nothing to sync: the copies are already identical.")).weak());
            } else {
                // Do ponto de vista do cofre atual…
                let linhas = [
                    (s.added, tr_n(s.added, "{n} item added from the other copy", "{n} items added from the other copy")),
                    (s.updated, tr_n(s.updated, "{n} item updated (the other copy was newer)", "{n} items updated (the other copy was newer)")),
                    (s.trashed, tr_n(s.trashed, "{n} item moved to the trash by the other copy", "{n} items moved to the trash by the other copy")),
                    (s.restored, tr_n(s.restored, "{n} item restored from the trash", "{n} items restored from the trash")),
                    (s.conflicts, tr_n(s.conflicts, "{n} tie: this vault keeps its item", "{n} ties: this vault keeps its item")),
                    (s.folders_added, tr_n(s.folders_added, "{n} folder added", "{n} folders added")),
                ];
                for (n, texto) in linhas {
                    if n > 0 {
                        ui.label(texto);
                    }
                }
                ui.add_space(6.0);
                ui.separator();
                ui.add_space(6.0);
                // …e o que a outra cópia ganha.
                let outras = [
                    (s.to_other, tr_n(s.to_other, "{n} item goes to the other copy", "{n} items go to the other copy")),
                    (s.folders_to_other, tr_n(s.folders_to_other, "{n} folder goes to the other copy", "{n} folders go to the other copy")),
                ];
                for (n, texto) in outras {
                    if n > 0 {
                        ui.label(egui::RichText::new(texto).weak());
                    }
                }
                ui.add_space(6.0);
                ui.label(egui::RichText::new(tr("After saving, both copies are identical.")).small().weak());
            }
            ui.add_space(12.0);
            ui.horizontal(|ui| {
                if s.nothing_changes() {
                    if pill(ui, tr("Close"), true).clicked() {
                        fecha = true;
                    }
                } else {
                    if pill(ui, tr("Save to both copies"), true).clicked() {
                        grava = true;
                    }
                    if pill(ui, tr("Cancel"), false).clicked() {
                        fecha = true;
                    }
                }
            });
        });
        if grava {
            self.sync_apply();
        } else if fecha || resp.should_close() {
            self.sync_open = false;
            self.sync_plan = None;
        }
    }

    /// Diálogo da senha do OUTRO cofre (a chave da sessão não o abriu): campo oculto com 👁, erro dentro, Abrir.
    fn merge_password_modal(&mut self, ctx: &egui::Context) {
        if !self.merge_open {
            return;
        }
        let busy = self.job_label.is_some();
        let mut open = false;
        let mut cancel = false;
        let resp = egui::Modal::new(egui::Id::unique("merge-password")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            dialog_width(ui, 380.0);
            ui.heading(tr("Open the other vault"));
            ui.add_space(4.0);
            ui.add(egui::Label::new(egui::RichText::new(&self.merge_path).small().weak()).truncate());
            ui.add_space(8.0);
            let field = (ui.available_width() - 130.0).clamp(120.0, 220.0);
            ui.horizontal(|ui| {
                let label = ui.label(tr("Password"));
                ui.add(line(&mut self.merge_password).password(!self.merge_reveal).desired_width(field - 28.0)).labelled_by(label.id);
                if ghost(ui, if self.merge_reveal { "🙈" } else { "👁" }).on_hover_text(tr("Show/hide")).clicked() {
                    self.merge_reveal = !self.merge_reveal;
                }
            });
            if let Some(error) = &self.merge_error {
                ui.add_space(6.0);
                ui.label(egui::RichText::new(error).small().color(egui::Color32::LIGHT_RED));
            }
            ui.add_space(12.0);
            ui.horizontal(|ui| {
                let ready = !busy && !self.merge_password.is_empty();
                if ui.add_enabled_ui(ready, |ui| pill(ui, tr("Open"), true)).inner.clicked() {
                    open = true;
                }
                if pill(ui, tr("Cancel"), false).clicked() {
                    cancel = true;
                }
            });
        });
        if open {
            self.merge_other_open(ctx);
        } else if cancel || resp.should_close() {
            self.merge_open = false;
            drop(Zeroizing::new(std::mem::take(&mut self.merge_password)));
            self.merge_reveal = false;
            self.merge_error = None;
        }
    }

    /// Resumo da junção ANTES de gravar: itens que entram, duplicados (mesmo id), já presentes e pastas. O outro
    /// arquivo nunca é gravado; confirmar grava o cofre aberto UMA vez.
    fn merge_summary_modal(&mut self, ctx: &egui::Context) {
        let Some(plan) = self.merge_plan.clone() else {
            return;
        };
        let s = plan.summary;
        // "Nada a juntar": o resultado é o próprio cofre aberto (itens já presentes não mudam nada; uma pasta
        // "unida" que nenhum item usava também não).
        let nothing = self.raw.as_ref() == Some(&plan.merged);
        let mut merge = false;
        let mut close = false;
        let resp = egui::Modal::new(egui::Id::unique("merge-other")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            dialog_width(ui, 400.0);
            ui.heading(tr("Merge another vault"));
            ui.add_space(4.0);
            ui.add(egui::Label::new(egui::RichText::new(&plan.other_path).small().weak()).truncate());
            ui.add_space(8.0);
            if nothing {
                ui.label(egui::RichText::new(tr("Nothing to merge: this vault already has everything from the other vault.")).weak());
            } else {
                let linhas = [
                    (s.added as u64, tr_n(s.added as u64, "{n} item added from the other vault", "{n} items added from the other vault")),
                    (
                        s.collisions as u64,
                        tr_n(s.collisions as u64, "{n} item kept as a duplicate (same id)", "{n} items kept as duplicates (same id)"),
                    ),
                    (s.already_present as u64, tr_n(s.already_present as u64, "{n} item was already here", "{n} items were already here")),
                    (
                        s.folders_added as u64,
                        tr_n(s.folders_added as u64, "{n} folder added from the other vault", "{n} folders added from the other vault"),
                    ),
                    (
                        s.folders_merged as u64,
                        tr_n(
                            s.folders_merged as u64,
                            "{n} folder united with a folder of this vault",
                            "{n} folders united with folders of this vault",
                        ),
                    ),
                ];
                for (n, texto) in linhas {
                    if n > 0 {
                        ui.label(texto);
                    }
                }
                ui.add_space(6.0);
                ui.label(egui::RichText::new(tr("The other vault is only read; this vault is saved once.")).small().weak());
            }
            ui.add_space(12.0);
            ui.horizontal(|ui| {
                if nothing {
                    if pill(ui, tr("Close"), true).clicked() {
                        close = true;
                    }
                } else {
                    if pill(ui, tr("Merge"), true).clicked() {
                        merge = true;
                    }
                    if pill(ui, tr("Cancel"), false).clicked() {
                        close = true;
                    }
                }
            });
        });
        if merge {
            self.merge_apply();
        } else if close || resp.should_close() {
            self.merge_plan = None;
        }
    }

    /// Export em claro (JSON/CSV) — aviso forte antes de gravar.
    fn export_modal(&mut self, ctx: &egui::Context) {
        if !self.export_open {
            return;
        }
        let busy = self.job_label.is_some();
        let mut cancel = false;
        let mut run = false;
        let resp = egui::Modal::new(egui::Id::unique("export")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            dialog_width(ui, 420.0);
            ui.heading(tr("Export"));
            ui.add_space(8.0);
            // Aviso FORTE: o export não tem cifra nenhuma.
            egui::Frame::NONE
                .fill(egui::Color32::from_rgb(0xC0, 0x3A, 0x3A).gamma_multiply(0.22))
                .corner_radius(egui::CornerRadius::same(6))
                .inner_margin(egui::Margin::symmetric(10, 8))
                .show(ui, |ui| {
                    ui.label(
                        egui::RichText::new(tr(
                            "WARNING: the export is UNENCRYPTED — any app can read it. Use it only to migrate, then delete the file.",
                        ))
                        .color(egui::Color32::from_rgb(0xE0, 0x6C, 0x6C)),
                    );
                });
            ui.add_space(10.0);
            ui.horizontal(|ui| {
                ui.selectable_value(&mut self.export_kind, ExportKind::Json, tr("JSON (vault in the clear)"));
                ui.selectable_value(&mut self.export_kind, ExportKind::Csv, tr("CSV (spreadsheet)"));
            });
            ui.add_space(10.0);
            ui.horizontal(|ui| {
                let label = ui.label(tr("File"));
                ui.add(line(&mut self.export_path).hint_text(format!("C:\\...\\cofre.{}", self.export_kind.ext())).desired_width(230.0))
                    .labelled_by(label.id);
                if ui.button("…").on_hover_text(tr("Choose file…")).clicked() {
                    let kind = self.export_kind;
                    let dialog =
                        rfd::AsyncFileDialog::new().add_filter("Cofre em claro", &[kind.ext()]).set_file_name(format!("cofre.{}", kind.ext()));
                    let _ = self.pickers.ask(
                        ui.ctx(),
                        pickers::Ask::Save(dialog),
                        Box::new(|app, paths| {
                            if let Some(p) = paths.first() {
                                app.export_path = p.display().to_string();
                            }
                        }),
                    );
                }
            });
            ui.add_space(12.0);
            ui.horizontal(|ui| {
                let ready = !busy && !self.export_path.trim().is_empty();
                if ui.add_enabled_ui(ready, |ui| pill(ui, tr("Export"), true)).inner.clicked() {
                    run = true;
                }
                if pill(ui, tr("Cancel"), false).clicked() {
                    cancel = true;
                }
            });
        });
        if run {
            self.run_export(ctx);
        } else if cancel || resp.should_close() {
            self.export_open = false;
        }
    }

    /// Diálogo do cofre NOVO da seleção (unidade 6): o destino escolhido no "Salvar como" (só leitura — o nome do
    /// cofre é o do arquivo) e a senha pedida duas vezes. Erros de validação aparecem DENTRO do diálogo.
    fn export_vault_modal(&mut self, ctx: &egui::Context) {
        if !self.export_vault_open {
            return;
        }
        let busy = self.job_label.is_some();
        let mut create = false;
        let mut cancel = false;
        let resp = egui::Modal::new(egui::Id::unique("export-vault")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            dialog_width(ui, 420.0);
            ui.heading(tr("Export to vault"));
            ui.add_space(8.0);
            ui.add(
                egui::Label::new(tr(
                    "The selected items go to a NEW encrypted vault (.tkeys). Only the folders they use go with them; attachments travel inside.",
                ))
                .wrap(),
            );
            ui.add_space(10.0);
            ui.horizontal_wrapped(|ui| {
                ui.label(egui::RichText::new(tr("File")).strong());
                ui.add(egui::Label::new(egui::RichText::new(&self.export_vault_path).monospace()).wrap());
            });
            ui.add_space(10.0);
            let field = (ui.available_width() - 130.0).clamp(120.0, 220.0);
            egui::Grid::new("export-vault-grid").num_columns(3).spacing([10.0, 8.0]).show(ui, |ui| {
                let label = ui.label(tr("Password"));
                ui.add(line(&mut self.export_vault_pass).password(!self.export_vault_reveal).desired_width(field - 28.0)).labelled_by(label.id);
                if ghost(ui, if self.export_vault_reveal { "🙈" } else { "👁" }).on_hover_text(tr("Show/hide")).clicked() {
                    self.export_vault_reveal = !self.export_vault_reveal;
                }
                ui.end_row();
                let label = ui.label(tr("Confirm password"));
                ui.add(line(&mut self.export_vault_confirm).password(!self.export_vault_reveal).desired_width(field - 28.0)).labelled_by(label.id);
                ui.end_row();
            });
            if let Some(error) = &self.export_vault_err {
                ui.add_space(6.0);
                ui.label(egui::RichText::new(error).small().color(egui::Color32::LIGHT_RED));
            }
            ui.add_space(12.0);
            ui.horizontal(|ui| {
                let ready = !busy && !self.export_vault_path.trim().is_empty();
                if ui.add_enabled_ui(ready, |ui| pill(ui, tr("Create vault"), true)).inner.clicked() {
                    create = true;
                }
                if pill(ui, tr("Cancel"), false).clicked() {
                    cancel = true;
                }
            });
        });
        if create {
            self.run_export_vault(ctx);
        } else if cancel || resp.should_close() {
            self.export_vault_open = false;
            drop(Zeroizing::new(std::mem::take(&mut self.export_vault_pass)));
            drop(Zeroizing::new(std::mem::take(&mut self.export_vault_confirm)));
            self.export_vault_reveal = false;
            self.export_vault_err = None;
        }
    }
}

impl KeysApp {
    /// Um quadro da interface: o `eframe::App::ui` e os testes de UI (`ui_tests.rs`) chamam isto.
    fn frame(&mut self, ui: &mut egui::Ui) {
        let ctx = &ui.ctx().clone();
        // Fechar (X) minimiza pra bandeja — SÓ no Windows, onde há bandeja.
        // (No Linux o X fecha de verdade.) Sem lock ao esconder: o cofre fica
        // destrancado na memória (decisão do app — PC da própria pessoa).
        // IMPORTANTE: isto precisa rodar ANTES de qualquer processamento, senão
        // modais/jobs podem engolir o close_requested.
        #[cfg(windows)]
        if ctx.input(|i| i.viewport().close_requested()) {
            let quitting = self.quit.load(Ordering::Relaxed);
            if !quitting {
                ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
                // Fechar esconde a janela: a seleção da lista não fica esperando na memória.
                self.clear_selection();
                winctl::hide();
                return; // não processa o resto do frame
            }
        }

        // "Sair" da bandeja: encerra pelo caminho limpo, com a janela visível.
        if self.quit.load(Ordering::Relaxed) {
            ctx.send_viewport_cmd(egui::ViewportCommand::Close);
            return;
        }

        self.updater.tick(ctx);
        // Seletor nativo (src/pickers.rs): consome as respostas que chegaram
        // da thread (ela acorda este frame com request_repaint).
        for picked in self.pickers.take() {
            (picked.then)(self, picked.paths);
        }
        self.poll_jobs(ctx);
        // Ctrl+K: a paleta (o Keys não dá outro uso ao atalho).
        if craft_palette::Palette::shortcut_pressed(ctx) {
            self.palette.toggle();
        }

        // Check pedido ("Verificar atualizações"): nada encontrado/erro vira
        // toast; release nova abre o prompt do updater lá embaixo.
        // Com as Configurações abertas o resultado aparece nelas (shell.rs), não no toast.
        match self.updater.status().clone() {
            _ if self.settings_open => {}
            craft_update::updater::Status::UpToDate => {
                self.toast = Some((tr("up to date").to_string(), std::time::Instant::now()));
                self.updater.dismiss_status();
            }
            craft_update::updater::Status::Failed(e) => {
                self.toast = Some((e, std::time::Instant::now()));
                self.updater.dismiss_status();
            }
            _ => {}
        }

        // Bandeja (Windows): descobre o HWND o quanto antes (a thread do tray
        // precisa dele pra ShowWindow com a janela oculta).
        #[cfg(windows)]
        winctl::discover();

        // TOTP visível → repaint pra contagem andar (barato: 2×/s).
        let tem_totp = self.items.iter().any(|i| i.totp.is_some());
        if tem_totp {
            ctx.request_repaint_after(std::time::Duration::from_millis(500));
        }

        // Job pesado em andamento: drena o que a thread devolveu e mantém o
        // spinner animado (a thread avisa no fim, mas o giro precisa de frames).
        if self.job_label.is_some() {
            ctx.request_repaint_after(std::time::Duration::from_millis(100));
        }

        egui::containers::Panel::top("topo").show(ui, |ui| {
            ui.horizontal(|ui| {
                ui.strong("Local Keys");
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| self.shell_buttons(ui));
            });
        });

        egui::CentralPanel::default().show(ui, |ui| {
            if self.locked() {
                self.locked_ui(ui, ctx);
            } else {
                self.unlocked_ui(ui, ctx);
            }
        });

        // ── diálogos (egui::Modal: bloqueia o fundo; Esc/clique fora fecham) ──
        // A ordem é a de empilhamento: o seletor de tipo abre o form, o form abre o gerador.
        self.new_kind_modal(ctx);
        self.form_modal(ctx);
        self.delete_modal(ctx);
        self.batch_delete_modal(ctx);
        self.import_modal(ctx);
        self.report_modal(ctx);
        self.generator_modal(ctx);
        self.change_modal(ctx);
        self.attachments_modal(ctx);
        self.folders_modal(ctx);
        self.new_folder_modal(ctx);
        self.trash_modal(ctx);
        self.export_modal(ctx);
        self.export_vault_modal(ctx);
        self.sync_modal(ctx);
        self.merge_password_modal(ctx);
        self.merge_summary_modal(ctx);

        self.settings_modal(ctx);
        self.palette_ui(ctx);

        // Prompt de update (craft_update): modal próprio do crate, por cima de
        // tudo. "Atualizar e reiniciar" pede o fechamento pelo caminho limpo
        // que a bandeja já usa (flag de quit → `ViewportCommand::Close`) — o
        // helper do craft_update instala e relança depois que o app sai.
        match self.updater.show(ctx) {
            craft_update::updater::Action::None => {}
            craft_update::updater::Action::OpenUrl(url) => open_url(&url),
            craft_update::updater::Action::Close => self.quit.store(true, Ordering::Relaxed),
        }

        // rodapé: progresso do job (card + spinner) e o toast, centro embaixo
        self.footer(ctx);
    }
}

impl eframe::App for KeysApp {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        self.frame(ui);
    }
}

impl KeysApp {
    fn locked_ui(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        // Job rodando (Argon2id): destrancar/criar ficam desabilitados.
        let busy = self.job_label.is_some();
        ui.heading(tr("Vault"));
        ui.add_space(10.0);
        // Os campos encolhem com a janela (340 pt é a largura mínima) e nunca passam de 260.
        let field = (ui.available_width() - 150.0).clamp(120.0, 260.0);

        egui::Grid::new("unlock").num_columns(2).spacing([10.0, 8.0]).show(ui, |ui| {
            let label = ui.label(".tkeys");
            ui.horizontal(|ui| {
                ui.add(line(&mut self.path).hint_text("C:\\...\\cofre.tkeys").desired_width(field)).labelled_by(label.id);
                if ui.button("…").on_hover_text(tr("Choose file…")).clicked() {
                    let dialog = rfd::AsyncFileDialog::new().add_filter("Cofre LocalKeys (.tkeys)", &["tkeys"]);
                    let _ = self.pickers.ask(
                        ui.ctx(),
                        pickers::Ask::File(dialog),
                        Box::new(|app, paths| {
                            if let Some(p) = paths.first() {
                                app.path = p.display().to_string();
                                app.quick_ativa = app.path_has_stored_key();
                            }
                        }),
                    );
                }
            });
            ui.end_row();

            let label = ui.label(tr("Master password"));
            ui.add(line(&mut self.master).password(true).desired_width(field)).labelled_by(label.id);
            ui.end_row();
        });

        ui.add_space(4.0);
        #[cfg(windows)]
        ui.checkbox(&mut self.quick_unlock, tr("Quick unlock on this PC"));
        #[cfg(not(windows))]
        let _ = &self.quick_unlock; // campo existe, UI não (stub desligado)
        ui.add_space(8.0);
        ui.horizontal(|ui| {
            let ready = !busy && !self.path.trim().is_empty() && !self.master.is_empty();
            if ui.add_enabled_ui(ready, |ui| pill(ui, tr("Unlock"), true)).inner.clicked()
                || (!busy && !self.palette.is_open() && ui.input(|i| i.key_pressed(egui::Key::Enter) && !self.master.is_empty()))
            {
                self.unlock(ctx);
            }
        });

        if let Some(e) = &self.err {
            ui.add_space(4.0);
            ui.label(egui::RichText::new(format!("{}: {e}", tr("Error"))).color(egui::Color32::LIGHT_RED));
        }

        ui.add_space(18.0);
        ui.separator();
        ui.add_space(18.0);

        // ── novo cofre ─────────────────────────────────────────────────
        ui.heading(tr("New vault"));
        ui.add_space(6.0);
        egui::Grid::new("new").num_columns(2).spacing([10.0, 8.0]).show(ui, |ui| {
            let label = ui.label(".tkeys");
            ui.horizontal(|ui| {
                ui.add(line(&mut self.path).hint_text("C:\\...\\novo-cofre.tkeys").desired_width(field)).labelled_by(label.id);
                if ui.button("…").on_hover_text(tr("Choose file…")).clicked() {
                    let dialog = rfd::AsyncFileDialog::new().add_filter("Cofre LocalKeys (.tkeys)", &["tkeys"]).set_file_name("novo-cofre.tkeys");
                    let _ = self.pickers.ask(
                        ui.ctx(),
                        pickers::Ask::Save(dialog),
                        Box::new(|app, paths| {
                            if let Some(p) = paths.first() {
                                app.path = p.display().to_string();
                            }
                        }),
                    );
                }
            });
            ui.end_row();
            let label = ui.label(tr("Master password"));
            ui.add(line(&mut self.master).password(true).desired_width(field)).labelled_by(label.id);
            ui.end_row();
            let label = ui.label(tr("Confirm password"));
            ui.add(line(&mut self.confirm).password(true).desired_width(field)).labelled_by(label.id);
            ui.end_row();
        });
        ui.add_space(10.0);
        ui.horizontal(|ui| {
            let ready = !busy && !self.path.trim().is_empty() && !self.master.is_empty();
            if ui.add_enabled_ui(ready, |ui| pill(ui, tr("New vault"), true)).inner.clicked() {
                self.create(ctx);
            }
        });
    }

    fn unlocked_ui(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let skin = Skin::of(ui);
        // Dados owned antes do UI (nenhum borrow de self.raw nas closures).
        let mut rows: Vec<Row> = self
            .items
            .iter()
            .filter(|i| self.in_view(i))
            .map(|i| Row {
                id: i.id.clone(),
                name: i.name.clone(),
                kind: i.kind,
                summary: i.summary.clone(),
                username: i.username.clone(),
                totp: i.totp.clone(),
                favorite: i.favorite,
            })
            .collect();
        // Ordenação SÓ de renderização (não regrava nada): favoritos primeiro
        // e, dentro do grupo, nome ascendente — o mesmo sort do VaultScreen do
        // oficial (`Number(b.favorite) - Number(a.favorite) || nome`).
        rows.sort_by(|a, b| b.favorite.cmp(&a.favorite).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())));

        // Os botões primeiro (à direita); o caminho ocupa o que sobra e corta com "…" na janela estreita.
        ui.horizontal(|ui| {
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if pill(ui, tr("Add"), true).clicked() {
                    self.new_kind_open = true;
                }
                if pill(ui, tr("Lock"), false).clicked() {
                    self.lock();
                }
                // O que se faz de vez em quando com o cofre aberto (e é sensível) fica no ⋯, fora da linha do dia a dia.
                let more = egui::Button::image(craft_icons::ui::image(ui, craft_icons::Icon::More)).frame_when_inactive(false);
                let (response, _) = egui::containers::menu::MenuButton::from_button(more).ui(ui, |ui| {
                    if ui.button(format!("{}…", tr("Import"))).clicked() {
                        self.import_open = true;
                        self.reveal_import = false;
                    }
                    if ui.button(format!("{}…", tr("Export"))).clicked() {
                        self.export_path.clear();
                        self.export_open = true;
                    }
                    ui.separator();
                    if ui.button(format!("{}…", tr("Change master password"))).clicked() {
                        self.change_open = true;
                    }
                    ui.separator();
                    // fase D — sincronização, sempre manual
                    if ui.button(format!("{}…", tr("Save a copy to a synced folder"))).clicked() {
                        self.pick_sync_copy_target(ui.ctx());
                    }
                    if ui.button(format!("{}…", tr("Sync with another copy"))).clicked() {
                        self.pick_sync_other(ui.ctx());
                    }
                    if ui.button(format!("{}…", tr("Merge another vault"))).clicked() {
                        self.pick_merge_other(ui.ctx());
                    }
                });
                response.on_hover_text(tr("More actions"));
                ui.with_layout(egui::Layout::left_to_right(egui::Align::Center), |ui| {
                    ui.add(egui::Label::new(egui::RichText::new(&self.path).strong()).truncate()).on_hover_text(&self.path);
                });
            });
        });

        ui.add_space(2.0);
        // Ferramentas do dia a dia (importar, exportar e trocar a senha mestra moram no ⋯ do cabeçalho). Criar pasta
        // fica dentro de Pastas (o campo do topo da janela) e na paleta.
        ui.horizontal_wrapped(|ui| {
            if ghost(ui, tr("Generator")).clicked() {
                self.gen_open = true;
            }
            if ghost(ui, tr("Trash")).clicked() {
                self.trash_open = true;
                self.purge_ask = None;
            }
            if ghost(ui, tr("Folders")).clicked() {
                self.folders_open = true;
                self.folder_edit_id = None;
                self.folder_edit_name.clear();
            }
        });

        // Chips de pasta: "Todas" (i18n) + cada pasta. Seleção = filtro imediato da lista.
        let mut chips: Vec<(Option<String>, String)> = vec![(None, tr("All").to_string())];
        for f in self.raw.as_ref().map(vault::folders).unwrap_or_default() {
            chips.push((Some(f.id), f.name));
        }
        ui.horizontal_wrapped(|ui| {
            for (id, nome) in &chips {
                let ativa = self.folder_filter.as_deref() == id.as_deref();
                let ink = if ativa { skin.accent } else { skin.muted };
                if ghost_ink(ui, nome, ink).clicked() {
                    self.set_folder_filter(id.clone());
                }
            }
        });

        ui.add_space(4.0);
        // "Filtrar (n)" com os chips dos filtros ligados ao lado; a busca fica na linha de baixo para os
        // chips quebrarem linha na janela estreita sem empurrá-la para fora.
        let filtros_antes = self.filters;
        ui.horizontal_wrapped(|ui| {
            let ligados = self.filters.active_count();
            let rotulo = if ligados > 0 { tr_fmt("Filter ({n})", &[("n", &ligados.to_string())]) } else { tr("Filter").to_string() };
            let ink = if ligados > 0 { skin.accent } else { skin.muted };
            let botao = ghost_ink(ui, &rotulo, ink);
            let mut rascunho = self.filters;
            let _ = egui::Popup::from_toggle_button_response(&botao)
                .gap(4.0)
                .close_behavior(egui::PopupCloseBehavior::CloseOnClickOutside)
                .frame(modal_frame(ui.ctx()))
                .show(|ui| {
                    ui.set_min_width(200.0);
                    ui.label(egui::RichText::new(tr("Type")).small().weak());
                    for kind in [
                        filters::KindFilter::All,
                        filters::KindFilter::Logins,
                        filters::KindFilter::Notes,
                        filters::KindFilter::Cards,
                        filters::KindFilter::Identities,
                    ] {
                        ui.radio_value(&mut rascunho.kind, kind, filter_kind_label(kind));
                    }
                    ui.add_space(6.0);
                    ui.separator();
                    ui.add_space(4.0);
                    ui.label(egui::RichText::new(tr("Show only")).small().weak());
                    ui.checkbox(&mut rascunho.only.totp, tr("With TOTP"));
                    ui.checkbox(&mut rascunho.only.attachments, tr("With attachments"));
                    ui.checkbox(&mut rascunho.only.favorites, tr("Favorites"));
                    ui.add_space(6.0);
                    ui.separator();
                    ui.add_space(4.0);
                    ui.horizontal(|ui| {
                        if ui.add_enabled(!rascunho.is_clear(), egui::Button::new(tr("Clear"))).clicked() {
                            rascunho.clear();
                        }
                        // Fecha o popup sem precisar clicar fora (o "Pronto" do Android).
                        if ui.button(tr("Done")).clicked() {
                            ui.close();
                        }
                    });
                });
            self.filters = rascunho;
            // Cada filtro ligado é um chip com ×: o clique desliga SÓ aquele, sem abrir o popup.
            let mut desligar = None;
            for chip in self.filters.active_chips() {
                let nome = match chip {
                    filters::Chip::Kind(kind) => filter_kind_label(kind),
                    filters::Chip::Totp => tr("With TOTP"),
                    filters::Chip::Attachments => tr("With attachments"),
                    filters::Chip::Favorites => tr("Favorites"),
                };
                let acessivel = tr_fmt("Remove filter {name}", &[("name", nome)]);
                if ghost_ink_named(ui, &format!("{nome} ×"), &acessivel, skin.muted).clicked() {
                    desligar = Some(chip);
                }
            }
            if let Some(chip) = desligar {
                self.filters.remove(chip);
            }
            // Trocar o filtro troca a lista: a seleção antiga não vale para a nova.
            if self.filters != filtros_antes {
                self.reset_selection_for_filter_change();
            }
        });
        let mut busca_mudou = false;
        ui.horizontal(|ui| {
            busca_mudou = ui.add(line(&mut self.search).hint_text(tr("Search…")).desired_width(ui.available_width() - 24.0)).changed();
            // Botão "X" pra limpar a busca sem precisar de backspace.
            if !self.search.is_empty() && craft_icons::ui::close_button(ui, tr("Clear")).clicked() {
                self.search.clear();
                busca_mudou = true;
            }
        });
        if busca_mudou {
            self.reset_selection_for_filter_change();
        }
        ui.add_space(6.0);

        if let Some(e) = &self.err {
            ui.label(egui::RichText::new(format!("{}: {e}", tr("Error"))).color(egui::Color32::LIGHT_RED));
            ui.add_space(6.0);
        }

        // `rows` já é a lista visível (busca, filtros e pasta aplicados por `in_view`): a ordem dela é a que o Shift
        // e o Ctrl+A usam.
        let view: Vec<&Row> = rows.iter().collect();
        let visible_ids: Vec<String> = view.iter().map(|row| row.id.clone()).collect();

        if view.is_empty() {
            if self.items.is_empty() {
                ui.label(egui::RichText::new(tr("empty — copy something")).weak());
            } else {
                // Há itens no cofre, mas os filtros/busca/pasta escondem todos: diz o porquê e oferece o
                // "Limpar" (some quando nada está escondendo — aí é só a pasta vazia, com os chips acima).
                let (por_filtro, por_busca, por_pasta) = (!self.filters.is_clear(), !self.search.is_empty(), self.folder_filter.is_some());
                let motivo = match (por_filtro, por_busca, por_pasta) {
                    (true, false, false) => tr("No items match these filters"),
                    (false, true, false) => tr("No items match the search"),
                    (false, false, true) => tr("No items in this folder"),
                    _ => tr("No items match the search, folder and filters"),
                };
                ui.label(egui::RichText::new(motivo).weak());
                if (por_filtro || por_busca || por_pasta) && ghost(ui, tr("Clear")).clicked() {
                    self.filters.clear();
                    self.search.clear();
                    self.set_folder_filter(None);
                }
            }
        } else {
            egui::ScrollArea::vertical().show(ui, |ui| {
                for row in view {
                    let selected = self.selection.contains(&row.id);
                    // A linha marcada ganha fundo (marca visual) e o estado de seleção vai também na árvore de
                    // acessibilidade pelo `selectable_label`.
                    let fill = if selected { skin.accent.gamma_multiply(0.16) } else { egui::Color32::TRANSPARENT };
                    egui::Frame::NONE.fill(fill).corner_radius(egui::CornerRadius::same(6)).inner_margin(egui::Margin::symmetric(5, 3)).show(
                        ui,
                        |ui| {
                            ui.horizontal(|ui| {
                                // ★ agora é BOTÃO: liga/desliga o favorito (o oficial
                                // faz isso pelo editor, que passa no updateItem).
                                let star = if row.favorite { "★" } else { "☆" };
                                let star_ink = if row.favorite { skin.accent } else { skin.muted };
                                if ghost_ink(ui, star, star_ink).on_hover_text(tr("Favorite")).clicked() {
                                    self.toggle_favorite(&row.id);
                                }
                                ui.vertical(|ui| {
                                    let title =
                                        ui.selectable_label(selected, egui::RichText::new(format!("{} {}", kind_icon(row.kind), row.name)).strong());
                                    if title.clicked() {
                                        let modifiers = ui.input(|i| i.modifiers);
                                        self.row_click(&row.id, &visible_ids, modifiers);
                                    }
                                    if let Some(s) = &row.summary {
                                        ui.label(egui::RichText::new(s).small().weak());
                                    }
                                    // TOTP ao vivo: código + contagem (o repaint de
                                    // 500ms já está ligado quando há algum).
                                    if let Some(secret) = &row.totp {
                                        match totp::now(secret) {
                                            Ok(code) => {
                                                ui.horizontal(|ui| {
                                                    ui.label(egui::RichText::new(code.code.clone()).monospace().strong());
                                                    ui.label(egui::RichText::new(format!("{}{}", code.seconds_remaining, tr("s"))).small().weak());
                                                    if ghost(ui, tr("TOTP")).on_hover_text(tr("Copy")).clicked() {
                                                        // código é efêmero, mas marcar
                                                        // exclusão de histórico não custa
                                                        if clipboard::copy_secret(code.code).is_ok() {
                                                            self.copied(&row.name);
                                                        }
                                                    }
                                                });
                                            }
                                            Err(e) => {
                                                ui.label(egui::RichText::new(format!("TOTP: {e}")).small().color(egui::Color32::LIGHT_RED));
                                            }
                                        }
                                    }
                                    // Ações do item: ghost buttons (transparentes até
                                    // o hover), como no local-pdf. Copiar usuário/senha
                                    // só existe em login (os outros tipos não têm).
                                    ui.horizontal_wrapped(|ui| {
                                        if row.kind == vault::ItemKind::Login
                                            && row.username.is_some()
                                            && ghost(ui, tr("Username")).on_hover_text(tr("Copy")).clicked()
                                            && let Some((user, _, _)) = self.raw.as_ref().and_then(|r| vault::login_triple(r, &row.id))
                                        {
                                            // usuário não é segredo: cópia simples
                                            if arboard::Clipboard::new().and_then(|mut c| c.set_text(user)).is_ok() {
                                                self.copied(&row.name);
                                            }
                                        }
                                        if row.kind == vault::ItemKind::Login
                                            && ghost(ui, tr("Password")).on_hover_text(tr("Copy")).clicked()
                                            && let Some((_, pass, _)) = self.raw.as_ref().and_then(|r| vault::login_triple(r, &row.id))
                                            && clipboard::copy_secret(pass).is_ok()
                                        {
                                            self.copied(&row.name);
                                        }
                                        if ghost(ui, tr("Attachments")).clicked() {
                                            self.attach_item = Some(row.id.clone());
                                            self.attach_path.clear();
                                        }
                                        if ghost(ui, tr("Edit")).clicked() {
                                            self.open_edit_form(&row.id);
                                        }
                                        if ghost(ui, tr("Delete"))
                                            .on_hover_text(tr("The item goes to the Trash, where you can restore it."))
                                            .clicked()
                                        {
                                            // Confirmação: exclusão é reversível só na
                                            // lixeira — clique sem querer não apaga.
                                            self.delete_ask = Some((row.id.clone(), row.name.clone()));
                                        }
                                    });
                                });
                            });
                        },
                    );
                    ui.separator();
                }
            });
        }

        ui.with_layout(egui::Layout::bottom_up(egui::Align::LEFT), |ui| {
            if self.selection.is_empty() {
                ui.horizontal(|ui| {
                    // O rodapé conta o cofre inteiro (não o que o popup/busca deixou na tela).
                    ui.label(egui::RichText::new(format!("{}: {}", tr("Items"), self.items.len())).small().weak());
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |_ui| {});
                });
            } else {
                self.selection_bar(ui);
            }
        });
        // O teclado da seleção é lido DEPOIS dos popups deste quadro (o ⋯, os filtros e o menu de mover do rodapé):
        // o `any_popup_open` do egui só enxerga o popup que já se desenhou no pass atual.
        self.selection_keys(ctx, &visible_ids);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// O `.tkeys` do argv vira o cofre da tela de desbloqueio; flags e outros arquivos não.
    #[test]
    fn argv_reconhece_o_caminho_tkeys() {
        assert_eq!(vault_from_args(["C:/cofres/meu.tkeys"]), Some("C:/cofres/meu.tkeys".to_string()));
        assert_eq!(vault_from_args(["/home/a/Meu Cofre.TKEYS"]), Some("/home/a/Meu Cofre.TKEYS".to_string()));
        // o primeiro `.tkeys` vence; o que vem antes e não é cofre é ignorado
        assert_eq!(vault_from_args(["--x", "nota.txt", "a.tkeys", "b.tkeys"]), Some("a.tkeys".to_string()));
        // sem cofre: nada (a tela abre no último usado)
        assert_eq!(vault_from_args(Vec::<String>::new()), None);
        assert_eq!(vault_from_args(["nota.txt", "tkeys", "a.tkeys.bak"]), None);
    }

    /// Trancar o cofre zera os filtros da lista (o próximo abre sem filtro nenhum ligado).
    #[test]
    fn lock_clears_the_list_filters() {
        let mut app = KeysApp::bare(Prefs::default());
        app.filters = filters::Filters { kind: filters::KindFilter::Cards, only: filters::Only { favorites: true, totp: true, attachments: false } };
        app.selection.toggle("a");
        app.selection_anchor = Some("a".into());
        assert!(!app.filters.is_clear());
        app.lock();
        assert!(app.filters.is_clear(), "trancar deixa os filtros limpos");
        assert!(app.selection.is_empty(), "trancar limpa a seleção da lista");
        assert!(app.selection_anchor.is_none());
    }

    /// `--finish-update` (o helper de update do Windows, tratado antes de tudo) não é cofre, mesmo
    /// com o caminho da instalação do lado.
    #[test]
    fn argv_do_finish_update_fica_intacto() {
        assert_eq!(vault_from_args(["--finish-update"]), None);
        assert_eq!(vault_from_args(["--finish-update", "C:/Program Files/Local Keys/local-keys.exe"]), None);
    }

    /// A data da lixeira é o único uso de calendário do app (sem crate de
    /// data): confere epoch, virada de ano e um dia comum.
    #[test]
    fn data_da_lixeira_sai_em_dd_mm_aaaa() {
        assert_eq!(date_from_ms(0), "01/01/1970");
        assert_eq!(date_from_ms(1_704_067_200_000), "01/01/2024");
        assert_eq!(date_from_ms(1_700_000_000_000), "14/11/2023");
        // 29/02 de um ano bissexto
        assert_eq!(date_from_ms(1_709_164_800_000), "29/02/2024");
    }

    /// Linux: múltiplos caminhos de anexo chegam separados por `;` — separa,
    /// apara espaços e descarta os vazios (campos tipo "  ;  " viram nada).
    #[test]
    fn caminhos_linux_de_anexos_separam_por_ponto_e_virgula() {
        assert_eq!(split_attachment_paths(" /a/b.png ; /c/d.pdf;;  ; "), vec!["/a/b.png", "/c/d.pdf"]);
        assert!(split_attachment_paths("").is_empty());
        assert!(split_attachment_paths("   ;  ;").is_empty());
        assert_eq!(split_attachment_paths("só-um"), vec!["só-um".to_string()]);
    }

    /// O id do item RECÉM-criado é o primeiro id que não existia antes do
    /// `add_login` (que gera o id internamente).
    #[test]
    fn id_do_item_novo_eh_o_diff_de_ids() {
        let raw = serde_json::json!({ "items": [ { "id": "a" }, { "id": "b" } ] });
        let antes = item_ids(&raw);
        assert_eq!(antes, vec!["a".to_string(), "b".to_string()]);
        assert_eq!(novo_item_id(&raw, &antes), None);

        let raw = serde_json::json!({ "items": [ { "id": "a" }, { "id": "novo" } ] });
        assert_eq!(novo_item_id(&raw, &["a".into()]), Some("novo".to_string()));

        // Vault sem `items`: nada a achar, e não explode.
        assert_eq!(novo_item_id(&serde_json::json!({ "items": [] }), &[]), None);
    }
}
