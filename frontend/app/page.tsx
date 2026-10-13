"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import Link from "next/link";
import { PageContainer } from "@/components/layout/PageContainer";
import { usePageHeaderOverride } from "@/components/layout/PageHeaderContext";
import { EmptyState } from "@/components/ui/EmptyState";
import { SectionCard } from "@/components/ui/SectionCard";
import { StatusBadge } from "@/components/ui/StatusBadge";
import {
  AnalyticsIcon,
  ArrowRightIcon,
  ContactIcon,
  OperationIcon,
  PlayIcon,
  PlusIcon,
  SurveyIcon,
} from "@/components/ui/Icons";
import { useAuth } from "@/lib/auth";
import {
  fetchCompanyOperations,
  fetchOperationAnalytics,
  fetchOperationCallJobsPage,
  fetchOperationContactSummary,
} from "@/lib/operations";
import { fetchCompanySurveys } from "@/lib/surveys";
import { ResponseTrendChart } from "@/components/charts/DashboardCharts";
import type { CallJob, Operation, OperationAnalytics, OperationContact, Survey } from "@/lib/types";

// Latest call jobs read per operation for the activity list.
const RECENT_JOBS_PER_OPERATION = 5;
const ACTIVITY_ROW_LIMIT = 6;

type ContactStatusTotals = Partial<Record<OperationContact["status"], number>>;

type DashboardCallJob = CallJob & {
  operationName: string;
};

type QuickLink = {
  title: string;
  href: string;
  icon: ReactNode;
};

type DashboardTask = {
  id: string;
  title: string;
  area: string;
  priority: "Yüksek" | "Orta" | "Düşük";
  createdAt: string;
  actionLabel: string;
  href: string;
};

type DashboardActivityRow = {
  id: string;
  timestamp: number;
  event: string;
  area: string;
  result: string;
  href: string;
};

