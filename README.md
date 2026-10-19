# Local Flow

Private dictation for your Mac, in the style of Wispr Flow. Hold a key, speak, let go, and your words are typed wherever your cursor is: email, Word, WhatsApp, the browser, anywhere.

- **100% on your Mac.** Speech is transcribed by OpenAI's Whisper (large-v3-turbo) running on the Mac's own chip. No audio or text is sent anywhere, and there's no subscription.
- **Fast and accurate.** About 1 second to transcribe 10 seconds of speech on an M4.
- **Arabic and English together.** Switch languages mid-sentence and each is written in its own script. Common Islamic phrases (السلام عليكم, إن شاء الله, بسم الله, صلى الله عليه وسلم…) come out in Arabic.
- **Starts at login** and sits quietly in the menu bar.

---

## Quickest way: let Claude set it up

If you have [Claude Code](https://claude.com/claude-code) (or the Claude desktop app's Code tab), open it and paste this:

> Set up Local Flow on this Mac by following the "Instructions for Claude" section of the README at https://github.com/Yahmed-A/local-flow. Tell me when I need to click anything in System Settings.

Claude will install everything, then guide you through the three permission switches only you can turn on.

---

## What you need

| | |
|---|---|
| Mac | Apple Silicon (M1, M2, M3, M4 or later). **Intel Macs are not supported.** Check with Apple menu → About This Mac → Chip. |
| macOS | 13 Ventura or newer |
| Memory | 8 GB minimum, 16 GB recommended |
| Disk | About 3 GB free (1.6 GB for the speech model) |
| Internet | Only for the first install (downloads the model); after that it works offline |

---

## Manual setup (step by step)

Open the **Terminal** app (press ⌘ Space, type *Terminal*, press Return) and run each command below.

### 1. Install Apple's developer tools (gives you `git`)

```bash
xcode-select --install
```

If it says they're already installed, carry on. Otherwise click **Install** in the window that pops up and wait for it to finish.

### 2. Install uv (manages Python for the app)

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
```

Then **close Terminal and open it again** so it can find `uv`.

### 3. Download Local Flow

```bash
git clone https://github.com/Yahmed-A/local-flow.git ~/Projects/local-flow
```

Keep it in a normal folder like this. **Don't** put it inside iCloud Drive, Google Drive or Dropbox, because syncing can break the app.

### 4. Install it

```bash
~/Projects/local-flow/install.sh
```

This takes a few minutes. It:
- creates a private Python environment inside the folder,
- installs the app's libraries,
- sets the app to start every time you log in, and starts it now.

At the end it prints a line like:

```
Python binary to allow in Privacy & Security:
  /Users/you/.local/share/uv/python/cpython-3.12.x-macos-aarch64-none/bin/python3.12
```

Keep that path handy for the next step.

A **◌** appears in the menu bar (top right of the screen). The first time, it downloads the speech model (~1.6 GB), so it can stay ◌ for a few minutes. It turns to **◎** when ready.

### 5. Turn on the three permissions

macOS may show pop-ups asking for these; click **Allow** / **Open System Settings**. If not, do it by hand:

Open **System Settings → Privacy & Security**, then for each of these three:

1. **Microphone**, so it can hear you
2. **Accessibility**, so it can paste the text for you
3. **Input Monitoring**, so it can notice the hotkey

…find **python3.12** in the list and switch it **on**.

If `python3.12` isn't in a list: click **+**, press **⌘ ⇧ G**, paste the folder part of the path from step 4 (everything up to and including `/bin/`), press Return, choose **python3.12**, and click **Open**.

### 6. Restart the app so the permissions take effect

```bash
~/Projects/local-flow/install.sh
```

Wait for **◎** in the menu bar. Done.

---

## How to use it

| Action | What happens |
|---|---|
| **Hold Right Option (⌥)**, speak, let go | Your words are typed where the cursor is |
| **Tap Right Option once** | Hands-free: speak as long as you like, tap again to finish |
| **Esc** while listening | Cancel, nothing is typed |

A small dark bubble appears at the bottom of the screen while it's listening (bars move with your voice) and while it's transcribing. A soft sound plays when it starts and stops.

Menu bar icon: **◌** loading · **◎** ready · **●** listening · **◐** transcribing · **⚠** problem

Your clipboard is put back after each paste, so copy/paste keeps working as normal.

### The ◎ menu

- **Language**
  - *Arabic + English (mixed)*: the default. Each language in its own script.
  - *English only*: always English letters (e.g. "InshaAllah").
  - *Arabic only*
  - *Auto-detect*: picks one language per dictation.
- **Hotkey**: Right Option, Right Command, Right Control or Fn/Globe. If you choose **Fn/Globe**, go to System Settings → Keyboard and set *"Press 🌐 key to"* → **Do Nothing**, or the emoji picker will pop up.
- **Paste last dictation again**
- **Show floating bubble** (on/off)
- **Edit vocabulary…**: names and terms it should always spell your way (people, brands, places). Comma-separated; saved instantly.
- **Open history**: every dictation, with the time. Handy if a paste went to the wrong window.
- **Quit Local Flow**

---

## Settings and files

Everything lives in `~/Library/Application Support/LocalFlow/`:

| File | What it is |
|---|---|
| `config.json` | Settings (changing the menu updates it). `"sounds": false` turns off the beeps; `"restore_clipboard": false` leaves the dictated text on the clipboard. Restart after editing by hand. |
| `vocabulary.txt` | Your custom words (also via the menu) |
| `history.txt` | Everything you've dictated |
| `stderr.log` | Technical log for troubleshooting |

To add more Arabic phrases that should be converted from English letters, edit the `PHRASES` list in `arabic_phrases.py`.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| No ◎ in the menu bar | It may be hidden behind the camera notch. Hold ⌘ and drag other menu bar icons away, or quit some menu bar apps. Check it's running: `launchctl print gui/$(id -u)/com.jaryah.localflow \| grep state` |
| Icon stays ◌ for a long time | The first run downloads 1.6 GB. Check your internet and look at `stderr.log`. |
| Pressing the key does nothing | **Input Monitoring** is missing for python3.12. Turn it on, then run `install.sh` again. |
| The bubble shows and it transcribes, but no text appears | **Accessibility** is missing. Turn it on, then run `install.sh` again. Your text is still in **Open history**. |
| Text is empty or "Thank you." | **Microphone** is missing, or the wrong mic is selected in System Settings → Sound → Input. |
| Typing special characters with Option cancels dictation | That's on purpose: Option + another key is treated as a shortcut, not dictation. |
| A word keeps coming out wrong | Add it to **Edit vocabulary…** exactly as you want it written. |
| Arabic phrase comes out in English letters | Make sure Language is *Arabic + English (mixed)*. Add the phrase in Arabic script to the vocabulary. |
| Anything else | Run `install.sh` again (safe to repeat), then read the last lines of `stderr.log`. |

## Update to the latest version

```bash
cd ~/Projects/local-flow && git pull && ./install.sh
```

## Uninstall

```bash
~/Projects/local-flow/uninstall.sh
rm -rf ~/Projects/local-flow ~/Library/Application\ Support/LocalFlow
```

Optionally also delete the speech model: `rm -rf ~/.cache/huggingface/hub/models--mlx-community--whisper-large-v3-turbo`, and switch python3.12 off in Privacy & Security.

---

## Instructions for Claude

You are setting up Local Flow on the user's Mac. Do the work yourself in the terminal; the user only needs to click things in System Settings. Keep explanations short and non-technical.

1. **Check the machine.** `uname -m` must print `arm64` (Apple Silicon); if it prints `x86_64`, stop and tell the user this app needs an Apple Silicon Mac. Check `sw_vers -productVersion` is 13 or higher, and free disk space is at least 3 GB (`df -h ~`).
2. **Tools.** If `git` is missing, run `xcode-select --install` and ask the user to click Install and tell you when it's done. If `uv` is missing (check `~/.local/bin/uv` too), install it with `curl -LsSf https://astral.sh/uv/install.sh | sh` and use the full path `~/.local/bin/uv` for this session (or update `PATH`).
3. **Download.** `git clone https://github.com/Yahmed-A/local-flow.git ~/Projects/local-flow`. If the folder already exists and is this repo, run `git -C ~/Projects/local-flow pull` instead. Never place it in a cloud-synced folder.
4. **Install.** Run `~/Projects/local-flow/install.sh` (allow up to 10 minutes). It creates `.venv` with Python 3.12, installs `requirements.txt`, writes `~/Library/LaunchAgents/com.jaryah.localflow.plist` and starts the agent. Note the python3.12 path it prints. If it fails with "Bootstrap failed: 5", wait a few seconds and run it again.
5. **Pre-download the model** so the first start isn't slow (optional but helpful):
   `~/Projects/local-flow/.venv/bin/python -c "from huggingface_hub import snapshot_download; snapshot_download('mlx-community/whisper-large-v3-turbo')"`
6. **Permissions.** Tell the user, in plain words, to open System Settings → Privacy & Security and switch on **python3.12** under **Microphone**, **Accessibility** and **Input Monitoring**, adding it with **+** (⌘⇧G, paste the folder path up to `/bin/`) if it isn't listed. You can open the panes for them:
   `open "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone"`
   `open "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"`
   `open "x-apple.systempreferences:com.apple.preference.security?Privacy_ListenEvent"`
   Wait for the user to confirm, then run `install.sh` again to restart with the permissions.
7. **Verify.** `launchctl print gui/$(id -u)/com.jaryah.localflow | grep -E "^\s+state ="` should say `running`. After ~30–60 s, `grep -iE "error|traceback|permission" ~/Library/Application\ Support/LocalFlow/stderr.log ~/Library/Application\ Support/LocalFlow/history.txt` should show nothing new. Optional offline accuracy test:
   `say -o /tmp/lf.wav --file-format=WAVE --data-format=LEI16@16000 "Testing Local Flow, one two three." && cd ~/Projects/local-flow && .venv/bin/python -c "import wave,numpy as np,app;w=wave.open('/tmp/lf.wav');a=np.frombuffer(w.readframes(w.getnframes()),np.int16).astype(np.float32)/32768;t=app.Transcriber(app.load_config());t.load();print(app.clean(t(a)))"`
8. **Hand over.** Ask the user to click into any text box, hold **Right Option**, say a sentence and let go. If nothing is typed, use the Troubleshooting table above. Then briefly explain the hotkey, hands-free tap, Esc, the ◎ menu (language, vocabulary, history), and offer to add their own names/terms to `~/Library/Application Support/LocalFlow/vocabulary.txt` (the default list contains the original author's brand names; replace them with the user's).

---

## How it works (for the curious)

- `app.py`: menu bar app (rumps). A macOS event tap watches the hotkey; audio is recorded at 16 kHz with sounddevice; [mlx-whisper](https://github.com/ml-explore/mlx-examples/tree/main/whisper) transcribes on the Apple GPU; the result is pasted via the clipboard and ⌘V, then the old clipboard is restored. Very short or silent clips are ignored, and common Whisper "hallucinations" are filtered out.
- Mixed mode gives Whisper a code-switched Arabic/English example plus your vocabulary as a starting prompt, which keeps each language in its own script.
- `arabic_phrases.py`: converts Islamic phrases Whisper wrote in Latin letters (many spellings) into Arabic script.
- `bubble.py`: the floating indicator (a borderless, non-activating panel that never takes focus).
- `install.sh` / `uninstall.sh`: set up or remove the login agent `com.jaryah.localflow`.
