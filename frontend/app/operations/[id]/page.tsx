"use client";

import Link from "next/link";
import { notFound, useParams, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent, type KeyboardEvent } from "react";
import { useNotifications } from "@/lib/notifications";
import { usePolling } from "@/lib/usePolling";
import { OperationAnalyticsSection } from "@/components/operations/OperationAnalyticsSection";
import { PageContainer } from "@/components/layout/PageContainer";
import { usePageHeaderOverride } from "@/components/layout/PageHeaderContext";
import { DataTable } from "@/components/ui/DataTable";
import { KpiCard } from "@/components/ui/KpiCard";
import { SectionCard } from "@/components/ui/SectionCard";
import { EyeIcon, PauseIcon, PlayIcon } from "@/components/ui/Icons";
import { ActionsMenu } from "@/components/ui/ActionsMenu";
import { StatusBadge } from "@/components/ui/StatusBadge";
import {
  ACCEPTED_OPERATION_CONTACT_FILE_TYPES,
  OPERATION_CONTACT_IMPORT_PREVIEW_LIMIT,
  buildPreviewRows,
  createEmptyImportSummary,
  downloadOperationContactsTemplate,
  normalizePhoneNumber,
  readOperationContactImportRows,
  type ImportPreviewRow,
  type ImportSummary,
} from "@/lib/operation-contact-import";
import { getAnalyticsKpisCompact, getOperationStatusConfig, getPrimaryAction } from "@/lib/operation-detail";
import {
  uploadOperationContactsInBatches,
  deleteOperation,
  fetchOperationAnalytics,
  fetchOperationById,
  fetchOperationCallJobsPage,
  fetchOperationContactSummary,
  fetchOperationContacts,
  pauseOperation,
  resumeOperation,
  startOperation,
  updateOperationCallJobSurveyResponse,
  type CallJobPage,
  type OperationContactSummary,
} from "@/lib/operations";
import { CallJob, Operation, OperationAnalytics, TableColumn } from "@/lib/types";

type OperationWorkspaceTab = "analysis" | "firstLook" | "details" | "jobs";

// Setup and status first, then the calls and their results; İlk bakış is the keyword lexicon preview.
const OPERATION_TABS: Array<{ key: OperationWorkspaceTab; label: string }> = [
  { key: "details", label: "Operasyon Detay" },
  { key: "jobs", label: "Çağrılar" },
  { key: "analysis", label: "Sonuçlar" },
  { key: "firstLook", label: "İlk bakış" },
];

type OperationFirstLookQuestionSuggestion = {
  questionId?: string;
  questionCode?: string;
  questionTitle?: string;
  lexicon?: {
    type?: string;
    domain?: string;
    entries?: Array<{
      label?: string;
      aliases?: string[];
    }>;
  };
};

type OperationFirstLookPreview = {
  generatedAt?: string;
  operationName?: string;
  surveyId?: string;
  surveyName?: string;
  keywordCount?: number;
  keywords?: string[];
  questionSuggestions?: OperationFirstLookQuestionSuggestion[];
};

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

function parseFirstLookPreview(sourcePayloadJson: string | null | undefined): OperationFirstLookPreview | null {
  if (!sourcePayloadJson?.trim()) {
    return null;
  }

  try {
    const root = JSON.parse(sourcePayloadJson) as {
      autoEntityLexiconPreview?: OperationFirstLookPreview;
    };
    const preview = root.autoEntityLexiconPreview;
    if (!preview || typeof preview !== "object") {
      return null;
    }
    return preview;
  } catch {
    return null;
  }
}

