"""Draws the launcher app icon (dark rounded square, white ◎) as a .icns."""
import os, subprocess, sys, tempfile
from AppKit import (NSBezierPath, NSBitmapImageRep, NSColor, NSGraphicsContext,
                    NSPNGFileType, NSDeviceRGBColorSpace)
from Foundation import NSMakeRect

def draw(px):
    rep = NSBitmapImageRep.alloc().initWithBitmapDataPlanes_pixelsWide_pixelsHigh_bitsPerSample_samplesPerPixel_hasAlpha_isPlanar_colorSpaceName_bytesPerRow_bitsPerPixel_(
        None, px, px, 8, 4, True, False, NSDeviceRGBColorSpace, 0, 0)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.setCurrentContext_(NSGraphicsContext.graphicsContextWithBitmapImageRep_(rep))
    s = px / 1024
    m = 100 * s
    NSColor.colorWithCalibratedWhite_alpha_(0.09, 1).setFill()
    NSBezierPath.bezierPathWithRoundedRect_xRadius_yRadius_(
        NSMakeRect(m, m, px - 2 * m, px - 2 * m), 185 * s, 185 * s).fill()
    NSColor.whiteColor().set()
    ring = NSBezierPath.bezierPathWithOvalInRect_(NSMakeRect(312 * s, 312 * s, 400 * s, 400 * s))
    ring.setLineWidth_(44 * s)
    ring.stroke()
    NSBezierPath.bezierPathWithOvalInRect_(NSMakeRect(437 * s, 437 * s, 150 * s, 150 * s)).fill()
    NSGraphicsContext.restoreGraphicsState()
    return rep.representationUsingType_properties_(NSPNGFileType, None)

out = sys.argv[1]
d = tempfile.mkdtemp() + "/icon.iconset"
os.makedirs(d)
for size in (16, 32, 128, 256, 512):
    for scale in (1, 2):
        name = f"icon_{size}x{size}{'@2x' if scale == 2 else ''}.png"
        draw(size * scale).writeToFile_atomically_(f"{d}/{name}", True)
subprocess.run(["iconutil", "-c", "icns", d, "-o", out], check=True)
