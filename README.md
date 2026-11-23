# LocalKeys

Gerenciador de senhas local: o cofre é um arquivo seu, sem conta, sem nuvem e sem
telemetria. Rust puro + egui — parte da suíte Local. A única conexão de rede é a
consulta de atualização às releases do GitHub (no desktop, ao abrir; no Android, só
quando você liga ou pede); o conteúdo do cofre nunca sai do aparelho.

## Recursos
- Cofre `.tkeys` cifrado (Argon2id + cifra forte), compatível com o formato do
  gerenciador oficial (logins, TOTP, anexos, pastas, lixeira, histórico de senha)
- Desbloqueio rápido (Windows, opcional)
- Importação: KeePass `.kdbx`, Bitwarden (cifrado ou não), CSV
- Exportação: JSON/CSV **em claro** — o arquivo sai sem cifra, cuide dele
- Gerador de senhas/frases
- Casca da suíte: engrenagem (idioma, tema e Sobre), lupa/Ctrl+K (paleta de comandos)
  e ⋯ (Importar, Exportar, Trocar senha mestra)
- Windows: MSI per-machine (WiX) + portable zip; Linux: AppImage, tar.gz, deb, rpm
  e Arch (PKGBUILD)

## Build
```
cargo build --locked
cargo test --locked
cargo xtask ci    # o gate local: o mesmo que o CI roda
```

## Release
Tag `vX.Y.Z` → o GitHub Actions builda e publica tudo. Detalhes para agentes: `AGENTS.md`.

## Licença
MIT OR Apache-2.0 (`LICENSE-MIT` e `LICENSE-APACHE`)
