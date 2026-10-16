"""The Phase 0 prototype's own three-question survey (age, municipal rating, presidential candidate).

Used for calls placed with call.py, which have no call attempt in the backend; calls the backend places
run the backend interview engine instead (backend.py). verify_call.py reads the prototype's answers with
SurveyFlow.interpret.
"""
import json
import re

from phrases import ALREADY_CALLED, CALLED_AGAIN, HEARD, PRESENCE_CHECKS, STOP_SURVEY, has_any, normalize

OPENING = "Merhaba, ben Ayna Araştırma adına kısa bir anket için arıyorum. Size birkaç soru sorabilir miyim?"
IDENTITY = "Ben Ayna Araştırma adına kısa bir anket için arıyorum."
REASON = "Bu soruyu anket gereği tüm katılımcılara soruyoruz. Cevap vermek istemezseniz bunu da söyleyebilirsiniz."
UNCLEAR = "Sizi net anlayamadım."
CONSENT_QUESTION = "Size birkaç soru sorabilir miyim?"
SILENCE = "Sizi duyamadım."
CLOSING = "Vakit ayırdığınız için teşekkür ederim. Anketimiz burada sona erdi. İyi günler dilerim."
DECLINED = "Anladım, teşekkür ederim. İyi günler dilerim."
# (code, question, shorter form used when the question has to be asked again). Gender is not asked: it is
# noted from the callee's voice over the whole call (gender.py).
QUESTIONS = [
    ("age", "Kaç yaşındasınız?", "Kaç yaşındasınız?"),
    ("rating", "Belediye hizmetlerinden ne kadar memnunsunuz? Bir ile beş arasında bir puan verir misiniz?",
     "Bir ile beş arasında kaç puan verirsiniz?"),
    # The political question comes last.
    ("candidate", "Cumhurbaşkanı adayı olarak kimi görmek istersiniz? Recep Tayyip Erdoğan, Ekrem İmamoğlu ya da "
     "Mansur Yavaş?", "Recep Tayyip Erdoğan, Ekrem İmamoğlu ve Mansur Yavaş arasından kimi aday olarak görmek istersiniz?"),
]
# Any part of a name counts ("Tayyip", "İmamoğlu'nu"); for "Yavaş", an everyday word too, see
# named_candidates. Listed in the order the question reads them out.
CANDIDATES = {
    "Recep Tayyip Erdoğan": ("recep", "tayyip", "erdogan", "rte"),
    "Ekrem İmamoğlu": ("ekrem", "imamoglu", "imam oglu"),
    "Mansur Yavaş": ("mansur",),
}
# Hints for speech recognition (Scribe realtime: up to 50 terms of up to 20 characters). It once wrote
# "Hiçbiri" as "Bir, iki, üç."
KEYTERMS = ["Recep Tayyip Erdoğan", "Erdoğan", "Ekrem İmamoğlu", "İmamoğlu", "Mansur Yavaş", "Hiçbiri",
            "Hiçbirini", "Kararsızım"]
# Question particle after each name, for "Mansur Yavaş mı, Ekrem İmamoğlu mu?"
QUESTION_PARTICLE = {"Recep Tayyip Erdoğan": "mı", "Ekrem İmamoğlu": "mu", "Mansur Yavaş": "mı"}
# "İkisi de": no choice between the names offered.
NO_CHOICE = ("ikisi de", "ikisini de", "ikisi", "fark etmez", "farketmez", "karar veremedim", "secemiyorum",
             "secemem", "bilmiyorum", "emin degilim")
# "İkincisi", "sonuncusu": a choice by the order the names were read out.
ORDINALS = {"birinci": 0, "birincisi": 0, "ilki": 0, "ikinci": 1, "ikincisi": 1, "ucuncu": 2, "ucuncusu": 2,
            "sonuncu": 2, "sonuncusu": 2}
# Replies to "X olarak not alıyorum, doğru mu?"
CONFIRM_YES = ("evet", "dogru", "aynen", "tamam", "oyle", "evet dogru")
CONFIRM_NO = ("hayir", "yanlis", "degil", "oyle degil", "yok")
UNDECIDED = ("kararsizim", "kararsiz", "bilmiyorum", "bilmem", "emin degilim", "fikrim yok", "dusunmedim")
NONE_OF_THEM = ("hicbiri", "hicbirini", "hicbirisi", "hicbire", "hicbirine", "hic biri", "hic birini", "kimseyi",
                "hic kimse")  # Scribe wrote "Eee, hiçbiri" as "hiçbire"
