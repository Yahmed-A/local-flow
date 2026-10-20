"""Self-test for the Windows build (run in CI and by `LocalFlow.exe --selftest`).

Speaks a sentence with Windows' built-in voice, transcribes it with the same Transcriber
and text steps the app uses, and checks the clipboard round-trip. Exit code 0 = pass.
"""
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

SENTENCE = "Please send the invoice to the publisher by Friday afternoon."


def speak_to_wav(path):
    ps = ("Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
          "$f = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, 'Sixteen', 'Mono'); "
          f"$s.SetOutputToWaveFile('{path}', $f); $s.Speak('{SENTENCE}'); $s.Dispose()")
    subprocess.run(["powershell", "-NoProfile", "-Command", ps], check=True)


class _Log:
    """Prints to the console and to %TEMP%\\localflow_selftest.log (the built .exe has no console)."""
    def __init__(self):
        self.f = open(os.path.join(tempfile.gettempdir(), "localflow_selftest.log"), "w", encoding="utf-8")
    def write(self, s):
        self.f.write(s); self.f.flush()
        if sys.__stdout__:  # Windows consoles may not be UTF-8; never fail the test over printing
            enc = sys.__stdout__.encoding or "utf-8"
            sys.__stdout__.write(s.encode(enc, "replace").decode(enc))
    def flush(self):
        pass


def main():
    sys.stdout = _Log()
    import app_win as A
    ok = True
    # 1. clipboard round-trip with Arabic
    sample = "السلام عليكم — test ✓"
    A.set_clipboard(sample)
    got = A.get_clipboard()
    print("clipboard:", "ok" if got == sample else f"FAIL ({got!r})")
    ok &= got == sample

    # 2. speech -> text through the app's own pipeline
    wav = os.path.join(tempfile.gettempdir(), "localflow_selftest.wav")
    speak_to_wav(wav)
    with wave.open(wav) as w:
        audio = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768
    model = os.environ.get("LOCALFLOW_TEST_MODEL", "tiny")
    cfg = dict(A.DEFAULT_CONFIG, model=model, language="en")
    stt = A.Transcriber(cfg)
    stt.load()
    app = type("X", (), {"cfg": cfg})()
    text = A.LocalFlow.process(app, stt(audio), audio)
    print(f"model {model} on {stt.device}: {text!r}")
    words = {"invoice", "publisher", "friday"}
    hit = sum(w in text.lower() for w in words)
    print("transcription:", "ok" if hit >= 2 else "FAIL")
    ok &= hit >= 2

    # 3. Arabic phrase conversion + formatting are shared with the Mac app
    t = A.to_arabic("Assalamu alaikum, inshaAllah we will finish.")
    print("arabic phrases:", t)
    ok &= "إن شاء الله" in t or "السلام" in t
    print("SELFTEST", "PASSED" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
