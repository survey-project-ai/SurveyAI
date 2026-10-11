"use client";

import Link from "next/link";
import { notFound, useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState, type CSSProperties } from "react";
import { usePageHeaderOverride } from "@/components/layout/PageHeaderContext";
import { PageContainer } from "@/components/layout/PageContainer";
import { SectionCard } from "@/components/ui/SectionCard";
import { EyeIcon, PlayIcon, PlusIcon, SurveyIcon } from "@/components/ui/Icons";
import { fetchCompanyOperations, fetchOperationAnalytics } from "@/lib/operations";
import { questionTypeLabels } from "@/lib/survey-builder";
import { fetchSurveyBuilderSurvey, importSurveyBuilderData, saveSurveyBuilderSurvey } from "@/lib/survey-builder-api";
import type { Operation, OperationAnalytics, SurveyBuilderSurvey, SurveyQuestionType } from "@/lib/types";

type QuestionResponseTone = "strong" | "steady" | "fragile" | "empty";
type QuestionResponseItem = {
  id: string;
  code: string;
  title: string;
  order: number;
  type: SurveyQuestionType;
  answeredCount: number;
  responseRate: number;
  optionBreakdown: Array<{ label: string; count: number; percentage: number }>;
  tone: QuestionResponseTone;
  progressionRate: number | null;
  dropOffCount: number | null;
  nextQuestionTitle: string | null;
  recommendation: string;
};

