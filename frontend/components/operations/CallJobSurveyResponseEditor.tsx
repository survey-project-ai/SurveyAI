"use client";

import { useState } from "react";
import { SectionCard } from "@/components/ui/SectionCard";
import { CallJobDetail, CallJobSurveyResponseAnswer } from "@/lib/types";
import { useUnsavedChangesGuard } from "@/lib/unsaved-changes";

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
  title = "Cevap editörü",
  description = "Agent'ın yanlış yorumladığı cevapları transcripti kontrol ederek burada düzeltebilirsiniz.",
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
  useUnsavedChangesGuard(hasUnsavedAnswerChanges);
  const responseStatus = detail.surveyResponse?.status ?? (detail.partialResponseDataExists ? "Kısmi veri var" : "Yanıt yok");
  const responseDetail = detail.surveyResponse
    ? detail.surveyResponse.usableResponse
      ? `${detail.surveyResponse.validAnswerCount} geçerli cevap işlenmiş.`
      : `Yanıt kaydı var fakat kullanılabilir eşleşen cevap sayısı ${detail.surveyResponse.validAnswerCount}.`
    : detail.partialResponseDataExists
      ? "Transcript veya kısıtlı veri mevcut, ama bağlı bir survey_response seçilemiyor."
      : "Bu iş için henüz survey_response kaydı yok.";

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
            {isSaving ? "Yanıtlar kaydediliyor..." : "Yanıtları kaydet"}
          </button>
        ) : null}
      >
        {saveErrorMessage ? (
          <div className="operation-inline-message is-danger compact">
            <strong>Yanıtlar kaydedilemedi</strong>
            <span>{saveErrorMessage}</span>
          </div>
        ) : null}

        {saveSuccessMessage ? (
          <div className="operation-inline-message is-accent compact">
            <strong>Yanıtlar güncellendi</strong>
            <span>{saveSuccessMessage}</span>
          </div>
        ) : null}

        {!detail.surveyResponse ? (
          <div className="operation-empty-state">
            <strong>Düzenlenebilir bir response kaydı yok</strong>
            <p>Bu job için önce survey response oluşmalı. Transcript varsa yine de aşağıda inceleyebilirsiniz.</p>
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
                      {answer.manuallyEdited ? "Manuel düzeltildi" : answer.valid ? "Geçerli" : "Kontrol gerekli"}
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
                      <span>{answer.questionType === "RATING" ? "Yeni puan" : "Yeni sayı"}</span>
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
                      <span>Seçenek</span>
                      <select
                        value={draft.selectedOptionId}
                        onChange={(event) => updateAnswerDraft(answer.questionId, (current) => ({
                          ...current,
                          selectedOptionId: event.target.value,
                        }))}
                      >
                        <option value="">Seçin</option>
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
                      <span>Seçenekler</span>
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
        description="Bu çağrı işinin kullanılabilir survey verisi üretip üretmediği burada net görünür."
      >
        <div className="stack-list">
          <div className="detail-row">
            <span>Survey response durumu</span>
            <strong>{responseStatus}</strong>
          </div>
          <div className="detail-row">
            <span>Kullanılabilir veri</span>
            <strong>{responseDetail}</strong>
          </div>
          <div className="detail-row">
            <span>Provider referansı</span>
            <strong>{detail.latestProviderCallId ?? "Provider call id yok"}</strong>
          </div>
          <div className="detail-row">
            <span>Transcript referansı</span>
            <strong>{detail.latestTranscriptStorageKey ?? "Transcript storage key yok"}</strong>
          </div>
        </div>

        <div className="section-card-body">
          <div className="list-item">
            <div>
              <strong>Transcript özeti</strong>
              <span>{detail.transcriptSummary ?? "Özet üretilmemiş."}</span>
            </div>
          </div>
          <div className="call-job-transcript">
            <strong>Transcript blok</strong>
            <pre>{detail.transcriptText ?? "Bu iş için transcript metni henüz mevcut değil."}</pre>
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
      return "Açık uç";
    case "RATING":
      return "Puan";
    case "NUMBER":
      return "Sayısal giriş";
    case "SINGLE_CHOICE":
      return "Tek seçim";
    case "MULTI_CHOICE":
      return "Çoklu seçim";
    default:
      return questionType;
  }
}
