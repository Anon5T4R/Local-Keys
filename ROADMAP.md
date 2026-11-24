# Local Keys roadmap

The honest state of the app: what is known to be missing or weak, and what is planned. Release notes
list what changed in each version.

## Before 1.0

- **Merge two vaults into one.** Today the app merges two copies of the *same* vault; joining a
  different vault into yours is planned.
- **Select several entries at once** (to delete them after a bad or duplicated import).
- **Attachments panel in a narrow window:** on the desktop, at about 500 px wide, the panel opens
  shifted to the left and its title, file names and "Add attachment" button are cut off.
- A security review round (signing, release keys, the Android developer verification) and an
  interface round on desktop and Android.

## Known gaps

- **Desktop update check** runs at every start with no setting to turn it off (Android's is opt-in).
- **Error messages from the core** (wrong password, unreadable file) are still in Portuguese inside the
  code instead of going through the translation catalog.
- **"Delete"** on the desktop actually moves the entry to the trash; the label should say so, as
  Android's does.
- **Android, not yet verified on real devices in every case:** passkeys on real websites, the import
  from another password manager through Credential Exchange, and the minified release build with the
  native core.
- **Android still has Kotlin copies** of the generator, the merge, TOTP and the importers; only the
  vault format and cryptography run on the shared Rust core so far.
- **Android target SDK and AndroidX** are behind the current versions.
- **Languages:** English and Portuguese on Android; English, Portuguese and Spanish on the desktop.

## Planned

- Move the rest of the Android logic (generator, merge, TOTP, importers) to the Rust core.
- Receive passkeys exported by other managers (the Credential Exchange protocol), which needs Local
  Keys to become a full passkey provider on Android.
- Desktop and Android released together under the same version number.
