"""Local Flow for Windows: hold a key, speak, and the text is typed where your cursor is.

Everything runs on this PC (Whisper large-v3-turbo via faster-whisper, on an NVIDIA GPU
if one is usable, otherwise the CPU). Hold the hotkey to talk; release to paste.
Quick-tap it for hands-free, tap again to stop. Esc cancels.

Shares formatting.py and arabic_phrases.py with the Mac app (repo root).
"""
import ctypes
import json
import os
import re
import sys
import threading
import time
import winreg
import winsound
from pathlib import Path

import numpy as np
import sounddevice as sd
from pynput import keyboard

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from arabic_phrases import to_arabic  # noqa: E402
from formatting import format_text, paragraphs  # noqa: E402

import pystray  # noqa: E402
from PIL import Image, ImageDraw  # noqa: E402

from bubble_win import Bubble  # noqa: E402

APP = "Local Flow"
SUPPORT = Path(os.environ.get("APPDATA", Path.home())) / "LocalFlow"
SUPPORT.mkdir(parents=True, exist_ok=True)
CONFIG_FILE = SUPPORT / "config.json"
VOCAB_FILE = SUPPORT / "vocabulary.txt"
HISTORY_FILE = SUPPORT / "history.txt"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"

DEFAULT_CONFIG = {
    "hotkey": "right_ctrl",   # right_ctrl | right_alt | caps_lock | f8 | f9
    "language": "mixed",      # mixed | en | ar | auto
    "model": "large-v3-turbo",  # large-v3-turbo (accurate) | small (fast, for slower PCs)
    "sounds": True,
    "restore_clipboard": True,
    "bubble": True,
    "smart_format": True,
    "signoff_name": "",
    "input_device": "",       # "" = Windows default input
}
DEFAULT_VOCAB = """Jaryah, Kube, Kube Publishing, Deckhand, Manzil, Qur'an, hadith, ayah,
surah, tafsir, nahw, sarf, Allah, Muhammad, salallahu alayhi wa sallam, InshaAllah,
MashaAllah, Alhamdulillah, SubhanAllah, Bismillah, Ramadan, Eid, Sunnah, InDesign"""

HOTKEYS = {
    "right_ctrl": (keyboard.Key.ctrl_r, "Right Ctrl"),
    "right_alt": (keyboard.Key.alt_gr, "Right Alt / AltGr"),
    "caps_lock": (keyboard.Key.caps_lock, "Caps Lock"),
    "f8": (keyboard.Key.f8, "F8"),
    "f9": (keyboard.Key.f9, "F9"),
}
MODELS = {"large-v3-turbo": "Accurate (large-v3-turbo, ~1.6 GB)", "small": "Fast (small, ~0.5 GB, for slower PCs)"}
LANGS = {"mixed": "Arabic + English (mixed)", "en": "English only", "ar": "Arabic only", "auto": "Auto-detect"}
# Pressing these alongside the hotkey doesn't cancel (AltGr also sends a fake Left Ctrl).
MODIFIERS = {keyboard.Key.ctrl, keyboard.Key.ctrl_l, keyboard.Key.ctrl_r, keyboard.Key.shift, keyboard.Key.shift_l,
             keyboard.Key.shift_r, keyboard.Key.alt, keyboard.Key.alt_l, keyboard.Key.alt_r, keyboard.Key.alt_gr}
TAP_SECONDS = 0.3
RATE = 16000
MIN_SECONDS = 0.35
FILLERS = re.compile(r"\b(?:um+|uh+|erm+|uhm+)\b[,.]?\s*", re.I)
MIXED_PROMPT = ("السلام عليكم ورحمة الله وبركاته. قال الأستاذ: today we read باب الفاعل, and he said "
                "إن شاء الله we'll finish the book.")
HALLUCINATIONS = {"thank you.", "thanks for watching!", "thank you for watching.",
                  "you", ".", "bye.", "subtitles by the amara.org community"}


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    if CONFIG_FILE.exists():
        try:
            cfg.update(json.loads(CONFIG_FILE.read_text(encoding="utf-8")))
        except Exception:
            pass
    CONFIG_FILE.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    if not VOCAB_FILE.exists():
        VOCAB_FILE.write_text(DEFAULT_VOCAB, encoding="utf-8")
    return cfg