export default function SurveyDetailPage() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const surveyId = params.id;
  const [survey, setSurvey] = useState<SurveyBuilderSurvey | null>(null);
  const [operations, setOperations] = useState<Operation[]>([]);
  const [analytics, setAnalytics] = useState<OperationAnalytics[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isMissing, setIsMissing] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isCreatingDraftCopy, setIsCreatingDraftCopy] = useState(false);
  const [isImportModalOpen, setIsImportModalOpen] = useState(false);
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importOperationName, setImportOperationName] = useState("");
  const [importError, setImportError] = useState<string | null>(null);
  const [importWarnings, setImportWarnings] = useState<string[]>([]);
  const [isImportingData, setIsImportingData] = useState(false);

  const surveyStats = useMemo(() => {
    if (!survey) {
      return null;
    }

    const choiceCount = survey.questions.filter((question) =>
      ["single_choice", "multi_choice", "dropdown", "yes_no"].includes(question.type),
    ).length;
    const ratingCount = survey.questions.filter((question) =>
      question.type === "rating_1_5" || question.type === "rating_1_10",
    ).length;
    const openEndedCount = survey.questions.filter((question) =>
      ["short_text", "long_text", "full_name", "number", "phone", "date"].includes(question.type),
    ).length;
    const requiredCount = survey.questions.filter((question) => question.required).length;
    const distinctTypes = new Set(survey.questions.map((question) => question.type)).size;
    const readinessScore = [
      survey.name.trim().length > 0,
      survey.summary.trim().length > 0,
      survey.introPrompt.trim().length > 0,
      survey.closingPrompt.trim().length > 0,
      survey.questions.length > 0,
    ].filter(Boolean).length;

    return {
      choiceCount,
      ratingCount,
      openEndedCount,
      requiredCount,
      distinctTypes,
      readinessLabel:
        survey.status === "Live"
          ? "Yayinda"
          : readinessScore >= 5
            ? "Hazir"
            : readinessScore >= 3
              ? "Calisiliyor"
              : "Taslak",
    };
  }, [survey]);

  const linkedOperations = useMemo(
    () => operations.filter((operation) => operation.surveyId === surveyId),
    [operations, surveyId],
  );

  const assignedPeopleCount = useMemo(
    () =>
      linkedOperations.reduce(
        (sum, operation) => sum + operation.executionSummary.totalCallJobs,
        0,
      ),
    [linkedOperations],
  );

  const completedOperationsCount = useMemo(
    () => linkedOperations.filter((operation) => operation.status === "Completed").length,
    [linkedOperations],
  );

  const ongoingOperationsCount = useMemo(
    () => linkedOperations.filter((operation) => ["Ready", "Running", "Scheduled", "Paused"].includes(operation.status)).length,
    [linkedOperations],
  );

  const completedPeopleCount = useMemo(
    () => analytics.reduce((sum, item) => sum + item.completedResponses, 0),
    [analytics],
  );

  const questionTypeBreakdown = useMemo(() => {
    if (!survey) {
      return [];
    }

    const counts = new Map<SurveyQuestionType, number>();
    survey.questions.forEach((question) => {
      counts.set(question.type, (counts.get(question.type) ?? 0) + 1);
    });

    return Array.from(counts.entries())
      .map(([type, count]) => ({ type, count }))
      .sort((left, right) => right.count - left.count);
  }, [survey]);

  const operationStatusBreakdown = useMemo(() => {
    const statusLabels: Record<Operation["status"], string> = {
      Draft: "Taslak",
      Ready: "Hazir",
      Running: "Canli",
      Completed: "Tamamlandi",
      Failed: "Sorunlu",
      Scheduled: "Planli",
      Paused: "Duraklatildi",
      Cancelled: "Iptal",
    };

    const counts = new Map<string, number>();
    linkedOperations.forEach((operation) => {
      const label = statusLabels[operation.status] ?? operation.status;
      counts.set(label, (counts.get(label) ?? 0) + 1);
    });

    return Array.from(counts.entries())
      .map(([label, count]) => ({ label, count }))
      .sort((left, right) => right.count - left.count);
  }, [linkedOperations]);

  const responseStatusBreakdown = useMemo(() => {
    const totals = linkedOperations.reduce(
      (accumulator, operation) => {
        const operationAnalytics = analytics.find((item) => item.operationId === operation.id);
        if (!operationAnalytics) {
          return accumulator;
        }

        accumulator.completed += operationAnalytics.completedResponses;
        accumulator.partial += operationAnalytics.partialResponses;
        accumulator.abandoned += operationAnalytics.abandonedResponses;
        accumulator.invalid += operationAnalytics.invalidResponses;
        return accumulator;
      },
      { completed: 0, partial: 0, abandoned: 0, invalid: 0 },
    );

    return [
      { key: "completed", label: "Tamamlandi", count: totals.completed },
      { key: "partial", label: "Kismi", count: totals.partial },
      { key: "abandoned", label: "Yarida kaldi", count: totals.abandoned },
      { key: "invalid", label: "Gecersiz", count: totals.invalid },
    ].filter((item) => item.count > 0);
  }, [analytics, linkedOperations]);

  const responseOverview = useMemo(() => {
    const totals = linkedOperations.reduce(
      (accumulator, operation) => {
        const operationAnalytics = analytics.find((item) => item.operationId === operation.id);
        if (!operationAnalytics) {
          return accumulator;
        }

        accumulator.contacts += operationAnalytics.totalContacts;
        accumulator.responses += operationAnalytics.totalResponses;
        accumulator.calls += operationAnalytics.totalCallsAttempted;
        accumulator.completed += operationAnalytics.completedResponses;
        accumulator.partial += operationAnalytics.partialResponses;
        return accumulator;
      },
      { contacts: 0, responses: 0, calls: 0, completed: 0, partial: 0 },
    );

    const responseRate = totals.contacts > 0 ? Math.round((totals.responses / totals.contacts) * 100) : 0;
    const reachRate = totals.contacts > 0 ? Math.round((totals.calls / totals.contacts) * 100) : 0;
    return { ...totals, responseRate, reachRate };
  }, [analytics, linkedOperations]);

  const responseTrend = useMemo(() => {
    const counts = new Map<string, number>();

    linkedOperations.forEach((operation) => {
      const operationAnalytics = analytics.find((item) => item.operationId === operation.id);
      operationAnalytics?.responseTrend.forEach((point) => {
        counts.set(point.label, (counts.get(point.label) ?? 0) + point.count);
      });
    });

    return Array.from(counts.entries()).map(([label, count]) => ({ label, count }));
  }, [analytics, linkedOperations]);

  const questionResponseBreakdown = useMemo(() => {
    if (!survey) {
      return [];
    }

    const totalResponses = responseOverview.responses;
    const answeredCounts = new Map<string, number>();
    const breakdownByQuestion = new Map<string, Map<string, { label: string; count: number }>>();

    analytics.forEach((operationAnalytics) => {
      operationAnalytics.questionSummaries.forEach((summary) => {
        answeredCounts.set(summary.questionCode, (answeredCounts.get(summary.questionCode) ?? 0) + summary.answeredCount);
        if (!breakdownByQuestion.has(summary.questionCode)) {
          breakdownByQuestion.set(summary.questionCode, new Map());
        }

        const questionBreakdown = breakdownByQuestion.get(summary.questionCode);
        summary.breakdown.forEach((item) => {
          const existing = questionBreakdown?.get(item.key);
          questionBreakdown?.set(item.key, {
            label: item.label,
            count: (existing?.count ?? 0) + item.count,
          });
        });
      });
    });

    const items: QuestionResponseItem[] = survey.questions.map((question, index) => {
      const answeredCount = answeredCounts.get(question.code) ?? 0;
      const responseRate = totalResponses > 0 ? Math.round((answeredCount / totalResponses) * 100) : 0;
      const optionBreakdown = Array.from((breakdownByQuestion.get(question.code) ?? new Map()).values())
        .map((item) => ({
          label: item.label,
          count: item.count,
          percentage: answeredCount > 0 ? Math.round((item.count / answeredCount) * 100) : 0,
        }))
        .sort((left, right) => right.count - left.count);

      return {
        id: question.id,
        code: question.code,
        title: question.title,
        order: index + 1,
        type: question.type,
        answeredCount,
        responseRate,
        optionBreakdown,
        tone:
          responseRate >= 85 ? "strong" : responseRate >= 60 ? "steady" : responseRate > 0 ? "fragile" : "empty",
        progressionRate: null,
        dropOffCount: null,
        nextQuestionTitle: null,
        recommendation: "",
      };
    });

    const averageRate = items.length > 0
      ? Math.round(items.reduce((sum, item) => sum + item.responseRate, 0) / items.length)
      : 0;

    return items.map((item, index) => {
      const nextItem = items[index + 1];
      const progressionRate = nextItem && item.answeredCount > 0
        ? Math.max(0, Math.min(100, Math.round((nextItem.answeredCount / item.answeredCount) * 100)))
        : null;
      const dropOffCount = nextItem ? Math.max(item.answeredCount - nextItem.answeredCount, 0) : null;
      return {
        ...item,
        progressionRate,
        dropOffCount,
        nextQuestionTitle: nextItem?.title ?? null,
        recommendation: buildQuestionRecommendation(item, {
          progressionRate,
          dropOffCount,
          averageRate,
        }),
      };
    });
  }, [analytics, responseOverview.responses, survey]);

  const questionResponseHighlights = useMemo(() => {
    if (questionResponseBreakdown.length === 0) {
      return { strongest: null, weakest: null };
    }

    const sortedByResponseRate = [...questionResponseBreakdown].sort((left, right) => {
      if (left.responseRate === right.responseRate) {
        return left.title.localeCompare(right.title, "tr");
      }

      return right.responseRate - left.responseRate;
    });

    return {
      strongest: sortedByResponseRate[0] ?? null,
      weakest: sortedByResponseRate[sortedByResponseRate.length - 1] ?? null,
    };
  }, [questionResponseBreakdown]);

  const summaryCards = useMemo(
    () => survey ? [
      {
        key: "status",
        title: "Durum",
        value: survey.status === "Live" ? "Yayinda" : survey.status === "Archived" ? "Arsivde" : "Taslak",
        detail: `${survey.questions.length} soru / ${surveyStats?.readinessLabel ?? "Taslak"} asamasi`,
        icon: <SurveyIcon className="nav-icon" />,
        tone: "is-info",
      },
      {
        key: "operations",
        title: "Bagli operasyon",
        value: String(linkedOperations.length),
        detail: linkedOperations.length > 0
          ? `${completedOperationsCount} tamamlanan, ${ongoingOperationsCount} devam eden operasyon.`
          : "Henuz bir operasyona baglanmadi.",
        icon: <PlayIcon className="nav-icon" />,
        tone: "is-neutral",
      },
      {
        key: "people",
        title: "Atanmis kisi",
        value: String(assignedPeopleCount),
        detail: assignedPeopleCount > 0
          ? `${completedPeopleCount} kisi tamamlandi, kalan hacim operasyon akisinda.`
          : "Henuz kisi atamasi bulunmuyor.",
        icon: <EyeIcon className="nav-icon" />,
        tone: "is-warning",
      },
      {
        key: "preview",
        title: "Anket Preview",
        value: "Ac",
        detail: "Readonly gorusme akisina gecip anketi katilimci perspektifinden inceleyin.",
        icon: <EyeIcon className="nav-icon" />,
        tone: "is-neutral",
        href: `/surveys/${survey.id}/preview`,
      },
    ] : [],
    [assignedPeopleCount, completedOperationsCount, completedPeopleCount, linkedOperations.length, ongoingOperationsCount, survey, surveyStats?.readinessLabel],
  );

  const loadSurveyDetail = useCallback(async (signal?: AbortSignal) => {
    if (!surveyId) {
      return;
    }

    try {
      setIsLoading(true);
      setErrorMessage(null);
      setIsMissing(false);
      const [surveyResult, operationsResult] = await Promise.allSettled([
        fetchSurveyBuilderSurvey(surveyId, undefined, signal ? { signal } : undefined),
        fetchCompanyOperations(undefined, signal ? { signal } : undefined),
      ]);

      if (surveyResult.status === "rejected") {
        throw surveyResult.reason;
      }

      setSurvey(surveyResult.value);
      setOperations(operationsResult.status === "fulfilled" ? operationsResult.value : []);
    } catch (error) {
      if (signal?.aborted) {
        return;
      }

      const message = error instanceof Error ? error.message : "Anket detayi yuklenemedi.";
      if (message.includes("(404)")) {
        setIsMissing(true);
        return;
      }

      setErrorMessage(message);
    } finally {
      if (!signal?.aborted) {
        setIsLoading(false);
      }
    }
  }, [surveyId]);

  useEffect(() => {
    if (!surveyId) {
      return;
    }

    const controller = new AbortController();
    async function run() {
      await loadSurveyDetail(controller.signal);
    }
    void run();
    return () => controller.abort();
  }, [loadSurveyDetail, surveyId]);

  useEffect(() => {
    const controller = new AbortController();

    async function loadAnalytics() {
      if (linkedOperations.length === 0) {
        setAnalytics([]);
        return;
      }

      const results = await Promise.allSettled(
        linkedOperations.map((operation) =>
          fetchOperationAnalytics(operation.id, undefined, { signal: controller.signal }),
        ),
      );

      if (controller.signal.aborted) {
        return;
      }

      setAnalytics(results.flatMap((result) => (result.status === "fulfilled" ? [result.value] : [])));
    }

    void loadAnalytics();
    return () => controller.abort();
  }, [linkedOperations]);

  const handleCreateDraftCopy = useCallback(async () => {
    if (!survey) {
      return;
    }

    try {
      setIsCreatingDraftCopy(true);
      setErrorMessage(null);

      const copiedSurvey = buildDraftCopySurvey(survey);
      const result = await saveSurveyBuilderSurvey(copiedSurvey, "draft");
      router.push(`/surveys/${result.survey.id}`);
    } catch (error) {
      setErrorMessage(error instanceof Error ? error.message : "Taslak kopya olusturulamadi.");
    } finally {
      setIsCreatingDraftCopy(false);
    }
  }, [router, survey]);

  const handleImportData = useCallback(async () => {
    if (!survey) {
      return;
    }
    if (!importFile) {
      setImportError("Lutfen import edilecek dosyayi secin.");
      return;
    }

    try {
      setIsImportingData(true);
      setImportError(null);
      setImportWarnings([]);
      const result = await importSurveyBuilderData(survey.id, importFile, {
        operationName: importOperationName,
      });
      if (result.warnings.length > 0) {
        setImportWarnings(result.warnings);
      }
      setIsImportModalOpen(false);
      setImportFile(null);
      setImportOperationName("");
      await loadSurveyDetail();
      router.push(`/operations/${result.operationId}`);
    } catch (error) {
      setImportError(error instanceof Error ? error.message : "Veri importu basarisiz oldu.");
    } finally {
      setIsImportingData(false);
    }
  }, [importFile, importOperationName, loadSurveyDetail, router, survey]);

  const headerAction = useMemo(
    () => survey && survey.status !== "Archived" ? (
      <div className="survey-header-action-cluster">
        <div className="survey-header-action-buttons">
          {survey.status === "Draft" ? (
            <Link href={`/surveys/${survey.id}/edit`} className="button-secondary compact-button survey-header-button">
              <SurveyIcon className="nav-icon" />
              Duzenle
            </Link>
          ) : null}
          <button
            type="button"
            className="button-primary compact-button survey-header-button"
            onClick={() => {
              setImportError(null);
              setImportWarnings([]);
              setIsImportModalOpen(true);
            }}
          >
            <PlusIcon className="nav-icon" />
            Data Ekle
          </button>
          <Link href="/surveys/new" className="button-secondary compact-button survey-header-button is-new">
            <PlusIcon className="nav-icon" />
            Yeni anket
          </Link>
          {survey.status === "Live" ? (
            <button
              type="button"
              className="button-secondary compact-button survey-header-button is-copy"
              onClick={() => void handleCreateDraftCopy()}
              disabled={isCreatingDraftCopy}
            >
              <SurveyIcon className="nav-icon" />
              {isCreatingDraftCopy ? "Kopya hazirlaniyor..." : "Taslak Kopya Olustur"}
            </button>
          ) : null}
        </div>
        {survey.status === "Live" ? (
          <div className="survey-header-notice" role="note" aria-label="Yayin uyarisi">
            <SurveyIcon className="nav-icon" />
            <div>
              <strong>Yayinlandi</strong>
              <span>Yayinlanmis anketlerde degisiklik yapilamaz. Veri ekleme islemi analize yeni operasyon verisi olarak yansir; soru setini degistirmek icin taslak kopya kullanin.</span>
            </div>
          </div>
        ) : (
          <div className="survey-header-notice" role="note" aria-label="Veri import uyarisi">
            <SurveyIcon className="nav-icon" />
            <div>
              <strong>Saha verisi eklenebilir</strong>
              <span>Bu anketin mevcut soru seti korunur. Yukleyeceginiz Excel veya CSV cevaplari ayni ankete bagli yeni bir import operasyonu olarak analizlere eklenir.</span>
            </div>
          </div>
        )}
      </div>
    ) : null,
    [handleCreateDraftCopy, isCreatingDraftCopy, survey, setImportError, setImportWarnings],
  );

  usePageHeaderOverride({
    title: survey?.name.trim() || "Anket Detayi",
    subtitle: survey?.summary.trim()
      ? survey.summary.trim()
      : "Anket performansini, operasyon baglantilarini ve soru bazli dagilimi tek yuzeyde inceleyin.",
    action: headerAction,
  });

  if (isMissing) {
    notFound();
  }

  return (
    <PageContainer hideBackRow>
      {errorMessage ? (
        <section className="panel-card">
          <div className="operation-inline-message is-danger">
            <strong>Anket duzenleme alani yuklenemedi</strong>
            <span>{errorMessage}</span>
          </div>
        </section>
      ) : null}

      {isImportModalOpen ? (
        <div className="surveys-import-modal-backdrop" role="presentation" onClick={() => !isImportingData && setIsImportModalOpen(false)}>
          <section
            className="surveys-import-modal surveys-google-import-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="survey-data-import-modal-title"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="surveys-import-modal-head">
              <div>
                <span className="section-eyebrow">Data Ekle</span>
                <h2 id="survey-data-import-modal-title">Mevcut ankete saha verisi ekle</h2>
              </div>
              <button
                type="button"
                className="button-secondary compact-button"
                onClick={() => setIsImportModalOpen(false)}
                disabled={isImportingData}
              >
                Kapat
              </button>
            </div>
            <p>
              Ilk satiri soru kolonlari olacak sekilde hazirlanmis Excel veya CSV dosyasini yukleyin. Eslesen kolonlar bu ankete
              bagli yeni bir import operasyonu olarak eklenir ve analizlere mevcut operasyonlarla birlikte yansir.
            </p>

            <div className="surveys-google-import-grid">
              <label className="builder-field surveys-google-import-field surveys-google-import-field-full">
                <strong>Dosya</strong>
                <input
                  type="file"
                  accept=".xlsx,.xls,.csv"
                  onChange={(event) => setImportFile(event.target.files?.[0] ?? null)}
                  disabled={isImportingData}
                />
                <span>{importFile ? `Secilen dosya: ${importFile.name}` : "Desteklenen formatlar: .xlsx, .xls, .csv"}</span>
              </label>

              <label className="builder-field surveys-google-import-field surveys-google-import-field-full">
                <strong>Operasyon adi</strong>
                <input
                  value={importOperationName}
                  onChange={(event) => setImportOperationName(event.target.value)}
                  placeholder="Opsiyonel saha import operasyon adi"
                  disabled={isImportingData}
                />
                <span>Bos birakilirsa anket adina gore anlamli bir operasyon adi uretilir.</span>
              </label>
            </div>

            {importError ? <div className="surveys-google-import-error">{importError}</div> : null}
            {importWarnings.length > 0 ? (
              <div className="surveys-google-import-error">{importWarnings.join(" ")}</div>
            ) : null}

            <div className="surveys-import-modal-actions">
              <button
                type="button"
                className="button-primary compact-button"
                onClick={() => void handleImportData()}
                disabled={isImportingData}
              >
                <PlusIcon className="nav-icon" />
                {isImportingData ? "Data ekleniyor" : "Datayi Ankete Ekle"}
              </button>
              <button
                type="button"
                className="button-secondary compact-button"
                onClick={() => setIsImportModalOpen(false)}
                disabled={isImportingData}
              >
                Vazgec
              </button>
            </div>
          </section>
        </div>
      ) : null}

      {isLoading || !survey ? (
        <section className="panel-card">
          <div className="list-item">
            <div>
              <strong>Anket yukleniyor</strong>
              <span>Duzenleme ekrani hazirlaniyor.</span>
            </div>
          </div>
        </section>
      ) : (
        <div className="survey-detail-shell survey-detail-compact">
          <section className="ops-summary-strip survey-detail-summary-strip">
            {summaryCards.map((card) => card.href ? (
              <Link
                key={card.key}
                href={card.href}
                className={["survey-summary-kpi", card.tone, "survey-detail-kpi-link"].join(" ")}
              >
                <div className="survey-summary-kpi-icon">{card.icon}</div>
                <div className="survey-summary-kpi-copy">
                  <span>{card.title}</span>
                  <strong>{card.value}</strong>
                  <small>{card.detail}</small>
                </div>
              </Link>
            ) : (
              <article key={card.key} className={["survey-summary-kpi", card.tone].join(" ")}>
                <div className="survey-summary-kpi-icon">{card.icon}</div>
                <div className="survey-summary-kpi-copy">
                  <span>{card.title}</span>
                  <strong>{card.value}</strong>
                  <small>{card.detail}</small>
                </div>
              </article>
            ))}
          </section>

          <SectionCard
            eyebrow="Soru Analizi"
            title="Soru-Cevap Oranlari"
            description="Hangi sorularin daha rahat cevaplandigini ve gorusmelerin hangi adimlarda dusme egilimi gosterdigini izlemek icin kullanilir."
          >
            <QuestionResponseChart
              items={questionResponseBreakdown}
              strongest={questionResponseHighlights.strongest}
              weakest={questionResponseHighlights.weakest}
            />
          </SectionCard>

          <section className="survey-detail-analytics-grid">
            <SectionCard
              eyebrow="Operasyon"
              title="Bagli Operasyon Dagilimi"
              description="Bu sablonun sahadaki kullanim bicimini ve operasyon yogunlugunu ozetler."
            >
              <OperationLoadChart
                operationCount={linkedOperations.length}
                assignedPeopleCount={assignedPeopleCount}
                breakdown={operationStatusBreakdown}
              />
            </SectionCard>

            <SectionCard
              eyebrow="Yanit"
              title="Yanit Durumu"
              description="Bu ankete bagli operasyonlardan gelen tamamlanma ve kismi geri donus dagilimini gosterir."
            >
              <ResponseStatusChart overview={responseOverview} breakdown={responseStatusBreakdown} />
            </SectionCard>

            <SectionCard
              eyebrow="Trend"
              title="Yanit Hacmi Akisi"
              description="Gun bazinda toplanan cevap hacmini izleyerek hangi donemlerde verinin hizlandigini gormenizi saglar."
            >
              <ResponseTrendChart trend={responseTrend} totalResponses={responseOverview.responses} />
            </SectionCard>

            <SectionCard
              eyebrow="Analiz"
              title="Soru Yapisi Dagilimi"
              description="Anketin hangi soru tiplerine daha fazla yaslandigini tek bakista gosterir."
            >
              <QuestionMixChart total={survey.questions.length} breakdown={questionTypeBreakdown} />
            </SectionCard>
          </section>
        </div>
      )}
    </PageContainer>
  );
}