WITHHELD = ("soylemek istemiyorum", "soylemeyeyim", "soylemem", "cevap vermek istemiyorum", "gizli",
            "kalsin", "sizi ilgilendirmez")
# Words that are not an answer to "who", so a reply made only of them is not recorded as another name.
NOT_A_NAME = {"evet", "hayir", "tamam", "peki", "olur", "alo", "efendim", "pardon", "ne", "nasil", "hm", "hmm",
              "aday", "cumhurbaskani", "bilir", "misiniz", "mi",
              # courtesy, once recognised in place of "Hiçbiri" and read back as a name
              "tesekkurler", "tesekkur", "ederim", "sag", "olun", "sagolun", "rica"}
# "Erdoğan'ı istemiyorum", "İmamoğlu olmasın": a name with one of these is not a choice.
NEGATIONS = ("istemiyorum", "istemem", "olmasin", "degil", "asla")
# Trimmed from both ends of another name before it is recorded and read back ("Bence Özgür Özel olsun").
NAME_PADDING = {"olsun", "isterim", "derim", "diyorum", "valla", "vallahi", "bence", "tabii", "tabi", "ee", "eee",
                "yani", "sey", "herhalde", "olabilir", "bey", "hanim", "hayir", "evet"}
AGE_RANGE = (15, 110)
NUMBER_WORDS = {
    "bir": 1, "iki": 2, "uc": 3, "dort": 4, "bes": 5, "alti": 6, "yedi": 7, "sekiz": 8, "dokuz": 9,
    "on": 10, "yirmi": 20, "otuz": 30, "kirk": 40, "elli": 50, "altmis": 60, "yetmis": 70, "seksen": 80, "doksan": 90,
}


# The replies of a call that goes as planned (after the QUESTIONS they build on), synthesized while the phone
# rings if they are not cached yet.
def main_replies() -> list[str]:
    return [OPENING, "Teşekkür ederim. " + QUESTIONS[0][1], *(question for _, question, _ in QUESTIONS[1:]), CLOSING]


def spoken_number(text: str) -> int | None:
    digits = re.search(r"\d+", text)
    if digits:
        return int(digits.group())
    total, found = 0, False
    # Speech recognition wrote "elli bir" as "belli bir" in a test call.
    text = re.sub(r"\bbelli (?=(bir|iki|uc|dort|bes|alti|yedi|sekiz|dokuz)\b)", "elli ", text)
    for token in text.split():
        if token in NUMBER_WORDS:
            total, found = total + NUMBER_WORDS[token], True
    return total if found else None