export default function DashboardPage() {
  const { currentUser } = useAuth();
  const [surveys, setSurveys] = useState<Survey[]>([]);
  const [operations, setOperations] = useState<Operation[]>([]);
  const [contactStatusTotals, setContactStatusTotals] = useState<ContactStatusTotals>({});
  const [analytics, setAnalytics] = useState<OperationAnalytics[]>([]);
  const [recentCallJobs, setRecentCallJobs] = useState<DashboardCallJob[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [unavailableSections, setUnavailableSections] = useState<string[]>([]);

  const welcomeName = currentUser?.user.fullName ?? "Kullanıcı";

  usePageHeaderOverride({
    title: `Hoş geldiniz, ${welcomeName}`,
    subtitle: "Araştırma operasyonlarını buradan yönetebilir ve takip edebilirsiniz.",
  });

  useEffect(() => {
    let isMounted = true;

    async function loadDashboard() {
      try {
        setIsLoading(true);
        setErrorMessage(null);

        const [surveysResult, operationsResult] = await Promise.allSettled([fetchCompanySurveys(), fetchCompanyOperations()]);

        if (!isMounted) {
          return;
        }

        const nextSurveys = surveysResult.status === "fulfilled" ? surveysResult.value : [];
        const nextOperations = operationsResult.status === "fulfilled" ? operationsResult.value : [];

        setSurveys(nextSurveys);
        setOperations(nextOperations);

        // Per operation: contact status counts (not the contact list), analytics and the latest call jobs.
        const [summaryResults, analyticsResults, callJobResults] = nextOperations.length
          ? await Promise.all([
              Promise.allSettled(nextOperations.map((operation) => fetchOperationContactSummary(operation.id, { latestLimit: 0 }))),
              Promise.allSettled(nextOperations.map((operation) => fetchOperationAnalytics(operation.id))),
              Promise.allSettled(
                nextOperations.map(async (operation) => {
                  const page = await fetchOperationCallJobsPage(operation.id, {
                    size: RECENT_JOBS_PER_OPERATION,
                    sortBy: "updatedAt",
                    direction: "desc",
                  });
                  return page.items.map((job): DashboardCallJob => ({ ...job, operationName: operation.name }));
                }),
              ),
            ])
          : [[], [], []];

        if (!isMounted) {
          return;
        }

        const nextTotals: ContactStatusTotals = {};
        for (const result of summaryResults) {
          if (result.status !== "fulfilled") {
            continue;
          }
          for (const item of result.value.statusCounts) {
            nextTotals[item.status] = (nextTotals[item.status] ?? 0) + item.count;
          }
        }

        setContactStatusTotals(nextTotals);
        setAnalytics(analyticsResults.flatMap((result) => (result.status === "fulfilled" ? [result.value] : [])));
        setRecentCallJobs(callJobResults.flatMap((result) => (result.status === "fulfilled" ? result.value : [])));

        setUnavailableSections(
          [
            surveysResult.status === "rejected" ? "Anket akışı" : null,
            operationsResult.status === "rejected" ? "Operasyon akışı" : null,
            summaryResults.some((result) => result.status === "rejected") ? "Kişi özetleri" : null,
            analyticsResults.some((result) => result.status === "rejected") ? "Analitik özeti" : null,
            callJobResults.some((result) => result.status === "rejected") ? "Son aramalar" : null,
          ].filter((value): value is string => value !== null),
        );
      } catch (error) {
        if (!isMounted) {
          return;
        }

        setErrorMessage(error instanceof Error ? error.message : "Ana sayfa yüklenemedi.");
      } finally {
        if (isMounted) {
          setIsLoading(false);
        }
      }
    }

    void loadDashboard();

    return () => {
      isMounted = false;
    };
  }, []);

  const completion = useMemo(() => buildOperationCompletion(operations), [operations]);
  const callResults = useMemo(() => buildCallResultSummary(contactStatusTotals), [contactStatusTotals]);
  const responsePerformance = useMemo(() => buildResponsePerformance(analytics), [analytics]);
  const callQueue = useMemo(() => buildCallQueue(analytics), [analytics]);
  const tasks = useMemo(() => buildDashboardTasks(operations, surveys), [operations, surveys]);
  const activityRows = useMemo(() => buildDashboardActivityRows(operations, recentCallJobs), [operations, recentCallJobs]);

  const featuredQuickLinks: QuickLink[] = [
    { title: "Anketler", href: "/surveys", icon: <SurveyIcon className="nav-icon" /> },
    { title: "Yeni Operasyon", href: "/operations/new", icon: <PlayIcon className="nav-icon" /> },
  ];

  const compactQuickLinks: QuickLink[] = [
    { title: "Anketler", href: "/surveys", icon: <SurveyIcon className="nav-icon" /> },
    { title: "Operasyonlar", href: "/operations", icon: <OperationIcon className="nav-icon" /> },
    { title: "Kişiler", href: "/contacts", icon: <ContactIcon className="nav-icon" /> },
    { title: "Arama Operasyonları", href: "/calling-ops", icon: <PlayIcon className="nav-icon" /> },
    { title: "Analitik", href: "/analytics", icon: <AnalyticsIcon className="nav-icon" /> },
  ];

  return (
    <PageContainer hideBackRow>
      <section className="ops-dashboard-primary-actions">
        <Link href="/surveys/new" className="ops-dashboard-action is-primary">
          <PlusIcon className="nav-icon" />
          <span>Yeni Anket</span>
        </Link>
        <Link href="/operations/new" className="ops-dashboard-action">
          <PlayIcon className="nav-icon" />
          <span>Yeni Operasyon</span>
        </Link>
      </section>

      {errorMessage ? <EmptyState title="Ana sayfa yüklenemedi" description={errorMessage} tone="danger" /> : null}
      {unavailableSections.length > 0 ? (
        <EmptyState
          title="Bazı veri akışları eksik"
          description={`${unavailableSections.join(", ")} şu anda tam olarak alınmadı. Yüklenebilen bölümler gösterilmeye devam ediyor.`}
          tone="warning"
        />
      ) : null}

      <section className="ops-kpi-grid ops-kpi-grid-dashboard">
        <section className="panel-card ops-dashboard-kpi-card">
          <div className="ops-dashboard-kpi-head">
            <h2>Operasyon Tamamlanma Durumu</h2>
          </div>
          <div className="ops-dashboard-donut-layout">
            <div
              className="ops-dashboard-donut"
              style={{
                background: `conic-gradient(
                  #66a99c 0 ${completion.completedPercent}%,
                  #4e8fb2 ${completion.completedPercent}% ${completion.activePercent}%,
                  #d98b5f ${completion.activePercent}% ${completion.pausedPercent}%,
                  #b9d7d1 ${completion.pausedPercent}% ${completion.preparedPercent}%,
                  #edf2f0 ${completion.preparedPercent}% 100%
                )`,
              }}
            >
              <div className="ops-dashboard-donut-center">
                <strong>%{completion.completedDisplay}</strong>
                <span>Tamamlandı</span>
              </div>
            </div>

            <div className="ops-dashboard-kpi-list">
              <div className="ops-dashboard-kpi-list-item">
                <span className="ops-dot is-total" />
                <div>
                  <small>Toplam Operasyon</small>
                  <strong>{completion.total}</strong>
                </div>
              </div>
              <div className="ops-dashboard-kpi-list-item">
                <span className="ops-dot is-completed" />
                <div>
                  <small>Tamamlanan</small>
                  <strong>{completion.completed}</strong>
                </div>
              </div>
              <div className="ops-dashboard-kpi-list-item">
                <span className="ops-dot is-active" />
                <div>
                  <small>Devam Eden</small>
                  <strong>{completion.active}</strong>
                </div>
              </div>
              {completion.paused > 0 ? (
                <div className="ops-dashboard-kpi-list-item">
                  <span className="ops-dot is-paused" />
                  <div>
                    <small>Duraklatılan</small>
                    <strong>{completion.paused}</strong>
                  </div>
                </div>
              ) : null}
              <div className="ops-dashboard-kpi-list-item">
                <span className="ops-dot is-prepared" />
                <div>
                  <small>Hazırlıkta</small>
                  <strong>{completion.prepared}</strong>
                </div>
              </div>
            </div>
          </div>
        </section>

        <section className="panel-card ops-dashboard-kpi-card">
          <div className="ops-dashboard-kpi-head">
            <h2>Arama Sonuçları</h2>
          </div>
          {callResults.hasData ? (
            <>
              <div className="ops-dashboard-result-top">
                <div>
                  <strong>{callResults.success}</strong>
                  <span>Tamamlanan görüşme</span>
                </div>
                <div className="ops-dashboard-chip">%{callResults.successPercent} tamamlandı</div>
              </div>
              <div className="ops-dashboard-result-bars">
                {callResults.items.map((item) => (
                  <div key={item.label} className="ops-dashboard-result-bar-item">
                    <div className={`ops-dashboard-result-bar is-${item.tone}`}>
                      <span style={{ height: `${item.value > 0 ? Math.max(item.height, 18) : 0}%` }} />
                    </div>
                    <strong>{item.value}</strong>
                    <small>{item.label}</small>
                  </div>
                ))}
              </div>
            </>
          ) : (
            <div className="ops-dashboard-kpi-empty">
              <strong>Arama sonucu yok</strong>
              <span>Sonuçlar geldikçe başarılı, bekleyen ve tekrar aramalar burada dağılacak.</span>
            </div>
          )}
        </section>

        <section className="panel-card ops-dashboard-kpi-card">
          <div className="ops-dashboard-kpi-head">
            <h2>Yanıt Performansı</h2>
          </div>
          {responsePerformance.hasData ? (
            <>
              <p className="ops-dashboard-kpi-caption">
                Genel yanıt oranı %{responsePerformance.responseRate} ({responsePerformance.respondedContacts} kişi)
              </p>
              <ResponseTrendChart
                points={responsePerformance.points}
                totalLabel={`${responsePerformance.lastWeekTotal} yanıt`}
              />
            </>
          ) : (
            <div className="ops-dashboard-kpi-empty">
              <strong>Yanıt performansı hazır değil</strong>
              <span>Yanıt verisi oluştuğunda günlük trend burada çizilecek.</span>
            </div>
          )}
        </section>

        <section className="panel-card ops-dashboard-kpi-card">
          <div className="ops-dashboard-kpi-head">
            <h2>Çağrı Durumu</h2>
          </div>
          {callQueue.hasData ? (
            <>
              <div className="ops-dashboard-result-top">
                <div>
                  <strong>{callQueue.attempts}</strong>
                  <span>Çağrı denemesi</span>
                </div>
              </div>
              <div className="ops-dashboard-kpi-list">
                <div className="ops-dashboard-kpi-list-item">
                  <span className="ops-dot is-prepared" />
                  <div>
                    <small>Kuyrukta</small>
                    <strong>{callQueue.queued}</strong>
                  </div>
                </div>
                <div className="ops-dashboard-kpi-list-item">
                  <span className="ops-dot is-active" />
                  <div>
                    <small>Görüşmede</small>
                    <strong>{callQueue.inProgress}</strong>
                  </div>
                </div>
                <div className="ops-dashboard-kpi-list-item">
                  <span className="ops-dot is-completed" />
                  <div>
                    <small>Tamamlanan</small>
                    <strong>{callQueue.completed}</strong>
                  </div>
                </div>
                <div className="ops-dashboard-kpi-list-item">
                  <span className="ops-dot is-failed" />
                  <div>
                    <small>Başarısız</small>
                    <strong>{callQueue.failed}</strong>
                  </div>
                </div>
              </div>
            </>
          ) : (
            <div className="ops-dashboard-kpi-empty">
              <strong>Henüz çağrı işi yok</strong>
              <span>Bir operasyon başladığında kuyruk, süren ve biten aramalar burada görünür.</span>
            </div>
          )}
        </section>
      </section>

      <div className="ops-dashboard-reference-grid">
        <div className="ops-dashboard-reference-main">
          <SectionCard
            title="Devam Eden İşler"
            description="Öncelik verilmesi gereken işler ve operatör aksiyonları."
            action={
              <Link href="/operations" className="ops-inline-link">
                Tümünü görüntüle
                <ArrowRightIcon className="nav-icon" />
              </Link>
            }
          >
            {isLoading ? (
              <EmptyState title="Görev listesi hazırlanıyor" description="Operasyonlardan türetilen öncelikli işler getiriliyor." />
            ) : tasks.length === 0 ? (
              <EmptyState title="Bekleyen görev yok" description="Kritik aksiyon gerektiren iş bulunmuyor." />
            ) : (
              <div className="ops-dashboard-task-table">
                <div className="ops-dashboard-task-head">
                  <span>Görev</span>
                  <span>İlgili Alan</span>
                  <span>Öncelik</span>
                  <span>Oluşturulma</span>
                  <span>Aksiyon</span>
                </div>
                {tasks.map((task) => (
                  <div key={task.id} className="ops-dashboard-task-row">
                    <div className="ops-dashboard-task-main">
                      <strong>{task.title}</strong>
                    </div>
                    <span className="ops-dashboard-muted">{task.area}</span>
                    <StatusBadge status={task.priority === "Yüksek" ? "Warning" : task.priority === "Orta" ? "Pending" : "Ready"} label={task.priority} />
                    <span className="ops-dashboard-muted">{task.createdAt}</span>
                    <Link href={task.href} className="button-secondary compact-button">
                      {task.actionLabel}
                    </Link>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <section className="panel-card ops-dashboard-shortcuts">
            <div className="section-header">
              <div className="section-copy">
                <h2>Hızlı Geçiş</h2>
                <p>Sık kullandığınız alanlara hızlı erişim.</p>
              </div>
            </div>
            <div className="ops-dashboard-shortcuts-featured">
              {featuredQuickLinks.map((item) => (
                <Link key={item.href} href={item.href} className="ops-dashboard-shortcut-featured">
                  {item.icon}
                  <span>{item.title}</span>
                </Link>
              ))}
            </div>
            <div className="ops-dashboard-shortcuts-compact">
              {compactQuickLinks.map((item) => (
                <Link key={item.href} href={item.href} className="ops-dashboard-shortcut-compact">
                  {item.icon}
                  <span>{item.title}</span>
                </Link>
              ))}
            </div>
          </section>
        </div>

        <div className="ops-dashboard-reference-side">
          <SectionCard
            title="Son Aktiviteler"
            description="Son aramalar ve operasyon hareketleri"
            action={<Link href="/operations" className="button-secondary compact-button">Tümünü gör</Link>}
          >
            {isLoading ? (
              <EmptyState title="Aktiviteler yükleniyor" description="Son aramalar getiriliyor." />
            ) : activityRows.length === 0 ? (
              <EmptyState title="Aktivite bulunmuyor" description="Aramalar başladığında sonuçları burada listelenecek." />
            ) : (
              <div className="ops-dashboard-activity-list">
                {activityRows.map((row) => {
                  const time = formatActivityTime(row.timestamp);
                  return (
                    <Link key={row.id} href={row.href} className="ops-dashboard-activity-row">
                      <span className="ops-dashboard-activity-time">
                        <small>{time.day}</small>
                        {time.clock}
                      </span>
                      <div className="ops-dashboard-activity-copy">
                        <strong>
                          {row.event} · {row.area}
                        </strong>
                        <p>{row.result}</p>
                      </div>
                      <ArrowRightIcon className="nav-icon ops-dashboard-activity-arrow" />
                    </Link>
                  );
                })}
              </div>
            )}
          </SectionCard>
        </div>
      </div>
    </PageContainer>
  );
}

function buildOperationCompletion(operations: Operation[]) {
  const total = Math.max(operations.length, 1);
  const completed = operations.filter((operation) => operation.status === "Completed").length;
  const active = operations.filter((operation) => operation.status === "Running" || operation.status === "Ready").length;
  const paused = operations.filter((operation) => operation.status === "Paused").length;
  const prepared = operations.filter((operation) => operation.status === "Draft" || operation.status === "Scheduled").length;

  return {
    total: operations.length,
    completed,
    active,
    paused,
    prepared,
    completedDisplay: Math.round((completed / total) * 100),
    completedPercent: (completed / total) * 100,
    activePercent: ((completed + active) / total) * 100,
    pausedPercent: ((completed + active + paused) / total) * 100,
    preparedPercent: ((completed + active + paused + prepared) / total) * 100,
  };
}

function buildCallResultSummary(totals: ContactStatusTotals) {
  const success = totals.Completed ?? 0;
  const rejected = totals.Failed ?? 0;
  const pending = totals.Pending ?? 0;
  const retry = totals.Retry ?? 0;
  const invalid = totals.Invalid ?? 0;
  const allContacts = Object.values(totals).reduce((sum, count) => sum + (count ?? 0), 0);
  const max = Math.max(success, rejected, pending, retry, invalid, 1);

  return {
    hasData: allContacts > 0,
    success,
    successPercent: allContacts > 0 ? Math.round((success / allContacts) * 100) : 0,
    items: [
      { label: "Başarılı", value: success, tone: "success", height: (success / max) * 100 },
      { label: "Başarısız", value: rejected, tone: "danger", height: (rejected / max) * 100 },
      { label: "Bekleyen", value: pending, tone: "warning", height: (pending / max) * 100 },
      { label: "Tekrar", value: retry, tone: "warning", height: (retry / max) * 100 },
      { label: "Geçersiz", value: invalid, tone: "info", height: (invalid / max) * 100 },
    ],
  };
}

function buildResponsePerformance(analytics: OperationAnalytics[], now = new Date()) {
  const respondedContacts = analytics.reduce((sum, item) => sum + item.respondedContacts, 0);
  const totalContacts = analytics.reduce((sum, item) => sum + item.totalContacts, 0);

  const countsByDay = new Map<string, number>();
  for (const item of analytics) {
    for (const point of item.responseTrend) {
      countsByDay.set(point.date, (countsByDay.get(point.date) ?? 0) + point.count);
    }
  }

  const weekdayFormat = new Intl.DateTimeFormat("tr-TR", { weekday: "short" });
  const points = Array.from({ length: 7 }, (_, index) => {
    const day = new Date(now.getFullYear(), now.getMonth(), now.getDate() - (6 - index));
    return { label: weekdayFormat.format(day), value: countsByDay.get(toIsoDate(day)) ?? 0 };
  });

  return {
    hasData: totalContacts > 0,
    // Weighted by contacts, so a one-contact test operation does not count as much as a full field study.
    responseRate: totalContacts > 0 ? Math.round((respondedContacts / totalContacts) * 100) : 0,
    respondedContacts,
    lastWeekTotal: points.reduce((sum, point) => sum + point.value, 0),
    points,
  };
}

function buildCallQueue(analytics: OperationAnalytics[]) {
  const sum = (pick: (item: OperationAnalytics) => number) => analytics.reduce((total, item) => total + pick(item), 0);

  return {
    hasData: sum((item) => item.totalCallJobs) > 0,
    attempts: sum((item) => item.totalCallAttempts),
    queued: sum((item) => item.queuedJobs),
    inProgress: sum((item) => item.inProgressJobs),
    completed: sum((item) => item.completedCallJobs),
    failed: sum((item) => item.failedCallJobs),
  };
}

function buildDashboardTasks(operations: Operation[], surveys: Survey[]): DashboardTask[] {
  // Paused, completed and cancelled operations also report a blocking reason for their state; only setup blockers need fixing.
  const blockedOperation = operations.find(
    (operation) =>
      (operation.status === "Draft" || operation.status === "Ready" || operation.status === "Scheduled") &&
      operation.readiness.blockingReasons.length > 0,
  );
  const readyOperation = operations.find((operation) => operation.status === "Ready" && operation.readiness.readyToStart);
  const draftSurvey = surveys.find((survey) => survey.status === "Draft");
  const failedOperation = operations.find((operation) => operation.status === "Failed");
  const pausedOperation = operations.find((operation) => operation.status === "Paused");

  return [
    failedOperation
      ? {
          id: `failed-${failedOperation.id}`,
          title: `${failedOperation.name} başarısız oldu, nedenini inceleyin`,
          area: "Operasyonlar",
          priority: "Yüksek",
          createdAt: failedOperation.updatedAt,
          actionLabel: "İncele",
          href: `/operations/${failedOperation.id}`,
        }
      : null,
    blockedOperation
      ? {
          id: `blocked-${blockedOperation.id}`,
          title: `${blockedOperation.name} operasyonundaki blokajı gider`,
          area: "Operasyonlar",
          priority: "Yüksek",
          createdAt: blockedOperation.updatedAt,
          actionLabel: "Düzelt",
          href: `/operations/${blockedOperation.id}`,
        }
      : null,
    pausedOperation
      ? {
          id: `paused-${pausedOperation.id}`,
          title: `${pausedOperation.name} duraklatıldı, hazır olduğunuzda devam ettirin`,
          area: "Arama Operasyonları",
          priority: "Orta",
          createdAt: pausedOperation.updatedAt,
          actionLabel: "Aç",
          href: `/operations/${pausedOperation.id}`,
        }
      : null,
    readyOperation
      ? {
          id: `ready-${readyOperation.id}`,
          title: `${readyOperation.name} başlatılmaya hazır`,
          area: "Arama Operasyonları",
          priority: "Orta",
          createdAt: readyOperation.updatedAt,
          actionLabel: "Aç",
          href: `/operations/${readyOperation.id}`,
        }
      : null,
    draftSurvey
      ? {
          id: `draft-${draftSurvey.id}`,
          title: `${draftSurvey.name} anketinde yayın kontrolü yap`,
          area: "Anketler",
          priority: "Düşük",
          createdAt: draftSurvey.updatedAt,
          actionLabel: "İncele",
          href: `/surveys/${draftSurvey.id}`,
        }
      : null,
  ]
    .filter((item): item is DashboardTask => item !== null)
    .slice(0, 3);
}

function describeCallJob(job: CallJob): string {
  switch (job.status) {
    case "Completed":
      return "Görüşme tamamlandı";
    case "Failed":
      return "Arama başarısız";
    case "InProgress":
      return "Görüşme sürüyor";
    case "Skipped":
      return "Arama iptal edildi";
    default:
      return job.rawStatus === "RETRY" ? "Tekrar aranacak" : "Aranıyor";
  }
}

function buildDashboardActivityRows(operations: Operation[], callJobs: DashboardCallJob[]): DashboardActivityRow[] {
  // Jobs that were never dialed are queue entries, not activity.
  const callRows = callJobs
    .filter((job) => job.attemptCount > 0)
    .map((job) => ({
      id: `job-${job.id}`,
      timestamp: Date.parse(job.updatedAtIso),
      event: describeCallJob(job),
      area: job.operationName,
      result: job.lastResultSummary ? `${job.personName} · ${job.lastResultSummary}` : job.personName,
      href: `/operations/${job.operationId}/jobs/${job.id}`,
    }));

  const operationRows = operations.flatMap((operation) => [
    ...(operation.startedAt
      ? [
          {
            id: `started-${operation.id}`,
            timestamp: Date.parse(operation.startedAt),
            event: "Operasyon başlatıldı",
            area: operation.name,
            result: operation.survey,
            href: `/operations/${operation.id}`,
          },
        ]
      : []),
    ...(operation.completedAt
      ? [
          {
            id: `completed-${operation.id}`,
            timestamp: Date.parse(operation.completedAt),
            event: "Operasyon tamamlandı",
            area: operation.name,
            result: operation.survey,
            href: `/operations/${operation.id}`,
          },
        ]
      : []),
  ]);

  return [...callRows, ...operationRows]
    .filter((row) => !Number.isNaN(row.timestamp))
    .sort((left, right) => right.timestamp - left.timestamp)
    .slice(0, ACTIVITY_ROW_LIMIT);
}

function formatActivityTime(timestamp: number, now = new Date()) {
  const date = new Date(timestamp);
  const clock = date.toLocaleTimeString("tr-TR", { hour: "2-digit", minute: "2-digit" });
  const startOfDay = (value: Date) => new Date(value.getFullYear(), value.getMonth(), value.getDate()).getTime();
  const dayDifference = Math.round((startOfDay(now) - startOfDay(date)) / 86_400_000);

  if (dayDifference === 0) {
    return { day: "Bugün", clock };
  }
  if (dayDifference === 1) {
    return { day: "Dün", clock };
  }
  return { day: date.toLocaleDateString("tr-TR", { day: "numeric", month: "short" }), clock };
}

function toIsoDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}`;
}
