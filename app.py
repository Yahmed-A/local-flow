"""Local Flow: hold a key, speak, and the text is typed where your cursor is.

Everything runs on this Mac (Whisper large-v3-turbo on the GPU via MLX).
Hold the hotkey to talk; release to paste. Quick-tap it for hands-free,
tap again to stop. Esc cancels.
"""
import json
import os
import re
import threading
import time
from pathlib import Path

import numpy as np
import rumps
from arabic_phrases import to_arabic
from bubble import Bubble
from formatting import format_text, paragraphs
import sounddevice as sd
import Quartz
from AppKit import NSApplication, NSPasteboard, NSPasteboardItem, NSSound
from ApplicationServices import AXIsProcessTrustedWithOptions
from CoreFoundation import (CFRunLoopAddSource, CFRunLoopGetMain,
                            kCFRunLoopCommonModes)

SUPPORT = Path.home() / "Library/Application Support/LocalFlow"
SUPPORT.mkdir(parents=True, exist_ok=True)
CONFIG_FILE = SUPPORT / "config.json"
VOCAB_FILE = SUPPORT / "vocabulary.txt"
HISTORY_FILE = SUPPORT / "history.txt"

DEFAULT_CONFIG = {
    "hotkey": "right_option",  # right_option | right_command | right_control | fn
    "language": "mixed",       # mixed | en | ar | auto
    "model": "mlx-community/whisper-large-v3-turbo",
    "sounds": True,
    "restore_clipboard": True,
    "bubble": True,
    "smart_format": True,
    "signoff_name": "",
}
DEFAULT_VOCAB = """Jaryah, Kube, Kube Publishing, Deckhand, Manzil, Qur'an, hadith, ayah,
surah, tafsir, nahw, sarf, Allah, Muhammad, salallahu alayhi wa sallam, InshaAllah,
MashaAllah, Alhamdulillah, SubhanAllah, Bismillah, Ramadan, Eid, Sunnah, InDesign"""

# keycode, modifier flag that is set while the key is down
HOTKEYS = {
    "right_option": (61, Quartz.kCGEventFlagMaskAlternate),
    "right_command": (54, Quartz.kCGEventFlagMaskCommand),
    "right_control": (62, Quartz.kCGEventFlagMaskControl),
    "fn": (63, Quartz.kCGEventFlagMaskSecondaryFn),
}
ESC = 53
TAP_SECONDS = 0.3      # shorter press = tap (hands-free toggle)
RATE = 16000
MIN_SECONDS = 0.35
FILLERS = re.compile(r"\b(?:um+|uh+|erm+|uhm+)\b[,.]?\s*", re.I)
# A code-switched example steers Whisper to keep each language in its own script
MIXED_PROMPT = ("السلام عليكم ورحمة الله وبركاته. قال الأستاذ: today we read باب الفاعل, and he said "
                "إن شاء الله we'll finish the book.")
HALLUCINATIONS = {"thank you.", "thanks for watching!", "thank you for watching.",
                  "you", ".", "bye.", "subtitles by the amara.org community"}


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    if CONFIG_FILE.exists():
        try:
            cfg.update(json.loads(CONFIG_FILE.read_text()))
        except Exception:
            pass
    CONFIG_FILE.write_text(json.dumps(cfg, indent=2))
    if not VOCAB_FILE.exists():
        VOCAB_FILE.write_text(DEFAULT_VOCAB)
    return cfg


def play(name):
    s = NSSound.soundNamed_(name)
    if s:
        s.play()


class Recorder:
    def __init__(self):
        self.chunks, self.stream = [], None
        self.level = 0.0

    def start(self):
        self.chunks = []
        self.stream = sd.InputStream(samplerate=RATE, channels=1, dtype="float32",
                                     callback=self._on_audio)
        self.stream.start()

    def _on_audio(self, data, *_):
        self.chunks.append(data.copy())
        rms = float(np.sqrt(np.mean(data ** 2)))
        self.level = min(1.0, rms / 0.08) ** 0.6

    def stop(self):
        if self.stream:
            self.stream.stop()
            self.stream.close()
            self.stream = None
        return np.concatenate(self.chunks).flatten() if self.chunks else np.zeros(0, np.float32)