class SurveyFlow:
    """A tiny stand-in for the backend interview engine; enough to drive realistic turns."""

    def __init__(self) -> None:
        self.step = "greeting"
        self.questions = list(QUESTIONS)
        self.question_index = 0
        self.asked: set[str] = set()
        self.answers: dict[str, str] = {}
        # (question code, value) of an answer read back to the callee and waiting for "doğru mu?"
        self.confirming: tuple[str, str] | None = None
        # The callee's last reply to the current question when it gave no answer; read again together with
        # what follows if the callee talked over the agent's reply.
        self.unanswered: str | None = None
        # Candidates the callee named together and was asked to choose between.
        self.choosing: list[str] | None = None

    def current_question(self, full: bool = False) -> str:
        """The question in full the first time (or when asked to repeat it), the short form after that."""
        code, question, short = self.questions[self.question_index]
        if full or code not in self.asked:
            self.asked.add(code)
            return question
        return short

    def handle(self, raw: str, previous_reply_missed: bool = False) -> tuple[str, bool]:
        """previous_reply_missed: the agent's last reply was cut off before the callee heard it."""
        reply, end_call = self._handle(raw, previous_reply_missed)
        if not end_call and has_any(normalize(raw), *ALREADY_CALLED):
            reply = CALLED_AGAIN + " " + reply
        return reply, end_call

    def _handle(self, raw: str, previous_reply_missed: bool) -> tuple[str, bool]:
        text = normalize(raw)
        presence_check = has_any(text, *PRESENCE_CHECKS)
        if self.step == "greeting":
            self.step = "consent"
            return OPENING, False
        if has_any(text, *STOP_SURVEY) or (self.step == "consent" and has_any(text, "istemiyorum")):
            self.step = "done"
            return DECLINED, True
        if has_any(text, "kimsiniz", "kim ariyor", "nereden ariyorsunuz", "siz kimsiniz"):
            return IDENTITY + " " + (CONSENT_QUESTION if self.step == "consent" else self.current_question()), False
        if self.step == "consent":
            if has_any(text, "evet", "olur", "tabii", "tabi", "buyurun", "buyrun", "tamam", "sorabilirsiniz", "sorun degil", "dinliyorum"):
                self.step = "questions"
                return "Teşekkür ederim. " + self.current_question(), False
            ask = OPENING if previous_reply_missed else CONSENT_QUESTION
            if presence_check:
                return HEARD + " " + ask, False
            if previous_reply_missed:
                return OPENING, False
            return "Kısaca onayınızı rica edeceğim. " + CONSENT_QUESTION, False
        code = self.questions[self.question_index][0]
        if previous_reply_missed and self.unanswered:
            # Speech recognition split "Mansur Yavaş veya Ekrem İmamoğlu. Ama tabii Ekrem hapisten çıkarsa." in
            # two; the agent answered the first half and only the second was recorded. Read both together.
            raw = self.unanswered + " " + raw
            text = normalize(raw)
        if self.confirming is not None:
            confirm_code, value = self.confirming
            self.confirming = None
            if has_any(text, *CONFIRM_NO):
                # "Hayır, hiçbiri" carries the right answer; a bare "Hayır" gets the question again.
                answer = self.interpret(code, raw, text)
                if answer is None:
                    return "Peki. " + self.current_question(), False
                return self._take(code, *answer)
            if has_any(text, *CONFIRM_YES):
                return self._take(confirm_code, value, False)
            # Anything else is taken as a new answer to the question.
        if has_any(text, "neden", "niye", "nicin", "belli degil mi", "ne gerek var"):
            return REASON + " " + self.current_question(), False
        if has_any(text, "tekrar", "anlamadim", "ne dediniz", "efendim", "pardon", "yavas konusur", "yavas konusun",
                           "yavas konusabilir", "yavas soyler", "yavas soyleyin"):
            return self.current_question(full=True), False
        if code == "candidate" and has_any(text, *NEGATIONS) and not has_any(text, *UNDECIDED) and any(
                has_any(text, *keys) for keys in CANDIDATES.values()):
            return "Anladım. Peki " + self.current_question(), False
        if code == "candidate":
            named = [name for name in CANDIDATES if name in named_candidates(text)]
            if len(named) > 1 and not has_any(text, *UNDECIDED):
                # Two names: ask once which one, as an interviewer would; named together again, undecided.
                if self.choosing is None:
                    self.choosing = named
                    self.unanswered = None
                    choices = ", ".join(f"{name} {QUESTION_PARTICLE[name]}" for name in named)
                    return f"Anladım. Peki hangisini daha çok tercih edersiniz, {choices}?", False
                return self._take(code, "Kararsız (" + " / ".join(named) + ")", False)
            if self.choosing and not named and has_any(text, *NO_CHOICE):
                return self._take(code, "Kararsız (" + " / ".join(self.choosing) + ")", False)
        answer = self.interpret(code, raw, text)
        if answer is None:
            self.unanswered = raw
            if presence_check:
                return HEARD + " " + self.current_question(), False
            if previous_reply_missed:
                return self.current_question(), False
            return UNCLEAR + " " + self.current_question(), False
        return self._take(code, *answer)

    def _take(self, code: str, value: str, confirm: bool) -> tuple[str, bool]:
        if confirm:
            # A name not on the list may be misheard (once "Hiçbiri" came out as "Bir, iki, üç"), so the
            # callee hears it back and can correct it before the call moves on.
            self.confirming = (code, value)
            return f"{value.removeprefix('Diğer: ')} olarak not alıyorum, doğru mu?", False
        self.answers[code] = value
        self.unanswered, self.choosing = None, None
        self.question_index += 1
        if self.question_index >= len(self.questions):
            self.step = "done"
            return CLOSING, True
        return self.current_question(), False

    @staticmethod
    def interpret(code: str, raw: str, text: str) -> tuple[str, bool] | None:
        """(value to record, whether to read it back for confirmation), or None when it is not an answer."""
        if code == "age":
            number = spoken_number(text)
            # "Belli bir" once came out as 1; an impossible age gets the question again.
            return (str(number), False) if number is not None and AGE_RANGE[0] <= number <= AGE_RANGE[1] else None
        if code == "rating":
            if has_any(text, *WITHHELD):
                return "Cevap vermek istemiyor", False
            if has_any(text, *UNDECIDED):
                return "Bilmiyor", False
            number = spoken_number(text)
            return (str(number), False) if number is not None and 1 <= number <= 5 else None
        if code == "candidate":
            return candidate_answer(raw, text)
        return None


