"""Floating bubble at the bottom of every screen: live voice bars while listening,
a slow sweep while transcribing. Never takes focus or clicks."""
import math
import time
from collections import deque

from AppKit import (NSBackingStoreBuffered, NSBezierPath, NSColor, NSPanel, NSScreen,
                    NSView, NSWindowCollectionBehaviorCanJoinAllSpaces,
                    NSWindowCollectionBehaviorFullScreenAuxiliary,
                    NSWindowCollectionBehaviorStationary, NSWindowStyleMaskBorderless,
                    NSWindowStyleMaskNonactivatingPanel, NSStatusWindowLevel)
from Foundation import NSMakeRect
from objc import super  # noqa: A004  (PyObjC needs its own super)

W, H = 150, 36
BARS, BAR_W, GAP = 14, 3.0, 3.5


class BubbleView(NSView):
    def initWithFrame_(self, frame):
        self = super().initWithFrame_(frame)
        if self is not None:
            self.mode = "rec"
            self.handsfree = False
            self.levels = deque([0.0] * BARS, maxlen=BARS)
        return self

    def isOpaque(self):
        return False

    def drawRect_(self, rect):
        b = self.bounds()
        pill = NSBezierPath.bezierPathWithRoundedRect_xRadius_yRadius_(
            NSMakeRect(0.5, 0.5, b.size.width - 1, b.size.height - 1), H / 2 - 0.5, H / 2 - 0.5)
        NSColor.colorWithCalibratedWhite_alpha_(0.07, 0.94).setFill()
        pill.fill()
        NSColor.colorWithCalibratedWhite_alpha_(1.0, 0.16).setStroke()
        pill.setLineWidth_(1.0)
        pill.stroke()

        total = BARS * BAR_W + (BARS - 1) * GAP
        x0 = (b.size.width - total) / 2
        mid = b.size.height / 2
        t = time.time()
        for i in range(BARS):
            if self.mode == "rec":
                # newest level in the middle, spreading outwards
                d = abs(i - (BARS - 1) / 2)
                lvl = self.levels[-1 - int(d)]
                h = 3 + lvl * 20
                alpha = 0.95
            else:  # transcribing: soft travelling sweep
                phase = (t * 2.2 - i / BARS * 2 * math.pi)
                k = (math.sin(phase) + 1) / 2
                h = 3 + 5 * k
                alpha = 0.35 + 0.5 * k
            NSColor.colorWithCalibratedWhite_alpha_(1.0, alpha).setFill()
            NSBezierPath.bezierPathWithRoundedRect_xRadius_yRadius_(
                NSMakeRect(x0 + i * (BAR_W + GAP), mid - h / 2, BAR_W, h),
                BAR_W / 2, BAR_W / 2).fill()
        if self.handsfree and self.mode == "rec":
            # small square at the right edge: "tap again to stop"
            NSColor.colorWithCalibratedRed_green_blue_alpha_(0.95, 0.33, 0.30, 0.95).setFill()
            NSBezierPath.bezierPathWithRoundedRect_xRadius_yRadius_(
                NSMakeRect(b.size.width - 19, mid - 3.5, 7, 7), 1.5, 1.5).fill()


class Bubble:
    """One pill per display, so it shows on whichever screen you are looking at."""

    def __init__(self):
        self.pills, self.layout, self.visible = [], None, False

    @staticmethod
    def _make_pill():
        style = NSWindowStyleMaskBorderless | NSWindowStyleMaskNonactivatingPanel
        win = NSPanel.alloc().initWithContentRect_styleMask_backing_defer_(
            NSMakeRect(0, 0, W, H), style, NSBackingStoreBuffered, False)
        win.setLevel_(NSStatusWindowLevel)
        win.setOpaque_(False)
        win.setBackgroundColor_(NSColor.clearColor())
        win.setHasShadow_(True)
        win.setIgnoresMouseEvents_(True)
        win.setHidesOnDeactivate_(False)
        win.setReleasedWhenClosed_(False)
        win.setCollectionBehavior_(NSWindowCollectionBehaviorCanJoinAllSpaces |
                                   NSWindowCollectionBehaviorFullScreenAuxiliary |
                                   NSWindowCollectionBehaviorStationary)
        view = BubbleView.alloc().initWithFrame_(NSMakeRect(0, 0, W, H))
        win.setContentView_(view)
        return win, view

    def _place(self):
        frames = [s.visibleFrame() for s in NSScreen.screens()]
        layout = [(f.origin.x, f.origin.y, f.size.width) for f in frames]
        if layout == self.layout:
            return
        self.layout = layout  # displays plugged in, removed or rearranged
        while len(self.pills) < len(frames):
            self.pills.append(self._make_pill())
        for win, _ in self.pills[len(frames):]:
            win.orderOut_(None)
        del self.pills[len(frames):]
        for (win, _), f in zip(self.pills, frames):
            win.setFrameOrigin_((f.origin.x + (f.size.width - W) / 2, f.origin.y + 14))

    def update(self, state, level=None, handsfree=False):
        """Call on the main thread ~25x a second."""
        if state in ("rec", "busy"):
            if not self.visible:
                self._place()
                for win, view in self.pills:
                    view.levels.extend([0.0] * BARS)
                    win.setAlphaValue_(0.0)
                    win.orderFrontRegardless()
                self.visible = True
            for win, view in self.pills:
                a = win.alphaValue()
                if a < 1:
                    win.setAlphaValue_(min(1.0, a + 0.25))
                view.mode = state
                view.handsfree = handsfree
                if level is not None:
                    view.levels.append(level)
                view.setNeedsDisplay_(True)
        elif self.visible:
            for win, _ in self.pills:
                a = win.alphaValue() - 0.2
                if a <= 0:
                    win.orderOut_(None)
                else:
                    win.setAlphaValue_(a)
            self.visible = any(win.isVisible() for win, _ in self.pills)