def play(kind):
    alias = {"start": "SystemAsterisk", "stop": "SystemDefault", "cancel": "SystemHand"}[kind]
    try:
        winsound.PlaySound(alias, winsound.SND_ALIAS | winsound.SND_ASYNC)
    except RuntimeError:
        pass


# ---------- clipboard + paste (Unicode text) ----------

CF_UNICODETEXT = 13
k32, u32 = ctypes.windll.kernel32, ctypes.windll.user32
k32.GlobalAlloc.restype = ctypes.c_void_p
k32.GlobalLock.restype = ctypes.c_void_p
k32.GlobalLock.argtypes = [ctypes.c_void_p]
k32.GlobalUnlock.argtypes = [ctypes.c_void_p]
u32.GetClipboardData.restype = ctypes.c_void_p
u32.SetClipboardData.argtypes = [ctypes.c_uint, ctypes.c_void_p]


def _open_clipboard():
    for _ in range(20):  # another app may hold it for a moment
        if u32.OpenClipboard(None):
            return True
        time.sleep(0.02)
    return False


def get_clipboard():
    if not _open_clipboard():
        return None
    try:
        h = u32.GetClipboardData(CF_UNICODETEXT)
        if not h:
            return None
        p = k32.GlobalLock(h)
        try:
            return ctypes.wstring_at(p)
        finally:
            k32.GlobalUnlock(h)
    finally:
        u32.CloseClipboard()


def set_clipboard(text):
    if not _open_clipboard():
        return False
    try:
        u32.EmptyClipboard()
        data = ctypes.create_unicode_buffer(text)
        size = ctypes.sizeof(data)
        h = k32.GlobalAlloc(0x0042, size)  # GMEM_MOVEABLE | GMEM_ZEROINIT
        p = k32.GlobalLock(h)
        ctypes.memmove(p, data, size)
        k32.GlobalUnlock(h)
        u32.SetClipboardData(CF_UNICODETEXT, h)
        return True
    finally:
        u32.CloseClipboard()


_kb = keyboard.Controller()


def paste(text, restore=True):
    saved = get_clipboard() if restore else None
    set_clipboard(text)
    time.sleep(0.05)
    with _kb.pressed(keyboard.Key.ctrl):
        _kb.press("v")
        _kb.release("v")
    if restore and saved is not None:
        time.sleep(0.4)
        set_clipboard(saved)


# ---------- audio ----------

class Recorder:
    def __init__(self):
        self.chunks, self.stream, self.level = [], None, 0.0

    @staticmethod
    def inputs(rescan=False):
        if rescan:
            sd._terminate()
            sd._initialize()
        # One entry per device: Windows lists each mic once per audio API; keep the MME/default-API ones.
        api = sd.default.hostapi if isinstance(sd.default.hostapi, int) else 0
        names = [d["name"] for d in sd.query_devices() if d["max_input_channels"] > 0 and d["hostapi"] == api]
        return list(dict.fromkeys(names))

    @staticmethod
    def current_name(chosen):
        if chosen and chosen in Recorder.inputs():
            return chosen
        try:
            return sd.query_devices(kind="input")["name"]
        except Exception:
            return "none"

    def start(self, device_name=""):
        self.chunks = []
        self.inputs(rescan=True)
        device = next((i for i, d in enumerate(sd.query_devices())
                       if d["name"] == device_name and d["max_input_channels"] > 0), None)
        self.stream = sd.InputStream(samplerate=RATE, channels=1, dtype="float32",
                                     device=device, callback=self._on_audio)
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
    """faster-whisper; tries the NVIDIA GPU first and falls back to the CPU."""

    def __init__(self, cfg):
        self.cfg, self.ready, self.model, self.device = cfg, False, None, "cpu"

    def load(self):
        from faster_whisper import WhisperModel
        name = self.cfg.get("model", "large-v3-turbo")
        for device, compute in (("cuda", "float16"), ("cpu", "int8")):
            try:
                m = WhisperModel(name, device=device, compute_type=compute)
                list(m.transcribe(np.zeros(RATE, np.float32), language="en")[0])  # warm-up; fails if CUDA libs are missing
                self.model, self.device = m, device
                break
            except Exception as e:  # noqa: BLE001
                if device == "cpu":
                    raise
                print(f"[gpu unavailable] {e}")
        self.ready = True

    def __call__(self, audio):
        vocab = " ".join(VOCAB_FILE.read_text(encoding="utf-8").split()) if VOCAB_FILE.exists() else ""
        lang = self.cfg["language"]
        style = MIXED_PROMPT if lang == "mixed" else "Hello, here is a well-punctuated sentence."
        segs, _ = self.model.transcribe(
            audio, language={"auto": None, "mixed": "en"}.get(lang, lang),
            initial_prompt=(f"Glossary: {vocab}. " if vocab else "") + style,
            condition_on_previous_text=False, beam_size=1,  # greedy, like mlx-whisper on the Mac
        )
        segments = [{"start": s.start, "end": s.end, "text": s.text} for s in segs]
        return {"text": "".join(s["text"] for s in segments), "segments": segments}


