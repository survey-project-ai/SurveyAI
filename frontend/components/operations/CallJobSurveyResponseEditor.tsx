"use client";

import { useState } from "react";
import { SectionCard } from "@/components/ui/SectionCard";
import { CallJobDetail, CallJobSurveyResponseAnswer } from "@/lib/types";

type SurveyAnswerDraft = {
  questionId: string;
  questionType: CallJobSurveyResponseAnswer["questionType"];
  answerText: string;
  answerNumber: string;
  selectedOptionId: string;
  selectedOptionIds: string[];
};

type CallJobSurveyResponseEditorProps = {
  detail: CallJobDetail;
  onSave: (payload: Array<{
    questionId: string;
    answerText?: string | null;
    answerNumber?: number | null;
    selectedOptionId?: string | null;
    selectedOptionIds?: string[];
  }>) => Promise<void>;
  isSaving: boolean;
  saveErrorMessage: string | null;
  saveSuccessMessage: string | null;
  title?: string;
  description?: string;
};

export function CallJobSurveyResponseEditor({
  detail,
  onSave,
  isSaving,
  saveErrorMessage,
  saveSuccessMessage,
  title = "Cevap editoru",
  description = "Agent'in yanlis yorumladigi cevaplari transcripti kontrol ederek burada duzeltebilirsiniz.",
}: CallJobSurveyResponseEditorProps) {
  const [answerDrafts, setAnswerDrafts] = useState<SurveyAnswerDraft[]>([]);
  const [initialAnswerSignature, setInitialAnswerSignature] = useState("");

  // Reset the editable drafts whenever the incoming survey response changes.
  // Adjusted during render instead of in an effect.
  const [prevSurveyResponse, setPrevSurveyResponse] = useState(detail.surveyResponse);
  if (prevSurveyResponse !== detail.surveyResponse) {
    setPrevSurveyResponse(detail.surveyResponse);
    const nextDrafts = createAnswerDrafts(detail.surveyResponse?.answers ?? []);
    setAnswerDrafts(nextDrafts);
    setInitialAnswerSignature(serializeAnswerDrafts(nextDrafts));
  }

  const hasUnsavedAnswerChanges = serializeAnswerDrafts(answerDrafts) !== initialAnswerSignature;
  const responseStatus = detail.surveyResponse?.status ?? (detail.partialResponseDataExists ? "Kismi veri var" : "Yanit yok");
  const responseDetail = detail.surveyResponse
    ? detail.surveyResponse.usableResponse
      ? `${detail.surveyResponse.validAnswerCount} gecerli cevap islenmis.`
      : `Yanit kaydi var fakat kullanilabilir eslesen cevap sayisi ${detail.surveyResponse.validAnswerCount}.`
    : detail.partialResponseDataExists
      ? "Transcript veya kisitli veri mevcut, ama bagli bir survey_response secilemiyor."
      : "Bu is icin henuz survey_response kaydi yok.";

  function updateAnswerDraft(questionId: string, updater: (draft: SurveyAnswerDraft) => SurveyAnswerDraft) {
    setAnswerDrafts((current) => current.map((draft) => (
      draft.questionId === questionId ? updater(draft) : draft
    )));
  }

  async function handleSaveAnswers() {
    if (!detail.surveyResponse) {
      return;
    }

    await onSave(answerDrafts.map((draft) => ({
      questionId: draft.questionId,
      answerText: normalizeDraftText(draft.answerText),
      answerNumber: draft.answerNumber.trim() ? Number(draft.answerNumber) : null,
      selectedOptionId: draft.selectedOptionId || null,
      selectedOptionIds: draft.selectedOptionIds,
    })));
  }

  return (
    <>
      <SectionCard
        title={title}
        description={description}
        action={detail.surveyResponse ? (
          <button
            type="button"
            className="button-primary compact-button"
            disabled={isSaving || !hasUnsavedAnswerChanges}
            onClick={() => void handleSaveAnswers()}
          >
            {isSaving ? "Yanitlar kaydediliyor..." : "Yanitlari kaydet"}
          </button>
        ) : null}
      >
        {saveErrorMessage ? (
          <div className="operation-inline-message is-danger compact">
            <strong>Yanitlar kaydedilemedi</strong>
            <span>{saveErrorMessage}</span>
          </div>
        ) : null}

        {saveSuccessMessage ? (
          <div className="operation-inline-message is-accent compact">
            <strong>Yanitlar guncellendi</strong>
            <span>{saveSuccessMessage}</span>
          </div>
        ) : null}

        {!detail.surveyResponse ? (
          <div className="operation-empty-state">
            <strong>Duzenlenebilir bir response kaydi yok</strong>
            <p>Bu job icin once survey response olusmali. Transcript varsa yine de asagida inceleyebilirsiniz.</p>
          </div>
        ) : (
          <div className="call-job-answer-editor-list">
            {detail.surveyResponse.answers.map((answer) => {
              const draft = answerDrafts.find((item) => item.questionId === answer.questionId) ?? createAnswerDraft(answer);

              return (
                <article key={answer.questionId} className="call-job-answer-editor-card">
                  <div className="call-job-answer-editor-head">
                    <div>
                      <strong>Soru {answer.questionOrder}: {answer.questionTitle}</strong>
                      <span>
                        {answer.questionCode} · {formatQuestionType(answer.questionType)}
                        {answer.required ? " · zorunlu" : " · opsiyonel"}
                      </span>
                    </div>
                    <span className={answer.valid ? "operation-readiness-pill is-ready" : "operation-readiness-pill is-blocked"}>
                      {answer.manuallyEdited ? "Manuel duzeltildi" : answer.valid ? "Gecerli" : "Kontrol gerekli"}
                    </span>
                  </div>

                  <div className="detail-row">
                    <span>Mevcut yorum</span>
                    <strong>{answer.displayValue}</strong>
                  </div>

                  {answer.invalidReason ? (
                    <div className="operation-inline-message is-danger compact">
                      <strong>Mevcut invalid reason</strong>
                      <span>{answer.invalidReason}</span>
                    </div>
                  ) : null}

                  {answer.questionType === "OPEN_ENDED" ? (
                    <label className="call-job-answer-field">
                      <span>Yeni cevap</span>
                      <textarea
                        value={draft.answerText}
                        onChange={(event) => updateAnswerDraft(answer.questionId, (current) => ({
                          ...current,
                          answerText: event.target.value,
                        }))}
                        rows={4}
                      />
                    </label>
                  ) : null}

                  {answer.questionType === "RATING" || answer.questionType === "NUMBER" ? (
                    <label className="call-job-answer-field">
                      <span>{answer.questionType === "RATING" ? "Yeni puan" : "Yeni sayi"}</span>
                      <input
                        type="number"
                        value={draft.answerNumber}
                        onChange={(event) => updateAnswerDraft(answer.questionId, (current) => ({
                          ...current,
                          answerNumber: event.target.value,
                        }))}
                      />
                    </label>
                  ) : null}

                  {answer.questionType === "SINGLE_CHOICE" ? (
                    <label className="call-job-answer-field">
                      <span>Secenek</span>
                      <select
                        value={draft.selectedOptionId}
                        onChange={(event) => updateAnswerDraft(answer.questionId, (current) => ({
                          ...current,
                          selectedOptionId: event.target.value,
                        }))}
                      >
                        <option value="">Secin</option>
                        {answer.options.map((option) => (
                          <option key={option.id} value={option.id}>
                            {option.label}
                          </option>
                        ))}
                      </select>
                    </label>
                  ) : null}

                  {answer.questionType === "MULTI_CHOICE" ? (
                    <div className="call-job-answer-field">
                      <span>Secenekler</span>
                      <div className="call-job-answer-checkboxes">
                        {answer.options.map((option) => (
                          <label key={option.id} className="call-job-answer-checkbox">
                            <input
                              type="checkbox"
                              checked={draft.selectedOptionIds.includes(option.id)}
                              onChange={(event) => updateAnswerDraft(answer.questionId, (current) => ({
                                ...current,
                                selectedOptionIds: event.target.checked
                                  ? [...current.selectedOptionIds, option.id]
                                  : current.selectedOptionIds.filter((item) => item !== option.id),
                              }))}
                            />
                            <span>{option.label}</span>
                          </label>
                        ))}
                      </div>
                    </div>
                  ) : null}
                </article>
              );
            })}
          </div>
        )}
      </SectionCard>

      <SectionCard
        title="Response ve transcript"
        description="Bu cagri isinin kullanilabilir survey verisi uretip uretmedigi burada net gorunur."
      >
        <div className="stack-list">
          <div className="detail-row">
            <span>Survey response durumu</span>
            <strong>{responseStatus}</strong>
          </div>
          <div className="detail-row">
            <span>Kullanilabilir veri</span>
            <strong>{responseDetail}</strong>
          </div>
          <div className="detail-row">
            <span>Provider referansi</span>
            <strong>{detail.latestProviderCallId ?? "Provider call id yok"}</strong>
          </div>
          <div className="detail-row">
            <span>Transcript referansi</span>
            <strong>{detail.latestTranscriptStorageKey ?? "Transcript storage key yok"}</strong>
          </div>
        </div>

        <div className="section-card-body">
          <div className="list-item">
            <div>
              <strong>Transcript ozeti</strong>
              <span>{detail.transcriptSummary ?? "Ozet uretilmemis."}</span>
            </div>
          </div>
          <div className="call-job-transcript">
            <strong>Transcript blok</strong>
            <pre>{detail.transcriptText ?? "Bu is icin transcript metni henuz mevcut degil."}</pre>
          </div>
        </div>
      </SectionCard>
    </>
  );
}