export default function OperationDetailPage() {
  const params = useParams<{ id: string }>();
  const searchParams = useSearchParams();
  const router = useRouter();
  const operationId = params.id;
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const hasInitializedTabRef = useRef(false);
  const [operation, setOperation] = useState<Operation | null>(null);
  const [contactSummary, setContactSummary] = useState<OperationContactSummary | null>(null);
  const [analytics, setAnalytics] = useState<OperationAnalytics | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isAnalyticsLoading, setIsAnalyticsLoading] = useState(true);
  const [isStarting, setIsStarting] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [startErrorMessage, setStartErrorMessage] = useState<string | null>(null);
  const [deleteErrorMessage, setDeleteErrorMessage] = useState<string | null>(null);
  const [isMissing, setIsMissing] = useState(false);
  const [selectedFileName, setSelectedFileName] = useState<string | null>(null);
  const [importRows, setImportRows] = useState<ImportPreviewRow[]>([]);
  const [importSummary, setImportSummary] = useState<ImportSummary>(createEmptyImportSummary());
  const [importErrorMessage, setImportErrorMessage] = useState<string | null>(null);
  const [isImporting, setIsImporting] = useState(false);
  const [importProgress, setImportProgress] = useState<{ done: number; total: number } | null>(null);
  const [jobsPage, setJobsPage] = useState<CallJobPage | null>(null);
  const [jobsPageIndex, setJobsPageIndex] = useState(0);
  const [jobsQueryInput, setJobsQueryInput] = useState("");
  const [jobsQuery, setJobsQuery] = useState("");
  const [jobsStatusFilters, setJobsStatusFilters] = useState<CallJob["status"][]>([]);
  const [jobsSortBy, setJobsSortBy] = useState<"createdAt" | "updatedAt">("updatedAt");
  const [jobsDirection, setJobsDirection] = useState<"asc" | "desc">("desc");
  const [isJobsLoading, setIsJobsLoading] = useState(false);
  const [jobsErrorMessage, setJobsErrorMessage] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<OperationWorkspaceTab>("analysis");
  const prevStatusRef = useRef<string | null>(null);

  const { notify } = useNotifications();
  const isLive = operation?.status === "Running";

  const loadOperationWorkspace = useCallback(async (signal?: AbortSignal) => {
    if (!operationId) {
      return;
    }

    setIsAnalyticsLoading(true);

    const [operationResult, summaryResult, analyticsResult] = await Promise.allSettled([
      fetchOperationById(operationId, undefined, signal ? { signal } : undefined),
      fetchOperationContactSummary(operationId, {
        latestLimit: 5,
        init: signal ? { signal } : undefined,
      }),
      fetchOperationAnalytics(operationId, undefined, signal ? { signal } : undefined),
    ]);

    if (operationResult.status === "rejected") {
      throw operationResult.reason;
    }

    setOperation(operationResult.value);
    setContactSummary(summaryResult.status === "fulfilled" ? summaryResult.value : null);
    setAnalytics(analyticsResult.status === "fulfilled" ? analyticsResult.value : null);
    setIsAnalyticsLoading(false);
  }, [operationId]);

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
        await loadOperationWorkspace(controller.signal);
      } catch (error) {
        if (controller.signal.aborted) {
          return;
        }

        const message = error instanceof Error ? error.message : "Operasyon detayı yüklenemedi.";
        if (message.includes("(404)")) {
          setIsMissing(true);
          return;
        }

        setErrorMessage(message);
        setIsAnalyticsLoading(false);
      } finally {
        if (!controller.signal.aborted) {
          setIsLoading(false);
          setIsAnalyticsLoading(false);
        }
      }
    }

    void load();
    return () => controller.abort();
  }, [loadOperationWorkspace, operationId]);

  useEffect(() => {
    if (!operation || hasInitializedTabRef.current) {
      return;
    }

    hasInitializedTabRef.current = true;
    setActiveTab(
      operation.status === "Running" || operation.status === "Completed" || operation.status === "Failed"
        ? "analysis"
        : "details",
    );
  }, [operation]);

  useEffect(() => {
    if (!operation) return;
    const prev = prevStatusRef.current;
    const next = operation.status;
    if (prev !== null && prev !== next) {
      if (next === "Completed") {
        notify("success", `${operation.name} tamamlandı`, "Tüm aramalar bitti.");
      } else if (next === "Failed") {
        notify("error", `${operation.name} başarısız oldu`, "Operasyon hata durumunda.");
      } else if (next === "Paused") {
        notify("warning", `${operation.name} duraklatıldı`, "Süren görüşme bittikten sonra yeni çağrı başlatılmaz.");
      } else if (next === "Running" && prev !== "Running") {
        notify("info", `${operation.name} başladı`, "Aramalar devam ediyor.");
      }
    }
    prevStatusRef.current = next;
  }, [operation, notify]);

  usePolling(
    useCallback(() => loadOperationWorkspace(), [loadOperationWorkspace]),
    30000,
    isLive,
  );

  useEffect(() => {
    const timeout = window.setTimeout(() => {
      setJobsPageIndex(0);
      setJobsQuery(jobsQueryInput.trim());
    }, 250);

    return () => window.clearTimeout(timeout);
  }, [jobsQueryInput]);

  useEffect(() => {
    if (!operationId || activeTab !== "jobs") {
      return;
    }

    const controller = new AbortController();

    async function loadJobs() {
      try {
        setIsJobsLoading(true);
        setJobsErrorMessage(null);

        const nextJobsPage = await fetchOperationCallJobsPage(operationId, {
          page: jobsPageIndex,
          size: 25,
          query: jobsQuery,
          statuses: jobsStatusFilters,
          sortBy: jobsSortBy,
          direction: jobsDirection,
          init: { signal: controller.signal },
        });

        setJobsPage(nextJobsPage);
      } catch (error) {
        if (controller.signal.aborted) {
          return;
        }

        setJobsErrorMessage(error instanceof Error ? error.message : "Çağrı işi listesi yüklenemedi.");
      } finally {
        if (!controller.signal.aborted) {
          setIsJobsLoading(false);
        }
      }
    }

    void loadJobs();
    return () => controller.abort();
  }, [activeTab, jobsDirection, jobsPageIndex, jobsQuery, jobsSortBy, jobsStatusFilters, operationId]);

  const contactCount = contactSummary?.totalContacts ?? 0;
  const statusConfig = getOperationStatusConfig(operation, contactCount);
  const primaryAction = getPrimaryAction(operation, isStarting);
  const previewRows = importRows.slice(0, Math.min(OPERATION_CONTACT_IMPORT_PREVIEW_LIMIT, 6));
  const operationHasStarted = Boolean(operation?.startedAt);
  const canAppendContacts = operation?.status !== "Completed" && operation?.status !== "Cancelled";
  const contactStatusCounts = useMemo(() => {
    const counts: Partial<Record<CallJob["status"] | Operation["status"] | "Active" | "Completed" | "Failed" | "Retry" | "Invalid" | "Pending", number>> = {};
    for (const item of contactSummary?.statusCounts ?? []) {
      counts[item.status] = item.count;
    }
    return counts;
  }, [contactSummary?.statusCounts]);
  const successfulContactCount = contactStatusCounts.Completed ?? 0;
  const failedContactCount = (contactStatusCounts.Failed ?? 0) + (contactStatusCounts.Invalid ?? 0);
  // Contacts with at least one call attempt; contact status alone also counts queued and invalid contacts.
  const attemptedContactCount = analytics?.totalCallsAttempted ?? null;
  const operationIsTerminal =
    operation?.status === "Completed" || operation?.status === "Failed" || operation?.status === "Cancelled";

  const detailKpis = useMemo(() => {
    if (operation && analytics) {
      return getAnalyticsKpisCompact(operation, analytics);
    }

    return [
      {
        label: "Hedef kişi",
        value: String(contactCount),
        detail: "Operasyona yüklenen kişiler",
        tone: "neutral" as const,
      },
      {
        label: "Aranan kişi",
        value: String(analytics?.totalCallsAttempted ?? 0),
        detail: "Yürütme verisi geldikçe güncellenir",
        tone: "neutral" as const,
      },
      {
        label: "Yanıt veren kişi",
        value: String(analytics?.respondedContacts ?? 0),
        detail: "Tam ve kısmi yanıt veren kişi sayısı",
        tone: "neutral" as const,
      },
      {
        label: "Cevap oranı",
        value: `%${analytics?.responseRate ?? 0}`,
        detail: "Yanıt veren kişi / hedef kişi",
        tone: "neutral" as const,
      },
    ];
  }, [analytics, contactCount, operation]);

  const selectedJobFilterLabels = useMemo(() => {
    if (jobsStatusFilters.length === 0) {
      return "Tümü";
    }

    return JOB_FILTERS
      .filter(([value]) => value !== "All" && jobsStatusFilters.includes(value))
      .map(([, label]) => label)
      .join(", ");
  }, [jobsStatusFilters]);

  const firstLookPreview = useMemo(
    () => parseFirstLookPreview(operation?.sourcePayloadJson),
    [operation?.sourcePayloadJson],
  );

  const firstLookSuggestions = firstLookPreview?.questionSuggestions ?? [];
  const firstLookKeywords = firstLookPreview?.keywords ?? [];

  const toggleJobStatusFilter = useCallback((status: CallJob["status"]) => {
    setJobsPageIndex(0);
    setJobsStatusFilters((current) => (
      current.includes(status)
        ? current.filter((item) => item !== status)
        : [...current, status]
    ));
  }, []);

  // Arrow keys, Home and End move between the tabs and open the one they land on (WAI-ARIA tabs pattern).
  const handleTabKeyDown = useCallback((event: KeyboardEvent<HTMLButtonElement>) => {
    const index = OPERATION_TABS.findIndex((tab) => tab.key === activeTab);
    const nextIndex =
      event.key === "ArrowRight" ? (index + 1) % OPERATION_TABS.length
        : event.key === "ArrowLeft" ? (index - 1 + OPERATION_TABS.length) % OPERATION_TABS.length
          : event.key === "Home" ? 0
            : event.key === "End" ? OPERATION_TABS.length - 1
              : -1;
    if (nextIndex === -1) {
      return;
    }
    event.preventDefault();
    const nextKey = OPERATION_TABS[nextIndex].key;
    setActiveTab(nextKey);
    document.getElementById(`operation-tab-${nextKey}`)?.focus();
  }, [activeTab]);

  const headerTabs = useMemo(() => (
    <div className="operation-header-nav">
      <div className="operation-header-tabs" role="tablist" aria-label="Operasyon sekmeleri">
        {OPERATION_TABS.map((tab) => (
          <button
            key={tab.key}
            type="button"
            role="tab"
            id={`operation-tab-${tab.key}`}
            aria-selected={activeTab === tab.key}
            aria-controls="operation-tabpanel"
            // Only the active tab is in the Tab order; the arrow keys move between tabs.
            tabIndex={activeTab === tab.key ? 0 : -1}
            className={["operation-header-tab", activeTab === tab.key ? "is-active" : ""].filter(Boolean).join(" ")}
            onClick={() => setActiveTab(tab.key)}
            onKeyDown={handleTabKeyDown}
          >
            {tab.label}
          </button>
        ))}
      </div>
    </div>
  ), [activeTab, handleTabKeyDown]);

  const openImportPicker = useCallback(() => {
    fileInputRef.current?.click();
  }, []);

  const refreshAfterMutation = useCallback(async () => {
    await loadOperationWorkspace();
  }, [loadOperationWorkspace]);

  const handleSaveInlineSampleResponse = useCallback(async ({
    callJobId,
    questionId,
    responseText,
  }: {
    callJobId: string;
    questionId: string;
    responseText: string;
  }) => {
    if (!operationId) {
      return;
    }

    await updateOperationCallJobSurveyResponse(operationId, callJobId, [
      {
        questionId,
        answerText: responseText,
      },
    ]);

    await refreshAfterMutation();
  }, [operationId, refreshAfterMutation]);

  const handleStartOperation = useCallback(async () => {
    if (!operationId || !operation) {
      return;
    }

    try {
      setIsStarting(true);
      setStartErrorMessage(null);
      // The status change raises its own notification.
      const nextOperation = await startOperation(operationId);
      setOperation(nextOperation);
      await refreshAfterMutation();
    } catch (error) {
      setStartErrorMessage(error instanceof Error ? error.message : "Operasyon başlatılamadı.");
    } finally {
      setIsStarting(false);
    }
  }, [operation, operationId, refreshAfterMutation]);

  const handlePrimaryHeaderAction = useCallback(async () => {
    if (!operationId || !operation || primaryAction.disabled) {
      return;
    }

    try {
      setIsStarting(true);
      setStartErrorMessage(null);

      // The status change raises its own notification.
      const nextOperation = primaryAction.intent === "pause"
        ? await pauseOperation(operationId)
        : primaryAction.intent === "resume"
          ? await resumeOperation(operationId)
          : await startOperation(operationId);

      setOperation(nextOperation);
      await refreshAfterMutation();
    } catch (error) {
      setStartErrorMessage(error instanceof Error ? error.message : "İşlem tamamlanamadı.");
    } finally {
      setIsStarting(false);
    }
  }, [operation, operationId, primaryAction.disabled, primaryAction.intent, refreshAfterMutation]);

  const handleDeleteOperation = useCallback(async () => {
    if (!operationId || !operation || isDeleting) {
      return;
    }

    const confirmed = window.confirm(
      `"${operation.name}" operasyonunu silmek istediğinize emin misiniz? Bu işlem geri alınmaz.`,
    );
    if (!confirmed) {
      return;
    }

    try {
      setIsDeleting(true);
      setDeleteErrorMessage(null);
      await deleteOperation(operationId);
      notify("success", `${operation.name} silindi`);
      router.push("/operations");
      router.refresh();
    } catch (error) {
      setDeleteErrorMessage(error instanceof Error ? error.message : "Operasyon silinemedi.");
    } finally {
      setIsDeleting(false);
    }
  }, [isDeleting, notify, operation, operationId, router]);

  const pageHeader = useMemo(
    () => ({
      title: operation?.name?.trim() || "Operasyon",
      subtitle: headerTabs,
    }),
    [headerTabs, operation?.name],
  );

  const headerPlaybackState = useMemo(() => {
    if (operationIsTerminal) {
      return {
        actionLabel: "Operasyon Tamamlandı",
        actionClassName: "operation-media-action is-complete",
        actionIconClassName: "operation-media-action-icon is-complete",
        ActionIcon: PauseIcon,
        actionDisabled: true,
        actionHint: "Bu operasyonun çağrıları tamamlandı. Yeni bir aksiyon alınmaz.",
      };
    }

    if (operation?.status === "Running") {
      return {
        actionLabel: primaryAction.label,
        actionClassName: "operation-media-action is-stop",
        actionIconClassName: "operation-media-action-icon is-stop",
        ActionIcon: PauseIcon,
        actionDisabled: isStarting || primaryAction.disabled,
        actionHint: primaryAction.hint,
      };
    }

    return {
      actionLabel: primaryAction.label,
      actionClassName: "operation-media-action is-start",
      actionIconClassName: "operation-media-action-icon is-start",
      ActionIcon: PlayIcon,
      actionDisabled: isStarting || primaryAction.disabled,
      actionHint: primaryAction.hint,
    };
  }, [isStarting, operation?.status, operationIsTerminal, primaryAction.disabled, primaryAction.hint, primaryAction.label]);

  const headerAction = useMemo(() => operation ? (
      <div className="survey-header-action-cluster">
        <div className="operation-header-summary-card">
        <div className="operation-header-summary-item">
          <span>Hedef</span>
          <strong>{contactCount}</strong>
        </div>
        <div className="operation-header-summary-item is-progress">
          <span>Aranan</span>
          <strong>{attemptedContactCount ?? "–"}</strong>
        </div>
        <div className="operation-header-summary-item is-success">
          <span>Başarılı</span>
          <strong>{successfulContactCount}</strong>
        </div>
        <div className="operation-header-summary-item is-failure">
          <span>Başarısız</span>
          <strong>{failedContactCount}</strong>
        </div>
      </div>
      <div className="survey-header-action-buttons operation-media-controls">
        {/* A status, not a control: the badge keeps it from reading as a second button next to the action. */}
        <StatusBadge status={operation.status} label={statusConfig.badge.label} />
        {!operationIsTerminal ? (
          <button
            type="button"
            className={`compact-button survey-header-button ${headerPlaybackState.actionClassName}`}
            onClick={() => void handlePrimaryHeaderAction()}
            disabled={headerPlaybackState.actionDisabled}
            title={headerPlaybackState.actionHint}
          >
            <span className={headerPlaybackState.actionIconClassName}>
              <headerPlaybackState.ActionIcon className="nav-icon" />
            </span>
            {headerPlaybackState.actionLabel}
          </button>
        ) : null}
        <ActionsMenu
          items={[
            {
              label: isDeleting ? "Siliniyor..." : "Operasyonu sil",
              tone: "danger",
              disabled: isDeleting,
              onSelect: () => void handleDeleteOperation(),
            },
          ]}
        />
        {/* Why the action is unavailable; when it is available the hint stays in its tooltip. */}
        {!operationIsTerminal && headerPlaybackState.actionDisabled && headerPlaybackState.actionHint ? (
          <span className="survey-header-action-note">{headerPlaybackState.actionHint}</span>
        ) : null}
      </div>
    </div>
  ) : null, [attemptedContactCount, contactCount, failedContactCount, handleDeleteOperation, handlePrimaryHeaderAction, headerPlaybackState, isDeleting, operation, operationIsTerminal, statusConfig.badge.label, successfulContactCount]);

  usePageHeaderOverride({ ...pageHeader, action: headerAction });

  async function handleFileSelection(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    setImportErrorMessage(null);
    setImportRows([]);
    setImportSummary(createEmptyImportSummary());
    setSelectedFileName(file?.name ?? null);

    if (!file || !operationId) {
      return;
    }

    try {
      const existingContacts = await fetchOperationContacts(operationId);
      const existingPhoneNumbers = existingContacts
        .map((contact) => normalizePhoneNumber(contact.phoneNumber))
        .filter(Boolean);
      const rows = await readOperationContactImportRows(file);
      const result = buildPreviewRows(rows, { existingPhoneNumbers });

      setImportRows(result.previewRows);
      setImportSummary(result.summary);

      if (result.summary.totalRows === 0 && result.summary.ignoredRows === 0) {
        setImportErrorMessage("Dosyada veri satırı bulunamadı.");
      }
    } catch (error) {
      setImportErrorMessage(error instanceof Error ? error.message : "Dosya okunamadı.");
    } finally {
      event.target.value = "";
    }
  }

  async function handleImportContacts() {
    if (!operationId) {
      return;
    }

    const validRows = importRows.filter((row) => row.isValid);
    setImportErrorMessage(null);

    if (validRows.length === 0) {
      setImportErrorMessage("İçeri aktarılacak geçerli kişi bulunamadı.");
      return;
    }

    try {
      setIsImporting(true);
      setImportProgress({ done: 0, total: validRows.length });
      const upload = await uploadOperationContactsInBatches(operationId, validRows, (done, total) =>
        setImportProgress({ done, total }),
      );
      const importedRows = upload.importedRows;
      const failedRows = upload.failedRows.map((item) => ({ rowNumber: item.row.rowNumber, reason: item.reason }));

      await refreshAfterMutation();

      if (importedRows.length > 0) {
        const duplicateFeedback = importSummary.duplicateRows > 0 ? ` ${importSummary.duplicateRows} tekrar satırı dışarıda bırakıldı.` : "";
        notify(
          "success",
          "Toplu yükleme tamamlandı",
          failedRows.length > 0
            ? `${importedRows.length} kişi operasyona eklendi.${duplicateFeedback} ${failedRows.length} satır backend tarafında başarısız oldu.`
            : `${importedRows.length} kişi operasyona başarıyla eklendi.${duplicateFeedback}`,
        );
      }

      if (failedRows.length > 0) {
        const summaryText = failedRows.slice(0, 4).map((item) => `Satır ${item.rowNumber}: ${item.reason}`).join(" ");
        setImportErrorMessage(
          failedRows.length > 4
            ? `${failedRows.length} satır içe aktarılamadı. ${summaryText} Daha fazla satır için dosyayı gözden geçirin.`
            : `${failedRows.length} satır içe aktarılamadı. ${summaryText}`,
        );
      }

      if (importedRows.length > 0) {
        setImportRows((currentRows) => {
          const importedRowNumbers = new Set(importedRows.map((row) => row.rowNumber));
          const remainingRows = currentRows.filter((row) => !importedRowNumbers.has(row.rowNumber));
          setImportSummary(createImportSummaryFromRows(remainingRows, importSummary.ignoredRows));
          if (remainingRows.length === 0) {
            setSelectedFileName(null);
          }
          return remainingRows;
        });
      }
    } finally {
      setIsImporting(false);
      setImportProgress(null);
    }
  }

  const importedCount = Number(searchParams.get("imported") ?? "0");
  const invalidCount = Number(searchParams.get("invalid") ?? "0");
  const ignoredCount = Number(searchParams.get("ignored") ?? "0");
  const importRequested = searchParams.get("importRequested") === "1";
  const importError = searchParams.get("importError");
  const contactsSkipped = searchParams.get("contacts") === "skipped";
  const creationSucceeded = !importError && (contactsSkipped || importedCount > 0);
  // Only a creation whose contact import went wrong keeps a message on the page.
  const showCreationSummary = (importRequested || contactsSkipped) && !creationSucceeded;
  const hasAnnouncedCreationRef = useRef(false);

  // A successful creation is announced once, and the result parameters are dropped from the URL so a reload
  // does not announce it again.
  useEffect(() => {
    if (!creationSucceeded || hasAnnouncedCreationRef.current) {
      return;
    }

    hasAnnouncedCreationRef.current = true;
    notify(
      "success",
      contactsSkipped ? "Operasyon oluşturuldu" : "Operasyon ve kişi importu tamamlandı",
      contactsSkipped
        ? "Kişileri bu ekrandaki Kişi Ekle bölümünden yükleyebilirsiniz."
        : `${importedCount} geçerli kişi yeni operasyona bağlandı.${invalidCount > 0 ? ` Geçersiz satır: ${invalidCount}.` : ""}${ignoredCount > 0 ? ` Boş geçilen satır: ${ignoredCount}.` : ""}`,
    );
    router.replace(`/operations/${operationId}`, { scroll: false });
  }, [contactsSkipped, creationSucceeded, ignoredCount, importedCount, invalidCount, notify, operationId, router]);

  if (isMissing) {
    notFound();
  }

  // Whichever tab is open renders its content root with these.
  const tabPanelProps = {
    role: "tabpanel",
    id: "operation-tabpanel",
    "aria-labelledby": `operation-tab-${activeTab}`,
  } as const;

  return (
    <PageContainer hideBackRow>
      <div className="survey-detail-shell survey-detail-compact operation-detail-tight">
        {errorMessage ? (
          <section className="panel-card">
            <div className="operation-inline-message is-danger">
              <strong>Operasyon çalışma alanı yüklenemedi</strong>
              <span>{errorMessage}</span>
            </div>
          </section>
        ) : null}

        {deleteErrorMessage ? (
          <section className="panel-card">
            <div className="operation-inline-message is-danger">
              <strong>Operasyon silinemedi</strong>
              <span>{deleteErrorMessage}</span>
            </div>
          </section>
        ) : null}

        {showCreationSummary ? (
          <section className="panel-card">
            <div className={`operation-inline-message ${importError ? "is-danger" : "is-accent"}`}>
              <strong>
                {importError
                  ? importedCount > 0
                    ? "Operasyon oluşturuldu, bazı kişiler aktarılamadı"
                    : "Operasyon oluşturuldu, kişi importu tamamlanamadı"
                  : "Operasyon oluşturuldu, geçerli kişi bulunamadı"}
              </strong>
              <span>
                {importError
                  ? `${importedCount > 0 ? `${importedCount} kişi eklendi. ` : ""}${importError}${invalidCount > 0 ? ` Geçersiz satır: ${invalidCount}.` : ""}`
                  : `Dosya parse edildi ancak import edilecek geçerli kişi bulunamadı.${invalidCount > 0 ? ` Geçersiz satır: ${invalidCount}.` : ""}${ignoredCount > 0 ? ` Boş geçilen satır: ${ignoredCount}.` : ""}`}
              </span>
            </div>
          </section>
        ) : null}

        {activeTab === "analysis" ? (
          <div {...tabPanelProps} className="operation-tabpanel">
            <OperationAnalyticsSection
              operation={operation}
              analytics={analytics}
              contactCount={contactCount}
              isLoading={isAnalyticsLoading}
              view="overview"
              onSaveSampleResponse={handleSaveInlineSampleResponse}
            />
          </div>
        ) : activeTab === "firstLook" ? (
          <section {...tabPanelProps} className="panel-card operation-detail-content-stack">
            <div className="operation-detail-summary-strip">
              <div className="operation-detail-summary-copy">
                <div className="operation-detail-hero-meta">
                  <span className="operation-kicker">İlk bakış</span>
                  <StatusBadge
                    status={firstLookSuggestions.length > 0 ? "Completed" : "Draft"}
                    label={firstLookSuggestions.length > 0 ? "Hazırlandı" : "Boş"}
                  />
                </div>
                <h2>Operation autoEntityLexicon önizlemesi</h2>
                <p>
                  Operation oluşturulurken çıkarılan lexicon önerileri burada listelenir. Bu alan şu an inceleme
                  amaçlıdır; düzenleme akışını bir sonraki adımda ekleyebiliriz.
                </p>
              </div>
              <div className="operation-detail-summary-note">
                <span>Üretim özeti</span>
                <strong>{firstLookSuggestions.length} soru için öneriler hazır</strong>
                <small>
                  {firstLookPreview?.generatedAt
                    ? `Üretim zamanı: ${new Date(firstLookPreview.generatedAt).toLocaleString("tr-TR")}`
                    : "Üretim zamanı bilgisi bulunamadı."}
                </small>
              </div>
            </div>

            {firstLookPreview ? (
              <>
                <div className="operation-import-stats">
                  <div className="operation-import-stat"><span>Soru</span><strong>{firstLookSuggestions.length}</strong></div>
                  <div className="operation-import-stat"><span>Anahtar kelime</span><strong>{firstLookPreview.keywordCount ?? firstLookKeywords.length}</strong></div>
                  <div className="operation-import-stat"><span>Operasyon</span><strong>{firstLookPreview.operationName ?? operation?.name ?? "-"}</strong></div>
                  <div className="operation-import-stat"><span>Anket</span><strong>{firstLookPreview.surveyName ?? operation?.survey ?? "-"}</strong></div>
                </div>

                {firstLookKeywords.length > 0 ? (
                  <SectionCard
                    title="ASR anahtar kelimeleri"
                    description="Provider tarafına keyword olarak gidecek birleşik liste."
                  >
                    <div className="operation-first-look-chip-grid">
                      {firstLookKeywords.map((keyword) => (
                        <span key={keyword} className="operation-first-look-chip">{keyword}</span>
                      ))}
                    </div>
                  </SectionCard>
                ) : null}

                <SectionCard
                  title="Soru bazlı öneriler"
                  description="Her soru için çıkarılan named entity lexicon girişleri burada görünür."
                >
                  {firstLookSuggestions.length === 0 ? (
                    <div className="operation-empty-state">
                      <strong>Gösterilecek lexicon önerisi yok</strong>
                      <p>Bu operation için autoEntityLexicon önizlemesi üretilmedi ya da boş geldi.</p>
                    </div>
                  ) : (
                    <div className="operation-first-look-list">
                      {firstLookSuggestions.map((suggestion, index) => {
                        const entries = suggestion.lexicon?.entries ?? [];
                        return (
                          <article key={`${suggestion.questionId ?? suggestion.questionCode ?? index}`} className="operation-first-look-card">
                            <div className="operation-first-look-card-head">
                              <div>
                                <strong>{suggestion.questionTitle ?? suggestion.questionCode ?? `Soru ${index + 1}`}</strong>
                                <span>{suggestion.questionCode ?? "Kod yok"}</span>
                              </div>
                              <span className="operation-first-look-pill">
                                {suggestion.lexicon?.domain ?? suggestion.lexicon?.type ?? "named_entity"}
                              </span>
                            </div>

                            {entries.length === 0 ? (
                              <p className="operation-first-look-empty">Bu soru için entity girişi bulunamadı.</p>
                            ) : (
                              <div className="operation-first-look-entries">
                                {entries.map((entry) => (
                                  <div key={`${suggestion.questionCode ?? "q"}-${entry.label ?? "entry"}`} className="operation-first-look-entry">
                                    <strong>{entry.label ?? "Etiket yok"}</strong>
                                    <div className="operation-first-look-chip-grid">
                                      {(entry.aliases ?? []).map((alias) => (
                                        <span key={`${entry.label ?? "entry"}-${alias}`} className="operation-first-look-chip is-soft">{alias}</span>
                                      ))}
                                    </div>
                                  </div>
                                ))}
                              </div>
                            )}
                          </article>
                        );
                      })}
                    </div>
                  )}
                </SectionCard>
              </>
            ) : (
              <div className="operation-empty-state">
                <strong>İlk bakış verisi bulunamadı</strong>
                <p>Bu operation için `sourcePayloadJson` içinde `autoEntityLexiconPreview` bulunmuyor.</p>
              </div>
            )}
          </section>
        ) : activeTab === "jobs" ? (
          <section {...tabPanelProps} className="panel-card operation-detail-content-stack operation-jobs-panel">
            <div className="operation-list-toolbar operation-list-toolbar-compact">
              <label className="search-field operation-list-search">
                <input
                  value={jobsQueryInput}
                  onChange={(event) => setJobsQueryInput(event.target.value)}
                  placeholder="Kişi adı veya telefon ara"
                />
              </label>

              <label className="builder-field operation-jobs-filter-field" style={{ minWidth: 220 }}>
                <strong>Filtrele</strong>
                <details className="operation-filter-dropdown">
                  <summary className="operation-filter-dropdown-trigger">
                    <strong>{selectedJobFilterLabels}</strong>
                  </summary>
                  <div className="operation-filter-dropdown-menu">
                    <label className="operation-filter-option">
                      <input
                        type="checkbox"
                        checked={jobsStatusFilters.length === 0}
                        onChange={() => {
                          setJobsPageIndex(0);
                          setJobsStatusFilters([]);
                        }}
                      />
                      <span>Tümü</span>
                    </label>
                    {JOB_FILTERS.filter((item): item is [CallJob["status"], string] => item[0] !== "All").map(([value, label]) => (
                      <label key={value} className="operation-filter-option">
                        <input
                          type="checkbox"
                          checked={jobsStatusFilters.includes(value)}
                          onChange={() => toggleJobStatusFilter(value)}
                        />
                        <span>{label}</span>
                      </label>
                    ))}
                  </div>
                </details>
              </label>

              <label className="builder-field operation-jobs-sort-field" style={{ minWidth: 220 }}>
                <strong>Sırala</strong>
                <select
                  value={`${jobsSortBy}:${jobsDirection}`}
                  onChange={(event) => {
                    const [nextSortBy, nextDirection] = event.target.value.split(":") as ["createdAt" | "updatedAt", "asc" | "desc"];
                    setJobsPageIndex(0);
                    setJobsSortBy(nextSortBy);
                    setJobsDirection(nextDirection);
                  }}
                >
                  <option value="updatedAt:desc">Son güncellenen önce</option>
                  <option value="updatedAt:asc">İlk güncellenen önce</option>
                  <option value="createdAt:desc">Son oluşturulan önce</option>
                  <option value="createdAt:asc">İlk oluşturulan önce</option>
                </select>
              </label>
            </div>

            {jobsErrorMessage ? (
              <div className="operation-inline-message is-danger compact">
                <strong>Çağrı işi listesi yüklenemedi</strong>
                <span>{jobsErrorMessage}</span>
              </div>
            ) : null}

            {isJobsLoading ? (
              <div className="list-item operation-jobs-loading">
                <div>
                  <strong>Çağrı işi listesi yükleniyor</strong>
                  <span>Seçili operasyonun işleri backend üzerinden sayfalı olarak çekiliyor.</span>
                </div>
              </div>
            ) : !jobsPage || jobsPage.totalItems === 0 ? (
              <div className="operation-empty-state operation-jobs-empty-state">
                <strong>{getCallJobsEmptyState(operation, jobsQuery, jobsStatusFilters).title}</strong>
                <p>{getCallJobsEmptyState(operation, jobsQuery, jobsStatusFilters).description}</p>
              </div>
            ) : (
              <>
                <DataTable
                  columns={jobColumns}
                  rows={jobsPage.items}
                  toolbar={<span className="table-meta operation-jobs-table-meta">{jobsPage.totalItems} iş / sayfa {jobsPage.page + 1} / {Math.max(jobsPage.totalPages, 1)}</span>}
                />
                <div className="operation-pagination operation-pagination-compact">
                  <button
                    type="button"
                    className="button-secondary compact-button"
                    disabled={jobsPage.page === 0}
                    onClick={() => setJobsPageIndex((current) => Math.max(current - 1, 0))}
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
                    onClick={() => setJobsPageIndex((current) => current + 1)}
                  >
                    Sonraki
                  </button>
                </div>
              </>
            )}
          </section>
        ) : (
          <section {...tabPanelProps} className="panel-card operation-detail-content-stack">
            <div className="operation-detail-summary-strip">
              <div className="operation-detail-summary-copy">
                <div className="operation-detail-hero-meta">
                  <span className="operation-kicker">Operasyon Durumu</span>
                  <StatusBadge status={operation?.status ?? "Draft"} label={statusConfig.badge.label} />
                  {isLive ? (
                    <span className="live-indicator" aria-label="Canlı">
                      <span className="live-dot" />
                      Canlı
                    </span>
                  ) : null}
                </div>
                <h2>{statusConfig.title}</h2>
                <p>{statusConfig.summary}</p>
              </div>
              <div className="operation-detail-summary-note">
                <span>Bağlı anket</span>
                <strong>{operation?.survey ?? "Anket bağlanmadı"}</strong>
                <small>{operation?.surveyGoal?.trim() || "Anket hedef özeti tanımlanmamış."}</small>
                {operation?.surveyId ? (
                  <Link href={`/surveys/${operation.surveyId}/preview`} className="operation-detail-link-card-cta">
                    <EyeIcon className="nav-icon" />
                    Soruları gör
                  </Link>
                ) : null}
              </div>
            </div>

            <div className="operation-kpi-grid">
              {detailKpis.map((item) => (
                <KpiCard
                  key={item.label}
                  label={item.label}
                  value={item.value}
                  detail={item.detail}
                  tone={item.tone}
                />
              ))}
            </div>

            <SectionCard
              title={operationHasStarted ? "Kişi Ekle" : "Kişi İçe Aktar"}
              description={operationHasStarted
                ? "Akış başlatıldıktan sonra mevcut liste değiştirilmez. Eklenen kişiler mevcut çağrı sırasının sonuna eklenir."
                : "Kişi listesini CSV veya Excel dosyasından küçük bir kontrol yüzeyiyle bu operasyona ekleyin."}
              action={(
                <div className="operation-detail-inline-actions">
                  <button type="button" className="button-secondary compact-button" onClick={downloadOperationContactsTemplate}>
                    Örnek şablon
                  </button>
                </div>
              )}
            >
              <div className="operation-detail-import-stack">
                <div className="operation-detail-import-meta">
                  <div className="operation-contact-status-card">
                    <span>Seçilen dosya</span>
                    <div className="operation-contact-status-inline">
                      <strong>{selectedFileName ?? "Dosya seçilmedi"}</strong>
                      <button
                        type="button"
                        className="button-secondary compact-button"
                        onClick={openImportPicker}
                        disabled={!canAppendContacts}
                      >
                        Dosya seç
                      </button>
                    </div>
                  </div>
                </div>

                {(importSummary.totalRows > 0 || importSummary.ignoredRows > 0) ? (
                  <div className="operation-import-stats">
                    <div className="operation-import-stat"><span>Toplam satır</span><strong>{importSummary.totalRows}</strong></div>
                    <div className="operation-import-stat"><span>Geçerli</span><strong>{importSummary.validRows}</strong></div>
                    <div className="operation-import-stat"><span>Geçersiz</span><strong>{importSummary.invalidRows}</strong></div>
                    <div className="operation-import-stat"><span>Dosya tekrarı</span><strong>{importSummary.duplicateInFileRows}</strong></div>
                    <div className="operation-import-stat"><span>Operasyonda var</span><strong>{importSummary.duplicateInOperationRows}</strong></div>
                    <div className="operation-import-stat"><span>Boş geçilen</span><strong>{importSummary.ignoredRows}</strong></div>
                  </div>
                ) : null}

                {startErrorMessage ? (
                  <div className="operation-inline-message compact is-danger">
                    <strong>Başlatma tamamlanamadı</strong>
                    <span>{startErrorMessage}</span>
                  </div>
                ) : null}

                {importErrorMessage ? (
                  <div className="operation-inline-message compact is-danger">
                    <strong>Toplu yükleme sorunu</strong>
                    <span>{importErrorMessage}</span>
                  </div>
                ) : null}

                {importRows.length > 0 ? (
                  <div className="operation-import-preview operation-import-preview-compact">
                    <div className="operation-import-preview-head">
                      <strong>Önizleme</strong>
                      <span>İlk {previewRows.length} satır gösteriliyor.</span>
                    </div>
                    <div className="operation-import-table-wrap">
                      <table className="operation-import-table">
                        <thead>
                          <tr>
                            <th>Satır</th>
                            <th>Ad soyad</th>
                            <th>Telefon</th>
                            <th>Durum</th>
                          </tr>
                        </thead>
                        <tbody>
                          {previewRows.map((row) => (
                            <tr
                              key={row.rowNumber}
                              className={[
                                row.isValid ? "is-valid" : "is-invalid",
                                row.isDuplicateInFile || row.isDuplicateInOperation ? "is-duplicate" : "",
                              ].filter(Boolean).join(" ")}
                            >
                              <td>{row.rowNumber}</td>
                              <td>{row.name || "-"}</td>
                              <td>{row.normalizedPhoneNumber || row.phoneNumber || "-"}</td>
                              <td>{row.isValid ? "Hazır" : row.reason}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </div>
                ) : null}

                <div className="operation-detail-inline-actions">
                  <button
                    type="button"
                    className="button-primary compact-button"
                    disabled={isLoading || isImporting || !operation || !canAppendContacts || importSummary.validRows === 0}
                    onClick={() => void handleImportContacts()}
                  >
                    {isImporting
                      ? `Kişiler içe aktarılıyor... ${importProgress ? `${importProgress.done}/${importProgress.total}` : ""}`
                      : "Ekle"}
                  </button>
                  {operation?.status === "Ready" ? (
                    <button
                      type="button"
                      className="button-secondary compact-button"
                      disabled={primaryAction.disabled}
                      onClick={() => void handleStartOperation()}
                    >
                      <PlayIcon className="nav-icon" />
                      {primaryAction.label}
                    </button>
                  ) : null}
                </div>
              </div>
            </SectionCard>

            <input
              ref={fileInputRef}
              type="file"
              accept={ACCEPTED_OPERATION_CONTACT_FILE_TYPES}
              onChange={(event) => void handleFileSelection(event)}
              hidden
            />
          </section>
        )}
      </div>
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

function createImportSummaryFromRows(rows: ImportPreviewRow[], ignoredRows: number): ImportSummary {
  const validRows = rows.filter((row) => row.isValid).length;
  const invalidRows = rows.length - validRows;
  const duplicateInFileRows = rows.filter((row) => row.isDuplicateInFile).length;
  const duplicateInOperationRows = rows.filter((row) => row.isDuplicateInOperation).length;
  const duplicateRows = rows.filter((row) => row.isDuplicateInFile || row.isDuplicateInOperation).length;

  return {
    totalRows: rows.length,
    validRows,
    invalidRows,
    ignoredRows,
    duplicateRows,
    duplicateInFileRows,
    duplicateInOperationRows,
  };
}

function getCallJobsEmptyState(
  operation: Operation | null,
  query: string,
  statusFilters: CallJob["status"][],
): { title: string; description: string } {
  if (query || statusFilters.length > 0) {
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
