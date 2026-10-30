# LocalKeys

Gerenciador de senhas 100% local e offline. Rust puro + egui, sem conta, sem
telemetria, sem servidores de terceiros — parte da suíte Local.

## Recursos
- Cofre `.tkeys` cifrado (Argon2id + cifra forte), compatível com o formato do
  gerenciador oficial (logins, TOTP, anexos, pastas, lixeira, histórico de senha)
- Quick unlock via keyring do sistema
- Importação de outros gerenciadores, gerador de senhas/frmases
- Windows (instalador NSIS em `Program Files` + portable zip) e Linux (AppImage + tar.gz, PKGBUILD)

## Build
```
cargo build --locked
cargo test --locked
```

## Release
Tag `vX.Y.Z` → o GitHub Actions builda e publica tudo. Detalhes para agentes: `AGENTS.md`.

## Licença
GPL-3.0-or-later