function QuestionResponseChart({
  items,
  strongest,
  weakest,
}: {
  items: QuestionResponseItem[];
  strongest: QuestionResponseItem | null;
  weakest: QuestionResponseItem | null;
}) {
  const [sortBy, setSortBy] = useState<"flow" | "response" | "dropoff">("dropoff");
  const [filterBy, setFilterBy] = useState<"all" | "critical" | "choice" | "open" | "rating">("all");
  const toneLabels: Record<string, string> = {
    strong: "Guclu akiyor",
    steady: "Dengeli",
    fragile: "Takiliyor",
    empty: "Veri yok",
  };

  const filteredItems = useMemo(() => {
    return items.filter((item) => {
      if (filterBy === "critical") {
        return item.responseRate < 60 || (item.progressionRate !== null && item.progressionRate < 75);
      }
      if (filterBy === "choice") {
        return ["single_choice", "multi_choice", "dropdown", "yes_no"].includes(item.type);
      }
      if (filterBy === "open") {
        return ["short_text", "long_text", "full_name", "number", "phone", "date"].includes(item.type);
      }
      if (filterBy === "rating") {
        return item.type === "rating_1_5" || item.type === "rating_1_10";
      }
      return true;
    });
  }, [filterBy, items]);

  const sortedItems = useMemo(() => {
    const nextItems = [...filteredItems];

    if (sortBy === "flow") {
      return nextItems.sort((left, right) => left.order - right.order);
    }

    if (sortBy === "response") {
      return nextItems.sort((left, right) => right.responseRate - left.responseRate || left.order - right.order);
    }

    return nextItems.sort((left, right) => (right.dropOffCount ?? -1) - (left.dropOffCount ?? -1) || left.order - right.order);
  }, [filteredItems, sortBy]);

  const visibleItems = sortedItems.slice(0, 5);
  const criticalCount = items.filter((item) => item.responseRate < 60 || (item.progressionRate !== null && item.progressionRate < 75)).length;
  const averageRate = items.length > 0 ? Math.round(items.reduce((sum, item) => sum + item.responseRate, 0) / items.length) : 0;

  return (
    <div className="survey-detail-question-chart">
      <div className="survey-detail-question-summary-head">
        <div className="survey-detail-question-highlights">
          <div className="survey-detail-question-callout is-positive">
            <span>En rahat cevaplanan</span>
            <strong>{strongest?.title ?? "Henuz veri yok"}</strong>
            <p>{strongest ? `%${strongest.responseRate} cevaplanma orani ile akisin en puruzsuz noktasi.` : "Yanit geldikce bu alan dolacak."}</p>
          </div>
          <div className="survey-detail-question-callout is-warning">
            <span>En cok surtunen</span>
            <strong>{weakest?.title ?? "Henuz veri yok"}</strong>
            <p>{weakest ? `%${weakest.responseRate} cevaplanma orani ile sadeleştirme ya da siralama incelemesi gerektirebilir.` : "Yanit geldikce bu alan dolacak."}</p>
          </div>
          <div className="survey-detail-question-callout is-neutral">
            <span>Ortalama cevaplanma</span>
            <strong>%{averageRate}</strong>
            <p>{criticalCount > 0 ? `${criticalCount} soru kritik izleme listesinde.` : "Kritik esikte soru gorunmuyor."}</p>
          </div>
        </div>

        <div className="survey-detail-question-toolbar">
          <div className="filter-tabs">
            {[
              ["all", "Tum sorular"],
              ["critical", "Kritik"],
              ["choice", "Secimli"],
              ["open", "Acik uclu"],
              ["rating", "Olcek"],
            ].map(([value, label]) => (
              <button
                key={value}
                type="button"
                className={`filter-tab ${filterBy === value ? "is-active" : ""}`}
                onClick={() => setFilterBy(value as typeof filterBy)}
              >
                {label}
              </button>
            ))}
          </div>

          <div className="survey-detail-question-actions">
            <label className="survey-detail-select">
              <span>Siralama</span>
              <select value={sortBy} onChange={(event) => setSortBy(event.target.value as typeof sortBy)}>
                <option value="dropoff">En cok kayip</option>
                <option value="response">En yuksek cevap</option>
                <option value="flow">Akis sirasi</option>
              </select>
            </label>
          </div>
        </div>
      </div>

      {visibleItems.length > 0 ? (
        <div className="survey-detail-question-list">
          {visibleItems.map((item) => (
            <QuestionResponseRow key={item.id} item={item} toneLabels={toneLabels} />
          ))}
        </div>
      ) : (
        <div className="survey-detail-empty-chart">Secilen filtre icin soru bazli analiz bulunmuyor.</div>
      )}
    </div>
  );
}

