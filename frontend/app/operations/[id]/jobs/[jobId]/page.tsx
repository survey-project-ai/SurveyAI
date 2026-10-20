"use client";

import Link from "next/link";
import { notFound, useParams, useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { PageContainer } from "@/components/layout/PageContainer";
import { usePageHeaderOverride } from "@/components/layout/PageHeaderContext";
import { KeyValueList } from "@/components/ui/KeyValueList";
import { SectionCard } from "@/components/ui/SectionCard";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { fetchOperationById, fetchOperationCallJobDetail, redialOperationCallJob, updateOperationCallJobSurveyResponse } from "@/lib/operations";
import { CallJobAttempt, CallJobDetail, CallJobSurveyResponseAnswer, Operation } from "@/lib/types";
import { useUnsavedChangesGuard } from "@/lib/unsaved-changes";

type SurveyAnswerDraft = {
  questionId: string;
  questionType: CallJobSurveyResponseAnswer["questionType"];
  answerText: string;
  answerNumber: string;
  selectedOptionId: string;
  selectedOptionIds: string[];
};

export default function OperationJobDetailPage() {
  const params = useParams<{ id: string; jobId: string }>();
  const router = useRouter();
  const operationId = params.id;
  const callJobId = params.jobId;
  const [operation, setOperation] = useState<Operation | null>(null);
  const [detail, setDetail] = useState<CallJobDetail | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isRetrying, setIsRetrying] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [retryMessage, setRetryMessage] = useState<string | null>(null);
  const [retryErrorMessage, setRetryErrorMessage] = useState<string | null>(null);
  const [answerDrafts, setAnswerDrafts] = useState<SurveyAnswerDraft[]>([]);
  const [initialAnswerSignature, setInitialAnswerSignature] = useState("");
  const [isSavingAnswers, setIsSavingAnswers] = useState(false);
  const [saveSuccessMessage, setSaveSuccessMessage] = useState<string | null>(null);
  const [saveErrorMessage, setSaveErrorMessage] = useState<string | null>(null);
  const [isMissing, setIsMissing] = useState(false);

  const pageHeader = useMemo(
    () => ({
      title: detail?.personName ?? "Çağrı işi detayı",
      subtitle: detail ? `${detail.operationName} operasyonu içindeki tekil yürütme kaydı.` : "Çağrı işi detayı yükleniyor.",
    }),
    [detail],
  );

  usePageHeaderOverride(pageHeader);

  useEffect(() => {
    if (!operationId || !callJobId) {
      return;
    }

    const controller = new AbortController();

    async function load() {
      try {
        setIsLoading(true);
        setErrorMessage(null);
        setIsMissing(false);

        const [nextOperation, nextDetail] = await Promise.all([
          fetchOperationById(operationId, undefined, { signal: controller.signal }),
          fetchOperationCallJobDetail(operationId, callJobId, undefined, { signal: controller.signal }),
        ]);

        setOperation(nextOperation);
        setDetail(nextDetail);
      } catch (error) {
        if (controller.signal.aborted) {
          return;
        }

        const message = error instanceof Error ? error.message : "Çağrı işi detayı yüklenemedi.";
        if (message.includes("(404)")) {
          setIsMissing(true);
          return;
        }

        setErrorMessage(message);
      } finally {
        if (!controller.signal.aborted) {
          setIsLoading(false);
        }
      }
    }

    void load();
    return () => controller.abort();
  }, [callJobId, operationId]);

  // Reset the editable answer drafts whenever the loaded survey response
  // changes. Adjusted during render (not in an effect) to avoid an extra pass.
  const surveyResponse = detail?.surveyResponse;
  const [prevSurveyResponse, setPrevSurveyResponse] = useState(surveyResponse);
  if (prevSurveyResponse !== surveyResponse) {
    setPrevSurveyResponse(surveyResponse);
    const nextDrafts = createAnswerDrafts(surveyResponse?.answers ?? []);
    setAnswerDrafts(nextDrafts);
    setInitialAnswerSignature(serializeAnswerDrafts(nextDrafts));
  }

  const hasUnsavedAnswerChanges = serializeAnswerDrafts(answerDrafts) !== initialAnswerSignature;
  useUnsavedChangesGuard(hasUnsavedAnswerChanges);

  if (isMissing) {
    notFound();
  }

  async function handleRecall() {
    if (!detail) {
      return;
    }

    try {
      setIsRetrying(true);
      setRetryMessage(null);
      setRetryErrorMessage(null);
      const nextDetail = await redialOperationCallJob(operationId, callJobId);
      setRetryMessage("Aynı operasyon içinde yeni bir çağrı işi oluşturuldu. Yeni detay ekranına yönlendiriliyorsunuz.");
      router.push(`/operations/${operationId}/jobs/${nextDetail.id}`);
      router.refresh();
    } catch (error) {
      setRetryErrorMessage(error instanceof Error ? error.message : "Çağrı yeniden başlatılamadı.");
    } finally {
      setIsRetrying(false);
    }
  }

  function updateAnswerDraft(questionId: string, updater: (draft: SurveyAnswerDraft) => SurveyAnswerDraft) {
    setAnswerDrafts((current) => current.map((draft) => (
      draft.questionId === questionId ? updater(draft) : draft
    )));
  }

  async function handleSaveAnswers() {
    if (!detail?.surveyResponse) {
      return;
    }

    try {
      setIsSavingAnswers(true);
      setSaveErrorMessage(null);
      setSaveSuccessMessage(null);

      const nextDetail = await updateOperationCallJobSurveyResponse(
        operationId,
        callJobId,
        answerDrafts.map((draft) => ({
          questionId: draft.questionId,
          answerText: normalizeDraftText(draft.answerText),
          answerNumber: draft.answerNumber.trim() ? Number(draft.answerNumber) : null,
          selectedOptionId: draft.selectedOptionId || null,
          selectedOptionIds: draft.selectedOptionIds,
        })),
      );

      setDetail(nextDetail);
      const nextDrafts = createAnswerDrafts(nextDetail.surveyResponse?.answers ?? []);
      setAnswerDrafts(nextDrafts);
      setInitialAnswerSignature(serializeAnswerDrafts(nextDrafts));
      setSaveSuccessMessage("Yanıtlar güncellendi. Analitikler yeni değerlere göre hesaplanacak.");
    } catch (error) {
      setSaveErrorMessage(error instanceof Error ? error.message : "Yanıtlar kaydedilemedi.");
    } finally {
      setIsSavingAnswers(false);
    }
  }

  const responseStatus = detail?.surveyResponse?.status ?? (detail?.partialResponseDataExists ? "Kısmi veri var" : "Yanıt yok");
  const responseDetail = detail?.surveyResponse
    ? detail.surveyResponse.usableResponse
      ? `${detail.surveyResponse.validAnswerCount} geçerli cevap işlenmiş.`
      : `Yanıt kaydı var fakat kullanılabilir eşleşen cevap sayısı ${detail.surveyResponse.validAnswerCount}.`
    : detail?.partialResponseDataExists
      ? "Transcript veya kısıtlı veri mevcut, ama bağlı bir survey_response seçilemiyor."
      : "Bu iş için henüz survey_response kaydı yok.";

  return (
    <PageContainer>
      {errorMessage ? (
        <section className="panel-card">
          <div className="operation-inline-message is-danger">
            <strong>Çağrı işi detayı yüklenemedi</strong>
            <span>{errorMessage}</span>
          </div>
        </section>
      ) : null}

      {retryErrorMessage ? (
        <section className="panel-card">
          <div className="operation-inline-message is-danger compact">
            <strong>Yeniden deneme başlatılamadı</strong>
            <span>{retryErrorMessage}</span>
          </div>
        </section>
      ) : null}

      {retryMessage ? (
        <section className="panel-card">
          <div className="operation-inline-message is-accent compact">
            <strong>Yeniden deneme kaydedildi</strong>
            <span>{retryMessage}</span>
          </div>
        </section>
      ) : null}

      <section className="hero-card is-compact operation-workspace-hero">
        <div className="operation-workspace-hero-head">
          <div>
            <div className="eyebrow">Call Job Detail</div>
            <h2 className="hero-title">{detail?.personName ?? "Çağrı işi yükleniyor"}</h2>
            <p className="hero-text">
              {detail
                ? `${detail.operationName} içindeki tekil çağrı işi, deneme geçmişi ve survey response ilişkisi bu yüzeyde bir araya gelir.`
                : "Seçili çağrı işi, deneme geçmişi ve geri dönen veri yükleniyor."}
            </p>
          </div>
          <div className="operation-hero-status-cluster">
            <StatusBadge status={detail?.status ?? "Pending"} />
            <span className={detail?.retryable ? "operation-readiness-pill is-ready" : "operation-readiness-pill is-blocked"}>
              {detail?.retryable ? "Retry uygun" : "Retry kapalı"}
            </span>
            <span className={detail?.redialable ? "operation-readiness-pill is-ready" : "operation-readiness-pill is-blocked"}>
              {detail?.redialable ? "Tekrar ara uygun" : "Tekrar ara kapalı"}
            </span>
          </div>
        </div>
        <div className="chip-row">
          <span className="chip">Operasyon: {detail?.operationName ?? operation?.name ?? "Yükleniyor"}</span>
          <span className="chip">Anket: {detail?.surveyName ?? operation?.survey ?? "Yükleniyor"}</span>
          <span className="chip">Telefon: {detail?.phoneNumber ?? "-"}</span>
          <span className="chip">Deneme: {detail ? `${detail.attemptCount} / ${detail.maxAttempts}` : "..."}</span>
        </div>
      </section>

      {isLoading || !detail ? (
        <section className="panel-card">
          <div className="list-item">
            <div>
              <strong>Çağrı işi detayı yükleniyor</strong>
              <span>Attempt geçmişi, transcript ve response bağlantısı backend üzerinden getiriliyor.</span>
            </div>
          </div>
        </section>
      ) : (
        <div className="operation-workspace-grid call-job-detail-grid">
          <div className="operation-workspace-main">
            <SectionCard
              title="Operasyonel özet"
              description="Job durumu, hata görünürlüğü ve retry kontrolü aynı yerde tutulur."
              action={(
                <div className="operation-top-actions">
                  <Link href={`/operations/${operationId}/jobs`} className="button-secondary compact-button">
                    Listeye dön
                  </Link>
                  <button
                    type="button"
                    className="button-primary compact-button"
                    disabled={isRetrying}
                    onClick={() => void handleRecall()}
                  >
                    {isRetrying ? "Çağrı yeniden başlatılıyor..." : "Çağrıyı tekrar başlat"}
                  </button>
                </div>
              )}
            >
              <KeyValueList
                items={[
                  { label: "Oluşturulma", value: detail.createdAt },
                  { label: "Son güncelleme", value: detail.updatedAt },
                  { label: "Planlanan zaman", value: detail.scheduledFor },
                  { label: "Tekrar uygunluğu", value: detail.retryable ? "Uygun" : "Uygun değil" },
                  { label: "Yeni arama uygunluğu", value: detail.redialable ? "Uygun" : "Uygun değil" },
                  { label: "Failure reason", value: detail.failureReason ?? "Kayıtlı neden yok" },
                  { label: "Son hata", value: detail.lastErrorMessage ?? "Kayıtlı hata yok" },
                ]}
              />
            </SectionCard>

            <SectionCard
              title="Cevap editörü"
              description="Agent'in yanlış yorumladığı cevapları transcripti kontrol ederek burada düzeltebilirsiniz."
              action={detail.surveyResponse ? (
                <button
                  type="button"
                  className="button-primary compact-button"
                  disabled={isSavingAnswers || !hasUnsavedAnswerChanges}
                  onClick={() => void handleSaveAnswers()}
                >
                  {isSavingAnswers ? "Yanıtlar kaydediliyor..." : "Yanıtları kaydet"}
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

            <SectionCard
              title="Attempt geçmişi"
              description="Her retry aynı job altında yeni bir execution attempt olarak saklanır."
            >
              <div className="call-job-attempt-list">
                {detail.attempts.length === 0 ? (
                  <div className="operation-empty-state">
                    <strong>Kayıtlı attempt yok</strong>
                    <p>Job oluşturulmuş fakat henüz provider tarafına giden bir execution attempt kaydı görünmüyor.</p>
                  </div>
                ) : (
                  detail.attempts.map((attempt) => (
                    <article key={attempt.id} className="list-item call-job-attempt-card">
                      <div className="call-job-attempt-head">
                        <div>
                          <strong>Attempt #{attempt.attemptNumber}{attempt.latest ? " · son deneme" : ""}</strong>
                          <span>{formatAttemptStatus(attempt)} · {attempt.provider}</span>
                        </div>
                        <span className={attempt.surveyResponse?.usableResponse ? "operation-readiness-pill is-ready" : "operation-readiness-pill is-blocked"}>
                          {attempt.surveyResponse
                            ? attempt.surveyResponse.usableResponse ? "Usable response" : "Response var"
                            : attempt.failureReason ? "Failed / response yok" : "Result bekleniyor"}
                        </span>
                      </div>
                      <div className="call-job-attempt-meta">
                        <span>Dialed: {attempt.dialedAt}</span>
                        <span>Connected: {attempt.connectedAt}</span>
                        <span>Ended: {attempt.endedAt}</span>
                        <span>Provider call id: {attempt.providerCallId ?? "-"}</span>
                        <span>Süre: {attempt.durationSeconds ?? 0} sn</span>
                      </div>
                      {attempt.failureReason ? (
                        <div className="operation-inline-message is-danger compact">
                          <strong>Attempt failure</strong>
                          <span>{attempt.failureReason}</span>
                        </div>
                      ) : null}
                      <div className="call-job-attempt-body">
                        <div className="detail-row">
                          <span>Transcript key</span>
                          <strong>{attempt.transcriptStorageKey ?? "Kayıt yok"}</strong>
                        </div>
                        <div className="detail-row">
                          <span>Survey response</span>
                          <strong>
                            {attempt.surveyResponse
                              ? `${attempt.surveyResponse.status} · ${attempt.surveyResponse.validAnswerCount} geçerli cevap`
                              : "Bağlı response yok"}
                          </strong>
                        </div>
                      </div>
                    </article>
                  ))
                )}
              </div>
            </SectionCard>
          </div>

          <aside className="operation-workspace-side">
            <SectionCard
              title="İlişki bağlamı"
              description="Bu job kaydının operasyon, anket ve response ilişkileri tek bakışta görünsün."
            >
              <KeyValueList
                items={[
                  { label: "Operasyon", value: detail.operationName },
                  { label: "Anket", value: detail.surveyName },
                  { label: "Job raw status", value: detail.rawStatus },
                  { label: "İlk attempt mi", value: detail.firstAttempt ? "Evet" : "Hayır" },
                  { label: "Retry geçmişi", value: detail.retried ? "Var" : "Yok" },
                  { label: "Partial veri", value: detail.partialResponseDataExists ? "Var" : "Yok" },
                ]}
              />
            </SectionCard>

            <SectionCard
              title="Kenar durumlar"
              description="Bu yüzey bekleyen, başarısız, tamamlanmış ve yarım veri durumlarını ayrık göstermelidir."
            >
              <div className="stack-list">
                <div className="detail-row">
                  <span>Failed ve transcript yok</span>
                  <strong>{detail.failed && !detail.transcriptText ? "Bu kayda uyuyor" : "Hayır"}</strong>
                </div>
                <div className="detail-row">
                  <span>Completed ve response var</span>
                  <strong>{detail.rawStatus === "COMPLETED" && detail.surveyResponse ? "Evet" : "Hayır"}</strong>
                </div>
                <div className="detail-row">
                  <span>Completed ama usable cevap yok</span>
                  <strong>{detail.rawStatus === "COMPLETED" && detail.surveyResponse && !detail.surveyResponse.usableResponse ? "Evet" : "Hayır"}</strong>
                </div>
                <div className="detail-row">
                  <span>Queued ve result bekleniyor</span>
                  <strong>{detail.status === "Queued" && !detail.surveyResponse ? "Evet" : "Hayır"}</strong>
                </div>
                <div className="detail-row">
                  <span>Çoklu retry geçmişi</span>
                  <strong>{detail.attemptCount > 1 ? `${detail.attemptCount} attempt` : "Hayır"}</strong>
                </div>
              </div>
            </SectionCard>
          </aside>
        </div>
      )}
    </PageContainer>
  );
}

function formatAttemptStatus(attempt: CallJobAttempt): string {
  switch (attempt.status) {
    case "INITIATED":
      return "Hazırlanıyor";
    case "RINGING":
      return "Ring";
    case "IN_PROGRESS":
      return "Sürüyor";
    case "COMPLETED":
      return "Tamamlandı";
    case "FAILED":
      return "Başarısız";
    case "NO_ANSWER":
      return "Cevap yok";
    case "BUSY":
      return "Meşgul";
    case "VOICEMAIL":
      return "Sesli mesaja düştü";
    case "CANCELLED":
      return "İptal edildi";
    default:
      return attempt.status;
  }
}

function createAnswerDrafts(answers: CallJobSurveyResponseAnswer[]): SurveyAnswerDraft[] {
  return answers.map(createAnswerDraft);
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
      return "Açık uç"
    case "RATING":
      return "Puan"
    case "NUMBER":
      return "Sayısal giriş"
    case "SINGLE_CHOICE":
      return "Tek seçim"
    case "MULTI_CHOICE":
      return "Çoklu seçim"
    default:
      return questionType;
  }
}
