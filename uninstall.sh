#!/bin/zsh
launchctl bootout gui/$(id -u)/com.jaryah.localflow 2>/dev/null
rm -f "$HOME/Library/LaunchAgents/com.jaryah.localflow.plist"
rm -rf "$HOME/Applications/Local Flow.app"
echo "Local Flow removed from login items."
