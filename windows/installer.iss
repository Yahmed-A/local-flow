; Local Flow for Windows installer (Inno Setup 6). Built by windows/build_win.py.
; Installs per user (no admin prompt), adds a Start menu entry, optionally starts with Windows.
#define AppVer GetEnv("LOCALFLOW_VERSION")
#if AppVer == ""
  #define AppVer "dev"
#endif

[Setup]
AppId={{6F1C6B0E-6C1B-4C5D-9D7E-1A0C2B3D4E5F}
AppName=Local Flow
AppVersion={#AppVer}
AppPublisher=Jaryah
DefaultDirName={localappdata}\Programs\Local Flow
DefaultGroupName=Local Flow
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\dist
OutputBaseFilename=LocalFlowSetup
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\LocalFlow.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible
ArchitecturesAllowed=x64compatible

[Tasks]
Name: "startup"; Description: "Start Local Flow when Windows starts"; GroupDescription: "Options:"
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Options:"; Flags: unchecked

[Files]
Source: "..\dist\LocalFlow\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\Local Flow"; Filename: "{app}\LocalFlow.exe"
Name: "{group}\Uninstall Local Flow"; Filename: "{uninstallexe}"
Name: "{userdesktop}\Local Flow"; Filename: "{app}\LocalFlow.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Local Flow"; ValueData: """{app}\LocalFlow.exe"""; Flags: uninsdeletevalue; Tasks: startup

[Run]
Filename: "{app}\LocalFlow.exe"; Description: "Start Local Flow now"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "taskkill"; Parameters: "/IM LocalFlow.exe /F"; Flags: runhidden; RunOnceId: "StopLocalFlow"