def clean(text):
    text = FILLERS.sub("", text).strip()
    text = re.sub(r"[ \t]+", " ", text)
    return "" if text.lower() in HALLUCINATIONS else text


# ---------- start at login ----------

def login_enabled():
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, APP)
            return True
    except OSError:
        return False


def set_login(on):
    exe = f'"{sys.executable}"' if getattr(sys, "frozen", False) else f'"{sys.executable}" "{Path(__file__).resolve()}"'
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if on:
            winreg.SetValueEx(k, APP, 0, winreg.REG_SZ, exe)
        else:
            try:
                winreg.DeleteValue(k, APP)
            except OSError:
                pass


# ---------- tray icon ----------

def icon_image(state):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    ink = (40, 40, 40, 255)
    if state == "rec":
        d.ellipse((10, 10, 54, 54), fill=(200, 40, 40, 255))
    elif state == "busy":
        d.ellipse((8, 8, 56, 56), outline=ink, width=6)
        d.pieslice((8, 8, 56, 56), 90, 270, fill=ink)
    elif state == "error":
        d.polygon([(32, 6), (60, 56), (4, 56)], fill=(210, 140, 0, 255))
    elif state == "loading":
        for a in range(0, 360, 45):
            d.arc((8, 8, 56, 56), a, a + 25, fill=ink, width=6)
    else:  # idle
        d.ellipse((8, 8, 56, 56), outline=ink, width=6)
        d.ellipse((24, 24, 40, 40), fill=ink)
    return img


