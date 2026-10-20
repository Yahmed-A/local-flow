#!/bin/zsh
# Installs Local Flow as a login item (LaunchAgent) and starts it now.
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ ! -x "$DIR/.venv/bin/python" ]; then
  command -v uv >/dev/null || { echo "Install uv first: curl -LsSf https://astral.sh/uv/install.sh | sh"; exit 1; }
  uv venv --python 3.12 "$DIR/.venv"
  uv pip install --python "$DIR/.venv/bin/python" -r "$DIR/requirements.txt"
fi
PY="$(cd "$DIR" && .venv/bin/python -c 'import sys,os;print(os.path.realpath(sys.executable))')"
PLIST="$HOME/Library/LaunchAgents/com.jaryah.localflow.plist"
cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>com.jaryah.localflow</string>
  <key>ProgramArguments</key><array>
    <string>$DIR/.venv/bin/python</string><string>$DIR/app.py</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>ProcessType</key><string>Interactive</string>
  <key>StandardErrorPath</key><string>$HOME/Library/Application Support/LocalFlow/stderr.log</string>
</dict></plist>
PL
launchctl bootout gui/$(id -u)/com.jaryah.localflow 2>/dev/null || true
# bootout is async: wait until the old job is gone
for i in {1..20}; do launchctl print gui/$(id -u)/com.jaryah.localflow >/dev/null 2>&1 || break; sleep 0.5; done
launchctl bootstrap gui/$(id -u) "$PLIST"
# Launcher app: open "Local Flow" from Spotlight/Launchpad/Dock to start it again after quitting
APP="$HOME/Applications/Local Flow.app"
mkdir -p "$HOME/Applications"
rm -rf "$APP"
osacompile -o "$APP" -e '
set uid to do shell script "id -u"
set job to "gui/" & uid & "/com.jaryah.localflow"
set plist to (POSIX path of (path to home folder)) & "Library/LaunchAgents/com.jaryah.localflow.plist"
if (do shell script "launchctl print " & job & " 2>/dev/null | grep -q \"state = running\" && echo yes || echo no") is "yes" then
	display notification "Local Flow is already running. Look for ◎ in the menu bar." with title "Local Flow"
else
	try
		do shell script "launchctl kickstart " & job
	on error
		do shell script "launchctl bootstrap gui/" & uid & " " & quoted form of plist
	end try
	display notification "Starting… ready when ◎ appears in the menu bar." with title "Local Flow"
end if'
"$DIR/.venv/bin/python" "$DIR/launcher_icon.py" "$APP/Contents/Resources/applet.icns" 2>/dev/null || true
rm -f "$APP/Contents/Resources/Assets.car"   # so macOS uses our applet.icns
/usr/libexec/PlistBuddy -c "Delete :CFBundleIconName" "$APP/Contents/Info.plist" 2>/dev/null || true
codesign --force -s - "$APP" 2>/dev/null || true
touch "$APP"
echo "Installed. Python binary to allow in Privacy & Security:"
echo "  $PY"