function QuestionResponseRow({
  item,
  toneLabels,
}: {
  item: QuestionResponseItem;
  toneLabels: Record<string, string>;
}) {
  return (
    <div className="survey-detail-question-row">
      <div className="survey-detail-question-main">
        <div className="survey-detail-question-copy">
          <div>
            <strong>{item.title}</strong>
            <span>{questionTypeLabels[item.type]}</span>
          </div>
          <em className={`survey-detail-question-badge is-${item.tone}`}>{toneLabels[item.tone]}</em>
        </div>
        <div className="survey-detail-question-meta">
          <span>Soru {item.order}</span>
          <span>{item.answeredCount} yanit</span>
          <strong>%{item.responseRate}</strong>
        </div>
        <div className={`survey-detail-bar-track survey-detail-question-track is-${item.tone}`}>
          <span style={{ width: `${Math.max(item.responseRate, item.answeredCount > 0 ? 12 : 0)}%` }} />
        </div>
        <div className="survey-detail-question-insight-row">
          {item.progressionRate !== null ? (
            <span className="survey-detail-question-progress">
              Sonraki soruya gecis %{item.progressionRate}
              {item.dropOffCount ? ` · ${item.dropOffCount} kisi kaybi` : ""}
            </span>
          ) : (
            <span className="survey-detail-question-progress">Akin son sorusu</span>
          )}
        </div>
        <p className="survey-detail-question-recommendation">{item.recommendation}</p>
      </div>
      <div className="survey-detail-question-side">
        {item.optionBreakdown.length > 0 ? (
          <div className="survey-detail-question-options">
            {item.optionBreakdown.slice(0, 4).map((option) => (
              <div key={`${item.id}-${option.label}`} className="survey-detail-question-option-pill">
                <span>{option.label}</span>
                <strong>%{option.percentage}</strong>
              </div>
            ))}
          </div>
        ) : (
          <div className="survey-detail-question-options is-empty">
            <span>Secenek dagilimi bulunmuyor</span>
          </div>
        )}
      </div>
    </div>
  );
}