class LocalFlow:
    def __init__(self):
        self.cfg = load_config()
        self.rec, self.stt = Recorder(), Transcriber(self.cfg)
        self.state, self.status_text = "loading", "Loading model…"
        self.recording = self.handsfree = self.key_down = False
        self.down_at, self.last_text = 0.0, ""
        self.bubble = Bubble()
        self.icon = pystray.Icon(APP, icon_image("loading"), APP, menu=self.menu())

    # ---- menu ----
    def menu(self):
        M, I = pystray.Menu, pystray.MenuItem

        def radio(key, value, label):
            return I(label, lambda: self.set(key, value), checked=lambda _: self.cfg.get(key) == value, radio=True)

        def mics():
            chosen = self.cfg.get("input_device", "")
            items = [I("Windows default", lambda: self.set("input_device", ""), checked=lambda _: not self.cfg.get("input_device"), radio=True)]
            for n in Recorder.inputs():
                items.append(I(n, (lambda name: lambda: self.set("input_device", name))(n),
                               checked=(lambda name: lambda _: self.cfg.get("input_device") == name)(n), radio=True))
            if chosen and chosen not in Recorder.inputs():
                items.append(I(f"{chosen} (not connected)", None, enabled=False))
            items += [M.SEPARATOR, I("Refresh list", lambda: (Recorder.inputs(True), self.icon.update_menu()))]
            return items

        return M(
            I(lambda _: self.status_text, None, enabled=False),
            M.SEPARATOR,
            I("Language", M(*[radio("language", k, v) for k, v in LANGS.items()])),
            I("Hotkey", M(*[radio("hotkey", k, v[1]) for k, v in HOTKEYS.items()])),
            I(lambda _: f"Microphone: {Recorder.current_name(self.cfg.get('input_device', ''))}", M(mics)),
            I("Speech model", M(*[radio("model", k, v) for k, v in MODELS.items()])),
            M.SEPARATOR,
            I("Paste last dictation again", lambda: self.repaste(), enabled=lambda _: bool(self.last_text)),
            I("Show floating bubble", lambda: self.toggle("bubble"), checked=lambda _: self.cfg.get("bubble", True)),
            I("Smart paragraphs & direction", lambda: self.toggle("smart_format"), checked=lambda _: self.cfg.get("smart_format", True)),
            I("Sounds", lambda: self.toggle("sounds"), checked=lambda _: self.cfg.get("sounds", True)),
            I(lambda _: f"Sign-off name: {self.cfg['signoff_name']}…" if self.cfg.get("signoff_name") else "Sign-off name…",
              lambda: self.bubble.ask("Sign-off name",
                                      "Your name is added on its own line after sign-offs like Kind regards or "
                                      "JazakAllahu khayran in longer messages, unless you say a name. Leave empty to turn off.",
                                      self.cfg.get("signoff_name", ""), lambda v: self.set("signoff_name", v.strip()))),
            I("Edit vocabulary…", lambda: os.startfile(VOCAB_FILE)),
            I("Open history", lambda: os.startfile(HISTORY_FILE) if HISTORY_FILE.exists() else None),
            I("Start when Windows starts", lambda: (set_login(not login_enabled()), self.icon.update_menu()),
              checked=lambda _: login_enabled()),
            M.SEPARATOR,
            I("Quit Local Flow", lambda: self.quit()),
        )

    def save(self):
        CONFIG_FILE.write_text(json.dumps(self.cfg, indent=2), encoding="utf-8")
        self.icon.update_menu()

    def set(self, key, value):
        reload = key == "model" and self.cfg.get(key) != value
        self.cfg[key] = value
        self.save()
        if reload:
            self.state = "loading"
            threading.Thread(target=self.load_model, daemon=True).start()

    def toggle(self, key):
        self.set(key, not self.cfg.get(key, True))

    def repaste(self):
        if self.last_text:
            threading.Thread(target=paste, args=(self.last_text, self.cfg["restore_clipboard"]), daemon=True).start()

    def quit(self):
        self.icon.stop()
        self.bubble.close()

    # ---- state shown in tray + bubble ----
    def tick(self):
        label = HOTKEYS[self.cfg["hotkey"]][1]
        text = {
            "loading": "Loading model… (first run downloads it)",
            "idle": f"Ready: hold {label} to talk" + (" · GPU" if self.stt.device == "cuda" else ""),
            "rec": "Listening… (Esc to cancel)" + (" [hands-free]" if self.handsfree else ""),
            "busy": "Transcribing…",
            "error": "Error, see history file",
        }[self.state]
        if text != self.status_text or getattr(self, "_shown", None) != self.state:
            self.status_text, self._shown = text, self.state
            self.icon.icon = icon_image(self.state)
            self.icon.title = f"{APP}: {text}"
            self.icon.update_menu()
        self.bubble.update(self.state if self.cfg.get("bubble", True) else "idle",
                           self.rec.level if self.state == "rec" else None, self.handsfree)

    # ---- model ----
    def load_model(self):
        try:
            self.stt.load()
            self.state = "idle"
        except Exception as e:  # noqa: BLE001
            self.log(f"[model error] {e}")
            self.state = "error"

    # ---- keyboard ----
    def hotkey(self):
        return HOTKEYS[self.cfg["hotkey"]][0]

    def on_press(self, key):
        if key == keyboard.Key.esc:
            if self.recording:
                self.finish(cancel=True)
            return
        if key == self.hotkey() or (self.cfg["hotkey"] == "right_alt" and key == keyboard.Key.alt_r):
            if not self.key_down:
                self.key_down, self.down_at = True, time.time()
                if self.recording and self.handsfree:
                    self.finish()
                elif not self.recording:
                    self.begin()
        elif self.key_down and self.recording and not self.handsfree and key not in MODIFIERS:
            self.finish(cancel=True)  # hotkey used with another key (e.g. Ctrl+C), not dictation

    def on_release(self, key):
        if key == self.hotkey() or (self.cfg["hotkey"] == "right_alt" and key == keyboard.Key.alt_r):
            if self.key_down:
                self.key_down = False
                if self.recording and not self.handsfree:
                    if time.time() - self.down_at < TAP_SECONDS:
                        self.handsfree = True
                    else:
                        self.finish()

    # ---- dictation ----
    def begin(self):
        if self.state != "idle":
            return
        try:
            self.rec.start(self.cfg.get("input_device", ""))
        except Exception as e:  # noqa: BLE001
            self.log(f"[mic error] {e}")
            self.icon.notify("Couldn't open the microphone. Pick another one in the tray menu → Microphone.", APP)
            return
        self.recording, self.handsfree, self.state = True, False, "rec"
        if self.cfg["sounds"]:
            play("start")

    def finish(self, cancel=False):
        if not self.recording:
            return
        self.recording, self.handsfree = False, False
        audio = self.rec.stop()
        if not cancel and len(audio) >= RATE * MIN_SECONDS and np.abs(audio).max() < 1e-4:
            name = Recorder.current_name(self.cfg.get("input_device", ""))
            self.log(f"[silent mic] {name}")
            self.icon.notify(f"No sound from {name}. Pick another microphone in the tray menu → Microphone.", APP)
        if cancel or len(audio) < RATE * MIN_SECONDS or np.sqrt(np.mean(audio ** 2)) < 0.003:
            self.state = "idle"
            if self.cfg["sounds"]:
                play("cancel" if cancel else "stop")
            return
        if self.cfg["sounds"]:
            play("stop")
        self.state = "busy"
        threading.Thread(target=self.transcribe, args=(audio,), daemon=True).start()

    def transcribe(self, audio):
        try:
            self.last_text = self.process(self.stt(audio), audio) or self.last_text
            if self.last_text and self.state == "busy":
                self.log(self.last_text)
                paste(self.last_text, self.cfg["restore_clipboard"])
        except Exception as e:  # noqa: BLE001
            self.log(f"[error] {e}")
        self.state = "idle"

    def process(self, r, audio):
        """Whisper output → final text (same steps as the Mac app)."""
        smart = self.cfg.get("smart_format", True)
        parts = paragraphs(r["segments"], audio) if smart and r.get("segments") else [r["text"]]
        text = clean("\n\n".join(clean(p) for p in parts if clean(p)))
        if self.cfg["language"] != "en":
            text = to_arabic(text)
        if smart and text:
            text = format_text(text, self.cfg.get("signoff_name", ""))
        return text

    def log(self, line):
        with open(HISTORY_FILE, "a", encoding="utf-8") as f:
            f.write(time.strftime("%Y-%m-%d %H:%M  ") + line + "\n")

    def run(self):
        threading.Thread(target=self.load_model, daemon=True).start()
        keyboard.Listener(on_press=self.on_press, on_release=self.on_release, daemon=True).start()
        threading.Thread(target=self.icon.run, daemon=True).start()
        self.bubble.run(self.tick)  # tkinter owns the main thread; returns on Quit


def single_instance():
    """Only one Local Flow at a time (a second launch just exits)."""
    ctypes.windll.kernel32.CreateMutexW(None, False, "LocalFlowSingleInstance")
    return ctypes.windll.kernel32.GetLastError() != 183  # ERROR_ALREADY_EXISTS


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        from selftest import main as selftest
        sys.exit(selftest())
    if single_instance():
        LocalFlow().run()
