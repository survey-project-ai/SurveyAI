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
          ? "Yayında"
          : readinessScore >= 5
            ? "Hazır"
            : readinessScore >= 3
              ? "Çalışılıyor"
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
      Ready: "Hazır",
      Running: "Canlı",
      Completed: "Tamamlandı",
      Failed: "Sorunlu",
      Scheduled: "Planlı",
      Paused: "Duraklatıldı",
      Cancelled: "İptal",
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
      { key: "completed", label: "Tamamlandı", count: totals.completed },
      { key: "partial", label: "Kısmi", count: totals.partial },
      { key: "abandoned", label: "Yarıda kaldı", count: totals.abandoned },
      { key: "invalid", label: "Geçersiz", count: totals.invalid },
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
        value: survey.status === "Live" ? "Yayında" : survey.status === "Archived" ? "Arşivde" : "Taslak",
        detail: `${survey.questions.length} soru / ${surveyStats?.readinessLabel ?? "Taslak"} aşaması`,
        icon: <SurveyIcon className="nav-icon" />,
        tone: "is-info",
      },
      {
        key: "operations",
        title: "Bağlı operasyon",
        value: String(linkedOperations.length),
        detail: linkedOperations.length > 0
          ? `${completedOperationsCount} tamamlanan, ${ongoingOperationsCount} devam eden operasyon.`
          : "Henüz bir operasyona bağlanmadı.",
        icon: <PlayIcon className="nav-icon" />,
        tone: "is-neutral",
      },
      {
        key: "people",
        title: "Atanmış kişi",
        value: String(assignedPeopleCount),
        detail: assignedPeopleCount > 0
          ? `${completedPeopleCount} kişi tamamlandı, kalan hacim operasyon akışında.`
          : "Henüz kişi ataması bulunmuyor.",
        icon: <EyeIcon className="nav-icon" />,
        tone: "is-warning",
      },
      {
        key: "preview",
        title: "Anket Preview",
        value: "Aç",
        detail: "Readonly görüşme akışına geçip anketi katılımcı perspektifinden inceleyin.",
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

      const message = error instanceof Error ? error.message : "Anket detayı yüklenemedi.";
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
      setErrorMessage(error instanceof Error ? error.message : "Taslak kopya oluşturulamadı.");
    } finally {
      setIsCreatingDraftCopy(false);
    }
  }, [router, survey]);

  const handleImportData = useCallback(async () => {
    if (!survey) {
      return;
    }
    if (!importFile) {
      setImportError("Lütfen import edilecek dosyayı seçin.");
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
      setImportError(error instanceof Error ? error.message : "Veri importu başarısız oldu.");
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
              Düzenle
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
              {isCreatingDraftCopy ? "Kopya hazırlanıyor..." : "Taslak Kopya Oluştur"}
            </button>
          ) : null}
        </div>
        {survey.status === "Live" ? (
          <div className="survey-header-notice" role="note" aria-label="Yayın uyarısı">
            <SurveyIcon className="nav-icon" />
            <div>
              <strong>Yayınlandı</strong>
              <span>Yayınlanmış anketlerde değişiklik yapılamaz. Veri ekleme işlemi analize yeni operasyon verisi olarak yansır; soru setini değiştirmek için taslak kopya kullanın.</span>
            </div>
          </div>
        ) : (
          <div className="survey-header-notice" role="note" aria-label="Veri import uyarısı">
            <SurveyIcon className="nav-icon" />
            <div>
              <strong>Saha verisi eklenebilir</strong>
              <span>Bu anketin mevcut soru seti korunur. Yükleyeceğiniz Excel veya CSV cevapları aynı ankete bağlı yeni bir import operasyonu olarak analizlere eklenir.</span>
            </div>
          </div>
        )}
      </div>
    ) : null,
    [handleCreateDraftCopy, isCreatingDraftCopy, survey, setImportError, setImportWarnings],
  );

  usePageHeaderOverride({
    title: survey?.name.trim() || "Anket Detayı",
    subtitle: survey?.summary.trim()
      ? survey.summary.trim()
      : "Anket performansını, operasyon bağlantılarını ve soru bazlı dağılımı tek yüzeyde inceleyin.",
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
            <strong>Anket düzenleme alanı yüklenemedi</strong>
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
              İlk satırı soru kolonları olacak şekilde hazırlanmış Excel veya CSV dosyasını yükleyin. Eşleşen kolonlar bu ankete
              bağlı yeni bir import operasyonu olarak eklenir ve analizlere mevcut operasyonlarla birlikte yansır.
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
                <span>{importFile ? `Seçilen dosya: ${importFile.name}` : "Desteklenen formatlar: .xlsx, .xls, .csv"}</span>
              </label>

              <label className="builder-field surveys-google-import-field surveys-google-import-field-full">
                <strong>Operasyon adı</strong>
                <input
                  value={importOperationName}
                  onChange={(event) => setImportOperationName(event.target.value)}
                  placeholder="Opsiyonel saha import operasyon adı"
                  disabled={isImportingData}
                />
                <span>Boş bırakılırsa anket adına göre anlamlı bir operasyon adı üretilir.</span>
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
                {isImportingData ? "Data ekleniyor" : "Datayı Ankete Ekle"}
              </button>
              <button
                type="button"
                className="button-secondary compact-button"
                onClick={() => setIsImportModalOpen(false)}
                disabled={isImportingData}
              >
                Vazgeç
              </button>
            </div>
          </section>
        </div>
      ) : null}

      {isLoading || !survey ? (
        <section className="panel-card">
          <div className="list-item">
            <div>
              <strong>Anket yükleniyor</strong>
              <span>Düzenleme ekranı hazırlanıyor.</span>
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
            title="Soru-Cevap Oranları"
            description="Hangi soruların daha rahat cevaplandığını ve görüşmelerin hangi adımlarda düşme eğilimi gösterdiğini izlemek için kullanılır."
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
              title="Bağlı Operasyon Dağılımı"
              description="Bu şablonun sahadaki kullanım biçimini ve operasyon yoğunluğunu özetler."
            >
              <OperationLoadChart
                operationCount={linkedOperations.length}
                assignedPeopleCount={assignedPeopleCount}
                breakdown={operationStatusBreakdown}
              />
            </SectionCard>

            <SectionCard
              eyebrow="Yanıt"
              title="Yanıt Durumu"
              description="Bu ankete bağlı operasyonlardan gelen tamamlanma ve kısmi geri dönüş dağılımını gösterir."
            >
              <ResponseStatusChart overview={responseOverview} breakdown={responseStatusBreakdown} />
            </SectionCard>

            <SectionCard
              eyebrow="Trend"
              title="Yanıt Hacmi Akışı"
              description="Gün bazında toplanan cevap hacmini izleyerek hangi dönemlerde verinin hızlandığını görmenizi sağlar."
            >
              <ResponseTrendChart trend={responseTrend} totalResponses={responseOverview.responses} />
            </SectionCard>

            <SectionCard
              eyebrow="Analiz"
              title="Soru Yapısı Dağılımı"
              description="Anketin hangi soru tiplerine daha fazla yaslandığını tek bakışta gösterir."
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
    strong: "Güçlü akıyor",
    steady: "Dengeli",
    fragile: "Takılıyor",
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
            <strong>{strongest?.title ?? "Henüz veri yok"}</strong>
            <p>{strongest ? `%${strongest.responseRate} cevaplanma oranı ile akışın en pürüzsüz noktası.` : "Yanıt geldikçe bu alan dolacak."}</p>
          </div>
          <div className="survey-detail-question-callout is-warning">
            <span>En çok sürtünen</span>
            <strong>{weakest?.title ?? "Henüz veri yok"}</strong>
            <p>{weakest ? `%${weakest.responseRate} cevaplanma oranı ile sadeleştirme ya da sıralama incelemesi gerektirebilir.` : "Yanıt geldikçe bu alan dolacak."}</p>
          </div>
          <div className="survey-detail-question-callout is-neutral">
            <span>Ortalama cevaplanma</span>
            <strong>%{averageRate}</strong>
            <p>{criticalCount > 0 ? `${criticalCount} soru kritik izleme listesinde.` : "Kritik eşikte soru görünmüyor."}</p>
          </div>
        </div>

        <div className="survey-detail-question-toolbar">
          <div className="filter-tabs">
            {[
              ["all", "Tüm sorular"],
              ["critical", "Kritik"],
              ["choice", "Seçimli"],
              ["open", "Açık uçlu"],
              ["rating", "Ölçek"],
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
              <span>Sıralama</span>
              <select value={sortBy} onChange={(event) => setSortBy(event.target.value as typeof sortBy)}>
                <option value="dropoff">En çok kayıp</option>
                <option value="response">En yüksek cevap</option>
                <option value="flow">Akış sırası</option>
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
        <div className="survey-detail-empty-chart">Seçilen filtre için soru bazlı analiz bulunmuyor.</div>
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
          <span>{item.answeredCount} yanıt</span>
          <strong>%{item.responseRate}</strong>
        </div>
        <div className={`survey-detail-bar-track survey-detail-question-track is-${item.tone}`}>
          <span style={{ width: `${Math.max(item.responseRate, item.answeredCount > 0 ? 12 : 0)}%` }} />
        </div>
        <div className="survey-detail-question-insight-row">
          {item.progressionRate !== null ? (
            <span className="survey-detail-question-progress">
              Sonraki soruya geçiş %{item.progressionRate}
              {item.dropOffCount ? ` · ${item.dropOffCount} kişi kaybı` : ""}
            </span>
          ) : (
            <span className="survey-detail-question-progress">Akışın son sorusu</span>
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
            <span>Seçenek dağılımı bulunmuyor</span>
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
    return "Bu soruda hiç cevap yok. Soru metni, akış sırası veya operasyon hedef kitlesi birlikte yeniden gözden geçirilmeli.";
  }

  if (context.progressionRate !== null && context.progressionRate < 70) {
    return "Bu sorudan sonra belirgin bir kopuş var. Soruyu daha erken ya da daha sonra konumlandırmak akış kaybını azaltabilir.";
  }

  if (item.responseRate < context.averageRate - 15) {
    return "Bu soru anket ortalamasının belirgin altında. Soru dili sadeleştirilmeli ve beklenen yanıt tipi daha net hale getirilmeli.";
  }

  if (["single_choice", "multi_choice", "dropdown", "yes_no"].includes(item.type) && item.optionBreakdown.length <= 1) {
    return "Seçenekli bir soru için dağılım dar kalıyor. Seçenekler yetersiz veya birbirine fazla yakın olabilir.";
  }

  if (item.type === "long_text" || item.type === "short_text") {
    return "Açık uçlu sorularda cevap eforu yükselir. Gerekirse daha yönlendirici bir yardımcı metin eklenebilir.";
  }

  if (item.type === "rating_1_5" || item.type === "rating_1_10") {
    return "Ölçek soruları hızlı akar. Bu soruyu kritik karar noktalarından önce kullanmak katılım ritmini koruyabilir.";
  }

  return "Bu soru dengeli görünüyor. Mevcut akışta korunabilir; yine de sonraki soruya geçiş oranı izlenmeli.";
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
          <span>Yanıt</span>
        </div>
      </div>

      <div className="survey-detail-chart-stack">
        <div className="survey-detail-inline-stats">
          <div className="survey-detail-inline-stat">
            <span>Cevap oranı</span>
            <strong>%{overview.responseRate}</strong>
          </div>
          <div className="survey-detail-inline-stat">
            <span>Temas kapsamı</span>
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
          <div className="survey-detail-empty-chart">Bu ankete ait cevap verisi oluştukça durum dağılımı burada görünecek.</div>
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
          <span>Toplam yanıt</span>
          <strong>{totalResponses}</strong>
        </div>
        <div className="survey-detail-inline-stat">
          <span>Aktif gün</span>
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
        <div className="survey-detail-empty-chart">Yanıt trendi için henüz tarih bazlı veri bulunmuyor.</div>
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
          <div className="survey-detail-empty-chart">Soru dağılımı oluşturmak için veri bulunmuyor.</div>
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
          <span>Atanmış kişi</span>
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
        <div className="survey-detail-empty-chart">Bu anket henüz bir operasyona bağlanmadı.</div>
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
    createdAt: "Henüz oluşmadı",
    publishedAt: null,
    updatedAt: "Bugün",
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