function buildQuestionRecommendation(
  item: Pick<QuestionResponseItem, "type" | "responseRate" | "optionBreakdown">,
  context: {
    progressionRate: number | null;
    dropOffCount: number | null;
    averageRate: number;
  },
) {
  if (item.responseRate === 0) {
    return "Bu soruda hic cevap yok. Soru metni, akis sirasi veya operasyon hedef kitlesi birlikte yeniden gozden gecirilmeli.";
  }

  if (context.progressionRate !== null && context.progressionRate < 70) {
    return "Bu sorudan sonra belirgin bir kopus var. Soruyu daha erken ya da daha sonra konumlandirmak akis kaybini azaltabilir.";
  }

  if (item.responseRate < context.averageRate - 15) {
    return "Bu soru anket ortalamasinin belirgin altinda. Soru dili sadeleştirilmeli ve beklenen yanit tipi daha net hale getirilmeli.";
  }

  if (["single_choice", "multi_choice", "dropdown", "yes_no"].includes(item.type) && item.optionBreakdown.length <= 1) {
    return "Secenekli bir soru icin dagilim dar kaliyor. Secenekler yetersiz veya birbirine fazla yakin olabilir.";
  }

  if (item.type === "long_text" || item.type === "short_text") {
    return "Acik uclu sorularda cevap eforu yukselir. Gerekirse daha yonlendirici bir yardimci metin eklenebilir.";
  }

  if (item.type === "rating_1_5" || item.type === "rating_1_10") {
    return "Olcek sorulari hizli akar. Bu soruyu kritik karar noktalarindan once kullanmak katilim ritmini koruyabilir.";
  }

  return "Bu soru dengeli gorunuyor. Mevcut akista korunabilir; yine de sonraki soruya gecis orani izlenmeli.";
}

