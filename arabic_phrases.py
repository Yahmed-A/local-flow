"""Turn common Islamic phrases that Whisper wrote in Latin letters back into Arabic script.

Whisper habitually transliterates these in English context ("Assalamu alaikum"),
so in mixed mode we convert them, tolerating the many spellings.
"""
import re

S = r"[\s\-'’]*"                                 # optional space / hyphen / apostrophe
ALLAH = rf"(?:a?l{{1,2}}){S}?a+h"                # allah, llah, -lah
WA = rf"wa?{S}"
SALAM = rf"(?:a[sl]{{0,2}}{S})?sala+m(?:u|un)?"  # assalamu, as-salamu, salaam
ALAIKUM = r"[ae]?la[iy]?[kc]um"                  # alaikum, alaykum, alikum
RAHMA = rf"ra[hḥ]mat{S}?(?:u|ul)?{S}?{ALLAH}i?"  # rahmatullahi, rahmat Allah
BARAKA = r"bara?ka+t(?:u)?(?:hu?)?"              # barakatuhu, barakatuh

RAHMA_ARABIC = " ورحمة الله"
BARAKA_ARABIC = " وبركاته"


def _greeting(arabic):
    def sub(m):
        out = arabic
        if m.group("rahma"):
            out += RAHMA_ARABIC
        if m.group("baraka"):
            out += BARAKA_ARABIC
        return out
    return sub


TAIL = rf"(?P<rahma>{S}{WA}{RAHMA})?(?P<baraka>{S}{WA}{BARAKA})?"

PHRASES = [
    (rf"{WA}{ALAIKUM}{S}{SALAM}{TAIL}", _greeting("وعليكم السلام")),
    (rf"{SALAM}{S}{ALAIKUM}{TAIL}", _greeting("السلام عليكم")),
    (rf"bis{S}?mi?{S}?l{{1,2}}a+h(?:i)?{S}(?:[aei]?r{S})?ra[hḥ]ma+ni?{S}(?:[aei]?r{S})?ra[hḥ][ie]+m", "بسم الله الرحمن الرحيم"),
    (rf"bis{S}?mi?{S}?l{{1,2}}a+h", "بسم الله"),
    (rf"in{S}sha{S}?a?{S}?{ALLAH}", "إن شاء الله"),
    (rf"ma{S}sha{S}?a?{S}?{ALLAH}", "ما شاء الله"),
    (rf"al{S}?[hḥ]amdu?{S}?li{S}?l?{ALLAH}", "الحمد لله"),
    (rf"sub[hḥ]a+na?{S}?{ALLAH}", "سبحان الله"),
    (rf"{ALLAH}u?{S}akbar", "الله أكبر"),
    (rf"astagh?firu?{S}?{ALLAH}", "أستغفر الله"),
    (rf"jaza(?:k|ki|kum){S}?{ALLAH}u?{S}k?hai?y?ra?n?", "جزاك الله خيرا"),
    (rf"ba+rak{S}?{ALLAH}u?{S}f[ie]+(?:k|kum|ki)", "بارك الله فيك"),
    (rf"la{S}ila+ha{S}il{{1,2}}a?{S}?{ALLAH}", "لا إله إلا الله"),
    (rf"sal{{1,2}}a?{S}?{ALLAH}u?{S}[ae]?la[iy]hi?{S}wa{S}?sal{{1,2}}am", "صلى الله عليه وسلم"),
    (rf"rad[iy]a?{S}?{ALLAH}u?{S}[ae]?n(?:hu|ha|hum)", "رضي الله عنه"),
]
PHRASES = [(re.compile(rf"(?<![\w]){p}(?![\w])", re.I), r) for p, r in PHRASES]


def to_arabic(text):
    for pat, rep in PHRASES:
        text = pat.sub(rep, text)
    return text
