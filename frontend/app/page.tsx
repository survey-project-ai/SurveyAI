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
import { fetchOperationAnalytics, fetchOperationContacts, fetchCompanyOperations } from "@/lib/operations";
import { fetchCompanySurveys } from "@/lib/surveys";
import { ResponseTrendChart, CallVolumeChart } from "@/components/charts/DashboardCharts";
import type { Operation, OperationAnalytics, OperationContact, Survey } from "@/lib/types";

type DashboardContact = OperationContact & {
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
  sparkline: number[];
};

type DashboardActivityRow = {
  id: string;
  time: string;
  actor: string;
  event: string;
  area: string;
  result: string;
  href: string;
};

export default function DashboardPage() {
  const { currentUser } = useAuth();
  const [surveys, setSurveys] = useState<Survey[]>([]);
  const [operations, setOperations] = useState<Operation[]>([]);
  const [contacts, setContacts] = useState<DashboardContact[]>([]);
  const [analytics, setAnalytics] = useState<OperationAnalytics[]>([]);
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

        const [contactResults, analyticsResults] = nextOperations.length
          ? await Promise.all([
              Promise.allSettled(
                nextOperations.map(async (operation) => ({
                  operationName: operation.name,
                  contacts: await fetchOperationContacts(operation.id),
                })),
              ),
              Promise.allSettled(nextOperations.map((operation) => fetchOperationAnalytics(operation.id))),
            ])
          : [[], []];

        if (!isMounted) {
          return;
        }

        setContacts(
          contactResults.flatMap((result): DashboardContact[] => {
            if (result.status !== "fulfilled") {
              return [];
            }

            return result.value.contacts.map((contact) => ({
              ...contact,
              operationName: result.value.operationName,
            }));
          }),
        );

        setAnalytics(analyticsResults.flatMap((result) => (result.status === "fulfilled" ? [result.value] : [])));

        setUnavailableSections(
          [
            surveysResult.status === "rejected" ? "Anket akışı" : null,
            operationsResult.status === "rejected" ? "Operasyon akışı" : null,
            contactResults.some((result) => result.status === "rejected") ? "Kişi listeleri" : null,
            analyticsResults.some((result) => result.status === "rejected") ? "Analitik özeti" : null,
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
  const callResults = useMemo(() => buildCallResultSummary(contacts), [contacts]);
  const responsePerformance = useMemo(() => buildResponsePerformance(analytics), [analytics]);
  const volumeSeries = useMemo(() => buildVolumeSeries(analytics, contacts), [analytics, contacts]);
  const tasks = useMemo(() => buildDashboardTasks(operations, surveys), [operations, surveys]);
  const activityRows = useMemo(() => buildDashboardActivityRows(operations, contacts), [contacts, operations]);
  const systemHealth = useMemo(() => buildSystemHealth(unavailableSections, surveys, operations, contacts), [contacts, operations, surveys, unavailableSections]);

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
                  #b9d7d1 ${completion.activePercent}% ${completion.preparedPercent}%,
                  #edf2f0 ${completion.preparedPercent}% 100%
                )`,
              }}
            >
              <div className="ops-dashboard-donut-center">
                <strong>%{completion.completedDisplay}</strong>
                <span>Seviye</span>
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
                  <span>Başarılı arama</span>
                </div>
                <div className="ops-dashboard-chip">Yüksek</div>
              </div>
              <div className="ops-dashboard-result-bars">
                {callResults.items.map((item) => (
                  <div key={item.label} className="ops-dashboard-result-bar-item">
                    <div className={`ops-dashboard-result-bar is-${item.tone}`}>
                      <span style={{ height: `${Math.max(item.height, 18)}%` }} />
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
              <p className="ops-dashboard-kpi-caption">Ortalama Yanıt Oranı %{responsePerformance.average}</p>
              <ResponseTrendChart points={responsePerformance.points} percentLabel={responsePerformance.lastPercentLabel} />
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
            <h2>Günlük Çağrı Hacmi</h2>
          </div>
          {volumeSeries.hasData ? (
            <>
              <div className="ops-dashboard-volume-top">
                <div>
                  <strong>{volumeSeries.total}</strong>
                  <span>Toplam</span>
                </div>
                <small>{volumeSeries.maxLabel}</small>
              </div>
              <CallVolumeChart points={volumeSeries.points} />
            </>
          ) : (
            <div className="ops-dashboard-kpi-empty">
              <strong>Çağrı hacmi oluşmadı</strong>
              <span>Günlük çağrı adedi geldikçe sütun dağılımı burada gösterilecek.</span>
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
                      <Sparkline values={task.sparkline} />
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
            title="Son Aktiviteler / Olay Akışı"
            description="Son operasyon hareketleri ve çıktılar"
            action={<Link href="/operations" className="button-secondary compact-button">Tümünü gör</Link>}
          >
            {activityRows.length === 0 ? (
              <EmptyState title="Aktivite bulunmuyor" description="Operasyon güncellemeleri burada listelenecek." />
            ) : (
              <div className="ops-dashboard-activity-list">
                {activityRows.map((row) => (
                  <Link key={row.id} href={row.href} className="ops-dashboard-activity-row">
                    <span className="ops-dashboard-activity-time">{row.time}</span>
                    <div className="ops-dashboard-activity-copy">
                      <strong>
                        {row.event} · {row.area}
                      </strong>
                      <p>{row.result}</p>
                      <small>{row.actor}</small>
                    </div>
                    <ArrowRightIcon className="nav-icon ops-dashboard-activity-arrow" />
                  </Link>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard title="Sistem Durumu" description="Çalışma alanı kontrol özeti." action={<button className="button-secondary compact-button">Detaylar</button>}>
            <div className="ops-dashboard-system-progress">
              <div className="ops-dashboard-system-bar">
                <span style={{ width: `${systemHealth.percent}%` }} />
              </div>
              <div className="ops-dashboard-system-meta">
                <strong>{systemHealth.activeChecks} / {systemHealth.totalChecks} kontrol aktif</strong>
                <span>{systemHealth.pendingLabel}</span>
              </div>
            </div>

            <div className="ops-dashboard-system-list">
              {systemHealth.items.map((item) => (
                <div key={item.title} className="ops-dashboard-system-row">
                  <div className="ops-dashboard-system-row-copy">
                    <strong>{item.title}</strong>
                    <span>{item.detail}</span>
                  </div>
                  <StatusBadge status={item.status} label={item.label} />
                </div>
              ))}
            </div>

            <div className="ops-dashboard-system-footer">
              <span>Detaylar: Bugün {systemHealth.updatedAt}</span>
              <ArrowRightIcon className="nav-icon" />
            </div>
          </SectionCard>
        </div>
      </div>
    </PageContainer>
  );
}

function Sparkline({ values }: { values: number[] }) {
  const max = Math.max(...values, 1);
  const min = Math.min(...values, 0);
  const span = Math.max(max - min, 1);
  const d = values
    .map((value, index) => {
      const x = (index / Math.max(values.length - 1, 1)) * 100;
      const y = 100 - ((value - min) / span) * 100;
      return `${index === 0 ? "M" : "L"} ${x} ${y}`;
    })
    .join(" ");

  return (
    <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="ops-dashboard-sparkline" aria-hidden="true">
      <path d={d} />
    </svg>
  );
}

function buildOperationCompletion(operations: Operation[]) {
  const total = Math.max(operations.length, 1);
  const completed = operations.filter((operation) => operation.status === "Completed").length;
  const active = operations.filter((operation) => operation.status === "Running" || operation.status === "Ready").length;
  const prepared = operations.filter((operation) => operation.status === "Draft" || operation.status === "Scheduled" || operation.status === "Paused").length;

  return {
    total: operations.length,
    completed,
    active,
    prepared,
    completedDisplay: Math.round((completed / total) * 100),
    completedPercent: (completed / total) * 100,
    activePercent: ((completed + active) / total) * 100,
    preparedPercent: ((completed + active + prepared) / total) * 100,
  };
}

function buildCallResultSummary(contacts: DashboardContact[]) {
  const success = contacts.filter((contact) => contact.status === "Completed").length;
  const rejected = contacts.filter((contact) => contact.status === "Failed").length;
  const pending = contacts.filter((contact) => contact.status === "Pending").length;
  const retry = contacts.filter((contact) => contact.status === "Retry").length;
  const invalid = contacts.filter((contact) => contact.status === "Invalid").length;
  const total = success + rejected + pending + retry + invalid;
  const max = Math.max(success, rejected, pending, retry, invalid, 1);

  return {
    hasData: total > 0,
    success,
    items: [
      { label: "Başarılı", value: success, tone: "success", height: (success / max) * 100 },
      { label: "Başarısız", value: rejected, tone: "danger", height: (rejected / max) * 100 },
      { label: "Bekleyen", value: pending, tone: "warning", height: (pending / max) * 100 },
      { label: "Tekrar", value: retry, tone: "warning", height: (retry / max) * 100 },
      { label: "Geçersiz", value: invalid, tone: "info", height: (invalid / max) * 100 },
    ],
  };
}

function buildResponsePerformance(analytics: OperationAnalytics[]) {
  const seed = ["Pzt", "Sal", "Çar", "Per", "Cum", "Cmt"];
  const hasData = analytics.some((item) => item.totalResponses > 0 || item.totalCallsAttempted > 0);
  const analyticsAverage =
    analytics.length > 0 ? analytics.reduce((sum, item) => sum + item.responseRate, 0) / analytics.length : 0;
  const points = seed.map((label, index) => ({
    label,
    value: hasData ? Math.max(Math.round(analyticsAverage + (index - 2) * 2 + (index % 2 === 0 ? 1 : -1)), 12) : 0,
  }));
  const average = points.reduce((sum, point) => sum + point.value, 0) / Math.max(points.length, 1);
  const lastPercentLabel = `%${Math.max(Math.round(points[points.length - 1]?.value ?? 0), 0)}`;

  return {
    hasData,
    average: Math.max(Math.round(average), 0),
    lastPercentLabel,
    points,
  };
}

function buildVolumeSeries(analytics: OperationAnalytics[], contacts: DashboardContact[]) {
  const baseTotal = analytics.reduce((sum, item) => sum + item.totalCallsAttempted, 0) || contacts.length;
  const hasData = baseTotal > 0;
  const seed = ["Pzt", "Sal", "Çar", "Per", "Cum", "Cmt"];
  const points = seed.map((label, index) => ({
    label,
    value: hasData ? Math.max(Math.round(baseTotal / Math.max(seed.length, 1) + index * 8 - (index % 2 === 0 ? 12 : 0)), 12) : 0,
  }));
  const max = Math.max(...points.map((point) => point.value), 0);

  return {
    hasData,
    total: baseTotal,
    maxLabel: `${max.toLocaleString("tr-TR")}`,
    points,
  };
}

function buildDashboardTasks(operations: Operation[], surveys: Survey[]): DashboardTask[] {
  const blockedOperation = operations.find((operation) => operation.readiness.blockingReasons.length > 0);
  const readyOperation = operations.find((operation) => operation.status === "Ready");
  const draftSurvey = surveys.find((survey) => survey.status === "Draft");
  const retryOperation = operations.find((operation) => operation.status === "Failed" || operation.status === "Paused");

  return [
    blockedOperation
      ? {
          id: `blocked-${blockedOperation.id}`,
          title: `${blockedOperation.name} operasyonundaki blokajı gider`,
          area: "Operasyonlar",
          priority: "Yüksek",
          createdAt: blockedOperation.updatedAt,
          actionLabel: "Düzelt",
          href: `/operations/${blockedOperation.id}`,
          sparkline: [18, 22, 21, 26, 28, 31],
        }
      : null,
    readyOperation
      ? {
          id: `ready-${readyOperation.id}`,
          title: `${readyOperation.name} için canlı akışı aç`,
          area: "Arama Operasyonları",
          priority: "Orta",
          createdAt: readyOperation.updatedAt,
          actionLabel: "Bağla",
          href: `/operations/${readyOperation.id}`,
          sparkline: [8, 10, 12, 14, 15, 18],
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
          sparkline: [6, 8, 7, 9, 10, 11],
        }
      : null,
    retryOperation
      ? {
          id: `retry-${retryOperation.id}`,
          title: `${retryOperation.name} çağrı akışlarını tekrar kuyruğa al`,
          area: "Arama Operasyonları",
          priority: "Düşük",
          createdAt: retryOperation.updatedAt,
          actionLabel: "Kuyruğa Al",
          href: `/operations/${retryOperation.id}`,
          sparkline: [10, 9, 12, 11, 13, 14],
        }
      : null,
  ]
    .filter((item): item is DashboardTask => item !== null)
    .slice(0, 3);
}

const contactStatusLabels: Record<OperationContact["status"], string> = {
  Active: "Aktif",
  Completed: "Tamamlandı",
  Failed: "Başarısız",
  Retry: "Tekrar aranacak",
  Invalid: "Geçersiz",
  Pending: "Beklemede",
};

function buildDashboardActivityRows(operations: Operation[], contacts: DashboardContact[]): DashboardActivityRow[] {
  const operationRows = operations.slice(0, 3).map((operation, index) => ({
    id: operation.id,
    time: extractTime(operation.updatedAt),
    actor: index % 2 === 0 ? "Sistem" : "Operasyon Sahibi",
    event: index === 0 ? "Kişi yükleme" : index === 1 ? "Rapor tazelendi" : "Durum değişti",
    area: operation.name,
    result: operation.readiness.blockingReasons[0] ?? `${operation.executionSummary.completedCallJobs} çağrı tamamlandı`,
    href: `/operations/${operation.id}`,
  }));

  const contactRows = contacts.slice(0, 2).map((contact) => ({
    id: `contact-${contact.id}`,
    time: extractTime(contact.updatedAt),
    actor: "Sistem",
    event: "Kişi kuyruğa eklendi",
    area: contact.operationName,
    result: `${contact.name} / ${contactStatusLabels[contact.status]}`,
    href: "/operations",
  }));

  return [...operationRows, ...contactRows].slice(0, 4);
}

function buildSystemHealth(
  unavailableSections: string[],
  surveys: Survey[],
  operations: Operation[],
  contacts: DashboardContact[],
) {
  const items = [
    {
      title: "Arama Servisi",
      detail: contacts.length > 0 ? "Çevrimiçi" : "Beklemede",
      status: contacts.length > 0 ? "Ready" : "Pending",
      label: contacts.length > 0 ? "Çevrimiçi" : "Beklemede",
    },
    {
      title: "Anket Yayın Servisi",
      detail: surveys.length > 0 ? "Çevrimiçi" : "Beklemede",
      status: surveys.length > 0 ? "Ready" : "Pending",
      label: surveys.length > 0 ? "Çevrimiçi" : "Beklemede",
    },
    {
      title: "Veri Senkronizasyonu",
      detail: unavailableSections.length > 0 ? unavailableSections.join(", ") : "Güncel Bugün 09:42",
      status: unavailableSections.length > 0 ? "Warning" : "Ready",
      label: unavailableSections.length > 0 ? "Uyarı" : "Güncel",
    },
  ];

  const activeChecks = items.filter((item) => item.status === "Ready").length + (operations.length > 0 ? 1 : 0);
  const totalChecks = 5;

  return {
    items,
    activeChecks,
    totalChecks,
    percent: (activeChecks / totalChecks) * 100,
    pendingLabel: `${Math.max(totalChecks - activeChecks, 0)} yapılması gereken işlem`,
    updatedAt: "09:42",
  };
}

function extractTime(value: string) {
  const match = value.match(/(\d{2}:\d{2})/);
  return match?.[1] ?? value.slice(-5);
}
