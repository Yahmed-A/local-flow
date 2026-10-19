#!/bin/zsh
launchctl bootout gui/$(id -u)/com.jaryah.localflow 2>/dev/null
rm -f "$HOME/Library/LaunchAgents/com.jaryah.localflow.plist"
echo "Local Flow removed from login items."
