"use client";

import Link from "next/link";
import { notFound, useParams } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { PageContainer } from "@/components/layout/PageContainer";
import { usePageHeaderOverride } from "@/components/layout/PageHeaderContext";
import { DataTable } from "@/components/ui/DataTable";
import { SectionCard } from "@/components/ui/SectionCard";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { useTranslations } from "@/lib/i18n/LanguageContext";
import { fetchOperationById, fetchOperationCallJobsPage, type CallJobPage } from "@/lib/operations";
import { CallJob, Operation, TableColumn } from "@/lib/types";

const JOB_FILTERS: Array<["All" | CallJob["status"], string]> = [
  ["All", "Tüm durumlar"],
  ["Queued", "Kuyrukta"],
  ["InProgress", "Sürüyor"],
  ["Completed", "Tamamlandı"],
  ["Failed", "Başarısız"],
  ["Skipped", "Atlandı"],
];

const jobColumns: TableColumn<CallJob>[] = [
  {
    key: "person",
    label: "Kişi",
    render: (job) => (
      <Link href={`/operations/${job.operationId ?? ""}/jobs/${job.id}`} className="table-link-block">
        <div className="table-title">{job.personName}</div>
        <div className="table-subtitle">{job.phoneNumber}</div>
      </Link>
    ),
  },
  {
    key: "status",
    label: "Durum",
    render: (job) => (
      <div>
        <StatusBadge status={job.status} />
        <div className="table-subtitle">Ham durum: {job.rawStatus}</div>
      </div>
    ),
  },
  {
    key: "attempts",
    label: "Deneme",
    render: (job) => `${job.attemptCount} / ${job.maxAttempts}`,
  },
  {
    key: "result",
    label: "Özet",
    render: (job) => (
      <div>
        <div className="table-title">
          {buildJobResultTitle(job)}
        </div>
        <div className="table-subtitle">{job.lastErrorMessage ?? job.lastResultSummary ?? "Durum özeti"}</div>
      </div>
    ),
  },
  {
    key: "createdAt",
    label: "Oluşturuldu",
    render: (job) => job.createdAt,
  },
  {
    key: "updatedAt",
    label: "Güncellendi",
    render: (job) => job.updatedAt,
  },
  {
    key: "detail",
    label: "Detay",
    render: (job) => (
      <Link href={`/operations/${job.operationId ?? ""}/jobs/${job.id}`} className="button-secondary compact-button table-action-link">
        İncele
      </Link>
    ),
  },
];