class Transcriber:
    def __init__(self, cfg):
        self.cfg, self.ready = cfg, False

    def load(self):
        import mlx_whisper
        self.mlx_whisper = mlx_whisper
        # warm-up so the first real dictation is fast
        mlx_whisper.transcribe(np.zeros(RATE, np.float32), path_or_hf_repo=self.cfg["model"],
                               language="en")
        self.ready = True

    def __call__(self, audio):
        vocab = " ".join(VOCAB_FILE.read_text().split()) if VOCAB_FILE.exists() else ""
        lang = self.cfg["language"]
        style = MIXED_PROMPT if lang == "mixed" else "Hello, here is a well-punctuated sentence."
        r = self.mlx_whisper.transcribe(
            audio, path_or_hf_repo=self.cfg["model"],
            language={"auto": None, "mixed": "en"}.get(lang, lang),
            initial_prompt=(f"Glossary: {vocab}. " if vocab else "") + style,
            condition_on_previous_text=False,
        )
        return r


def clean(text):
    text = FILLERS.sub("", text).strip()
    text = re.sub(r"[ \t]+", " ", text)
    if text.lower() in HALLUCINATIONS:
        return ""
    return text


def paste(text, restore=True):
    pb = NSPasteboard.generalPasteboard()
    saved = []
    if restore:
        for item in pb.pasteboardItems() or []:
            saved.append({t: item.dataForType_(t) for t in item.types()})
    pb.clearContents()
    pb.setString_forType_(text, "public.utf8-plain-text")
    src = Quartz.CGEventSourceCreate(Quartz.kCGEventSourceStateCombinedSessionState)
    for down in (True, False):
        e = Quartz.CGEventCreateKeyboardEvent(src, 9, down)  # V
        Quartz.CGEventSetFlags(e, Quartz.kCGEventFlagMaskCommand)
        Quartz.CGEventPost(Quartz.kCGHIDEventTap, e)
    if restore and saved:
        time.sleep(0.4)
        pb.clearContents()
        items = []
        for types in saved:
            it = NSPasteboardItem.alloc().init()
            for t, d in types.items():
                if d is not None:
                    it.setData_forType_(d, t)
            items.append(it)
        pb.writeObjects_(items)


