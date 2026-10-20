"""Tests for the agent's side of a call the backend placed. Run: .venv/Scripts/python -m unittest test_backend"""
import unittest

from backend import PLEASE_WAIT, TECHNICAL_GOODBYE, BackendError, BackendInterview, dial_failure_event
from phrases import CALLED_AGAIN, HEARD, is_line_check, may_continue_number, signal_for


class SignalTest(unittest.TestCase):
    def test_requests_get_their_signal(self):
        self.assertEqual(signal_for("Tekrar eder misiniz?"), "REPEAT_REQUEST")
        self.assertEqual(signal_for("Pardon, ne dediniz?"), "REPEAT_REQUEST")
        self.assertEqual(signal_for("Siz kimsiniz?"), "IDENTITY_REQUEST")
        self.assertEqual(signal_for("Bunu neden soruyorsunuz?"), "REASON_REQUEST")
        self.assertEqual(signal_for("Şu an meşgulüm, kapatıyorum."), "STOP_REQUEST")

    def test_answers_with_request_words_stay_answers(self):
        self.assertEqual(signal_for("Neden olmasın, sorun."), "ANSWER")
        self.assertEqual(signal_for("Erdoğan tekrar seçilsin, bence o kazanır, öyle düşünüyorum."), "ANSWER")
        self.assertEqual(signal_for("Elli bir."), "ANSWER")

    def test_numbers_that_may_go_on(self):
        # "On dokuz" came as "On." and "Dokuz." in a test call.
        self.assertTrue(may_continue_number("On."))
        self.assertTrue(may_continue_number("Kırk"))
        self.assertTrue(may_continue_number("20"))
        self.assertFalse(may_continue_number("On dokuz."))
        self.assertFalse(may_continue_number("27 yaşındayım."))
        self.assertFalse(may_continue_number("Beş."))

    def test_line_checks(self):
        self.assertTrue(is_line_check("Alo? Alo."))
        self.assertTrue(is_line_check("Alo, beni duyuyor musunuz?"))
        self.assertFalse(is_line_check("Alo, elli bir."))
        self.assertFalse(is_line_check(""))


class DialFailureTest(unittest.TestCase):
    def test_a_dial_nobody_answered_is_reported_as_unanswered(self):
        # The second test call: the phone rang for LiveKit's 30-second window and nobody picked up.
        self.assertEqual(dial_failure_event(None, "sip request timed out"), "no_answer")

    def test_other_dial_failures_leave_the_reason_to_the_sip_status(self):
        self.assertEqual(dial_failure_event(486, "Busy Here"), "failed")
        self.assertEqual(dial_failure_event(None, "trunk not found"), "failed")


class FakeClient:
    def __init__(self, start=None, answers=()):
        self.start_response = start or {"prompt": None, "endCall": False, "question": None}
        self.answers = list(answers)  # responses, or BackendError to raise
        self.sent = []

    async def start(self):
        return self.start_response

    async def answer(self, text, signal, question_id):
        self.sent.append((text, signal, question_id))
        response = self.answers.pop(0)
        if isinstance(response, Exception):
            raise response
        return response

    async def finish(self):
        return {}


def question(question_id, prompt, retry=False):
    return {"id": question_id, "spokenPrompt": prompt, "retryNeeded": retry}


def progress(prompt, q):
    return {"prompt": prompt, "closingMessage": None, "endCall": False, "question": q}


class BackendInterviewTest(unittest.IsolatedAsyncioTestCase):
    async def make(self, client):
        said = []
        interview = BackendInterview(client, say=said.append)
        await interview.begin()
        return interview, said

    async def test_the_greeting_gets_the_opening_from_the_engine(self):
        client = FakeClient(answers=[progress("Merhaba, size birkaç soru sorabilir miyim?", None)])
        interview, _ = await self.make(client)

        reply, end_call = await interview.reply("Alo?")

        self.assertEqual(reply, "Merhaba, size birkaç soru sorabilir miyim?")
        self.assertFalse(end_call)
        self.assertEqual(client.sent, [("Alo?", "ANSWER", None)])
        self.assertEqual(interview.step, "interview")

    async def test_a_survey_without_an_opening_asks_its_first_question_after_the_greeting(self):
        age = question("q1", "Kaç yaşındasınız?")
        client = FakeClient(start={"prompt": "Kaç yaşındasınız?", "endCall": False, "question": age},
                            answers=[progress("Puanınız?", question("q2", "Puanınız?"))])
        interview, _ = await self.make(client)

        first, _ = await interview.reply("Alo, buyurun.")
        await interview.reply("Kırk.")

        self.assertEqual(first, "Kaç yaşındasınız?")
        # The greeting is not taken for the answer to the first question.
        self.assertEqual(client.sent, [("Kırk.", "ANSWER", "q1")])

    async def test_a_line_check_repeats_the_current_question_without_the_engine(self):
        age = question("q1", "Kaç yaşındasınız?")
        client = FakeClient(answers=[progress("Teşekkür ederim. Kaç yaşındasınız?", age)])
        interview, _ = await self.make(client)
        await interview.reply("Evet, buyurun.")

        reply, _ = await interview.reply("Alo, duyuyor musunuz?")

        self.assertEqual(reply, f"{HEARD} Kaç yaşındasınız?")
        self.assertEqual(len(client.sent), 1)

    async def test_an_answer_split_by_an_interrupted_retry_is_sent_whole(self):
        candidate = question("q3", "Kimi görmek istersiniz?")
        client = FakeClient(answers=[
            progress("Kimi görmek istersiniz?", candidate),
            progress("Sizi net duyamadım. Kimi görmek istersiniz?", question("q3", "Kimi görmek istersiniz?", retry=True)),
            {"prompt": "Teşekkürler.", "closingMessage": "Teşekkürler.", "endCall": True, "question": None},
        ])
        interview, _ = await self.make(client)
        await interview.reply("Alo")

        await interview.reply("Mansur Yavaş veya")
        reply, end_call = await interview.reply("Ekrem İmamoğlu.", previous_reply_missed=True)

        self.assertEqual(client.sent[-1], ("Mansur Yavaş veya Ekrem İmamoğlu.", "ANSWER", "q3"))
        self.assertEqual(reply, "Teşekkürler.")
        self.assertTrue(end_call)
        self.assertEqual(interview.step, "done")

    async def test_a_backend_failure_is_retried_once_after_asking_the_callee_to_wait(self):
        client = FakeClient(answers=[BackendError("timeout"), progress("Merhaba?", None)])
        interview, said = await self.make(client)

        reply, end_call = await interview.reply("Alo")

        self.assertEqual(said, [PLEASE_WAIT])
        self.assertEqual(reply, "Merhaba?")
        self.assertFalse(end_call)

    async def test_the_call_ends_politely_when_the_backend_stays_unreachable(self):
        client = FakeClient(answers=[BackendError("timeout"), BackendError("timeout")])
        interview, _ = await self.make(client)

        reply, end_call = await interview.reply("Alo")

        self.assertEqual(reply, TECHNICAL_GOODBYE)
        self.assertTrue(end_call)

    async def test_a_callee_who_was_called_before_hears_an_apology(self):
        client = FakeClient(answers=[progress("Merhaba, size birkaç soru sorabilir miyim?", None)])
        interview, _ = await self.make(client)

        reply, _ = await interview.reply("Alo, biraz önce de aramıştınız.")

        self.assertTrue(reply.startswith(CALLED_AGAIN))


if __name__ == "__main__":
    unittest.main()
