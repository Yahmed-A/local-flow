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
echo "Installed. Python binary to allow in Privacy & Security:"
echo "  $PY"