class LocalFlow(rumps.App):
    ICONS = {"loading": "◌", "idle": "◎", "rec": "●", "busy": "◐", "error": "⚠"}

    def __init__(self):
        super().__init__("Local Flow", title=self.ICONS["loading"], quit_button=None)
        self.cfg = load_config()
        self.rec = Recorder()
        self.stt = Transcriber(self.cfg)
        self.state = "loading"
        self.recording = self.handsfree = False
        self.down_at = 0.0
        self.key_down = False
        self.lock = threading.Lock()

        self.status = rumps.MenuItem("Loading model…")
        self.lang_items = {k: rumps.MenuItem(v, callback=self.set_lang) for k, v in
                           (("mixed", "Arabic + English (mixed)"), ("en", "English only"),
                            ("ar", "Arabic only"), ("auto", "Auto-detect"))}
        self.hotkey_items = {k: rumps.MenuItem(v, callback=self.set_hotkey) for k, v in
                             (("right_option", "Right Option ⌥"), ("right_command", "Right Command ⌘"),
                              ("right_control", "Right Control ⌃"), ("fn", "Fn / Globe"))}
        self.last = rumps.MenuItem("Paste last dictation again", callback=self.repaste)
        self.bubble_item = rumps.MenuItem("Show floating bubble", callback=self.toggle_bubble)
        self.format_item = rumps.MenuItem("Smart paragraphs & direction", callback=self.toggle_format)
        self.name_item = rumps.MenuItem("Sign-off name…", callback=self.set_signoff_name)
        self.bubble = Bubble()
        self.last_text = ""
        self.menu = [
            self.status, None,
            ("Language", list(self.lang_items.values())),
            ("Hotkey", list(self.hotkey_items.values())),
            None,
            self.last,
            self.bubble_item,
            self.format_item,
            self.name_item,
            rumps.MenuItem("Edit vocabulary…", callback=lambda _: os.system(f'open -t "{VOCAB_FILE}"')),
            rumps.MenuItem("Open history", callback=lambda _: os.system(f'open -t "{HISTORY_FILE}"')),
            None,
            rumps.MenuItem("Quit Local Flow", callback=lambda _: rumps.quit_application()),
        ]
        self.refresh_checks()
        self.install_tap()
        threading.Thread(target=self.load_model, daemon=True).start()
        self._ui = rumps.Timer(self.tick, 0.04)
        self._ui.start()

    # ---- menu ----
    def refresh_checks(self):
        for k, it in self.lang_items.items():
            it.state = k == self.cfg["language"]
        for k, it in self.hotkey_items.items():
            it.state = k == self.cfg["hotkey"]
        self.bubble_item.state = self.cfg.get("bubble", True)
        self.format_item.state = self.cfg.get("smart_format", True)
        name = self.cfg.get("signoff_name", "")
        self.name_item.title = f"Sign-off name: {name}…" if name else "Sign-off name…"

    def save(self):
        CONFIG_FILE.write_text(json.dumps(self.cfg, indent=2))
        self.refresh_checks()

    def set_lang(self, item):
        self.cfg["language"] = next(k for k, v in self.lang_items.items() if v is item)
        self.save()

    def set_hotkey(self, item):
        self.cfg["hotkey"] = next(k for k, v in self.hotkey_items.items() if v is item)
        self.save()

    def repaste(self, _):
        if self.last_text:
            threading.Thread(target=paste, args=(self.last_text, self.cfg["restore_clipboard"]),
                             daemon=True).start()

    def toggle_bubble(self, _):
        self.cfg["bubble"] = not self.cfg["bubble"]
        self.save()

    def set_signoff_name(self, _):
        w = rumps.Window(
            message="Added on its own line after sign-offs like \"Kind regards\" or "
                    "\"JazakAllahu khayran\" in longer messages, unless you say a name. "
                    "Leave empty to turn off.",
            title="Sign-off name", default_text=self.cfg.get("signoff_name", ""),
            ok="Save", cancel="Cancel", dimensions=(260, 24))
        NSApplication.sharedApplication().activateIgnoringOtherApps_(True)  # bring dialog to front
        r = w.run()
        if r.clicked:
            self.cfg["signoff_name"] = r.text.strip()
            self.save()

    def toggle_format(self, _):
        self.cfg["smart_format"] = not self.cfg.get("smart_format", True)
        self.save()

    def tick(self, _):
        if self.cfg["bubble"]:
            self.bubble.update(self.state, self.rec.level if self.state == "rec" else None,
                               self.handsfree)
        else:
            self.bubble.update("idle")
        if self.title != self.ICONS[self.state]:
            self.title = self.ICONS[self.state]
        key = self.hotkey_items[self.cfg["hotkey"]].title
        self.status.title = {
            "loading": "Loading model…",
            "idle": f"Ready: hold {key} to talk",
            "rec": "Listening… (Esc to cancel)" + (" [hands-free]" if self.handsfree else ""),
            "busy": "Transcribing…",
            "error": "Error, see history file",
        }[self.state]

    # ---- model ----
    def load_model(self):
        try:
            self.stt.load()
            self.state = "idle"
        except Exception as e:
            self.log(f"[model error] {e}")
            self.state = "error"

    # ---- keyboard ----
    def install_tap(self):
        AXIsProcessTrustedWithOptions({"AXTrustedCheckOptionPrompt": True})
        mask = (Quartz.CGEventMaskBit(Quartz.kCGEventFlagsChanged) |
                Quartz.CGEventMaskBit(Quartz.kCGEventKeyDown))
        self.tap = Quartz.CGEventTapCreate(Quartz.kCGSessionEventTap, Quartz.kCGHeadInsertEventTap,
                                           Quartz.kCGEventTapOptionListenOnly, mask, self.on_event, None)
        if self.tap is None:
            self.log("[permission] grant Accessibility + Input Monitoring, then restart")
            self.state = "error"
            return
        src = Quartz.CFMachPortCreateRunLoopSource(None, self.tap, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), src, kCFRunLoopCommonModes)
        Quartz.CGEventTapEnable(self.tap, True)

    def on_event(self, proxy, etype, event, refcon):
        if etype in (Quartz.kCGEventTapDisabledByTimeout, Quartz.kCGEventTapDisabledByUserInput):
            Quartz.CGEventTapEnable(self.tap, True)
            return event
        code = Quartz.CGEventGetIntegerValueField(event, Quartz.kCGKeyboardEventKeycode)
        if etype == Quartz.kCGEventKeyDown:
            if self.recording and code == ESC:
                self.finish(cancel=True)
            elif self.key_down and self.recording and not self.handsfree:
                self.finish(cancel=True)  # hotkey used as a modifier, not dictation
            return event
        hk_code, hk_flag = HOTKEYS[self.cfg["hotkey"]]
        if code != hk_code:
            return event
        pressed = bool(Quartz.CGEventGetFlags(event) & hk_flag)
        if pressed and not self.key_down:
            self.key_down, self.down_at = True, time.time()
            if self.recording and self.handsfree:
                self.finish()
            elif not self.recording:
                self.begin()
        elif not pressed and self.key_down:
            self.key_down = False
            if self.recording and not self.handsfree:
                if time.time() - self.down_at < TAP_SECONDS:
                    self.handsfree = True  # quick tap: keep listening until next tap
                else:
                    self.finish()
        return event

    # ---- dictation ----
    def begin(self):
        if self.state != "idle":
            return
        try:
            self.rec.start()
        except Exception as e:
            self.log(f"[mic error] {e}")
            return
        self.recording, self.handsfree, self.state = True, False, "rec"
        if self.cfg["sounds"]:
            play("Tink")

    def finish(self, cancel=False):
        if not self.recording:
            return
        self.recording, self.handsfree = False, False
        audio = self.rec.stop()
        if cancel or len(audio) < RATE * MIN_SECONDS or np.sqrt(np.mean(audio ** 2)) < 0.003:
            self.state = "idle"
            if self.cfg["sounds"]:
                play("Basso" if cancel else "Pop")
            return
        if self.cfg["sounds"]:
            play("Pop")
        self.state = "busy"
        threading.Thread(target=self.transcribe, args=(audio,), daemon=True).start()

    def transcribe(self, audio):
        try:
            r = self.stt(audio)
            smart = self.cfg.get("smart_format", True)
            parts = paragraphs(r["segments"], audio) if smart and r.get("segments") else [r["text"]]
            text = clean("\n\n".join(clean(p) for p in parts if clean(p)))
            if self.cfg["language"] != "en":
                text = to_arabic(text)
            if smart and text:
                text = format_text(text, self.cfg.get("signoff_name", ""))
            if text:
                self.last_text = text
                self.log(text)
                paste(text, self.cfg["restore_clipboard"])
        except Exception as e:
            self.log(f"[error] {e}")
        self.state = "idle"

    def log(self, line):
        with open(HISTORY_FILE, "a") as f:
            f.write(time.strftime("%Y-%m-%d %H:%M  ") + line + "\n")


if __name__ == "__main__":
    LocalFlow().run()
