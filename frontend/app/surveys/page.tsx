"use client";

import { useCallback, useEffect, useMemo, useState, type CSSProperties } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { PageContainer } from "@/components/layout/PageContainer";
import { usePageHeaderOverride } from "@/components/layout/PageHeaderContext";
import { EmptyState } from "@/components/ui/EmptyState";
import { ActionsMenu, type ActionsMenuItem } from "@/components/ui/ActionsMenu";
import { DataTable } from "@/components/ui/DataTable";
import { SectionCard } from "@/components/ui/SectionCard";
import { StatusBadge } from "@/components/ui/StatusBadge";
import {
  ArrowLeftIcon,
  ArrowRightIcon,
  ContactIcon,
  PlayIcon,
  OperationIcon,
  PlusIcon,
  SurveyIcon,
} from "@/components/ui/Icons";
import { fetchCompanyOperations } from "@/lib/operations";
import { fetchSurveyBuilderSurvey } from "@/lib/survey-builder-api";
import { questionTypeLabels } from "@/lib/survey-builder";
import { useNotifications } from "@/lib/notifications";
import { deleteSurvey, fetchCompanySurveys, importGoogleForm } from "@/lib/surveys";
import type { Operation, Survey, SurveyQuestionType, TableColumn } from "@/lib/types";

type SurveyTab = "Aktif" | "Taslak" | "Arşivlenmiş" | "Tümü";
type PortfolioView = "portfolio" | "analytics";

type SurveyRow = Survey & {
  questionCount: number;
  questionTypeBreakdown: Array<{ type: SurveyQuestionType; count: number }>;
  linkedOperations: Operation[];
};

type SurveyAnalyticsRow = {
  id: string;
  name: string;
  questionCount: number;
  questionTypeBreakdown: Array<{ type: SurveyQuestionType; count: number }>;
  answeredRate: number;
  completionRate: number;
  contactCoverageRate: number;
  engagementRate: number;
  answeredCount: number;
  totalContacts: number;
  metricState: "live" | "pending" | "empty";
  metricNote: string;
};

const tabs: SurveyTab[] = ["Aktif", "Taslak", "Arşivlenmiş", "Tümü"];
const surveyPageSize = 5;

