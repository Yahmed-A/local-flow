"""Smart layout for dictated text: paragraphs and text direction.

- greeting (salaam, "Hi Sarah,", "Dear ...,") on its own line
- sign-off ("Thanks.", "JazakAllahu khayran", "Kind regards, Yasir") as its own paragraph
- new paragraph where the speaker paused between thoughts
- spoken "new paragraph" / "new line" commands
- direction marks so a mostly-English message stays left-aligned even when a
  line starts with Arabic (and the reverse for mostly-Arabic messages)
"""
import re

import numpy as np

from arabic_phrases import ALAIKUM, S, SALAM, TAIL

LRM, RLM = "‎", "‏"
ARABIC = re.compile(r"[؀-ۿݐ-ݿﭐ-﷿ﹰ-﻿]")
LATIN = re.compile(r"[A-Za-z]")
SENT_END = re.compile(r"[.!?؟]['\"”’)]*$")
SENTENCES = re.compile(r"(?<=[.!?؟])\s+")

PAUSE_SECONDS = 0.9   # silence this long between sentences starts a new paragraph
MAX_SENTENCES = 5     # ...and a paragraph this long breaks at the next sentence end

GREETING = re.compile(
    rf"^\s*(?:(?:و?)?السلام عليكم(?: ورحمة الله)?(?: وبركاته)?|{SALAM}{S}{ALAIKUM}{TAIL}"
    r"|(?:hi|hello|hey|dear|salaam|salam|greetings|good (?:morning|afternoon|evening))\b)",
    re.I)
NOT_A_NAME = {"i", "we", "hope", "just", "can", "could", "please", "so", "this", "the", "it",
              "you", "how", "what", "is", "are", "i'm", "i've", "thanks", "thank"}
CLOSING = re.compile(
    r"^(?:thanks|thank you|many thanks|thanks again|kind regards|best regards|warm regards|regards"
    r"|best wishes|all the best|best|cheers|sincerely|yours sincerely|yours faithfully|take care"
    r"|speak soon|talk soon|wassalam|was-?salam|ma'?a ?salama"
    r"|جزاك الله خيرا|جزاكم الله خيرا|بارك الله فيك|والسلام عليكم|والسلام|مع السلامة|شكرا)\b",
    re.I)
COMMANDS = [  # the comma before a spoken command becomes a full stop
    (re.compile(r"([,،])?\s*\b(?:new|next) paragraph\b[,.!]?\s*", re.I), "\n\n"),
    (re.compile(r"([,،])?\s*\bnew line\b[,.!]?\s*", re.I), "\n"),
    (re.compile(r"([,،])?\s*فقرة جديدة[,.،]?\s*"), "\n\n"),
    (re.compile(r"([,،])?\s*سطر جديد[,.،]?\s*"), "\n"),
]


# ---------- paragraphs from pauses ----------

def silences(audio, rate=16000, min_len=PAUSE_SECONDS):
    """(start, end) seconds of every quiet stretch at least min_len long."""
    frame = int(rate * 0.03)
    n = len(audio) // frame
    if n < 2:
        return []
    rms = np.sqrt(np.mean(audio[: n * frame].reshape(n, frame) ** 2, axis=1))
    quiet = rms < max(0.006, 0.12 * np.percentile(rms, 90))
    out, start = [], None
    for i, q in enumerate(np.append(quiet, False)):
        if q and start is None:
            start = i
        elif not q and start is not None:
            if (i - start) * 0.03 >= min_len:
                out.append((start * 0.03, i * 0.03))
            start = None
    return out


