# Local Flow

Private, on-device dictation (a Wispr Flow-style tool). Whisper large-v3-turbo runs on the Apple GPU via MLX. No audio leaves the Mac.

- **Hold Right Option** and speak, then release: the text is pasted at your cursor.
- **Quick-tap Right Option** for hands-free mode, then tap again to stop. **Esc** cancels.
- Menu bar icon: ◌ loading · ◎ ready · ● listening · ◐ transcribing
- Menu: language (English / Arabic / Auto), hotkey, editable vocabulary (names and terms it should spell right), history, paste last again.
- Settings and history: `~/Library/Application Support/LocalFlow/`
- Starts at login (LaunchAgent `com.jaryah.localflow`). `./install.sh` re-installs; `./uninstall.sh` removes it.

## Install (Apple Silicon Mac)

```bash
git clone https://github.com/Yahmed-A/local-flow.git ~/Projects/local-flow
~/Projects/local-flow/install.sh
```

Needs [uv](https://docs.astral.sh/uv/). The first run downloads the Whisper model (~1.6 GB).
Then allow the Python binary that `install.sh` prints under System Settings → Privacy & Security →
**Microphone**, **Accessibility** and **Input Monitoring**, and run `install.sh` again.

## Languages

Default is **Arabic + English (mixed)**: each language is written in its own script, and common
Islamic phrases (salaam, in shā' Allāh, the basmala, ﷺ…) are converted to Arabic script
(`arabic_phrases.py`). Choose *English only* to keep Latin spellings.