def named_candidates(text: str) -> set[str]:
    named = {name for name, keys in CANDIDATES.items() if has_any(text, *keys)}
    # "Yavaş" alone is a name unless it is about speaking slowly ("yavaş konuşun", "yavaş söyler misiniz").
    if re.search(r"\byavas\b(?! (konus|soyle|sor|oku|anlat))", text):
        named.add("Mansur Yavaş")
    return named


def candidate_answer(raw: str, text: str) -> tuple[str, bool] | None:
    words = text.split()
    named = named_candidates(text)
    if has_any(text, *UNDECIDED):
        # "Kararsızım ama muhtemelen İmamoğlu ya da Yavaş": undecided, leaning towards the names given.
        # "Kararsızım ama Erdoğan değil" excludes a name rather than leaning towards it.
        leaning = [] if has_any(text, *NEGATIONS) else [name for name in CANDIDATES if name in named]
        return ("Kararsız (" + " / ".join(leaning) + ")" if leaning else "Kararsız"), False
    if len(named) == 1:
        return named.pop(), False
    if len(named) > 1:
        return None  # "Erdoğan değil, İmamoğlu": ask again rather than guess
    picked = {ORDINALS[word] for word in words if word in ORDINALS}
    if len(picked) == 1:
        return list(CANDIDATES)[picked.pop()], False
    if has_any(text, *WITHHELD):
        return "Cevap vermek istemiyor", False
    if has_any(text, *NONE_OF_THEM):
        return "Hiçbiri", False
    # Numbers are not a name. A bare "üç" might mean the third name, but the question is asked again
    # rather than guessed.
    if spoken_number(text) is not None:
        return None
    if 1 <= len(words) <= 5 and not set(words) <= NOT_A_NAME and not raw.rstrip().endswith("?"):
        name = other_name(raw)
        if name:
            return f"Diğer: {name}", True  # someone not on the list, read back for confirmation
    return None


def other_name(raw: str) -> str:
    """'Bence Özgür Özel'i olsun.' -> 'Özgür Özel'"""
    words = raw.strip(" .,!?").replace(",", " ").split()
    while words and normalize(words[0]) in NAME_PADDING:
        words.pop(0)
    while words and normalize(words[-1]) in NAME_PADDING:
        words.pop()
    if words and "'" in words[-1]:
        words[-1] = words[-1].split("'")[0]  # case suffix: Özel'i, Davutoğlu'nu
    return " ".join(words)


class PrototypeInterview:
    """SurveyFlow behind the interface agent.py drives both kinds of call with (see backend.BackendInterview)."""

    def __init__(self) -> None:
        self.flow = SurveyFlow()
        # Every reply to a survey question, for the check after the call (verify_call.py). Kept apart from
        # the turn log, which is written when the reply starts playing and so misses interrupted replies.
        self.answer_turns: list[dict] = []
        self.previous_heard_at: float | None = None

    @property
    def step(self) -> str:
        return self.flow.step

    async def begin(self) -> None:
        pass

    def takes_fillers(self) -> bool:
        """While an answer read back waits for "doğru mu?", a bare "evet" is the reply to it."""
        return self.flow.confirming is not None

    def first_replies(self) -> list[str]:
        return main_replies()

    async def reply(self, user_text: str, previous_reply_missed: bool = False, heard_at_s=None) -> tuple[str, bool]:
        question = self.flow.questions[self.flow.question_index][0] if self.flow.step == "questions" else None
        answers_before = dict(self.flow.answers)
        reply, end_call = self.flow.handle(user_text, previous_reply_missed=previous_reply_missed)
        if question is not None:
            recorded = self.flow.answers.get(question)
            self.answer_turns.append({
                "question": question,
                "heard_at_s": heard_at_s,
                "previous_heard_at_s": self.previous_heard_at,
                "text": user_text,
                "recorded": recorded if recorded != answers_before.get(question) else None,
            })
        self.previous_heard_at = heard_at_s
        return reply, end_call

    async def finish(self) -> None:
        pass

    def save_answer_turns(self, path) -> None:
        with path.open("w", encoding="utf-8") as handle:
            for entry in self.answer_turns:
                handle.write(json.dumps(entry, ensure_ascii=False) + "\n")
