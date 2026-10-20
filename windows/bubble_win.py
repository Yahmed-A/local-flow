"""Floating bubble for Windows (bottom centre, above the taskbar), same look as the Mac one:
a dark pill with live level bars while listening, a sweep while transcribing, and a red
square in hands-free mode.

The window never takes focus (WS_EX_NOACTIVATE) and clicks pass through it, so the paste
still lands in the app you were typing in. It is never hidden/shown, only faded, because
re-showing a window can activate it. Tkinter must own the main thread; other threads talk
to it through a queue.
"""
import ctypes
import math
import queue
import time
import tkinter as tk
from collections import deque

W, H = 150, 36
BARS, BAR_W, GAP = 14, 3.0, 3.5
KEY = "#ff00fe"  # transparent colour key around the rounded pill

GWL_EXSTYLE = -20
WS_EX_LAYERED, WS_EX_TRANSPARENT = 0x80000, 0x20
WS_EX_TOOLWINDOW, WS_EX_NOACTIVATE, WS_EX_TOPMOST = 0x80, 0x8000000, 0x8


def work_area():
    class RECT(ctypes.Structure):
        _fields_ = [("l", ctypes.c_long), ("t", ctypes.c_long), ("r", ctypes.c_long), ("b", ctypes.c_long)]
    r = RECT()
    ctypes.windll.user32.SystemParametersInfoW(0x30, 0, ctypes.byref(r), 0)  # SPI_GETWORKAREA
    return r.l, r.t, r.r, r.b


class Bubble:
    def __init__(self):
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)  # crisp on high-DPI screens
        except Exception:  # noqa: BLE001
            pass
        self.root = tk.Tk()
        self.root.withdraw()
        self.win = tk.Toplevel(self.root)
        self.win.overrideredirect(True)
        self.win.attributes("-topmost", True, "-alpha", 0.0, "-transparentcolor", KEY)
        self.win.configure(bg=KEY)
        self.scale = self.root.winfo_fpixels("1i") / 96
        w, h = int(W * self.scale), int(H * self.scale)
        self.canvas = tk.Canvas(self.win, width=w, height=h, bg=KEY, highlightthickness=0)
        self.canvas.pack()
        l, _, r, b = work_area()
        self.win.geometry(f"{w}x{h}+{(l + r - w) // 2}+{b - h - int(14 * self.scale)}")
        self.win.update_idletasks()
        hwnd = ctypes.windll.user32.GetParent(self.win.winfo_id()) or self.win.winfo_id()
        ex = ctypes.windll.user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
        ctypes.windll.user32.SetWindowLongW(hwnd, GWL_EXSTYLE,
                                            ex | WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TOPMOST)
        self.levels = deque([0.0] * BARS, maxlen=BARS)
        self.state, self.handsfree, self.alpha = "idle", False, 0.0
        self.jobs: "queue.Queue" = queue.Queue()

    # ---- called from any thread ----
    def update(self, state, level=None, handsfree=False):
        self.state, self.handsfree = state, handsfree
        if level is not None:
            self.levels.append(level)

    def ask(self, title, prompt, default, on_done):
        self.jobs.put(("ask", title, prompt, default, on_done))

    def close(self):
        self.jobs.put(("quit",))

    # ---- main thread ----
    def run(self, tick):
        def loop():
            while not self.jobs.empty():
                job = self.jobs.get()
                if job[0] == "quit":
                    self.root.quit()
                    return
                self._ask(*job[1:])
            tick()
            self._draw()
            self.root.after(40, loop)
        self.root.after(40, loop)
        self.root.mainloop()

    def _ask(self, title, prompt, default, on_done):
        d = tk.Toplevel(self.root)
        d.title(title)
        d.attributes("-topmost", True)
        d.resizable(False, False)
        tk.Label(d, text=prompt, wraplength=360, justify="left").pack(padx=16, pady=(14, 8), anchor="w")
        v = tk.StringVar(value=default)
        e = tk.Entry(d, textvariable=v, width=40)
        e.pack(padx=16, fill="x")
        row = tk.Frame(d)
        row.pack(pady=12, padx=16, anchor="e")

        def save(_=None):
            on_done(v.get())
            d.destroy()
        tk.Button(row, text="Cancel", width=10, command=d.destroy).pack(side="left", padx=4)
        tk.Button(row, text="Save", width=10, command=save, default="active").pack(side="left")
        d.bind("<Return>", save)
        d.bind("<Escape>", lambda _: d.destroy())
        d.update_idletasks()
        d.geometry(f"+{(d.winfo_screenwidth() - d.winfo_width()) // 2}+{(d.winfo_screenheight() - d.winfo_height()) // 3}")
        d.focus_force()
        e.focus_set()
        e.select_range(0, "end")

    def _draw(self):
        target = 0.0 if self.state in ("idle", "loading") else 1.0
        self.alpha += (target - self.alpha) * 0.35
        if abs(self.alpha - target) < 0.02:
            self.alpha = target
        self.win.attributes("-alpha", self.alpha)
        if self.alpha <= 0:
            return
        c, s = self.canvas, self.scale
        c.delete("all")
        w, h = W * s, H * s
        r = h / 2
        # rounded pill
        c.create_oval(0, 0, h, h, fill="#141414", outline="")
        c.create_oval(w - h, 0, w, h, fill="#141414", outline="")
        c.create_rectangle(r, 0, w - r, h, fill="#141414", outline="")
        total = (BARS * BAR_W + (BARS - 1) * GAP) * s
        x0, mid = (w - total) / 2, h / 2
        now = time.time()
        for i in range(BARS):
            if self.state == "rec":
                lvl = list(self.levels)[i]
                bh, shade = (3 + lvl * 20) * s, 242
            else:  # transcribing: a soft sweep across the bars
                k = max(0.0, math.cos((i / BARS - (now * 1.2) % 1.0) * math.pi * 2)) ** 3
                bh, shade = (3 + 5 * k) * s, int(90 + 140 * k)
            x = x0 + i * (BAR_W + GAP) * s
            col = f"#{shade:02x}{shade:02x}{shade:02x}"
            c.create_rectangle(x, mid - bh / 2, x + BAR_W * s, mid + bh / 2, fill=col, outline="")
        if self.handsfree and self.state == "rec":
            q = 8 * s
            c.create_rectangle(r * 0.7, mid - q / 2, r * 0.7 + q, mid + q / 2, fill="#f2544d", outline="")