function ResponseStatusChart({
  overview,
  breakdown,
}: {
  overview: {
    contacts: number;
    responses: number;
    calls: number;
    completed: number;
    partial: number;
    responseRate: number;
    reachRate: number;
  };
  breakdown: Array<{ key: string; label: string; count: number }>;
}) {
  const total = breakdown.reduce((sum, item) => sum + item.count, 0);
  const palette: Record<string, string> = {
    completed: "#4eaf7b",
    partial: "#d6a046",
    abandoned: "#e07c62",
    invalid: "#7d8aa6",
  };

  let currentDeg = 0;
  const chartBackground =
    breakdown.length > 0
      ? `conic-gradient(${breakdown
          .map((item) => {
            const size = total > 0 ? (item.count / total) * 360 : 0;
            const start = currentDeg;
            currentDeg += size;
            return `${palette[item.key] ?? "#8ea0b8"} ${start}deg ${currentDeg}deg`;
          })
          .join(", ")})`
      : "conic-gradient(rgba(255, 255, 255, 0.14) 0deg 360deg)";

  return (
    <div className="survey-detail-chart-grid">
      <div className="survey-detail-donut-chart is-response" style={{ background: chartBackground } as CSSProperties}>
        <div className="survey-detail-donut-center">
          <strong>{overview.responses}</strong>
          <span>Yanit</span>
        </div>
      </div>

      <div className="survey-detail-chart-stack">
        <div className="survey-detail-inline-stats">
          <div className="survey-detail-inline-stat">
            <span>Cevap orani</span>
            <strong>%{overview.responseRate}</strong>
          </div>
          <div className="survey-detail-inline-stat">
            <span>Temas kapsami</span>
            <strong>%{overview.reachRate}</strong>
          </div>
        </div>

        {breakdown.length > 0 ? (
          <div className="survey-detail-chart-legend">
            {breakdown.map((item) => (
              <div key={item.key} className="survey-detail-legend-row">
                <span className="survey-detail-legend-copy">
                  <i style={{ backgroundColor: palette[item.key] ?? "#8ea0b8" }} />
                  {item.label}
                </span>
                <strong>{item.count}</strong>
              </div>
            ))}
          </div>
        ) : (
          <div className="survey-detail-empty-chart">Bu ankete ait cevap verisi olustukca durum dagilimi burada gorunecek.</div>
        )}
      </div>
    </div>
  );
}