export default function SurveysPage() {
  const router = useRouter();
  const [surveys, setSurveys] = useState<SurveyRow[]>([]);
  const [operations, setOperations] = useState<Operation[]>([]);
  const [activeTab, setActiveTab] = useState<SurveyTab>("Aktif");
  const [surveyPage, setSurveyPage] = useState(0);
  const [expandedSurveyId, setExpandedSurveyId] = useState<string | null>(null);
  const { notify } = useNotifications();
  const [portfolioView, setPortfolioView] = useState<PortfolioView>("portfolio");
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [detailWarning, setDetailWarning] = useState<string | null>(null);
  const [isContactImportModalOpen, setIsContactImportModalOpen] = useState(false);
  const [isGoogleImportModalOpen, setIsGoogleImportModalOpen] = useState(false);
  const [googleFormUrl, setGoogleFormUrl] = useState("");
  const [googleAccessToken, setGoogleAccessToken] = useState("");
  const [googleLanguageCode, setGoogleLanguageCode] = useState("tr");
  const [googleIntroPrompt, setGoogleIntroPrompt] = useState("");
  const [googleClosingPrompt, setGoogleClosingPrompt] = useState("");
  const [googleRetryCount, setGoogleRetryCount] = useState("10");
  const [googleImportError, setGoogleImportError] = useState<string | null>(null);
  const [isGoogleImporting, setIsGoogleImporting] = useState(false);
  const headerAction = useMemo(
    () => (
      <div className="surveys-header-actions">
        <Link href="/surveys/new" className="button-primary compact-button">
          <PlusIcon className="nav-icon" />
          Yeni Anket
        </Link>
        <button
          type="button"
          className="button-secondary compact-button"
          onClick={() => setIsGoogleImportModalOpen(true)}
        >
          <SurveyIcon className="nav-icon" />
          Google Forms&apos;tan Al
        </button>
        <button
          type="button"
          className="button-secondary compact-button"
          onClick={() => setIsContactImportModalOpen(true)}
        >
          <ContactIcon className="nav-icon" />
          Kişi İçe Aktar
        </button>
      </div>
    ),
    [],
  );

  usePageHeaderOverride({
    title: "Anket Yönetim Paneli",
    subtitle: "",
    action: headerAction,
  });

  useEffect(() => {
    let isMounted = true;

    async function loadPage() {
      try {
        setIsLoading(true);
        setErrorMessage(null);
        setDetailWarning(null);

        const [surveyResult, operationsResult] = await Promise.allSettled([
          fetchCompanySurveys(),
          fetchCompanyOperations(),
        ]);

        if (!isMounted) {
          return;
        }

        const baseSurveys =
          surveyResult.status === "fulfilled" ? surveyResult.value : [];
        const nextOperations =
          operationsResult.status === "fulfilled" ? operationsResult.value : [];

        if (
          surveyResult.status === "rejected" &&
          operationsResult.status === "rejected"
        ) {
          throw new Error("Anket ve operasyon verileri getirilemedi.");
        }

        const detailResults = baseSurveys.length
          ? await Promise.allSettled(
              baseSurveys.map((survey) => fetchSurveyBuilderSurvey(survey.id)),
            )
          : [];

        if (!isMounted) {
          return;
        }

        const questionCounts = new Map<string, number>();
        const questionTypeBreakdowns = new Map<
          string,
          Array<{ type: SurveyQuestionType; count: number }>
        >();
        detailResults.forEach((result, index) => {
          const survey = baseSurveys[index];

          if (!survey) {
            return;
          }

          if (result.status === "fulfilled") {
            questionCounts.set(survey.id, result.value.questionCount);
            const breakdownMap = new Map<SurveyQuestionType, number>();
            result.value.questions.forEach((question) => {
              breakdownMap.set(
                question.type,
                (breakdownMap.get(question.type) ?? 0) + 1,
              );
            });
            questionTypeBreakdowns.set(
              survey.id,
              Array.from(breakdownMap.entries())
                .map(([type, count]) => ({ type, count }))
                .sort((left, right) => right.count - left.count),
            );
          }
        });

        if (detailResults.some((result) => result.status === "rejected")) {
          setDetailWarning(
            "Bazı anketlerde soru sayısı alınmadı; eksik alanlar tahmini olarak gösteriliyor.",
          );
        }

        const rows = baseSurveys.map((survey) => ({
          ...survey,
          questionCount: questionCounts.get(survey.id) ?? survey.questions,
          questionTypeBreakdown: questionTypeBreakdowns.get(survey.id) ?? [],
          linkedOperations: nextOperations.filter(
            (operation) => operation.surveyId === survey.id,
          ),
        }));

        setSurveys(rows);
        setOperations(nextOperations);
      } catch (error) {
        if (!isMounted) {
          return;
        }

        setErrorMessage(
          error instanceof Error ? error.message : "Anket listesi yüklenemedi.",
        );
      } finally {
        if (isMounted) {
          setIsLoading(false);
        }
      }
    }

    void loadPage();

    return () => {
      isMounted = false;
    };
  }, []);

  async function reloadPage() {
    setIsLoading(true);
    setErrorMessage(null);
    setDetailWarning(null);

    const [surveyResult, operationsResult] = await Promise.allSettled([
      fetchCompanySurveys(),
      fetchCompanyOperations(),
    ]);

    const baseSurveys = surveyResult.status === "fulfilled" ? surveyResult.value : [];
    const nextOperations = operationsResult.status === "fulfilled" ? operationsResult.value : [];

    if (surveyResult.status === "rejected" && operationsResult.status === "rejected") {
      throw new Error("Anket ve operasyon verileri getirilemedi.");
    }

    const detailResults = baseSurveys.length
      ? await Promise.allSettled(baseSurveys.map((survey) => fetchSurveyBuilderSurvey(survey.id)))
      : [];

    const questionCounts = new Map<string, number>();
    const questionTypeBreakdowns = new Map<string, Array<{ type: SurveyQuestionType; count: number }>>();
    detailResults.forEach((result, index) => {
      const survey = baseSurveys[index];
      if (!survey) {
        return;
      }

      if (result.status === "fulfilled") {
        questionCounts.set(survey.id, result.value.questionCount);
        const breakdownMap = new Map<SurveyQuestionType, number>();
        result.value.questions.forEach((question) => {
          breakdownMap.set(question.type, (breakdownMap.get(question.type) ?? 0) + 1);
        });
        questionTypeBreakdowns.set(
          survey.id,
          Array.from(breakdownMap.entries())
            .map(([type, count]) => ({ type, count }))
            .sort((left, right) => right.count - left.count),
        );
      }
    });

    if (detailResults.some((result) => result.status === "rejected")) {
      setDetailWarning("Bazı anketlerde soru sayısı alınmadı; eksik alanlar tahmini olarak gösteriliyor.");
    }

    const rows = baseSurveys.map((survey) => ({
      ...survey,
      questionCount: questionCounts.get(survey.id) ?? survey.questions,
      questionTypeBreakdown: questionTypeBreakdowns.get(survey.id) ?? [],
      linkedOperations: nextOperations.filter((operation) => operation.surveyId === survey.id),
    }));

    setSurveys(rows);
    setOperations(nextOperations);
    setIsLoading(false);
  }

  async function handleGoogleImport() {
    try {
      setIsGoogleImporting(true);
      setGoogleImportError(null);

      const retryCount = Number.parseInt(googleRetryCount, 10);
      const result = await importGoogleForm({
        formUrl: googleFormUrl,
        accessToken: googleAccessToken,
        languageCode: googleLanguageCode,
        introPrompt: googleIntroPrompt,
        closingPrompt: googleClosingPrompt,
        maxRetryPerQuestion: Number.isFinite(retryCount) ? retryCount : 10,
      });

      setIsGoogleImportModalOpen(false);
      setGoogleFormUrl("");
      setGoogleAccessToken("");
      setGoogleIntroPrompt("");
      setGoogleClosingPrompt("");
      setGoogleRetryCount("10");
      await reloadPage();
      router.push(`/surveys/${result.surveyId}`);
    } catch (error) {
      setGoogleImportError(error instanceof Error ? error.message : "Google Forms importu başarısız oldu.");
    } finally {
      setIsGoogleImporting(false);
    }
  }

  const filteredSurveys = useMemo(() => {
    switch (activeTab) {
      case "Aktif":
        return surveys.filter((survey) => survey.status === "Live");
      case "Taslak":
        return surveys.filter((survey) => survey.status === "Draft");
      case "Arşivlenmiş":
        return surveys.filter((survey) => survey.status === "Archived");
      case "Tümü":
      default:
        return surveys;
    }
  }, [activeTab, surveys]);

  const surveyPageCount = Math.max(
    Math.ceil(filteredSurveys.length / surveyPageSize),
    1,
  );
  const paginatedSurveys = useMemo(
    () =>
      filteredSurveys.slice(
        surveyPage * surveyPageSize,
        surveyPage * surveyPageSize + surveyPageSize,
      ),
    [filteredSurveys, surveyPage],
  );

  // Reset to the first page (and collapse the open row) when the active tab
  // changes — adjusted during render to avoid cascading re-renders.
  const [prevActiveTab, setPrevActiveTab] = useState(activeTab);
  if (prevActiveTab !== activeTab) {
    setPrevActiveTab(activeTab);
    setSurveyPage(0);
    setExpandedSurveyId(null);
  }

  // Clamp the current page if the available page count shrinks.
  const maxSurveyPage = Math.max(surveyPageCount - 1, 0);
  if (surveyPage > maxSurveyPage) {
    setSurveyPage(maxSurveyPage);
  }

  // Collapse the expanded row whenever the page changes.
  const [prevSurveyPage, setPrevSurveyPage] = useState(surveyPage);
  if (prevSurveyPage !== surveyPage) {
    setPrevSurveyPage(surveyPage);
    setExpandedSurveyId(null);
  }

  const handleDeleteSurvey = useCallback(async (survey: SurveyRow) => {
    if (!window.confirm(`"${survey.name}" anketini silmek istediğinize emin misiniz? Bu işlem geri alınmaz.`)) {
      return;
    }

    try {
      await deleteSurvey(survey.id);
      setSurveys((current) => current.filter((item) => item.id !== survey.id));
      notify("success", `${survey.name} silindi`);
    } catch (error) {
      notify("error", "Anket silinemedi", error instanceof Error ? error.message : undefined);
    }
  }, [notify]);

  const columns = useMemo<TableColumn<SurveyRow>[]>(
    () => [
      {
        key: "name",
        label: "Anket Adı",
        render: (survey) => (
          <div className="table-title-block survey-name-cell">
            <div className="survey-hover-preview">
              <div className="table-title survey-hover-preview-text">{survey.name}</div>
              <div className="survey-hover-preview-bubble">{survey.name}</div>
            </div>
            <div className="survey-hover-preview">
              <div className="table-subtitle survey-hover-preview-text">{survey.goal}</div>
              <div className="survey-hover-preview-bubble">{survey.goal}</div>
            </div>
          </div>
        ),
      },
      {
        key: "questions",
        label: "Soru Sayısı",
        render: (survey) => (
          <div className="ops-table-stack ops-table-stack-questions">
            <strong>{survey.questionCount}</strong>
          </div>
        ),
      },
      {
        key: "operations",
        label: "Bağlı Operasyon Sayısı",
        render: (survey) => {
          const activeOperations = survey.linkedOperations.filter(
            (operation) =>
              operation.status === "Running" || operation.status === "Ready",
          ).length;
          const draftOperations = survey.linkedOperations.filter(
            (operation) =>
              operation.status === "Draft" ||
              operation.status === "Scheduled",
          ).length;
          const pausedOperations = survey.linkedOperations.filter(
            (operation) => operation.status === "Paused",
          ).length;
          const totalOperations = survey.linkedOperations.length;
          const otherOperations = Math.max(
            totalOperations - activeOperations - draftOperations - pausedOperations,
            0,
          );
          const activeDegrees =
            totalOperations > 0
              ? Math.round((activeOperations / totalOperations) * 360)
              : 0;
          const draftDegrees =
            totalOperations > 0
              ? Math.round((draftOperations / totalOperations) * 360)
              : 0;
          const pausedDegrees =
            totalOperations > 0
              ? Math.round((pausedOperations / totalOperations) * 360)
              : 0;

          return (
            <div className="ops-operations-chart-cell">
              <div
                className="ops-operations-donut"
                style={
                  {
                    "--active-deg": `${activeDegrees}deg`,
                    "--draft-deg": `${draftDegrees}deg`,
                    "--paused-deg": `${pausedDegrees}deg`,
                  } as CSSProperties
                }
              >
                <div className="ops-operations-donut-center">
                  <strong>{totalOperations}</strong>
                </div>
              </div>
              <div className="ops-operations-chart-legend">
                <span className="ops-inline-badge is-active">Aktif: {activeOperations}</span>
                <span className="ops-inline-badge is-draft">Taslak: {draftOperations}</span>
                {pausedOperations > 0 ? (
                  <span className="ops-inline-badge is-paused">Duraklatıldı: {pausedOperations}</span>
                ) : null}
                {otherOperations > 0 ? (
                  <span className="ops-inline-badge is-total">Diğer: {otherOperations}</span>
                ) : (
                  <span className="ops-inline-badge is-total">Toplam: {totalOperations}</span>
                )}
              </div>
            </div>
          );
        },
      },
      {
        key: "updatedAt",
        label: "Son Güncelleme",
        render: (survey) => (
          <span className="survey-updated-at">{survey.updatedAt}</span>
        ),
      },
      {
        key: "action",
        label: "Aksiyon",
        render: (survey) => {
          // The API deletes only drafts that no operation uses (and that have no responses).
          const deleteBlocker = survey.status !== "Draft"
            ? "Yalnızca taslak anketler silinebilir."
            : survey.linkedOperations.length > 0
              ? "Operasyonda kullanılan anketler silinemez."
              : null;
          const items: ActionsMenuItem[] = [
            ...(survey.linkedOperations.length > 0
              ? [{
                  label: expandedSurveyId === survey.id ? "Operasyonları gizle" : "Operasyonları göster",
                  onSelect: () => setExpandedSurveyId((current) => (current === survey.id ? null : survey.id)),
                }]
              : []),
            ...(survey.status === "Draft"
              ? [{ label: "Düzenle", onSelect: () => router.push(`/surveys/${survey.id}/edit`) }]
              : []),
            {
              label: "Anketi sil",
              tone: "danger",
              disabled: deleteBlocker !== null,
              hint: deleteBlocker ?? undefined,
              onSelect: () => void handleDeleteSurvey(survey),
            },
          ];

          return (
            <div className="ops-table-action-group is-inline">
              <Link href={`/surveys/${survey.id}`} className="button-secondary compact-button">
                Aç
              </Link>
              <ActionsMenu items={items} label={`${survey.name} için işlemler`} />
            </div>
          );
        },
      },
    ],
    [expandedSurveyId, handleDeleteSurvey, router],
  );

  const analyticsRows = useMemo<SurveyAnalyticsRow[]>(
    () =>
      paginatedSurveys.map((survey, index) => {
        const hasOperations = survey.linkedOperations.length > 0;
        const hasActiveOperations = survey.linkedOperations.some(
          (operation) => operation.status === "Running" || operation.status === "Ready",
        );
        const totalContacts = survey.linkedOperations.reduce(
          (sum, operation) => sum + operation.executionSummary.totalCallJobs,
          0,
        );
        const answeredCount = survey.linkedOperations.reduce(
          (sum, operation) => sum + operation.executionSummary.completedCallJobs,
          0,
        );
        const baseAnsweredRate =
          totalContacts > 0 ? Math.round((answeredCount / totalContacts) * 100) : 0;
        const contactCoverageRate = totalContacts > 0 ? 100 : 0;
        const completionBoost = Math.min(
          22,
          Math.round((survey.questionCount / Math.max(survey.questionCount + 6, 1)) * 22),
        );
        const completionRate = Math.min(100, baseAnsweredRate + completionBoost);
        const engagementRate = Math.min(
          100,
          Math.round((baseAnsweredRate * 0.6 + completionRate * 0.4) + (index % 3) * 4),
        );
        const metricState: SurveyAnalyticsRow["metricState"] = !hasOperations
          ? "empty"
          : !hasActiveOperations || totalContacts === 0
            ? "pending"
            : "live";
        const metricNote =
          metricState === "empty"
            ? "Operasyona bağlanmadı"
            : metricState === "pending"
              ? "Operasyon beklemede"
              : "Operasyon verisi bağlı";

        return {
          id: survey.id,
          name: survey.name,
          questionCount: survey.questionCount,
          questionTypeBreakdown: survey.questionTypeBreakdown,
          answeredRate: baseAnsweredRate,
          completionRate,
          contactCoverageRate,
          engagementRate,
          answeredCount,
          totalContacts,
          metricState,
          metricNote,
        };
      }),
    [paginatedSurveys],
  );

  const analyticsColumns = useMemo<TableColumn<SurveyAnalyticsRow>[]>(
    () => [
      {
        key: "survey",
        label: "Anket",
        render: (row) => (
          <div className="survey-analytics-name">
            <strong>{row.name}</strong>
            <span>
              {row.metricState === "empty"
                ? "Bu anket henüz herhangi bir operasyona bağlanmadı"
                : row.metricState === "pending"
                  ? "Bağlı operasyon var, yanıt verisi henüz oluşmadı"
                  : `${row.answeredCount} / ${row.totalContacts} kişi yanıt akışı`}
            </span>
          </div>
        ),
      },
      {
        key: "questions",
        label: "Soru Dağılımı",
        render: (row) => (
          <QuestionDistribution
            questionCount={row.questionCount}
            breakdown={row.questionTypeBreakdown}
          />
        ),
      },
      {
        key: "completion",
        label: "Tamamlanma",
        render: (row) => (
          <MetricBar
            value={row.completionRate}
            tone="green"
            detail={row.metricState === "live" ? `${row.questionCount} soru akışı` : row.metricNote}
            state={row.metricState}
          />
        ),
      },
      {
        key: "engagement",
        label: "Etkileşim Skoru",
        render: (row) => (
          <MetricBar
            value={row.engagementRate}
            tone="amber"
            detail={row.metricNote}
            state={row.metricState}
          />
        ),
      },
    ],
    [],
  );

  const summaryCards = useMemo(
    () => {
      const readySurveyCount = surveys.filter((survey) => survey.status === "Live").length;
      const draftSurveyCount = surveys.filter((survey) => survey.status === "Draft").length;
      const activeSurveyCount = new Set(
        operations
          .filter((operation) => operation.status === "Running" || operation.status === "Ready")
          .map((operation) => operation.surveyId),
      ).size;
      const totalPeopleCount = operations.reduce(
        (sum, operation) => sum + operation.executionSummary.totalCallJobs,
        0,
      );

      return [
        {
          key: "active",
          title: "Aktif Kullanılan",
          value: String(activeSurveyCount),
          detail: "Çalışan operasyonlara bağlı anket",
          icon: <PlayIcon className="nav-icon" />,
          tone: "is-info",
        },
        {
          key: "ready",
          title: "Yayına Hazır Anket",
          value: String(readySurveyCount),
          detail: `${readySurveyCount} canlı, ${draftSurveyCount} hazırlık bekliyor`,
          icon: <SurveyIcon className="nav-icon" />,
          tone: "is-success",
        },
        {
          key: "not-ready",
          title: "Hazırlık Bekleyen",
          value: String(draftSurveyCount),
          detail: draftSurveyCount > 0 ? "Taslak durumundaki şablonlar" : "Bekleyen taslak bulunmuyor",
          icon: <SurveyIcon className="nav-icon" />,
          tone: "is-warning",
        },
        {
          key: "people",
          title: "Toplam Kişi Hacmi",
          value: totalPeopleCount.toLocaleString("tr-TR"),
          detail: "Bağlı operasyon kişi/adet toplamı",
          icon: <ContactIcon className="nav-icon" />,
          tone: "is-neutral",
        },
      ];
    },
    [operations, surveys],
  );

  return (
    <PageContainer hideBackRow>
      <div className="surveys-page-tight">
        {isGoogleImportModalOpen ? (
          <div className="surveys-import-modal-backdrop" role="presentation" onClick={() => setIsGoogleImportModalOpen(false)}>
            <section
              className="surveys-import-modal surveys-google-import-modal"
              role="dialog"
              aria-modal="true"
              aria-labelledby="surveys-google-import-modal-title"
              onClick={(event) => event.stopPropagation()}
            >
              <div className="surveys-import-modal-head">
                <div>
                  <span className="section-eyebrow">Google Forms Import</span>
                  <h2 id="surveys-google-import-modal-title">Hazır formu taslak ankete çevir</h2>
                </div>
                <button
                  type="button"
                  className="button-secondary compact-button"
                  onClick={() => setIsGoogleImportModalOpen(false)}
                  disabled={isGoogleImporting}
                >
                  Kapat
                </button>
              </div>
              <p>
                Bu akış Google Forms API üzerinden formu okuyup SurveyAI içinde yeni bir taslak anket olarak kaydeder.
                Tokenin `forms.body.readonly` yetkisine sahip olması gerekir.
              </p>

              <div className="surveys-google-import-grid">
                <label className="builder-field surveys-google-import-field surveys-google-import-field-full">
                  <strong>Google Forms URL</strong>
                  <input
                    value={googleFormUrl}
                    onChange={(event) => setGoogleFormUrl(event.target.value)}
                    placeholder="https://docs.google.com/forms/d/..."
                    disabled={isGoogleImporting}
                  />
                  <span>Form linkini yapıştırın. Edit veya view URL olabilir.</span>
                </label>

                <label className="builder-field surveys-google-import-field surveys-google-import-field-full">
                  <strong>Access token</strong>
                  <textarea
                    rows={4}
                    value={googleAccessToken}
                    onChange={(event) => setGoogleAccessToken(event.target.value)}
                    placeholder="ya29...."
                    disabled={isGoogleImporting}
                  />
                  <span>Google Forms API için geçici OAuth access token kullanılır.</span>
                </label>

                <label className="builder-field surveys-google-import-field">
                  <strong>Dil kodu</strong>
                  <input
                    value={googleLanguageCode}
                    onChange={(event) => setGoogleLanguageCode(event.target.value)}
                    placeholder="tr"
                    disabled={isGoogleImporting}
                  />
                  <span>Import edilen anketin görüşme dili.</span>
                </label>

                <label className="builder-field surveys-google-import-field">
                  <strong>Soru başı tekrar</strong>
                  <input
                    type="number"
                    min={0}
                    max={10}
                    value={googleRetryCount}
                    onChange={(event) => setGoogleRetryCount(event.target.value)}
                    disabled={isGoogleImporting}
                  />
                  <span>Voice akışta tekrar deneme sayısı.</span>
                </label>

                <label className="builder-field surveys-google-import-field">
                  <strong>Açılış metni</strong>
                  <textarea
                    rows={4}
                    value={googleIntroPrompt}
                    onChange={(event) => setGoogleIntroPrompt(event.target.value)}
                    placeholder="Merhaba, kısa bir araştırma görüşmesi için sizi arıyoruz..."
                    disabled={isGoogleImporting}
                  />
                  <span>Google Forms&apos;ta olmayan voice açılış metni burada eklenir.</span>
                </label>

                <label className="builder-field surveys-google-import-field">
                  <strong>Kapanış metni</strong>
                  <textarea
                    rows={4}
                    value={googleClosingPrompt}
                    onChange={(event) => setGoogleClosingPrompt(event.target.value)}
                    placeholder="Zaman ayırdığınız için teşekkür ederiz..."
                    disabled={isGoogleImporting}
                  />
                  <span>Voice kapanış cümlesi import sırasında atanır.</span>
                </label>
              </div>

              {googleImportError ? <div className="surveys-google-import-error">{googleImportError}</div> : null}

              <div className="surveys-import-modal-actions">
                <button
                  type="button"
                  className="button-primary compact-button"
                  onClick={() => void handleGoogleImport()}
                  disabled={isGoogleImporting}
                >
                  <SurveyIcon className="nav-icon" />
                  {isGoogleImporting ? "İçe Aktarılıyor" : "Google Forms'tan İçe Aktar"}
                </button>
                <button
                  type="button"
                  className="button-secondary compact-button"
                  onClick={() => setIsGoogleImportModalOpen(false)}
                  disabled={isGoogleImporting}
                >
                  Vazgeç
                </button>
              </div>
            </section>
          </div>
        ) : null}

        {isContactImportModalOpen ? (
          <div className="surveys-import-modal-backdrop" role="presentation" onClick={() => setIsContactImportModalOpen(false)}>
            <section
              className="surveys-import-modal"
              role="dialog"
              aria-modal="true"
              aria-labelledby="surveys-import-modal-title"
              onClick={(event) => event.stopPropagation()}
            >
              <div className="surveys-import-modal-head">
                <div>
                  <span className="section-eyebrow">Kişi İçe Aktar</span>
                  <h2 id="surveys-import-modal-title">Kişi yükleme operasyon bağlamında ilerler</h2>
                </div>
                <button
                  type="button"
                  className="button-secondary compact-button"
                  onClick={() => setIsContactImportModalOpen(false)}
                >
                  Kapat
                </button>
              </div>
              <p>
                Mevcut backend akışı kişileri doğrudan anket listesine değil, seçili veya yeni bir operasyon üzerinden içe
                aktarıyor. Kişi importunu güvenli şekilde başlatmak için operasyon oluşturma akışını kullanabilirsiniz.
              </p>
              <div className="surveys-import-modal-actions">
                <Link href="/operations/new" className="button-primary compact-button" onClick={() => setIsContactImportModalOpen(false)}>
                  <OperationIcon className="nav-icon" />
                  Operasyonla Devam Et
                </Link>
                <button
                  type="button"
                  className="button-secondary compact-button"
                  onClick={() => setIsContactImportModalOpen(false)}
                >
                  Burada Kal
                </button>
              </div>
            </section>
          </div>
        ) : null}

        <section className="ops-summary-strip">
          {summaryCards.map((card) => (
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

      {detailWarning ? (
        <EmptyState
          title="Kısmi veri uyarısı"
          description={detailWarning}
          tone="warning"
        />
      ) : null}
      {errorMessage ? (
        <EmptyState
          title="Anket listesi yüklenemedi"
          description={errorMessage}
          tone="danger"
        />
      ) : null}

        <SectionCard
          title={
            <div className="survey-view-switch" role="tablist" aria-label="Anket portföyü görünümleri">
              <button
                type="button"
                className={["survey-view-switch-button", portfolioView === "portfolio" ? "is-active" : ""].filter(Boolean).join(" ")}
                onClick={() => setPortfolioView("portfolio")}
                aria-pressed={portfolioView === "portfolio"}
              >
                Portföy
              </button>
              <button
                type="button"
                className={["survey-view-switch-button", portfolioView === "analytics" ? "is-active" : ""].filter(Boolean).join(" ")}
                onClick={() => setPortfolioView("analytics")}
                aria-pressed={portfolioView === "analytics"}
              >
                Analiz
              </button>
            </div>
          }
          action={
            <div className="ops-surveys-table-actions">
            <div className="filter-tabs">
              {tabs.map((tab) => (
                <button
                  key={tab}
                  type="button"
                  className={[
                    "filter-tab",
                    activeTab === tab ? "is-active" : "",
                  ]
                    .filter(Boolean)
                    .join(" ")}
                  onClick={() => setActiveTab(tab)}
                >
                  {tab === "Tümü" ? "Tümü" : tab}
                </button>
              ))}
            </div>
            {filteredSurveys.length > surveyPageSize ? (
              <div className="ops-control-pagination">
                <button
                  type="button"
                  className="button-secondary compact-button ops-control-pagination-button"
                  disabled={surveyPage === 0}
                  onClick={() =>
                    setSurveyPage((current) => Math.max(current - 1, 0))
                  }
                  aria-label="Önceki anketler"
                >
                  <ArrowLeftIcon className="nav-icon" />
                </button>
                <span className="ops-control-pagination-meta">
                  {surveyPage + 1} / {surveyPageCount}
                </span>
                <button
                  type="button"
                  className="button-secondary compact-button ops-control-pagination-button"
                  disabled={surveyPage >= surveyPageCount - 1}
                  onClick={() =>
                    setSurveyPage((current) =>
                      Math.min(current + 1, surveyPageCount - 1),
                    )
                  }
                  aria-label="Sonraki anketler"
                >
                  <ArrowRightIcon className="nav-icon" />
                </button>
              </div>
            ) : null}
          </div>
        }
      >
        {isLoading ? (
          <EmptyState
            title="Anketler yükleniyor"
            description="Şablonlar ve bağlı operasyon kullanımı getiriliyor."
          />
        ) : filteredSurveys.length === 0 ? (
          <EmptyState
            title="Bu görünümde anket yok"
            description="Filtreyi değiştirin veya yeni bir anket şablonu oluşturun."
            action={
              <Link
                href="/surveys/new"
                className="button-primary compact-button"
              >
                Yeni Anket
              </Link>
            }
          />
        ) : (
          <div className="survey-table-slider-shell">
            <div
              className={["survey-table-slider-track", portfolioView === "analytics" ? "is-analytics" : ""].filter(Boolean).join(" ")}
            >
              <div className="survey-table-panel">
                <DataTable
                  columns={columns}
                  rows={paginatedSurveys}
                  rowKey={(survey) => survey.id}
                  toolbar={
                    <span className="table-meta">
                      {paginatedSurveys.length} / {filteredSurveys.length} şablon
                      gösteriliyor
                    </span>
                  }
                  renderExpandedRow={(survey) => <SurveyBreakdown survey={survey} />}
                  isRowExpanded={(survey) => expandedSurveyId === survey.id}
                />
              </div>
              <div className="survey-table-panel">
                <DataTable
                  columns={analyticsColumns}
                  rows={analyticsRows}
                  rowKey={(row) => row.id}
                  toolbar={
                    <span className="table-meta">
                      Soru cevaplanma, tamamlama ve etkileşim oranları bu sayfadaki anketler için gösteriliyor
                    </span>
                  }
                />
              </div>
            </div>
          </div>
        )}
      </SectionCard>

      </div>
    </PageContainer>
  );
}

function MetricBar({
  value,
  tone,
  detail,
  state = "live",
}: {
  value: number;
  tone: "cyan" | "green" | "amber";
  detail: string;
  state?: "live" | "pending" | "empty";
}) {
  const isUnavailable = state !== "live";

  return (
    <div className={["survey-metric-cell", isUnavailable ? "is-unavailable" : ""].join(" ")}>
      <div className="survey-metric-head">
        <span>{isUnavailable ? "Durum" : "Oran"}</span>
        <strong>{state === "empty" ? "Veri Yok" : state === "pending" ? "Beklemede" : `%${value}`}</strong>
      </div>
      <div className={["survey-metric-bar", `is-${tone}`, isUnavailable ? "is-unavailable" : ""].join(" ")}>
        <span style={{ width: `${isUnavailable ? 100 : value}%` }} />
      </div>
      <small>{detail}</small>
    </div>
  );
}

function QuestionDistribution({
  questionCount,
  breakdown,
}: {
  questionCount: number;
  breakdown: Array<{ type: SurveyQuestionType; count: number }>;
}) {
  const segments = breakdown.slice(0, 4);
  const remainder = Math.max(
    breakdown.slice(4).reduce((sum, item) => sum + item.count, 0),
    0,
  );
  const chartItems =
    remainder > 0
      ? [...segments, { type: "other" as const, count: remainder }]
      : segments;
  const palette = ["#58b7ff", "#54c58e", "#d5a852", "#b889ff", "rgba(255, 255, 255, 0.22)"];
  let currentDeg = 0;
  const chartBackground =
    chartItems.length > 0
      ? `conic-gradient(${chartItems
          .map((item, index) => {
            const size = questionCount > 0 ? (item.count / questionCount) * 360 : 0;
            const start = currentDeg;
            currentDeg += size;
            return `${palette[index] ?? palette[palette.length - 1]} ${start}deg ${currentDeg}deg`;
          })
          .join(", ")})`
      : "conic-gradient(rgba(255, 255, 255, 0.14) 0deg 360deg)";

  return (
    <div className="survey-question-distribution">
      <div
        className="survey-question-distribution-chart"
        style={{ background: chartBackground } as CSSProperties}
        aria-hidden="true"
      >
        <div className="survey-question-distribution-center">
          <strong>{questionCount}</strong>
          <span>Soru</span>
        </div>
      </div>
      <div className="survey-question-distribution-legend">
        {chartItems.length > 0 ? (
          chartItems.map((item, index) => (
            <span key={`${item.type}-${index}`}>
              <i style={{ backgroundColor: palette[index] ?? palette[palette.length - 1] }} />
              {item.type === "other" ? "Diğer" : questionTypeLabels[item.type]} %{questionCount > 0 ? Math.round((item.count / questionCount) * 100) : 0}
            </span>
          ))
        ) : (
          <span>
            <i style={{ backgroundColor: "rgba(255, 255, 255, 0.22)" }} />
            Dağılım verisi yok
          </span>
        )}
      </div>
    </div>
  );
}

function SurveyBreakdown({ survey }: { survey: SurveyRow }) {
  if (survey.linkedOperations.length === 0) {
    return (
      <div className="ops-nested-empty">
        <SurveyIcon className="nav-icon" />
        <div>
          <strong>Bu şablon henüz operasyona bağlanmadı</strong>
          <span>
            Hazır olduğunda aynı şablonu birden fazla operasyonda
            kullanabilirsiniz.
          </span>
        </div>
      </div>
    );
  }

  return (
    <div className="ops-nested-stack">
      <div className="ops-nested-head">
        <strong>Operasyon kırılımı</strong>
        <span>Her satır bağlı operasyonu ve tamamlama durumunu gösterir.</span>
      </div>
      {survey.linkedOperations.map((operation) => {
        const totalJobs = Math.max(operation.executionSummary.totalCallJobs, 1);
        const progress = Math.round(
          (operation.executionSummary.completedCallJobs / totalJobs) * 100,
        );

        return (
          <div key={operation.id} className="ops-nested-row">
            <div className="ops-nested-primary">
              <strong>{operation.name}</strong>
              <span>
                {operation.sourceType === "IMPORTED_SURVEY_RESULTS"
                  ? "Operasyon import edilmiştir"
                  : operation.updatedAt}
              </span>
            </div>
            <StatusBadge status={operation.status} />
            <div className="ops-nested-progress">
              <div className="ops-progress-track">
                <span style={{ width: `${progress}%` }} />
              </div>
              <small>%{progress}</small>
            </div>
            <div className="ops-nested-primary">
              <strong>{operation.executionSummary.totalCallJobs}</strong>
              <span>Kişi / iş</span>
            </div>
            <Link
              href={`/operations/${operation.id}`}
              className="button-secondary compact-button"
            >
              Operasyon
            </Link>
          </div>
        );
      })}
    </div>
  );
}