export default function OperationJobsPage() {
  const params = useParams<{ id: string }>();
  const operationId = params.id;
  const { t } = useTranslations();
  const [operation, setOperation] = useState<Operation | null>(null);
  const [jobsPage, setJobsPage] = useState<CallJobPage | null>(null);
  const [page, setPage] = useState(0);
  const [queryInput, setQueryInput] = useState("");
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<"All" | CallJob["status"]>("All");
  const [sortBy, setSortBy] = useState<"createdAt" | "updatedAt">("updatedAt");
  const [direction, setDirection] = useState<"asc" | "desc">("desc");
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isMissing, setIsMissing] = useState(false);

  const pageHeader = useMemo(
    () => ({
      title: operation?.name?.trim() || t("shell.pageMeta.operationJobs.title"),
      subtitle: t("shell.pageMeta.operationJobs.subtitle"),
    }),
    [operation?.name, t],
  );

  usePageHeaderOverride(pageHeader);

  useEffect(() => {
    window.scrollTo({ top: 0, left: 0, behavior: "auto" });
  }, [operationId]);

  useEffect(() => {
    const timeout = window.setTimeout(() => {
      setPage(0);
      setQuery(queryInput.trim());
    }, 250);

    return () => window.clearTimeout(timeout);
  }, [queryInput]);

  useEffect(() => {
    if (!operationId) {
      return;
    }

    const controller = new AbortController();

    async function load() {
      try {
        setIsLoading(true);
        setErrorMessage(null);
        setIsMissing(false);

        const [nextOperation, nextJobsPage] = await Promise.all([
          fetchOperationById(operationId, undefined, { signal: controller.signal }),
          fetchOperationCallJobsPage(operationId, {
            page,
            size: 25,
            query,
            statuses: statusFilter === "All" ? [] : [statusFilter],
            sortBy,
            direction,
            init: { signal: controller.signal },
          }),
        ]);

        setOperation(nextOperation);
        setJobsPage(nextJobsPage);
      } catch (error) {
        if (controller.signal.aborted) {
          return;
        }

        const message = error instanceof Error ? error.message : "Çağrı işi listesi yüklenemedi.";
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
  }, [direction, operationId, page, query, sortBy, statusFilter]);

  if (isMissing) {
    notFound();
  }

  const emptyState = getEmptyState(operation, query, statusFilter);

  return (
    <PageContainer>
      {errorMessage ? (
        <section className="panel-card">
          <div className="operation-inline-message is-danger">
            <strong>Çağrı işi listesi yüklenemedi</strong>
            <span>{errorMessage}</span>
          </div>
        </section>
      ) : null}

      <SectionCard
        title="Operasyon çağrı işi listesi"
        description="Arama, durum filtresi, zaman sıralama ve sayfalama aynı ekranda sunulur."
        action={(
          <Link href={`/operations/${operationId}`} className="button-secondary compact-button">
            Operasyona dön
          </Link>
        )}
      >
        <div className="operation-workspace-summary-list">
          <div className="operation-summary-row">
            <span>Operasyon durumu</span>
            <strong><StatusBadge status={operation?.status ?? "Pending"} /></strong>
          </div>
          <div className="operation-summary-row">
            <span>Toplam hazırlanan iş</span>
            <strong>{operation?.executionSummary.totalCallJobs ?? 0}</strong>
          </div>
          <div className="operation-summary-row">
            <span>Açık iş havuzu</span>
            <strong>{operation?.executionSummary.pendingCallJobs ?? 0}</strong>
          </div>
        </div>

        <div className="operation-list-toolbar">
          <label className="search-field operation-list-search">
            <input
              value={queryInput}
              onChange={(event) => setQueryInput(event.target.value)}
              placeholder="Kişi adı veya telefon ara"
            />
          </label>

          <div className="filter-tabs">
            {JOB_FILTERS.map(([value, label]) => (
              <button
                key={value}
                type="button"
                className={`filter-tab ${statusFilter === value ? "is-active" : ""}`}
                onClick={() => {
                  setPage(0);
                  setStatusFilter(value);
                }}
              >
                {label}
              </button>
            ))}
          </div>

          <label className="builder-field" style={{ minWidth: 220 }}>
            <strong>Sıralama</strong>
            <select
              value={`${sortBy}:${direction}`}
              onChange={(event) => {
                const [nextSortBy, nextDirection] = event.target.value.split(":") as ["createdAt" | "updatedAt", "asc" | "desc"];
                setPage(0);
                setSortBy(nextSortBy);
                setDirection(nextDirection);
              }}
            >
              <option value="updatedAt:desc">Son güncellenen önce</option>
              <option value="updatedAt:asc">İlk güncellenen önce</option>
              <option value="createdAt:desc">Son oluşturulan önce</option>
              <option value="createdAt:asc">İlk oluşturulan önce</option>
            </select>
          </label>
        </div>

        {isLoading ? (
          <div className="list-item">
            <div>
              <strong>Çağrı işi listesi yükleniyor</strong>
              <span>Seçili operasyonun işleri backend üzerinden sayfalı olarak çekiliyor.</span>
            </div>
          </div>
        ) : !jobsPage || jobsPage.totalItems === 0 ? (
          <div className="operation-empty-state">
            <strong>{emptyState.title}</strong>
            <p>{emptyState.description}</p>
          </div>
        ) : (
          <>
            <DataTable
              columns={jobColumns}
              rows={jobsPage.items}
              toolbar={<span className="table-meta">{jobsPage.totalItems} iş / sayfa {jobsPage.page + 1} / {Math.max(jobsPage.totalPages, 1)}</span>}
            />
            <div className="operation-pagination">
              <button
                type="button"
                className="button-secondary compact-button"
                disabled={jobsPage.page === 0}
                onClick={() => setPage((current) => Math.max(current - 1, 0))}
              >
                Önceki
              </button>
              <span className="operation-pagination-meta">
                {jobsPage.page * jobsPage.size + 1}
                -
                {Math.min((jobsPage.page + 1) * jobsPage.size, jobsPage.totalItems)}
                {" / "}
                {jobsPage.totalItems}
              </span>
              <button
                type="button"
                className="button-secondary compact-button"
                disabled={jobsPage.page >= jobsPage.totalPages - 1}
                onClick={() => setPage((current) => current + 1)}
              >
                Sonraki
              </button>
            </div>
          </>
        )}
      </SectionCard>
    </PageContainer>
  );
}

function buildJobResultTitle(job: CallJob): string {
  if ((job.answerCount ?? 0) > 0) {
    return `${job.answerCount ?? 0} soruya cevap verdi`;
  }

  if (job.status === "Failed") {
    return job.lastResultSummary ?? "Çağrı başarısız oldu";
  }

  if (job.status === "Completed") {
    return "Geçerli cevap kaydı yok";
  }

  return "Henüz cevap yok";
}

function getEmptyState(
  operation: Operation | null,
  query: string,
  statusFilter: "All" | CallJob["status"],
): { title: string; description: string } {
  if (query || statusFilter !== "All") {
    return {
      title: "Eşleşen çağrı işi bulunamadı",
      description: "Arama veya durum filtrelerini temizleyerek tüm kayıtları tekrar görüntüleyebilirsiniz.",
    };
  }

  if (!operation) {
    return {
      title: "Çağrı işi listesi henüz hazır değil",
      description: "Operasyon bilgisi yüklendikten sonra liste durumu gösterilecektir.",
    };
  }

  if (operation.status !== "Running" && operation.executionSummary.totalCallJobs === 0) {
    return {
      title: "Çağrı işi henüz oluşturulmadı",
      description: "Bu operasyon henüz başlatılmadı. Operasyonu başlattığınızda kişi bazlı çağrı işi kayıtları burada oluşur.",
    };
  }

  if (operation.executionSummary.totalCallJobs === 0) {
    return {
      title: "Hazırlanan aktif iş yok",
      description: "Operasyon başlatılmış olsa da şu anda izlenebilir bir çağrı işi kaydı bulunmuyor.",
    };
  }

  return {
    title: "Listelenecek çağrı işi yok",
    description: "Bu operasyon için henüz görüntülenecek bir kayıt bulunmuyor.",
  };
}