function ResponseTrendChart({
  trend,
  totalResponses,
}: {
  trend: Array<{ label: string; count: number }>;
  totalResponses: number;
}) {
  const maxCount = Math.max(...trend.map((item) => item.count), 1);

  return (
    <div className="survey-detail-bar-stack">
      <div className="survey-detail-inline-stats">
        <div className="survey-detail-inline-stat">
          <span>Toplam yanit</span>
          <strong>{totalResponses}</strong>
        </div>
        <div className="survey-detail-inline-stat">
          <span>Aktif gun</span>
          <strong>{trend.length}</strong>
        </div>
      </div>

      {trend.length > 0 ? (
        <div className="survey-detail-bar-list">
          {trend.map((item) => (
            <div key={item.label} className="survey-detail-bar-row">
              <div className="survey-detail-bar-copy">
                <span>{item.label}</span>
                <strong>{item.count}</strong>
              </div>
              <div className="survey-detail-bar-track is-response-trend">
                <span style={{ width: `${Math.max((item.count / maxCount) * 100, item.count > 0 ? 14 : 0)}%` }} />
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="survey-detail-empty-chart">YanÄ±t trendi icin henuz tarih bazli veri bulunmuyor.</div>
      )}
    </div>
  );
}

function QuestionMixChart({
  total,
  breakdown,
}: {
  total: number;
  breakdown: Array<{ type: SurveyQuestionType; count: number }>;
}) {
  const chartItems = breakdown.slice(0, 5);
  const palette = ["#58b7ff", "#54c58e", "#d5a852", "#8e7cf3", "#ef8f77"];
  let currentDeg = 0;
  const chartBackground =
    chartItems.length > 0
      ? `conic-gradient(${chartItems
          .map((item, index) => {
            const size = total > 0 ? (item.count / total) * 360 : 0;
            const start = currentDeg;
            currentDeg += size;
            return `${palette[index] ?? palette[palette.length - 1]} ${start}deg ${currentDeg}deg`;
          })
          .join(", ")})`
      : "conic-gradient(rgba(255, 255, 255, 0.14) 0deg 360deg)";

  return (
    <div className="survey-detail-chart-grid">
      <div className="survey-detail-donut-chart" style={{ background: chartBackground } as CSSProperties}>
        <div className="survey-detail-donut-center">
          <strong>{total}</strong>
          <span>Soru</span>
        </div>
      </div>

      <div className="survey-detail-chart-legend">
        {chartItems.length > 0 ? (
          chartItems.map((item, index) => (
            <div key={item.type} className="survey-detail-legend-row">
              <span className="survey-detail-legend-copy">
                <i style={{ backgroundColor: palette[index] ?? palette[palette.length - 1] }} />
                {questionTypeLabels[item.type]}
              </span>
              <strong>%{total > 0 ? Math.round((item.count / total) * 100) : 0}</strong>
            </div>
          ))
        ) : (
          <div className="survey-detail-empty-chart">Soru dagilimi olusturmak icin veri bulunmuyor.</div>
        )}
      </div>
    </div>
  );
}

function OperationLoadChart({
  operationCount,
  assignedPeopleCount,
  breakdown,
}: {
  operationCount: number;
  assignedPeopleCount: number;
  breakdown: Array<{ label: string; count: number }>;
}) {
  const maxCount = Math.max(...breakdown.map((item) => item.count), 1);

  return (
    <div className="survey-detail-bar-stack">
      <div className="survey-detail-inline-stats">
        <div className="survey-detail-inline-stat">
          <span>Toplam operasyon</span>
          <strong>{operationCount}</strong>
        </div>
        <div className="survey-detail-inline-stat">
          <span>Atanmis kisi</span>
          <strong>{assignedPeopleCount}</strong>
        </div>
      </div>

      {breakdown.length > 0 ? (
        <div className="survey-detail-bar-list">
          {breakdown.map((item) => (
            <div key={item.label} className="survey-detail-bar-row">
              <div className="survey-detail-bar-copy">
                <span>{item.label}</span>
                <strong>{item.count}</strong>
              </div>
              <div className="survey-detail-bar-track">
                <span style={{ width: `${Math.max((item.count / maxCount) * 100, item.count > 0 ? 12 : 0)}%` }} />
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="survey-detail-empty-chart">Bu anket henuz bir operasyona baglanmadi.</div>
      )}
    </div>
  );
}

function buildDraftCopySurvey(survey: SurveyBuilderSurvey): SurveyBuilderSurvey {
  return {
    ...survey,
    id: `draft-copy-${globalThis.crypto.randomUUID()}`,
    name: `${survey.name.trim() || "Anket"} - Taslak Kopya`,
    status: "Draft",
    createdAt: "Henuz olusmadi",
    publishedAt: null,
    updatedAt: "Bugun",
    questions: survey.questions.map((question, questionIndex) => ({
      ...question,
      id: `draft-question-${globalThis.crypto.randomUUID()}`,
      options: question.options?.map((option, optionIndex) => ({
        ...option,
        id: `draft-option-${questionIndex + 1}-${optionIndex + 1}-${globalThis.crypto.randomUUID()}`,
      })),
    })),
    questionCount: survey.questions.length,
  };
}