function createAnswerDrafts(answers: CallJobSurveyResponseAnswer[]): SurveyAnswerDraft[] {
  return answers
    .slice()
    .sort((left, right) => left.questionOrder - right.questionOrder)
    .map(createAnswerDraft);
}

function createAnswerDraft(answer: CallJobSurveyResponseAnswer): SurveyAnswerDraft {
  return {
    questionId: answer.questionId,
    questionType: answer.questionType,
    answerText: answer.answerText ?? "",
    answerNumber: answer.answerNumber === null ? "" : String(answer.answerNumber),
    selectedOptionId: answer.selectedOptionId ?? "",
    selectedOptionIds: [...answer.selectedOptionIds],
  };
}

function serializeAnswerDrafts(drafts: SurveyAnswerDraft[]): string {
  return JSON.stringify(drafts.map((draft) => ({
    questionId: draft.questionId,
    questionType: draft.questionType,
    answerText: draft.answerText.trim(),
    answerNumber: draft.answerNumber.trim(),
    selectedOptionId: draft.selectedOptionId,
    selectedOptionIds: [...draft.selectedOptionIds].sort(),
  })));
}

function normalizeDraftText(value: string): string | null {
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}

function formatQuestionType(questionType: CallJobSurveyResponseAnswer["questionType"]): string {
  switch (questionType) {
    case "OPEN_ENDED":
      return "Acik uc";
    case "RATING":
      return "Puan";
    case "NUMBER":
      return "Sayisal giris";
    case "SINGLE_CHOICE":
      return "Tek secim";
    case "MULTI_CHOICE":
      return "Coklu secim";
    default:
      return questionType;
  }
}