def paragraphs(segments, audio):
    """Join Whisper segments, breaking where the speaker paused after a sentence."""
    sil = silences(audio)
    paras = [[]]
    for i, seg in enumerate(segments):
        text = seg["text"].strip()
        if not text:
            continue
        cur = paras[-1]
        if cur and SENT_END.search(cur[-1]):
            prev_end, start = segments[i - 1]["end"], seg["start"]
            paused = any(s <= start + 0.4 and e >= prev_end - 0.4 for s, e in sil)
            n = len(SENTENCES.split(" ".join(cur)))
            if (paused and n >= 2) or n >= MAX_SENTENCES:
                paras.append([])
        paras[-1].append(text)
    return [" ".join(p) for p in paras if p]


# ---------- text rules ----------

def _cap(s):
    s = s.lstrip()
    return s[:1].upper() + s[1:] if s and s[0].isalpha() else s


def _words(s):
    return len(s.split())


def split_greeting(text):
    m = GREETING.match(text)
    if not m:
        return text
    rest = text[m.end():]
    # take a short name / vocative after the greeting, up to the first comma or stop
    v = re.match(r"^((?:[ ,،]*[^\s,.!?؟،]+){0,3}?)\s*([,.!،])", rest)
    if v and not any(w.lower().strip("'") in NOT_A_NAME for w in v.group(1).split()):
        head, body = text[: m.end() + v.end()], rest[v.end():]
    else:
        head, body = text[: m.end()], rest
        body = re.sub(r"^\s*[,.!،]", "", body)
    body = body.strip()
    is_salaam = bool(ARABIC.search(head)) or bool(re.search(r"sala+m", head, re.I))
    if not body or (not is_salaam and _words(body) < 8):
        return text  # short chat message: keep on one line
    if body.startswith("\n"):
        return head.rstrip() + "\n\n" + body.lstrip("\n")
    return head.rstrip() + "\n\n" + _cap(body)


def split_closing(text):
    paras = text.split("\n\n")
    last = paras[-1]
    sents = SENTENCES.split(last)
    if len(paras) == 1 and len(sents) < 2:
        return text
    k = 1
    tail = sents[-1].strip()
    # "Kind regards. Yasir." (Whisper split the name off as its own sentence)
    if (len(sents) >= 2 and _words(tail) <= 2 and CLOSING.match(sents[-2].strip())
            and _words(sents[-2]) <= 4 and not CLOSING.match(tail)):
        tail = sents[-2].strip().rstrip(".") + ", " + tail.rstrip(".")
        k = 2
    if not CLOSING.match(tail) or _words(tail) > 5:
        return text
    # "Kind regards, Yasir." -> name on the next line
    m = re.match(r"^(.*?[,،])\s*([^\s,.]+(?: [^\s,.]+)?)[.!]?$", tail)
    if m and not CLOSING.match(m.group(2)) and _words(m.group(1)) <= 3:
        tail = m.group(1) + "\n" + m.group(2)
    before = last[: last.rfind(sents[-k])].strip()
    if len(paras) == 1 and _words(before) < 12:
        return text  # short chat message: "Can you call me? Thanks." stays as is
    paras[-1] = (before + "\n\n" if before else "") + tail
    return "\n\n".join(p for p in paras if p.strip())


def apply_commands(text):
    for pat, rep in COMMANDS:
        text = pat.sub(lambda m, rep=rep: ("." if m.group(1) else "") + rep, text)
    lines = [_cap(l.strip()) for l in text.split("\n")]
    return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()


def fix_direction(text):
    """Make every line follow the message's main language: mostly English ->
    left-to-right lines, mostly Arabic -> right-to-left lines."""
    latin, arabic = len(LATIN.findall(text)), len(ARABIC.findall(text))
    if not latin or not arabic:
        return text
    mark, main = (LRM, LATIN) if latin >= arabic else (RLM, ARABIC)
    out = []
    for line in text.split("\n"):
        first = re.search(r"[A-Za-z؀-ۿ]", line)
        if first and not main.match(first.group()):
            line = mark + line
        out.append(line)
    return "\n".join(out)


def format_text(text):
    text = apply_commands(text)
    text = split_greeting(text)
    text = split_closing(text)
    return fix_direction(text)
