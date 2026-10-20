"""What the agent recognizes in the callee's words before, or instead of, asking the interview engine.

The backend interview engine interprets answers. The agent only tells it what kind of turn it was (the
signal) and handles the turns that never reach it: hesitations, "Alo?" while the call goes on, and words
said after the interview ended.
"""
import re
import unicodedata

HEARD = "Evet, sizi duyuyorum."
# "Biraz önce de aramıştınız": the callee was already called; the agent apologises before going on.
ALREADY_CALLED = ("aramistiniz", "aradiniz zaten", "zaten aradiniz", "daha once aradiniz", "cevap vermistim",
                  "zaten cevap verdim")
CALLED_AGAIN = "Kusura bakmayın, kısa bir kontrol için tekrar arıyoruz."
# Ending the survey. A bare "istemiyorum" only counts before the questions: during them it is usually
# about the question ("söylemek istemiyorum") or a candidate ("Erdoğan'ı istemiyorum").
STOP_SURVEY = ("rahatsiz etmeyin", "aramayin", "ilgilenmiyorum", "anket istemiyorum", "devam etmek istemiyorum",
               # a callee who is leaving gets a goodbye, not the question again (one hung up after that)
               "kapatiyorum", "kapatacagim", "kapatmam lazim", "mesgulum", "musait degilim", "simdi olmaz")
IDENTITY_QUESTIONS = ("kimsiniz", "kim ariyor", "nereden ariyorsunuz", "siz kimsiniz")
REASON_QUESTIONS = ("neden", "niye", "nicin", "belli degil mi", "ne gerek var")
REPEAT_REQUESTS = ("tekrar", "anlamadim", "ne dediniz", "efendim", "pardon", "yavas konusur", "yavas konusun",
                   "yavas konusabilir", "yavas soyler", "yavas soyleyin")
# A longer reply with "tekrar" or "neden" in it is an answer ("Erdoğan tekrar seçilsin"), not a request.
SHORT_REQUEST_WORDS = 6
# Short sounds that arrive before the agent's reply has been played cannot answer the next question; the
# recognizer also produced a phantom "Evet." from near-silence in the first test call.
FILLERS = {"", "evet", "tamam", "hi", "hm", "hmm", "ee", "eee", "aha", "alo", "efendim", "he", "hi hi"}
# "Eee", "hmm", "şey": the callee is thinking. Answering it cut in on a callee who was about to give a rating.
HESITATION = re.compile(r"(e+|i+|m+|h+m+|a+h+|sey|yani)( (e+|i+|m+|h+m+|a+h+|sey|yani))*")
# The callee checking whether the line works, usually because they did not hear the agent.
PRESENCE_CHECKS = ("alo", "duyuyor musunuz", "orada misiniz", "sesim geliyor mu", "sesimi duyuyor musunuz")
# Numbers a longer number starts with ("on" dokuz, "yüz" yirmi), as words or digits.
NUMBER_PREFIXES = {"on", "yirmi", "otuz", "kirk", "elli", "altmis", "yetmis", "seksen", "doksan", "yuz",
                   "10", "20", "30", "40", "50", "60", "70", "80", "90", "100"}
# A turn made only of these is a line check, not an answer: "Alo? Alo, beni duyuyor musunuz?"
_LINE_CHECK = re.compile(r"((alo|beni|sesimi|duyuyor musunuz|orada misiniz|sesim geliyor mu) ?)+")


def normalize(text: str) -> str:
    folded = text.lower().translate(str.maketrans("ıİşŞğĞüÜöÖçÇ", "iissgguuoocc"))
    folded = unicodedata.normalize("NFD", folded)
    folded = "".join(ch for ch in folded if unicodedata.category(ch) != "Mn")
    return re.sub(r"[^a-z0-9 ]+", " ", folded).strip()


def is_hesitation(text: str) -> bool:
    return HESITATION.fullmatch(" ".join(normalize(text).split())) is not None


def has_any(text: str, *phrases: str) -> bool:
    padded = f" {text} "
    return any(f" {phrase} " in padded for phrase in phrases)


def is_line_check(text: str) -> bool:
    """True for a turn that only checks whether the line works."""
    words = " ".join(normalize(text).split())
    return bool(words) and _LINE_CHECK.fullmatch(words + " ") is not None


def may_continue_number(text: str) -> bool:
    """True when a turn ends in a number a longer one starts with: "On." may be the start of "on dokuz".
    Speech recognition gave "On dokuz" as two turns in a test call, and "on" was recorded as the age."""
    words = normalize(text).split()
    return bool(words) and words[-1] in NUMBER_PREFIXES


def signal_for(text: str) -> str:
    """The interview engine's signal for a turn (InterviewConversationSignal)."""
    words = " ".join(normalize(text).split())
    short = len(words.split()) <= SHORT_REQUEST_WORDS
    if has_any(words, *STOP_SURVEY):
        return "STOP_REQUEST"
    if has_any(words, *IDENTITY_QUESTIONS):
        return "IDENTITY_REQUEST"
    # "Neden olmasın" agrees.
    if short and has_any(words, *REASON_QUESTIONS) and not has_any(words, "neden olmasin", "niye olmasin"):
        return "REASON_REQUEST"
    if short and has_any(words, *REPEAT_REQUESTS):
        return "REPEAT_REQUEST"
    return "ANSWER"
