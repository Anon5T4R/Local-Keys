!include "MUI2.nsh"

Name "LocalKeys"
OutFile "local-keys-setup.exe"
InstallDir "$PROGRAMFILES\LocalKeys"
RequestExecutionLevel admin
Unicode true

!define VERSION "0.9.1"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_WELCOME
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH

Section "Install"
  SetOutPath "$INSTDIR"
  File "target\release\local-keys.exe"

  ; Atalhos no contexto do USUÁRIO (não do admin elevado).
  ; Sem isto, $DESKTOP aponta pro desktop do Administrator.
  SetShellVarContext current

  ; Menu Iniciar
  CreateDirectory "$SMPROGRAMS\LocalKeys"
  CreateShortCut "$SMPROGRAMS\LocalKeys\LocalKeys.lnk" "$INSTDIR\local-keys.exe"
  ; Desktop
  CreateShortCut "$DESKTOP\LocalKeys.lnk" "$INSTDIR\local-keys.exe"

  ; Registro de desinstalação (Adicionar/Remover programas)
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalKeys" "DisplayName" "LocalKeys"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalKeys" "UninstallString" "$INSTDIR\uninstall.exe"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalKeys" "DisplayVersion" "${VERSION}"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalKeys" "Publisher" "Anon5T4R"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalKeys" "DisplayIcon" "$INSTDIR\local-keys.exe"
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalKeys" "NoModify" 1
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalKeys" "NoRepair" 1

  WriteUninstaller "$INSTDIR\uninstall.exe"
SectionEnd

Section "Uninstall"
  ; Remove atalhos no contexto do usuário
  SetShellVarContext current
  Delete "$SMPROGRAMS\LocalKeys\LocalKeys.lnk"
  Delete "$DESKTOP\LocalKeys.lnk"
  RMDir "$SMPROGRAMS\LocalKeys"

  ; Remove arquivos
  Delete "$INSTDIR\local-keys.exe"
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"

  ; Remove registro
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalKeys"
SectionEnd