"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { PageContainer } from "@/components/layout/PageContainer";
import { usePageHeaderOverride } from "@/components/layout/PageHeaderContext";
import { DataTable } from "@/components/ui/DataTable";
import { EmptyState } from "@/components/ui/EmptyState";
import { SectionCard } from "@/components/ui/SectionCard";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { ArrowLeftIcon, ArrowRightIcon, PlusIcon } from "@/components/ui/Icons";
import { fetchCompanyOperations, fetchOperationAnalytics } from "@/lib/operations";
import type { Operation, OperationAnalytics, TableColumn } from "@/lib/types";

type OperationTab = "Aktif" | "Taslak" | "Tamamlandi" | "Tumu";
type PortfolioView = "portfolio" | "analytics";
const operationTabs: OperationTab[] = ["Aktif", "Taslak", "Tamamlandi", "Tumu"];

type OperationAnalyticsRow = {
  id: string;
  name: string;
  survey: string;
  totalJobs: number;
  completedJobs: number;
  pendingJobs: number;
  responseRate: number;
  participationRate: number;
  completionRate: number;
  rejectionRate: number;
  metricState: "live" | "pending" | "empty";
  metricNote: string;
  engagementDetail: string;
  rejectionDetail: string;
};

export default function OperationsPage() {
  const [operations, setOperations] = useState<Operation[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [priorityPage, setPriorityPage] = useState(0);
  const [activeTab, setActiveTab] = useState<OperationTab>("Aktif");
  const [portfolioView, setPortfolioView] = useState<PortfolioView>("portfolio");
  const [analyticsByOperationId, setAnalyticsByOperationId] = useState<Record<string, OperationAnalytics | null>>({});
  const headerAction = useMemo(
    () => (
      <div className="surveys-header-actions">
        <Link href="/operations/new" className="button-primary compact-button">
          <PlusIcon className="nav-icon" />
          Yeni Operasyon
        </Link>
      </div>
    ),
    [],
  );

  usePageHeaderOverride({
    title: "Operasyon Kontrol Paneli",
    subtitle: "Hazirlik, canli akis ve son operasyon hareketleri tek panelde toplanir.",
    action: headerAction,
  });

  useEffect(() => {
    const controller = new AbortController();

    async function loadOperations() {
      try {
        setIsLoading(true);
        setErrorMessage(null);
        const nextOperations = await fetchCompanyOperations(undefined, { signal: controller.signal });
        setOperations(nextOperations);
      } catch (error) {
        if (controller.signal.aborted) {
          return;
        }

        setErrorMessage(error instanceof Error ? error.message : "Operasyon listesi yuklenemedi.");
      } finally {
        if (!controller.signal.aborted) {
          setIsLoading(false);
        }
      }
    }

    void loadOperations();
    return () => controller.abort();
  }, []);

  const summary = useMemo(() => buildSummary(operations), [operations]);
  const activeContactFlow = useMemo(() => buildActiveContactFlow(operations, analyticsByOperationId), [analyticsByOperationId, operations]);
  const priorityPageSize = 5;
  const filteredOperations = useMemo(() => {
    switch (activeTab) {
      case "Aktif":
        return operations.filter((operation) => operation.status === "Running" || operation.status === "Ready");
      case "Taslak":
        return operations.filter(
          (operation) => operation.status === "Draft" || operation.status === "Scheduled" || operation.status === "Paused",
        );
      case "Tamamlandi":
        return operations.filter(
          (operation) => operation.status === "Completed" || operation.status === "Cancelled" || operation.status === "Failed",
        );
      case "Tumu":
      default:
        return operations;
    }
  }, [activeTab, operations]);
  const priorityPageCount = Math.max(Math.ceil(filteredOperations.length / priorityPageSize), 1);
  const paginatedPriorityOperations = useMemo(
    () => filteredOperations.slice(priorityPage * priorityPageSize, priorityPage * priorityPageSize + priorityPageSize),
    [filteredOperations, priorityPage],
  );

  // Reset to the first page when the active tab changes (adjust state during
  // render instead of in an effect to avoid a cascading re-render).
  const [prevActiveTab, setPrevActiveTab] = useState(activeTab);
  if (prevActiveTab !== activeTab) {
    setPrevActiveTab(activeTab);
    setPriorityPage(0);
  }

  // Clamp the current page if the available page count shrinks.
  const maxPriorityPage = Math.max(priorityPageCount - 1, 0);
  if (priorityPage > maxPriorityPage) {
    setPriorityPage(maxPriorityPage);
  }

  useEffect(() => {
    let isMounted = true;
    const analyticsTargets = [...paginatedPriorityOperations, ...operations.filter((operation) => operation.status === "Running" || operation.status === "Ready")];
    const visibleOperations = analyticsTargets.filter(
      (operation, index, items) => items.findIndex((candidate) => candidate.id === operation.id) === index && !(operation.id in analyticsByOperationId),
    );

    if (visibleOperations.length === 0) {
      return () => {
        isMounted = false;
      };
    }

    async function loadVisibleAnalytics() {
      const results = await Promise.allSettled(
        visibleOperations.map(async (operation) => ({
          operationId: operation.id,
          analytics: await fetchOperationAnalytics(operation.id),
        })),
      );

      if (!isMounted) {
        return;
      }

      setAnalyticsByOperationId((current) => {
        const next = { ...current };

        results.forEach((result, index) => {
          const operationId = visibleOperations[index]?.id;

          if (!operationId) {
            return;
          }

          next[operationId] = result.status === "fulfilled" ? result.value.analytics : null;
        });

        return next;
      });
    }

    void loadVisibleAnalytics();

    return () => {
      isMounted = false;
    };
  }, [analyticsByOperationId, operations, paginatedPriorityOperations]);

  const portfolioColumns = useMemo<TableColumn<Operation>[]>(
    () => [
      {
        key: "operation",
        label: "Operasyon",
        render: (operation) => (
          <div className="table-title-block survey-name-cell">
            <div className="survey-hover-preview">
              <div className="table-title survey-hover-preview-text">{operation.name}</div>
              <div className="survey-hover-preview-bubble">{operation.name}</div>
            </div>
            <div className="survey-hover-preview">
              <div className="table-subtitle survey-hover-preview-text">{operation.summary}</div>
              <div className="survey-hover-preview-bubble">{operation.summary}</div>
            </div>
          </div>
        ),
      },
      {
        key: "status",
        label: "Durum",
        render: (operation) => <StatusBadge status={resolveLifecycleStatus(operation)} label={resolveLifecycleLabel(operation)} />,
      },
      {
        key: "survey",
        label: "Bagli Anket",
        render: (operation) =>
          operation.surveyId ? (
            <Link href={`/surveys/${operation.surveyId}`} className="table-link-block ops-table-stack ops-table-stack-questions">
              <strong>{operation.survey}</strong>
              <span>{operation.owner}</span>
            </Link>
          ) : (
            <div className="ops-table-stack ops-table-stack-questions">
              <strong>{operation.survey}</strong>
              <span>{operation.owner}</span>
            </div>
          ),
      },
      {
        key: "jobs",
        label: "Cagri YukÃ¼",
        render: (operation) => (
          <div className="ops-inline-badges">
            <span className="ops-inline-badge is-total">Toplam: {operation.executionSummary.totalCallJobs}</span>
            <span className="ops-inline-badge is-draft">Bekleyen: {operation.executionSummary.pendingCallJobs}</span>
            <span className="ops-inline-badge is-active">Tamam: {operation.executionSummary.completedCallJobs}</span>
          </div>
        ),
      },
      {
        key: "progress",
        label: "Progress",
        render: (operation) => {
          const progress = resolveOperationProgress(operation);

          return (
            <div className="ops-control-progress-cell">
              <div className="ops-control-progress-track">
                <span style={{ width: `${progress}%` }} />
              </div>
              <small>%{progress}</small>
            </div>
          );
        },
      },
      {
        key: "action",
        label: "Aksiyon",
        render: (operation) => (
          <div className="ops-table-action-group">
            <Link href={`/operations/${operation.id}`} className="button-secondary compact-button">
              Detaya Git
            </Link>
          </div>
        ),
      },
    ],
    [],
  );

  const analyticsRows = useMemo<OperationAnalyticsRow[]>(
    () =>
      paginatedPriorityOperations.map((operation) => {
        const analytics = analyticsByOperationId[operation.id];
        const totalJobs = operation.executionSummary.totalCallJobs;
        const completedJobs = operation.executionSummary.completedCallJobs;
        const pendingJobs = operation.executionSummary.pendingCallJobs;
        const completionRate = totalJobs > 0 ? Math.round((completedJobs / totalJobs) * 100) : 0;
        const responseRate = analytics?.responseRate ? Math.round(analytics.responseRate) : 0;
        const participationRate = analytics?.participationRate ? Math.round(analytics.participationRate) : 0;
        const rejectedCalls = (analytics?.failedCallJobs ?? 0) + (analytics?.skippedCallJobs ?? 0);
        const attemptedCalls = analytics?.totalCallsAttempted ?? 0;
        const rejectionRate = attemptedCalls > 0 ? Math.min(100, Math.round((rejectedCalls / attemptedCalls) * 100)) : 0;
        const metricState: OperationAnalyticsRow["metricState"] =
          totalJobs === 0 ? "empty" : analytics ? "live" : "pending";
        const metricNote =
          metricState === "empty"
            ? "Cagri isi henuz olusmadi"
            : metricState === "pending"
              ? "Analitik veri hazirlaniyor"
              : `${completedJobs} / ${totalJobs} cagri tamamlandi`;
        const engagementDetail =
          metricState === "empty"
            ? "Yanit veya katilim verisi yok"
            : metricState === "pending"
              ? "Oranlar hazirlaniyor"
              : `%${responseRate} yanit / %${participationRate} katilim`;
        const rejectionDetail =
          metricState === "empty"
            ? "Red hesabi icin islenmis cagri yok"
            : metricState === "pending"
              ? "Red verisi yukleniyor"
              : `${analytics?.failedCallJobs ?? 0} basarisiz + ${analytics?.skippedCallJobs ?? 0} atlanan cagri`;

        return {
          id: operation.id,
          name: operation.name,
          survey: operation.survey,
          totalJobs,
          completedJobs,
          pendingJobs,
          responseRate,
          participationRate,
          completionRate,
          rejectionRate,
          metricState,
          metricNote,
          engagementDetail,
          rejectionDetail,
        };
      }),
    [analyticsByOperationId, paginatedPriorityOperations],
  );

  const analyticsColumns = useMemo<TableColumn<OperationAnalyticsRow>[]>(
    () => [
      {
        key: "operation",
        label: "Operasyon",
        render: (row) => (
          <div className="survey-analytics-name">
            <strong>{row.name}</strong>
            <span>{row.metricState === "live" ? `${row.survey} anketi aktif akis uzerinde` : row.metricNote}</span>
          </div>
        ),
      },
      {
        key: "engagement",
        label: "Yanit / Katilim",
        render: (row) => (
          <DualMetricBar
            primaryLabel="Yanit"
            primaryValue={row.responseRate}
            secondaryLabel="Katilim"
            secondaryValue={row.participationRate}
            detail={row.engagementDetail}
            state={row.metricState}
          />
        ),
      },
      {
        key: "rejection",
        label: "Cagri Red Orani",
        render: (row) => (
          <MetricBar
            value={row.rejectionRate}
            tone="amber"
            detail={row.rejectionDetail}
            state={row.metricState}
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
            detail={row.metricNote}
            state={row.metricState}
          />
        ),
      },
    ],
    [],
  );

  return (
    <PageContainer hideBackRow>
      <div className="ops-control-page-tight">
        {errorMessage ? <EmptyState title="Operasyon verileri yuklenemedi" description={errorMessage} tone="danger" /> : null}

        <section className="ops-summary-strip ops-control-summary-grid">
          <article className={["survey-summary-kpi", summary.activeToday > 0 ? "is-success" : "is-neutral"].join(" ")}>
            <div className="survey-summary-kpi-icon">
              <div className="ops-control-stat-icon is-success" />
            </div>
            <div className="survey-summary-kpi-copy">
              <span>Aktif Operasyon</span>
              <strong>{summary.activeToday}</strong>
              <small>Hazir veya yuruyen operasyon adedi</small>
            </div>
          </article>

          <article className="survey-summary-kpi is-success">
            <div className="survey-summary-kpi-icon">
              <div className="ops-control-stat-icon is-success" />
            </div>
            <div className="survey-summary-kpi-copy">
              <span>Hazir Baslatilabilir</span>
              <strong>{summary.readyCount}</strong>
              <small>Readiness kontrollerini gecmis operasyon sayisi</small>
            </div>
          </article>

          <article className="survey-summary-kpi is-warning">
            <div className="survey-summary-kpi-icon">
              <div className="ops-control-stat-icon is-warning" />
            </div>
            <div className="survey-summary-kpi-copy">
              <span>Blokajli Operasyon</span>
              <strong>{summary.blockedCount}</strong>
              <small>Mudahale gerektiren operasyon sayisi</small>
            </div>
          </article>

          <article className="survey-summary-kpi is-info">
            <div className="survey-summary-kpi-icon">
              <div className="ops-control-stat-icon is-info" />
            </div>
            <div className="survey-summary-kpi-copy">
              <span>Tamamlanma Orani</span>
              <strong>%{summary.completionRate}</strong>
              <small>{summary.completedCalls.toLocaleString("tr-TR")} / {summary.totalJobs.toLocaleString("tr-TR")} cagri tamamlandi</small>
            </div>
          </article>

          <article className="survey-summary-kpi is-neutral">
            <div className="survey-summary-kpi-icon">
              <div className="ops-control-stat-icon is-info" />
            </div>
            <div className="survey-summary-kpi-copy">
              <span>Tamamlanan Operasyon Orani</span>
              <strong>%{summary.completedOperationRate}</strong>
              <small>{summary.completedOperationCount} / {summary.totalOperationCount} operasyon tamamlandi</small>
            </div>
          </article>
        </section>

        {isLoading ? (
          <EmptyState title="Operasyon paneli hazirlaniyor" description="Hazirlik, aksiyon ve canli durum verileri getiriliyor." />
        ) : (
          <>
            <SectionCard
              title={
                <div className="survey-view-switch" role="tablist" aria-label="Operasyon portfoyu gorunumleri">
                  <button
                    type="button"
                    className={["survey-view-switch-button", portfolioView === "portfolio" ? "is-active" : ""].filter(Boolean).join(" ")}
                    onClick={() => setPortfolioView("portfolio")}
                    aria-pressed={portfolioView === "portfolio"}
                  >
                    Portfoy
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
                    {operationTabs.map((tab) => (
                      <button
                        key={tab}
                        type="button"
                        className={["filter-tab", activeTab === tab ? "is-active" : ""].filter(Boolean).join(" ")}
                        onClick={() => setActiveTab(tab)}
                      >
                        {tab}
                      </button>
                    ))}
                  </div>
                  <button
                    type="button"
                    className="button-secondary compact-button ops-control-pagination-button"
                    disabled={priorityPage === 0}
                    onClick={() => setPriorityPage((current) => Math.max(current - 1, 0))}
                    aria-label="Onceki operasyonlar"
                  >
                    <ArrowLeftIcon className="nav-icon" />
                  </button>
                  <span className="ops-control-pagination-meta">
                    {priorityPage + 1} / {priorityPageCount}
                  </span>
                  <button
                    type="button"
                    className="button-secondary compact-button ops-control-pagination-button"
                    disabled={priorityPage >= priorityPageCount - 1}
                    onClick={() => setPriorityPage((current) => Math.min(current + 1, priorityPageCount - 1))}
                    aria-label="Sonraki operasyonlar"
                  >
                    <ArrowRightIcon className="nav-icon" />
                  </button>
                </div>
              }
            >
              {paginatedPriorityOperations.length === 0 ? (
                <EmptyState title="Listelenecek operasyon yok" description="Filtreyi degistirin veya yeni operasyon ekleyin." />
              ) : (
                <div className="survey-table-slider-shell">
                  <div
                    className={["survey-table-slider-track", portfolioView === "analytics" ? "is-analytics" : ""].filter(Boolean).join(" ")}
                  >
                    <div className="survey-table-panel is-operations-portfolio">
                      <DataTable
                        columns={portfolioColumns}
                        rows={paginatedPriorityOperations}
                        rowKey={(operation) => operation.id}
                        toolbar={<span className="table-meta">{paginatedPriorityOperations.length} / {filteredOperations.length} operasyon gosteriliyor</span>}
                      />
                    </div>
                    <div className="survey-table-panel is-operations-analytics">
                      <DataTable
                        columns={analyticsColumns}
                        rows={analyticsRows}
                        rowKey={(row) => row.id}
                        toolbar={<span className="table-meta">Yanit, katilim, red ve tamamlanma metrikleri secili operasyonlar icin gosteriliyor</span>}
                      />
                    </div>
                  </div>
                </div>
              )}
            </SectionCard>

            <div className="ops-control-layout">
              <div className="ops-active-flow-layout">
                <SectionCard
                  title="Aktif Operasyon Kisi Akisi"
                  description="Toplam hedef kisiden yanit veren kisilere kadar mevcut arama akisini ozetler"
                >
                  {activeContactFlow.hasActiveOperations ? (
                    <>
                      <div className="ops-active-flow-split">
                        <div className="ops-active-flow-summary">
                          <div className="ops-active-flow-pill">
                            <span>Aktif operasyon</span>
                            <strong>{activeContactFlow.operationCount}</strong>
                          </div>
                          <div className="ops-active-flow-pill">
                            <span>Degerlendirmeye Alinan</span>
                            <strong>{activeContactFlow.totalTargets.toLocaleString("tr-TR")}</strong>
                          </div>
                          <div className="ops-active-flow-pill is-warning">
                            <span>Temas edilmis ama katilmamis</span>
                            <strong>{activeContactFlow.reachedButDidNotParticipateCount.toLocaleString("tr-TR")}</strong>
                          </div>
                          <div className="ops-active-flow-pill is-danger">
                            <span>Temas edilemeyen</span>
                            <strong>{activeContactFlow.unreachedCount.toLocaleString("tr-TR")}</strong>
                          </div>
                        </div>

                        <div className="ops-active-flow-grid">
                          {activeContactFlow.steps.filter((step) => step.key !== "targets").map((step) => (
                            <div key={step.label} className="ops-active-flow-card">
                              <div className="ops-active-flow-card-head">
                                <span>{step.label}</span>
                                <strong>{step.value.toLocaleString("tr-TR")}</strong>
                              </div>
                              <div className={["survey-metric-bar", step.tone].join(" ")}>
                                <span style={{ width: `${step.width}%` }} />
                              </div>
                              <small>{step.detail}</small>
                            </div>
                          ))}
                        </div>
                      </div>

                      <p className="ops-active-flow-note">
                        {activeContactFlow.pendingOperationCount > 0
                          ? `${activeContactFlow.pendingOperationCount} aktif operasyonun analitigi henuz hazir degil; bu kart mevcut veriye gore hesaplanir.`
                          : "Tum aktif operasyonlarin analitik verisi bu akis ozetine dahil edildi."}
                      </p>
                    </>
                  ) : (
                    <EmptyState
                      title="Aktif operasyon akis verisi yok"
                      description="Running veya Ready durumunda operasyon geldiginde kisi akis analizi burada gosterilecek."
                    />
                  )}
                </SectionCard>
              </div>
            </div>
          </>
        )}
      </div>
    </PageContainer>
  );
}

function buildSummary(operations: Operation[]) {
  const activeToday = operations.filter((operation) => operation.status === "Running" || operation.status === "Ready").length;
  const completedCalls = operations.reduce((sum, operation) => sum + operation.executionSummary.completedCallJobs, 0);
  const totalJobs = operations.reduce((sum, operation) => sum + operation.executionSummary.totalCallJobs, 0);
  const blockedCount = operations.filter((operation) => operation.readiness.blockingReasons.length > 0).length;
  const readyCount = operations.filter((operation) => operation.readiness.readyToStart).length;
  const completionRate = totalJobs > 0 ? Math.round((completedCalls / totalJobs) * 100) : 0;
  const completedOperationCount = operations.filter((operation) => operation.status === "Completed").length;
  const totalOperationCount = operations.length;
  const completedOperationRate = totalOperationCount > 0 ? Math.round((completedOperationCount / totalOperationCount) * 100) : 0;

  return {
    activeToday,
    completedCalls,
    totalJobs,
    blockedCount,
    readyCount,
    completionRate,
    completedOperationCount,
    totalOperationCount,
    completedOperationRate,
  };
}

function buildActiveContactFlow(operations: Operation[], analyticsByOperationId: Record<string, OperationAnalytics | null>) {
  const activeOperations = operations.filter((operation) => operation.status === "Running" || operation.status === "Ready");
  const activeAnalytics = activeOperations
    .map((operation) => analyticsByOperationId[operation.id])
    .filter((analytics): analytics is OperationAnalytics => Boolean(analytics));
  const totalTargets = activeAnalytics.reduce((sum, analytics) => sum + analytics.totalContacts, 0);
  const totalCalled = activeAnalytics.reduce((sum, analytics) => sum + analytics.totalCallsAttempted, 0);
  const totalReached = activeAnalytics.reduce(
    (sum, analytics) => sum + Math.min(analytics.totalContacts, Math.round((analytics.totalContacts * analytics.contactReachRate) / 100)),
    0,
  );
  const totalResponders = activeAnalytics.reduce((sum, analytics) => sum + analytics.totalResponses, 0);
  const unreachedCount = Math.max(totalCalled - totalReached, 0);
  const reachedButDidNotParticipateCount = Math.max(totalReached - totalResponders, 0);
  const base = Math.max(totalTargets, totalCalled, totalReached, totalResponders, 1);
  const coveredOperationCount = activeAnalytics.length;
  const operationCount = activeOperations.length;
  const pendingOperationCount = Math.max(operationCount - coveredOperationCount, 0);
  const coverageRate = operationCount > 0 ? Math.round((coveredOperationCount / operationCount) * 100) : 0;
  const buildStep = (
    label: string,
    value: number,
    detail: string,
    valueLabel: string,
    previousValue: number | null,
    tone: "is-cyan" | "is-amber" | "is-green",
  ) => {
    const overallRate = totalTargets > 0 ? Math.round((value / totalTargets) * 100) : 0;
    const stageRate = previousValue && previousValue > 0 ? Math.round((value / previousValue) * 100) : 100;

    return {
      label,
      value,
      width: Math.max(Math.round((value / base) * 100), value > 0 ? 24 : 0),
      detail,
      valueLabel,
      tone,
      overallRate,
      stageRateLabel: previousValue === null ? "Baz adim" : `Onceki asamadan gecis %${stageRate}`,
    };
  };

  return {
    hasActiveOperations: operationCount > 0,
    operationCount,
    coveredOperationCount,
    pendingOperationCount,
    coverageRate,
    totalTargets,
    unreachedCount,
    reachedButDidNotParticipateCount,
    steps: [
      { key: "targets", ...buildStep("Toplam hedef kisi", totalTargets, "Operasyon listelerine yuklenmis toplam kisi", "Hedef havuz", null, "is-cyan") },
      buildStep(
        "Aranan",
        totalCalled,
        totalTargets > 0 ? `%${Math.round((totalCalled / totalTargets) * 100)} hedefe temas denemesi` : "Arama denemesi henuz baslamadi",
        "Temas denemesi",
        totalTargets,
        "is-amber",
      ),
      buildStep(
        "Ulasilan",
        totalReached,
        totalTargets > 0 ? `%${Math.round((totalReached / totalTargets) * 100)} erisim kapsami` : "Erismeye dair veri yok",
        "Cevaplanan arama",
        totalCalled,
        "is-green",
      ),
      buildStep(
        "Yanit veren",
        totalResponders,
        totalReached > 0 ? `%${Math.round((totalResponders / totalReached) * 100)} erisilen kisiden yanit alindi` : "Yanit verisi henuz olusmadi",
        "Ankete katilan",
        totalReached,
        "is-cyan",
      ),
    ].map((step, index) => ("key" in step ? step : { key: `step-${index}`, ...step })),
  };
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

function DualMetricBar({
  primaryLabel,
  primaryValue,
  secondaryLabel,
  secondaryValue,
  detail,
  state = "live",
}: {
  primaryLabel: string;
  primaryValue: number;
  secondaryLabel: string;
  secondaryValue: number;
  detail: string;
  state?: "live" | "pending" | "empty";
}) {
  const isUnavailable = state !== "live";
  const safePrimary = isUnavailable ? 100 : primaryValue;
  const safeSecondary = isUnavailable ? 100 : secondaryValue;

  return (
    <div className={["ops-dual-metric-cell", isUnavailable ? "is-unavailable" : ""].join(" ")}>
      <div className="ops-dual-metric-row">
        <div className="ops-dual-metric-head">
          <span>{primaryLabel}</span>
          <strong>{isUnavailable ? (state === "empty" ? "Veri Yok" : "Beklemede") : `%${primaryValue}`}</strong>
        </div>
        <div className={["survey-metric-bar", "is-cyan", isUnavailable ? "is-unavailable" : ""].join(" ")}>
          <span style={{ width: `${safePrimary}%` }} />
        </div>
      </div>

      <div className="ops-dual-metric-row">
        <div className="ops-dual-metric-head">
          <span>{secondaryLabel}</span>
          <strong>{isUnavailable ? (state === "empty" ? "Veri Yok" : "Beklemede") : `%${secondaryValue}`}</strong>
        </div>
        <div className={["survey-metric-bar", "is-green", isUnavailable ? "is-unavailable" : ""].join(" ")}>
          <span style={{ width: `${safeSecondary}%` }} />
        </div>
      </div>

      <small>{detail}</small>
    </div>
  );
}

function resolveOperationProgress(operation: Operation) {
  const total = operation.executionSummary.totalCallJobs;

  if (total <= 0) {
    return operation.status === "Completed" ? 100 : 0;
  }

  return Math.max(0, Math.min(100, Math.round((operation.executionSummary.completedCallJobs / total) * 100)));
}

function resolveLifecycleStatus(operation: Operation) {
  if (operation.status === "Running" || operation.status === "Ready") {
    return "active";
  }

  if (operation.status === "Completed" || operation.status === "Cancelled" || operation.status === "Failed") {
    return "completed";
  }

  return "draft";
}

function resolveLifecycleLabel(operation: Operation) {
  if (operation.status === "Running" || operation.status === "Ready") {
    return "Aktif";
  }

  if (operation.status === "Completed" || operation.status === "Cancelled" || operation.status === "Failed") {
    return "Tamamlandi";
  }

  return "Taslak";
}



