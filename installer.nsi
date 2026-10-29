!include "MUI2.nsh"

Name "LocalKeys"
OutFile "local-keys-setup.exe"
InstallDir "$PROGRAMFILES\LocalKeys"
RequestExecutionLevel admin

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_WELCOME
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH

Section
  SetOutPath "$INSTDIR"
  File "target\release\local-keys.exe"
  CreateDirectory "$SMPROGRAMS\LocalKeys"
  CreateShortCut "$SMPROGRAMS\LocalKeys\LocalKeys.lnk" "$INSTDIR\local-keys.exe"
  CreateShortCut "$DESKTOP\LocalKeys.lnk" "$INSTDIR\local-keys.exe"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalKeys" "DisplayName" "LocalKeys"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalKeys" "UninstallString" "$INSTDIR\uninstall.exe"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalKeys" "DisplayVersion" "0.9.0"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalKeys" "Publisher" "Anon5T4R"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalKeys" "DisplayIcon" "$INSTDIR\local-keys.exe"
  WriteUninstaller "$INSTDIR\uninstall.exe"
SectionEnd

Section "Uninstall"
  Delete "$INSTDIR\local-keys.exe"
  Delete "$INSTDIR\uninstall.exe"
  Delete "$SMPROGRAMS\LocalKeys\LocalKeys.lnk"
  Delete "$DESKTOP\LocalKeys.lnk"
  RMDir "$SMPROGRAMS\LocalKeys"
  RMDir "$INSTDIR"
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LocalKeys"
SectionEnd
