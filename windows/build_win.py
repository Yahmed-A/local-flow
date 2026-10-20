"""Builds the Windows app: dist/LocalFlow/LocalFlow.exe, then LocalFlowSetup.exe if Inno Setup is installed.

Run on Windows from the repo root:  py windows\build_win.py
(GitHub Actions runs this on every push; see .github/workflows/windows.yml.)
"""
import shutil
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent


def make_icon():
    """App icon: the same ring-and-dot as the tray icon, on a dark rounded square."""
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((8, 8, 248, 248), radius=56, fill=(20, 20, 20, 255))
    d.ellipse((60, 60, 196, 196), outline=(240, 240, 240, 255), width=18)
    d.ellipse((104, 104, 152, 152), fill=(240, 240, 240, 255))
    ico = HERE / "icon.ico"
    img.save(ico, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    return ico


def main():
    ico = make_icon()
    args = [
        sys.executable, "-m", "PyInstaller", str(HERE / "app_win.py"),
        "--name", "LocalFlow", "--noconsole", "--noconfirm", "--clean", "--icon", str(ico),
        "--paths", str(ROOT), "--paths", str(HERE),
        "--collect-all", "faster_whisper", "--collect-all", "ctranslate2", "--collect-all", "av",
        "--hidden-import", "pystray._win32", "--hidden-import", "pynput.keyboard._win32",
        "--hidden-import", "pynput.mouse._win32", "--hidden-import", "selftest",
        "--distpath", str(ROOT / "dist"), "--workpath", str(ROOT / "build"), "--specpath", str(ROOT / "build"),
    ]
    subprocess.run(args, check=True)
    iscc = shutil.which("iscc") or r"C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
    if Path(iscc).exists():
        subprocess.run([iscc, str(HERE / "installer.iss")], check=True)
        print("Installer:", ROOT / "dist" / "LocalFlowSetup.exe")
    else:
        print("Inno Setup not found: skipped the installer (the app is in dist/LocalFlow).")


if __name__ == "__main__":
    main()
