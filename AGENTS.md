# AGENTS.md — LocalKeys

Gerenciador de senhas local (cofre = arquivo do usuário), parte da suíte Local do João.
Rust puro + egui: o app (`src/`) e o núcleo sem UI (`crates/core`, `localkeys-core`). Sem conta, sem nuvem, sem telemetria; a única rede é a consulta de atualização às releases do GitHub (desktop: ao abrir; Android: opt-in).

## Regras
- **Dependências:** toda dep que entra/sai/atualiza exige `Cargo.lock` commitado +
  linha no inventário do repo `Anon5T4R/local-deps` (regra completa em
  `local-deps/WORKFLOW.md`).
- **Sem fontes git:** nenhuma dependência pode apontar para `git+` (o CI valida).
  Tudo vem do crates.io (pinado pelo lock).
- **Commits:** português, sem Co-Authored-By.
- **Release:** tag `vX.Y.Z` via `release.sh`; fluxo, gate local e checklist no
  `docs/git-protocol.md` do dev-notes.
- **Disco:** no workspace do João vale a política de `target/` do dev-notes (`MEMORY.md` §4 — limpa o que
  está frio ou feito, mantém durante iteração ativa); fora dele, `cargo clean` ao terminar. Builds pesados são do CI.

## Estrutura
- `crates/core` — `localkeys-core`, o núcleo sem UI: formato `.tkeys` (`crypto.rs`), cofre em JSON bruto com
  escrita conservadora (`vault.rs`), importadores, exportação, TOTP e gerador. O Keys Android o usa por UniFFI
  (`crates/ffi`); nada de UI ou i18n entra aqui.
- `crates/ffi` — `localkeys-ffi`, a ponte UniFFI do núcleo para o Kotlin (hoje: o formato `.tkeys`, a cripto e a sessão
  de gravação). **Workspace próprio** (fora do `Cargo.lock` e do `cargo-deny` do desktop: o UniFFI é MPL-2.0 e só entra
  no `.so` do Android). O Gradle o compila sozinho (`cargoBuildHost`, `generateUniffi`, `cargoNdk` em
  `android/app/build.gradle.kts`); precisa de Rust com os targets `*-linux-android`, `cargo install cargo-ndk` e o NDK.
  Os bindings Kotlin e os `.so` ficam fora do git. `cargo test` na pasta testa a ponte.
- `android/` — o Keys Android (Kotlin + Compose; histórico do antigo `LocalKeysAndroid` trazido por `git subtree`).
  É do Claude (`AGENTS.md` do dev-notes). CI em `.github/workflows/android-ci.yml`; release pela tag
  **`android-vX.Y.Z`** (`android-release.yml`, APK assinado com os secrets `KEYSTORE_B64` e `KEYSTORE_PROPERTIES_B64`,
  publicado com `make_latest: false` para o atualizador do desktop não confundir com a release dele). Build local:
  `android/local.properties` com `sdk.dir=C:/Android` (fora do git).
- `src/` — o app (`src/pickers.rs`: seletor nativo async via rfd + pollster,
  portal XDG no Linux; também `src/i18n.rs`, `src/prefs.rs`, `src/theme.rs`, `src/shell.rs` e
  `i18n/locales/<code>/*.tsv`)
- `docs/sync-merge.md` — as regras de juntar duas cópias do cofre ("Sincronizar com outra cópia"), as mesmas no
  Android e no desktop; os casos comuns (JSON puro) ficam em `testdata/sync-merge/` e os dois lados os rodam.
- Casca da suíte = `src/shell.rs` + craft-icons + craft-palette + craft-desktop-theme
  (espelhos do local-libs)
- `icons/` — ícones do app (master PNG 1024)
- `assets/app-icon/hicolor/` — árvore de ícones commitada (gerada por `packaging/icons.sh`)
- `packaging/windows/package.ps1` + `local-keys.wxs` — MSI per-machine (WiX v5, Program
  Files\Local Keys) e portable zip
- `packaging/linux/` — `package.sh` (AppImage + tar FHS + install.sh + deb/rpm),
  `local-keys.desktop`, `local-keys.metainfo.xml.in`, `nfpm.yaml`, `postinst.sh`
- `packaging/arch/PKGBUILD` — pacote Arch a partir do tarball FHS da release

## Build
```
cargo build --locked                       # debug
cargo test --locked                        # testes
cargo xtask ci                             # o gate local: o mesmo que o CI (fmt, clippy, build, testes, cargo-deny,
                                           # espelhos do local-libs)
cargo xtask version set X.Y.Z              # bump (Cargo.toml + lock)
cargo xtask check                          # confere as versões sincronizadas
```


## Upstream e a pasta `<root>/storytold`

Menções a `storytold/*` em código, docs e atribuições (NOTICE/ATTRIBUTION) são referência histórica e
obrigação de licença — nada é lido dessa pasta em build, teste ou release.
