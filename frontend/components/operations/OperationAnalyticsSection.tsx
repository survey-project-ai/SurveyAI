"use client";

import { useRef, useState, type ReactElement } from "react";
import { jsPDF } from "jspdf";
import { toBlob, toPng } from "html-to-image";
import { KpiCard } from "@/components/ui/KpiCard";
import { SectionCard } from "@/components/ui/SectionCard";
import { getAnalyticsEmptyState, getAnalyticsKpisCompact, getQuestionChartPresentation } from "@/lib/operation-detail";
import { Operation, OperationAnalytics } from "@/lib/types";

export type OperationAnalyticsView = "overview" | "health" | "questions" | "audience";

type OperationAnalyticsSectionProps = {
  operation: Operation | null;
  analytics: OperationAnalytics | null;
  contactCount: number;
  isLoading: boolean;
  view: OperationAnalyticsView;
  onSaveSampleResponse?: (payload: { callJobId: string; questionId: string; responseText: string }) => Promise<void>;
};

type GroupedChartTooltip = {
  groupCode: string;
  x: number;
  y: number;
  rowLabel: string;
  seriesLabel: string;
  value: number;
} | null;

type DonutChartHover = {
  chartKey: string;
  label: string;
  count: number;
  percentage: number;
  color: string;
  x: number;
  y: number;
} | null;

const DONUT_LEGEND_PAGE_SIZE = 5;

const CHART_COLORS = ["#5aa7ff", "#74c0fc", "#8ce99a", "#ffd166", "#f4978e", "#b197fc", "#63e6be", "#ffa94d"];
const GROUP_CHART_HEIGHT = 222;
const GROUP_CHART_COLUMN_WIDTH = 126;
const GROUP_CHART_LEFT_GUTTER = 38;
const GROUP_CHART_TOP_GUTTER = 12;
const GROUP_CHART_BOTTOM_GUTTER = 84;
const GROUP_CHART_BAR_GAP = 4;
const GROUP_CHART_GROUP_GAP = 12;

function formatCompactPercent(value: number) {
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}

function buildAxisTicks(maxValue: number) {
  if (maxValue <= 0) {
    return [0, 1];
  }

  const roughStep = maxValue / 4;
  const magnitude = 10 ** Math.max(0, Math.floor(Math.log10(roughStep)));
  const normalized = roughStep / magnitude;
  const niceStep = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
  const step = niceStep * magnitude;
  const maxTick = Math.max(step, Math.ceil(maxValue / step) * step);
  const ticks: number[] = [];

  for (let value = 0; value <= maxTick; value += step) {
    ticks.push(value);
  }

  return ticks;
}

function buildGroupedLabelLines(questionOrder: number, rowLabel: string) {
  const prefix = `${questionOrder}. `;
  const normalized = rowLabel.replace(/\s+/g, " ").trim();
  const parentheticalMatch = normalized.match(/^(.*?)(\s*\(.+\))$/);

  if (parentheticalMatch) {
    return [`${prefix}${parentheticalMatch[1].trim()}`, parentheticalMatch[2].trim()];
  }

  const words = normalized.split(" ");
  const lines: string[] = [];
  let current = prefix;

  words.forEach((word) => {
    const next = current.trim().length === 0 ? word : `${current} ${word}`.trim();
    if (next.length > 20 && current.trim().length > prefix.trim().length) {
      lines.push(current.trim());
      current = word;
      return;
    }
    current = next;
  });

  if (current.trim()) {
    lines.push(current.trim());
  }

  return lines.slice(0, 3);
}

function polarToCartesian(centerX: number, centerY: number, radius: number, angleInDegrees: number) {
  const angleInRadians = ((angleInDegrees - 90) * Math.PI) / 180;
  return {
    x: centerX + radius * Math.cos(angleInRadians),
    y: centerY + radius * Math.sin(angleInRadians),
  };
}

function describeArcPath(centerX: number, centerY: number, radius: number, startAngle: number, endAngle: number) {
  const start = polarToCartesian(centerX, centerY, radius, endAngle);
  const end = polarToCartesian(centerX, centerY, radius, startAngle);
  const largeArcFlag = endAngle - startAngle <= 180 ? "0" : "1";
  return `M ${centerX} ${centerY} L ${start.x} ${start.y} A ${radius} ${radius} 0 ${largeArcFlag} 0 ${end.x} ${end.y} Z`;
}

export function OperationAnalyticsSection({
  operation,
  analytics,
  contactCount,
  isLoading,
  view,
  onSaveSampleResponse,
}: OperationAnalyticsSectionProps) {
  const [groupedChartTooltip, setGroupedChartTooltip] = useState<GroupedChartTooltip>(null);
  const [donutChartHover, setDonutChartHover] = useState<DonutChartHover>(null);
  const [donutLegendPages, setDonutLegendPages] = useState<Record<string, number>>({});
  const [copyFeedback, setCopyFeedback] = useState<Record<string, string>>({});
  const [copyingKey, setCopyingKey] = useState<string | null>(null);
  const [isExportingPdf, setIsExportingPdf] = useState(false);
  const [sampleResponseDrafts, setSampleResponseDrafts] = useState<Record<string, string>>({});
  const [sampleResponseSavingKeys, setSampleResponseSavingKeys] = useState<Record<string, boolean>>({});
  const [sampleResponseStatus, setSampleResponseStatus] = useState<Record<string, string>>({});
  const chartCopyRefs = useRef<Record<string, HTMLElement | null>>({});
  const analyticsExportRef = useRef<HTMLDivElement | null>(null);
  const emptyState = getAnalyticsEmptyState(operation?.status ?? "Draft", contactCount);
  const kpis = getAnalyticsKpisCompact(operation, analytics);
  const demographicBreakdowns = analytics?.audienceBreakdowns ?? [];
  const questionGroups = (analytics?.questionGroups ?? [])
    .map((group) => {
      const rowIndexes = group.rows
        .map((row, index) => ({ row, index }))
        .sort((left, right) => left.row.questionOrder - right.row.questionOrder);

      return {
        ...group,
        rows: rowIndexes.map((item) => item.row),
        series: group.series.map((series) => ({
          ...series,
          data: rowIndexes.map((item) => series.data[item.index] ?? 0),
        })),
      };
    })
    .sort((left, right) => {
      const leftOrder = Math.min(...left.rows.map((row) => row.questionOrder));
      const rightOrder = Math.min(...right.rows.map((row) => row.questionOrder));
      return leftOrder - rightOrder;
    });
  const groupedQuestionIds = new Set(questionGroups.flatMap((group) => group.rows.map((row) => row.questionId)));
  const questionSummaries = (analytics?.questionSummaries ?? [])
    .filter((summary) => !groupedQuestionIds.has(summary.questionId))
    .slice()
    .sort((left, right) => left.questionOrder - right.questionOrder);
  const orderedQuestionItems = [
    ...questionGroups.map((group) => ({
      kind: "group" as const,
      order: Math.min(...group.rows.map((row) => row.questionOrder)),
      key: `group-${group.groupCode}`,
      group,
    })),
    ...questionSummaries.map((summary) => ({
      kind: "summary" as const,
      order: summary.questionOrder,
      key: `summary-${summary.questionId}`,
      summary,
    })),
  ].sort((left, right) => left.order - right.order);
  // Rebuild the editable sample-response drafts whenever analytics data
  // changes. Adjusted during render instead of in an effect.
  const [prevAnalytics, setPrevAnalytics] = useState(analytics);
  if (prevAnalytics !== analytics) {
    setPrevAnalytics(analytics);
    const nextDrafts: Record<string, string> = {};

    for (const summary of analytics?.questionSummaries ?? []) {
      for (const sample of summary.rawResponses) {
        nextDrafts[buildSampleResponseKey(summary.questionId, sample.callJobId)] = sample.responseText ?? "";
      }
    }

    setSampleResponseDrafts(nextDrafts);
  }

  const setChartCopyRef = (key: string) => (node: HTMLElement | null) => {
    chartCopyRefs.current[key] = node;
  };

  const handleCopyChart = async (key: string) => {
    const node = chartCopyRefs.current[key];
    if (!node || copyingKey === key) {
      return;
    }

    setCopyingKey(key);

    try {
      const blob = await toBlob(node, {
        cacheBust: true,
        pixelRatio: 2,
        backgroundColor: "#ffffff",
        filter: (currentNode) => {
          return !(currentNode instanceof HTMLElement && currentNode.dataset.chartCopyIgnore === "true");
        },
      });

      if (!blob) {
        throw new Error("Chart could not be rendered.");
      }

      if (navigator.clipboard && "write" in navigator && typeof ClipboardItem !== "undefined") {
        await navigator.clipboard.write([
          new ClipboardItem({
            [blob.type]: blob,
          }),
        ]);
        setCopyFeedback((current) => ({ ...current, [key]: "Kopyalandi" }));
      } else {
        throw new Error("Clipboard image API unavailable");
      }
    } catch {
      setCopyFeedback((current) => ({ ...current, [key]: "Kopyalanamadi" }));
    } finally {
      setCopyingKey(null);
      globalThis.setTimeout(() => {
        setCopyFeedback((current) => {
          const next = { ...current };
          delete next[key];
          return next;
        });
      }, 1800);
    }
  };

  const handleExportPdf = async () => {
    const node = analyticsExportRef.current;
    if (!node || isExportingPdf) {
      return;
    }

    let exportHost: HTMLDivElement | null = null;
    try {
      setIsExportingPdf(true);
      exportHost = document.createElement("div");
      exportHost.className = "operation-analytics-export-host";
      const exportNode = node.cloneNode(true) as HTMLDivElement;
      exportNode.classList.add("operation-analytics-export-mode");
      exportNode.style.width = "1280px";
      exportNode.style.maxWidth = "1280px";
      exportHost.appendChild(exportNode);
      document.body.appendChild(exportHost);

      await new Promise((resolve) => window.requestAnimationFrame(() => resolve(undefined)));

      const pdf = new jsPDF({
        orientation: "portrait",
        unit: "pt",
        format: "a4",
      });
      const margin = 24;
      const pageWidth = pdf.internal.pageSize.getWidth();
      const pageHeight = pdf.internal.pageSize.getHeight();
      const usableWidth = pageWidth - margin * 2;
      const usableHeight = pageHeight - margin * 2;
      let cursorY = margin;
      const exportBlocks = Array.from(
        exportNode.querySelectorAll<HTMLElement>(".operation-kpi-grid, .operation-chart-card, .operation-question-card, .operation-empty-state, .operation-audience-empty-state"),
      );
      const blocks = exportBlocks.length > 0 ? exportBlocks : [exportNode];

      for (let index = 0; index < blocks.length; index += 1) {
        const block = blocks[index];
        block.style.maxWidth = "none";
        block.style.width = `${Math.max(block.scrollWidth, block.clientWidth, 960)}px`;
        block.style.height = "auto";

        const imageDataUrl = await toPng(block, {
          cacheBust: true,
          pixelRatio: 2,
          backgroundColor: "#ffffff",
          width: Math.max(block.scrollWidth, block.clientWidth, 960),
          height: Math.max(block.scrollHeight, block.clientHeight),
          filter: (currentNode) => {
            return !(currentNode instanceof HTMLElement && currentNode.dataset.chartCopyIgnore === "true");
          },
        });

        const imageProps = pdf.getImageProperties(imageDataUrl);
        const renderHeight = (imageProps.height * usableWidth) / imageProps.width;

        if (cursorY > margin && cursorY + renderHeight > pageHeight - margin) {
          pdf.addPage();
          cursorY = margin;
        }

        if (renderHeight <= usableHeight) {
          pdf.addImage(imageDataUrl, "PNG", margin, cursorY, usableWidth, renderHeight, undefined, "FAST");
          cursorY += renderHeight + 14;
          continue;
        }

        let remainingHeight = renderHeight;
        let offsetY = cursorY;
        pdf.addImage(imageDataUrl, "PNG", margin, offsetY, usableWidth, renderHeight, undefined, "FAST");
        remainingHeight -= pageHeight - margin - offsetY;

        while (remainingHeight > 0) {
          pdf.addPage();
          offsetY = margin - (renderHeight - remainingHeight);
          pdf.addImage(imageDataUrl, "PNG", margin, offsetY, usableWidth, renderHeight, undefined, "FAST");
          remainingHeight -= usableHeight;
        }

        cursorY = margin + (remainingHeight <= 0 ? 14 : 0);
      }

      const operationSlug = (operation?.name?.trim() || "operasyon-sonuclari")
        .toLocaleLowerCase("tr-TR")
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-+|-+$/g, "");
      pdf.save(`${operationSlug || "operasyon-sonuclari"}-sonuclar.pdf`);
    } finally {
      exportHost?.remove();
      setIsExportingPdf(false);
    }
  };

  const handleSampleResponseChange = (sampleKey: string, value: string) => {
    setSampleResponseDrafts((current) => ({
      ...current,
      [sampleKey]: value,
    }));
  };

  const handleSaveOpenEndedResponse = async (
    sampleKey: string,
    callJobId: string,
    questionId: string,
    initialValue: string,
  ) => {
    if (!onSaveSampleResponse) {
      return;
    }

    const draftValue = (sampleResponseDrafts[sampleKey] ?? initialValue).trim();
    const normalizedInitialValue = initialValue.trim();
    if (draftValue === normalizedInitialValue) {
      return;
    }

    setSampleResponseSavingKeys((current) => ({ ...current, [sampleKey]: true }));
    setSampleResponseStatus((current) => {
      const next = { ...current };
      delete next[sampleKey];
      return next;
    });

    try {
      await onSaveSampleResponse({
        callJobId,
        questionId,
        responseText: draftValue,
      });
      setSampleResponseDrafts((current) => ({
        ...current,
        [sampleKey]: draftValue,
      }));
      setSampleResponseStatus((current) => ({ ...current, [sampleKey]: "Kaydedildi" }));
    } catch {
      setSampleResponseDrafts((current) => ({
        ...current,
        [sampleKey]: initialValue,
      }));
      setSampleResponseStatus((current) => ({ ...current, [sampleKey]: "Kaydedilemedi" }));
    } finally {
      setSampleResponseSavingKeys((current) => {
        const next = { ...current };
        delete next[sampleKey];
        return next;
      });
      globalThis.setTimeout(() => {
        setSampleResponseStatus((current) => {
          const next = { ...current };
          delete next[sampleKey];
          return next;
        });
      }, 1800);
    }
  };

  const renderEmptyState = () => (
    <div className="operation-empty-state operation-analytics-empty">
      <strong>{emptyState.title}</strong>
      <p>{emptyState.description}</p>
      {operation?.status === "Ready" && analytics ? (
        <div className="operation-analytics-preflight">
          <div className="operation-contact-status-card">
            <span>Toplam kisi</span>
            <strong>{analytics.totalContacts}</strong>
          </div>
          <div className="operation-contact-status-card">
            <span>Hazir is</span>
            <strong>{analytics.totalPreparedJobs}</strong>
          </div>
        </div>
      ) : null}
    </div>
  );

  const renderOutcomeBreakdown = () => (
    <article className="operation-chart-card">
      <div className="operation-chart-head">
        <div>
          <span className="operation-kicker">Durum dagilimi</span>
          <h3>Yurutme sonucu kirilimi</h3>
        </div>
        {analytics?.partialData ? <span className="operation-live-pill">Canli / Kismi</span> : null}
      </div>
      {analytics && analytics.outcomeBreakdown.some((item) => item.count > 0) ? (
        renderBarRows(analytics.outcomeBreakdown)
      ) : (
        <div className="operation-mini-empty">Dagilim olusmasi icin yurutme sonuclari bekleniyor.</div>
      )}
    </article>
  );

  const renderConsentBreakdown = () => (
    <article className="operation-consent-summary">
      <span className="operation-kicker">Katilim onayi</span>
      <div className="operation-consent-summary-metrics">
        <div className="operation-consent-summary-item">
          <span>Kabul</span>
          <strong>{analytics?.consentBreakdown.find((item) => item.key === "katilmayi-kabul-etti")?.count ?? 0}</strong>
        </div>
        <div className="operation-consent-summary-item is-danger">
          <span>Red</span>
          <strong>{analytics?.consentBreakdown.find((item) => item.key === "katilmayi-reddetti")?.count ?? 0}</strong>
        </div>
      </div>
    </article>
  );

  const renderInsights = () => (
    <article className="operation-chart-card">
      <div className="operation-chart-head">
        <div>
          <span className="operation-kicker">One cikan icgoruler</span>
          <h3>Ozet yorum</h3>
        </div>
      </div>
      <div className="operation-insight-card">
        <strong>{analytics?.insightSummary ?? "Bu operasyonda henuz ozet uretilecek kadar veri yok."}</strong>
        <p>
          {analytics?.insightSummary
            ? "Bu metin mevcut operasyon verisinden uretilen kisa bir yonlendirme ozeti sunar."
            : "Ilk cagri ve yanit kayitlari geldikce burada kisa operasyon ozeti gorunur."}
        </p>
      </div>
      {analytics && analytics.insightItems.length > 0 ? (
        <div className="operation-contact-glimpse-list">
          {analytics.insightItems.map((item) => (
            <div key={item.key} className="operation-contact-glimpse-item">
              <div>
                <strong>{item.title}</strong>
                <span>{item.detail}</span>
              </div>
              <span className="operation-live-pill">
                {item.tone === "warning" ? "Izle" : item.tone === "positive" ? "Pozitif" : "Hazir"}
              </span>
            </div>
          ))}
        </div>
      ) : null}
      <div className="operation-response-health">
        <div className="operation-response-health-item">
          <span>Cevap orani (kisi bazli)</span>
          <strong>%{analytics?.personResponseRate ?? 0}</strong>
        </div>
        <div className="operation-response-health-item">
          <span>Yanit veren kisi</span>
          <strong>{analytics?.respondedContacts ?? 0}</strong>
        </div>
        <div className="operation-response-health-item">
          <span>Tam yanit</span>
          <strong>{analytics?.completedResponses ?? 0}</strong>
        </div>
      </div>
    </article>
  );

  const renderQuestionInsights = () => (
    <div className="operation-question-stack">
      {orderedQuestionItems.length > 0 ? (
        <div className="operation-question-grid">
          {orderedQuestionItems.map((item) => {
            if (item.kind === "group") {
              const group = item.group;
              return (
                <article
                  key={item.key}
                  className="operation-chart-card operation-chart-card-grouped"
                  ref={setChartCopyRef(`group-${group.groupCode}`)}
                >
                  <div className="operation-chart-head">
                    <div>
                      <span className="operation-kicker">{group.chartKind === "GROUPED_MULTI_CHOICE" ? "Coklu secim tablosu" : "Tablo dagilimi"}</span>
                      <h3>{group.groupTitle}</h3>
                    </div>
                    <div className="operation-chart-head-actions">
                      <span className="operation-live-pill">{group.rows.length} satir</span>
                      <button
                        type="button"
                        className="operation-chart-copy-button"
                        onClick={() => handleCopyChart(`group-${group.groupCode}`)}
                        data-chart-copy-ignore="true"
                      >
                        <span className="operation-chart-copy-icon" aria-hidden="true">
                          <svg viewBox="0 0 20 20" fill="none">
                            <rect x="6" y="3" width="10" height="13" rx="2" />
                            <rect x="3" y="6" width="10" height="11" rx="2" />
                          </svg>
                        </span>
                        <span>
                          {copyingKey === `group-${group.groupCode}`
                            ? "Kopyalaniyor..."
                            : copyFeedback[`group-${group.groupCode}`] ?? "Grafigi kopyala"}
                        </span>
                      </button>
                    </div>
                  </div>
                  <div className="operation-question-stat-row operation-question-stat-row-grouped">
                    {group.optionSetCode ? <span className="operation-inline-filter-pill is-type">{group.optionSetCode}</span> : null}
                  </div>
                  {group.series.some((series) => series.data.some((value) => value > 0))
                    ? renderGroupedChoiceVisualization(group)
                    : <div className="operation-mini-empty">{group.emptyStateMessage ?? "Bu tablo icin grafik olusacak kadar veri yok."}</div>}
                </article>
              );
            }

            const summary = item.summary;
            const presentation = getQuestionChartPresentation(summary);
            const hasBreakdownData = summary.breakdown.some((breakdownItem) => breakdownItem.count > 0);
            const hasSpecialAnswerData = summary.specialAnswerBreakdown.some((breakdownItem) => breakdownItem.count > 0);
            const hasSampleData = summary.rawResponses.length > 0;
            const hasData = hasBreakdownData || hasSpecialAnswerData || hasSampleData;
            const shouldRenderSummaryChart = hasBreakdownData || hasSpecialAnswerData;

            return (
              <article
                key={item.key}
                className="operation-question-card"
                ref={setChartCopyRef(`question-${summary.questionId}`)}
              >
                <div className="operation-chart-head">
                  <div>
                    <span className="operation-kicker">{`Soru ${summary.questionOrder}`}</span>
                    <h3>{summary.questionTitle}</h3>
                  </div>
                  <div className="operation-chart-head-actions">
                    <span className="operation-question-meta">{summary.answeredCount} yanit</span>
                    {hasData ? (
                      <button
                        type="button"
                        className="operation-chart-copy-button"
                        onClick={() => handleCopyChart(`question-${summary.questionId}`)}
                        data-chart-copy-ignore="true"
                      >
                        <span className="operation-chart-copy-icon" aria-hidden="true">
                          <svg viewBox="0 0 20 20" fill="none">
                            <rect x="6" y="3" width="10" height="13" rx="2" />
                            <rect x="3" y="6" width="10" height="11" rx="2" />
                          </svg>
                        </span>
                        <span>
                          {copyingKey === `question-${summary.questionId}`
                            ? "Kopyalaniyor..."
                            : copyFeedback[`question-${summary.questionId}`] ?? "Grafigi kopyala"}
                        </span>
                      </button>
                    ) : null}
                  </div>
                </div>
                {summary.averageRating !== null ? (
                  <div className="operation-question-stat-row">
                    <span className="operation-inline-filter-pill is-emphasis">Ort. {summary.averageRating.toFixed(1)}</span>
                  </div>
                ) : null}
                {summary.chartKind === "OPEN_ENDED" ? (
                  <div className="operation-question-support-row">
                    <span>Grafige dahil edilen net yanit</span>
                    <strong>{summary.breakdown.reduce((sum, item) => sum + item.count, 0)}</strong>
                    <small>Ham cevap: {summary.rawResponses.length}</small>
                  </div>
                ) : null}
                {summary.dropOffCount > 0 ? (
                  <div className="operation-question-support-row">
                    <span>Bu sorudan sonra akistan cikan</span>
                    <strong>{summary.dropOffCount} kisi</strong>
                    <small>%{summary.dropOffRate}</small>
                  </div>
                ) : null}
                {summary.chartKind === "OPEN_ENDED"
                  ? renderOpenEndedWorkspace(summary, presentation.empty)
                  : shouldRenderSummaryChart
                    ? renderSummaryChart(summary)
                    : null}
                {!hasData && summary.chartKind !== "OPEN_ENDED" ? <div className="operation-mini-empty">{presentation.empty}</div> : null}
                {summary.specialAnswerBreakdown.length > 0 ? (
                  <div className="operation-question-special-answer-block">
                    <div className="operation-question-special-answer-head">
                      <strong>Ozel cevaplar</strong>
                      <span>Grafik disi tutulan yanitlar</span>
                    </div>
                    {renderBarRows(summary.specialAnswerBreakdown)}
                  </div>
                ) : null}
              </article>
            );
          })}
        </div>
      ) : (
        <div className="operation-mini-empty">Bu ankette henuz soru bazli gosterilecek cevap verisi yok.</div>
      )}
    </div>
  );

  const renderOpenEndedWorkspace = (
    summary: OperationAnalytics["questionSummaries"][number],
    emptyMessage: string,
  ) => (
    <div className="operation-open-ended-workspace">
      <div className="operation-open-ended-column">
        {summary.rawResponses.length > 0 ? (
          <div className={`operation-open-response-list${summary.rawResponses.length > 5 ? " is-scrollable" : ""}`}>
            {summary.rawResponses.map((response, index) => {
              const sampleKey = buildSampleResponseKey(summary.questionId, response.callJobId);
              const initialValue = response.responseText ?? "";
              const draftValue = sampleResponseDrafts[sampleKey] ?? initialValue;
              const isSaving = sampleResponseSavingKeys[sampleKey] ?? false;
              const statusMessage = sampleResponseStatus[sampleKey] ?? null;
              const isEditable = canSaveSampleResponse(response.callJobId);

              return (
                <div key={`${summary.questionId}-raw-${index}`} className="operation-open-response-item">
                  <textarea
                    className="operation-open-response-editor"
                    value={draftValue}
                    onChange={(event) => handleSampleResponseChange(sampleKey, event.target.value)}
                    onBlur={() => void handleSaveOpenEndedResponse(sampleKey, response.callJobId, summary.questionId, initialValue)}
                    rows={Math.max(2, Math.min(6, Math.ceil(((draftValue ?? "").length || 1) / 96)))}
                    readOnly={!isEditable}
                  />
                  <div className="operation-open-response-status">
                    <span>{isEditable && isSaving ? "Kaydediliyor..." : ""}</span>
                    {statusMessage ? <strong>{statusMessage}</strong> : null}
                  </div>
                </div>
              );
            })}
          </div>
        ) : (
          <div className="operation-mini-empty">Bu soru icin henuz ham cevap yok.</div>
        )}
      </div>
      <div className="operation-open-ended-column">
        {summary.breakdown.some((item) => item.count > 0) || summary.specialAnswerBreakdown.some((item) => item.count > 0)
          ? renderSummaryChart(summary)
          : <div className="operation-mini-empty">{emptyMessage}</div>}
      </div>
    </div>
  );

  const renderAudienceDistribution = () => (
    <div className="operation-analytics-stack">
      <div className="operation-analytics-grid operation-analytics-grid-audience">
        <article className="operation-chart-card">
          <div className="operation-chart-head">
            <div>
              <span className="operation-kicker">Demografik dagilim</span>
              <h3>Yanitlardan uretilen kirilimlar</h3>
            </div>
          </div>
          {demographicBreakdowns.length > 0 ? (
            <div className="operation-question-grid">
              {demographicBreakdowns.map((group) => (
                <article key={group.key} className="operation-question-card">
                  <div className="operation-chart-head">
                    <div>
                      <span className="operation-kicker">{group.questionCode}</span>
                      <h3>{group.label}</h3>
                    </div>
                    <span className="operation-question-meta">{group.answeredCount} yanit</span>
                  </div>
                  {group.breakdown.length > 0 ? renderAudienceChart(group.breakdown, group.answeredCount, group.label) : (
                    <div className="operation-mini-empty">Bu soru icin dagilim olusturacak kadar gecerli cevap bulunmuyor.</div>
                  )}
                </article>
              ))}
            </div>
          ) : (
            <div className="operation-audience-empty-state">
              <strong>Demografik kirilim olusmadi</strong>
              <p>Bu operasyona bagli ankette yas, sehir veya cinsiyet gibi demografik soru bulunmuyor ya da cevaplar kirilim uretmek icin yeterli degil.</p>
              <div className="operation-response-health operation-response-health-extended">
                <div className="operation-response-health-item">
                  <span>Hedef kitle</span>
                  <strong>{operation?.surveyAudience?.trim() || "Tanimsiz"}</strong>
                </div>
                <div className="operation-response-health-item">
                  <span>Toplam yanit</span>
                  <strong>{analytics?.totalResponses ?? 0}</strong>
                </div>
                <div className="operation-response-health-item">
                  <span>Demografik soru</span>
                  <strong>0</strong>
                </div>
              </div>
            </div>
          )}
        </article>

        <article className="operation-chart-card">
          <div className="operation-chart-head">
            <div>
              <span className="operation-kicker">Kitle baglami</span>
              <h3>Bagli anket ozetleri</h3>
            </div>
          </div>
          <div className="operation-contact-glimpse-list">
            <div className="operation-contact-glimpse-item">
              <div>
                <strong>Demografik soru durumu</strong>
                <span>{demographicBreakdowns.length > 0 ? `${demographicBreakdowns.length} demografik soru analytics'e baglandi.` : "Analytics'te demografik soru tespit edilmedi."}</span>
              </div>
            </div>
            <div className="operation-contact-glimpse-item">
              <div>
                <strong>Hedef kitle</strong>
                <span>{operation?.surveyAudience?.trim() || "Hedef kitle bilgisi henuz tanimli degil."}</span>
              </div>
            </div>
            <div className="operation-contact-glimpse-item">
              <div>
                <strong>Anket hedefi</strong>
                <span>{operation?.surveyGoal?.trim() || "Anket hedef ozeti tanimlanmamis."}</span>
              </div>
            </div>
            <div className="operation-contact-glimpse-item">
              <div>
                <strong>Operasyon sahibi</strong>
                <span>{operation?.owner?.trim() || "Sahip bilgisi bulunmuyor."}</span>
              </div>
            </div>
          </div>
        </article>
      </div>
    </div>
  );

  const renderOverview = () => (
    <div className="operation-analytics-stack">
      {renderConsentBreakdown()}
      {renderQuestionInsights()}
    </div>
  );

  const renderHealthMetrics = () => (
    <article className="operation-chart-card">
      <div className="operation-chart-head">
        <div>
          <span className="operation-kicker">Saglik gostergeleri</span>
          <h3>Cagri operasyon durumu</h3>
        </div>
      </div>
      <div className="operation-response-health operation-response-health-extended">
        <div className="operation-response-health-item">
          <span>Hazirlanan is</span>
          <strong>{analytics?.totalPreparedJobs ?? 0}</strong>
        </div>
        <div className="operation-response-health-item">
          <span>Cagri denemesi</span>
          <strong>{analytics?.totalCallsAttempted ?? 0}</strong>
        </div>
        <div className="operation-response-health-item">
          <span>Kuyrukta</span>
          <strong>{analytics?.queuedJobs ?? 0}</strong>
        </div>
        <div className="operation-response-health-item">
          <span>Aktif is</span>
          <strong>{analytics?.inProgressJobs ?? 0}</strong>
        </div>
        <div className="operation-response-health-item">
          <span>Basarisiz cagrilar</span>
          <strong>{analytics?.failedCallJobs ?? 0}</strong>
        </div>
        <div className="operation-response-health-item">
          <span>Atlanan cagrilar</span>
          <strong>{analytics?.skippedCallJobs ?? 0}</strong>
        </div>
      </div>
    </article>
  );

  const renderHealthStatus = () => (
    <article className="operation-chart-card">
      <div className="operation-chart-head">
        <div>
          <span className="operation-kicker">Oran ozeti</span>
          <h3>Temas ve yanit kalitesi</h3>
        </div>
      </div>
      <div className="operation-response-health operation-response-health-extended">
        <div className="operation-response-health-item">
          <span>Cevap orani (kisi bazli)</span>
          <strong>%{analytics?.personResponseRate ?? 0}</strong>
        </div>
        <div className="operation-response-health-item">
          <span>Yanit veren kisi</span>
          <strong>{analytics?.respondedContacts ?? 0}</strong>
        </div>
        <div className="operation-response-health-item">
          <span>Tamamlama orani (yanit bazli)</span>
          <strong>%{analytics?.completionRate ?? 0}</strong>
        </div>
        <div className="operation-response-health-item">
          <span>Tam yanit</span>
          <strong>{analytics?.completedResponses ?? 0}</strong>
        </div>
        <div className="operation-response-health-item">
          <span>Kismi yanit</span>
          <strong>{analytics?.partialResponses ?? 0}</strong>
        </div>
        <div className="operation-response-health-item">
          <span>Ortalama soru tamamlama</span>
          <strong>%{analytics?.averageCompletionPercent ?? 0}</strong>
        </div>
      </div>
    </article>
  );

  const renderHealth = () => (
    <div className="operation-analytics-stack">
      <div className="operation-kpi-grid">
        {kpis.map((item) => (
          <KpiCard
            key={item.label}
            label={item.label}
            value={item.value}
            detail={item.detail}
            tone={item.tone}
          />
        ))}
      </div>
      <div className="operation-analytics-grid">
        {renderOutcomeBreakdown()}
        {renderInsights()}
      </div>
      <div className="operation-analytics-grid">
        {renderHealthMetrics()}
        {renderHealthStatus()}
      </div>
    </div>
  );

  const renderBody = () => {
    if (isLoading) {
      return (
        <div className="operation-empty-state">
          <strong>Analitik yukleniyor</strong>
          <p>Operasyona ait cagri ve yanit toplamlari getiriliyor.</p>
        </div>
      );
    }

    if (!analytics || (analytics.totalResponses === 0 && analytics.totalPreparedJobs === 0)) {
      return renderEmptyState();
    }

    switch (view) {
      case "health":
        return renderHealth();
      case "questions":
        return renderQuestionInsights();
      case "audience":
        return renderAudienceDistribution();
      case "overview":
      default:
        return renderOverview();
    }
  };

  function renderSummaryChart(summary: OperationAnalytics["questionSummaries"][number]) {
    switch (summary.chartKind) {
      case "CHOICE":
      case "BINARY":
        return renderDonutChart(summary.breakdown, summary.answeredCount, summary.questionTitle);
      case "RATING":
        return renderRatingScaleChart(summary.breakdown, summary.answeredCount, summary.averageRating, summary.questionTitle);
      case "MULTI_CHOICE":
        return renderDonutChart(
          summary.breakdown,
          summary.breakdown.reduce((sum, item) => sum + item.count, 0),
          summary.questionTitle,
        );
      case "OPEN_ENDED":
        if (summary.breakdown.some((item) => item.count > 0)) {
          return summary.breakdown.length <= 5
            ? renderDonutChart(
              summary.breakdown,
              summary.breakdown.reduce((sum, item) => sum + item.count, 0),
              summary.questionTitle,
            )
            : renderBarRows(summary.breakdown);
        }
        if (summary.specialAnswerBreakdown.some((item) => item.count > 0)) {
          return renderOpenEndedState("Bu soruda sadece ozel cevaplar kaydedildi.");
        }
        return renderOpenEndedState();
      default:
        return renderOpenEndedState();
    }
  }

  function renderGroupedChoiceVisualization(group: OperationAnalytics["questionGroups"][number]) {
    const activeSeries = group.series.filter((item) => item.data.some((value) => value > 0));
    const chartSeries = activeSeries.length > 0 ? activeSeries : group.series;
    return renderGroupedChoiceChart(group, chartSeries);
  }

  function renderGroupedChoiceChart(
    group: OperationAnalytics["questionGroups"][number],
    chartSeries: OperationAnalytics["questionGroups"][number]["series"],
  ) {
    const maxValue = Math.max(0, ...chartSeries.flatMap((item) => item.data));
    const ticks = buildAxisTicks(maxValue);
    const chartHeight = GROUP_CHART_HEIGHT - GROUP_CHART_TOP_GUTTER - GROUP_CHART_BOTTOM_GUTTER;
    const rowBandWidth = GROUP_CHART_COLUMN_WIDTH;
    const barCount = Math.max(chartSeries.length, 1);
    const totalBarGap = GROUP_CHART_BAR_GAP * Math.max(0, barCount - 1);
    const barWidth = Math.max(10, Math.min(24, (rowBandWidth - totalBarGap) / barCount));
    const plotWidth = group.rows.length * rowBandWidth + Math.max(0, group.rows.length - 1) * GROUP_CHART_GROUP_GAP;
    const svgWidth = GROUP_CHART_LEFT_GUTTER + plotWidth + 12;
    const tooltipWidth = 188;
    const tooltipHeight = 58;
    const activeTooltip = groupedChartTooltip?.groupCode === group.groupCode ? groupedChartTooltip : null;

    return (
      <div className="operation-grouped-chart">
        {renderGroupedChartLegend(group, chartSeries)}
        <div className="operation-grouped-chart-scroll">
          <svg
            className="operation-grouped-chart-svg"
            width={svgWidth}
            height={GROUP_CHART_HEIGHT}
            viewBox={`0 0 ${svgWidth} ${GROUP_CHART_HEIGHT}`}
            role="img"
            aria-label={`${group.groupTitle} cevap dagilim grafigi`}
          >
            {ticks.map((tick) => {
              const y = GROUP_CHART_TOP_GUTTER + chartHeight - ((maxValue > 0 ? tick / ticks[ticks.length - 1] : 0) * chartHeight);
              return (
                <g key={`${group.groupCode}-tick-${tick}`}>
                  <line
                    x1={GROUP_CHART_LEFT_GUTTER}
                    x2={svgWidth - 6}
                    y1={y}
                    y2={y}
                    className="operation-grouped-chart-gridline"
                  />
                  <text x={GROUP_CHART_LEFT_GUTTER - 8} y={y + 4} textAnchor="end" className="operation-grouped-chart-tick">
                    {tick}
                  </text>
                </g>
              );
            })}
            {group.rows.map((row, rowIndex) => {
              const groupStart = GROUP_CHART_LEFT_GUTTER + rowIndex * (rowBandWidth + GROUP_CHART_GROUP_GAP);
              const barsWidth = barCount * barWidth + totalBarGap;
              const startX = groupStart + (rowBandWidth - barsWidth) / 2;
              const labelX = groupStart + rowBandWidth / 2;
              const labelLines = buildGroupedLabelLines(row.questionOrder, row.rowLabel);
              const labelBaseY = GROUP_CHART_HEIGHT - 48 - Math.max(0, (labelLines.length - 1) * 11);

              return (
                <g key={`${group.groupCode}-${row.rowKey}`}>
                  {chartSeries.map((item, seriesIndex) => {
                    const value = item.data[rowIndex] ?? 0;
                    const height = maxValue > 0 ? (value / ticks[ticks.length - 1]) * chartHeight : 0;
                    const x = startX + seriesIndex * (barWidth + GROUP_CHART_BAR_GAP);
                    const y = GROUP_CHART_TOP_GUTTER + chartHeight - height;
                    return (
                      <g key={`${row.rowKey}-${item.key}`}>
                        <rect
                          x={x}
                          y={y}
                          width={barWidth}
                          height={Math.max(height, value > 0 ? 6 : 0)}
                          rx={Math.max(3, barWidth / 6)}
                          fill={CHART_COLORS[seriesIndex % CHART_COLORS.length]}
                          className="operation-grouped-chart-bar"
                          onMouseEnter={() => {
                            setGroupedChartTooltip({
                              groupCode: group.groupCode,
                              x: x + barWidth / 2,
                              y: Math.max(y - 14, 18),
                              rowLabel: row.rowLabel,
                              seriesLabel: item.label,
                              value,
                            });
                          }}
                          onMouseLeave={() => setGroupedChartTooltip(null)}
                        >
                        </rect>
                        {value > 0 ? (
                          <text x={x + barWidth / 2} y={Math.max(y - 8, GROUP_CHART_TOP_GUTTER + 10)} textAnchor="middle" className="operation-grouped-chart-value">
                            {value}
                          </text>
                        ) : null}
                      </g>
                    );
                  })}
                  <text x={labelX} y={labelBaseY} textAnchor="middle" className="operation-grouped-chart-label">
                    {labelLines.map((line, lineIndex) => (
                      <tspan key={`${row.rowKey}-label-${lineIndex}`} x={labelX} dy={lineIndex === 0 ? 0 : 11}>
                        {line}
                      </tspan>
                    ))}
                  </text>
                  <text x={labelX} y={GROUP_CHART_HEIGHT - 16} textAnchor="middle" className="operation-grouped-chart-sublabel">
                    {`${row.answeredCount} yanit • %${formatCompactPercent(row.responseRate)} kapsama`}
                  </text>
                </g>
              );
            })}
            {activeTooltip ? (
              <g
                transform={`translate(${Math.max(10, Math.min(activeTooltip.x - tooltipWidth / 2, svgWidth - tooltipWidth - 10))}, ${Math.max(8, activeTooltip.y - tooltipHeight)})`}
                className="operation-grouped-chart-tooltip-svg"
              >
                <rect
                  width={tooltipWidth}
                  height={tooltipHeight}
                  rx={12}
                  className="operation-grouped-chart-tooltip-bg"
                />
                <text x={16} y={24} className="operation-grouped-chart-tooltip-title">
                  {activeTooltip.rowLabel}
                </text>
                <text x={16} y={44} className="operation-grouped-chart-tooltip-copy">
                  {`${activeTooltip.seriesLabel}: ${activeTooltip.value}`}
                </text>
              </g>
            ) : null}
          </svg>
        </div>
      </div>
    );
  }

  function renderGroupedChartLegend(
    group: OperationAnalytics["questionGroups"][number],
    chartSeries: OperationAnalytics["questionGroups"][number]["series"],
  ) {
    return (
      <div className="operation-grouped-chart-legend">
        {chartSeries.map((item, index) => (
          <div key={`${group.groupCode}-${item.key}`} className="operation-grouped-chart-legend-item">
            <span className="operation-grouped-chart-legend-swatch" style={{ backgroundColor: CHART_COLORS[index % CHART_COLORS.length] }} />
            <strong>{item.label}</strong>
          </div>
        ))}
      </div>
    );
  }

  function renderAudienceChart(
    breakdown: OperationAnalytics["audienceBreakdowns"][number]["breakdown"],
    answeredCount: number,
    title: string,
  ) {
    return breakdown.length <= 5 ? renderDonutChart(breakdown, answeredCount, title) : renderBarRows(breakdown);
  }

  function renderDonutChart(
    breakdown: Array<{ label: string; count: number; percentage: number }>,
    total: number,
    title: string,
  ) {
    const visibleItems = breakdown.filter((item) => item.count > 0);
    const chartKey = `${title}-${total}`;
    const size = 196;
    const center = size / 2;
    const radius = 96;
    const tooltipWidth = 154;
    const tooltipHeight = 54;
    const legendPageCount = Math.max(1, Math.ceil(visibleItems.length / DONUT_LEGEND_PAGE_SIZE));
    const legendPage = Math.min(donutLegendPages[chartKey] ?? 0, legendPageCount - 1);
    const pagedLegendItems = legendPageCount > 1
      ? visibleItems.slice(legendPage * DONUT_LEGEND_PAGE_SIZE, (legendPage + 1) * DONUT_LEGEND_PAGE_SIZE)
      : visibleItems;

    return (
      <div className="operation-summary-chart-grid">
        <div className="operation-summary-donut-chart" aria-label={`${title} dagilim grafigi`}>
          <svg className="operation-summary-donut-svg" width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img">
            {visibleItems.length > 0 ? (
              visibleItems.reduce<{ startAngle: number; nodes: ReactElement[] }>((acc, item, index) => {
                const sliceAngle = (item.percentage / 100) * 360;
                const endAngle = acc.startAngle + sliceAngle;
                const midAngle = acc.startAngle + sliceAngle / 2;
                const color = CHART_COLORS[index % CHART_COLORS.length];
                const isActive = donutChartHover?.chartKey === chartKey && donutChartHover.label === item.label;
                const tooltipAnchor = polarToCartesian(center, center, radius + 12, midAngle);
                acc.nodes.push(
                  <path
                    key={`${chartKey}-${item.label}`}
                    d={describeArcPath(center, center, radius, acc.startAngle, endAngle)}
                    fill={color}
                    className={`operation-summary-donut-slice${isActive ? " is-active" : ""}`}
                    onMouseEnter={() => {
                      setDonutChartHover({
                        chartKey,
                        label: item.label,
                        count: item.count,
                        percentage: item.percentage,
                        color,
                        x: tooltipAnchor.x,
                        y: tooltipAnchor.y,
                      });
                    }}
                    onMouseLeave={() => setDonutChartHover(null)}
                  />,
                );
                acc.startAngle = endAngle;
                return acc;
              }, { startAngle: 0, nodes: [] }).nodes
            ) : (
              <circle cx={center} cy={center} r={radius} fill="#dbe7f2" />
            )}
            {donutChartHover?.chartKey === chartKey ? (
              <g
                transform={`translate(${Math.max(4, Math.min(donutChartHover.x - tooltipWidth / 2, size - tooltipWidth - 4))}, ${Math.max(2, Math.min(donutChartHover.y - tooltipHeight - 10, size - tooltipHeight - 4))})`}
                className="operation-summary-donut-tooltip"
                pointerEvents="none"
              >
                <rect width={tooltipWidth} height={tooltipHeight} rx={10} className="operation-summary-donut-tooltip-bg" />
                <text x={12} y={21} className="operation-summary-donut-tooltip-title">
                  {donutChartHover.label}
                </text>
                <text x={12} y={39} className="operation-summary-donut-tooltip-copy">
                  {`${donutChartHover.count} (${formatCompactPercent(donutChartHover.percentage)}%)`}
                </text>
              </g>
            ) : null}
          </svg>
        </div>
        <div className="operation-summary-chart-stack">
          <div className="operation-summary-chart-legend">
            {pagedLegendItems.map((item) => {
              const colorIndex = visibleItems.findIndex((visibleItem) => visibleItem.label === item.label);
              return (
              <div key={`${title}-${item.label}`} className="operation-summary-legend-row">
                <span className="operation-summary-legend-dot" style={{ backgroundColor: CHART_COLORS[colorIndex % CHART_COLORS.length] }} />
                <strong>{item.label}</strong>
                <span>{item.count}</span>
                <span>%{item.percentage}</span>
              </div>
              );
            })}
          </div>
          {legendPageCount > 1 ? (
            <div className="operation-summary-legend-pager" aria-label="Pie chart legend sayfalama">
              <button
                type="button"
                className="operation-summary-legend-pager-button"
                onClick={() => setDonutLegendPages((current) => ({ ...current, [chartKey]: Math.max(0, legendPage - 1) }))}
                disabled={legendPage === 0}
                aria-label="Onceki legend sayfasi"
              >
                ▲
              </button>
              <span className="operation-summary-legend-pager-label">{legendPage + 1}/{legendPageCount}</span>
              <button
                type="button"
                className="operation-summary-legend-pager-button is-next"
                onClick={() => setDonutLegendPages((current) => ({ ...current, [chartKey]: Math.min(legendPageCount - 1, legendPage + 1) }))}
                disabled={legendPage === legendPageCount - 1}
                aria-label="Sonraki legend sayfasi"
              >
                ▼
              </button>
            </div>
          ) : null}
        </div>
      </div>
    );
  }

  function renderRatingScaleChart(
    breakdown: Array<{ label: string; count: number; percentage: number }>,
    total: number,
    averageRating: number | null,
    title: string,
  ) {
    const score = averageRating !== null ? Math.max(0, Math.min(10, averageRating)) : null;

    return (
      <div className="operation-rating-scale-chart">
        <div className="operation-rating-scale-stat operation-rating-scale-stat-single">
          <div className="operation-rating-scale-copy">
            <span>Ortalama puan</span>
            <div className="operation-rating-scale-scoreline">
              <strong>{score !== null ? score.toFixed(1) : "-"}</strong>
              <small>/10</small>
            </div>
          </div>
          <div className="operation-rating-scale-meter" aria-label="Ortalama puan olcegi">
            <div className="operation-rating-scale-meter-track">
              <div
                className="operation-rating-scale-meter-fill"
                style={{ width: `${score !== null ? score * 10 : 0}%` }}
              />
            </div>
            <div className="operation-rating-scale-meter-ticks">
              <span>1</span>
              <span>5</span>
              <span>10</span>
            </div>
          </div>
        </div>
        {breakdown.length > 0 ? renderDonutChart(breakdown, total, title) : null}
      </div>
    );
  }

  function renderBarRows(items: Array<{ label: string; count: number; percentage: number }>) {
    return (
      <div className="operation-bar-list compact">
        {items.map((item, index) => (
          <div key={`${item.label}-${index}`} className="operation-bar-row">
            <div className="operation-bar-copy">
              <strong>{item.label}</strong>
              <span>{item.count}</span>
            </div>
            <div className="operation-bar-track">
              <div
                className="operation-bar-fill"
                style={{
                  width: `${Math.max(item.percentage, item.count > 0 ? 8 : 0)}%`,
                  background: `linear-gradient(90deg, ${CHART_COLORS[index % CHART_COLORS.length]}, rgba(185, 215, 251, 0.98))`,
                }}
              />
            </div>
            <span className="operation-bar-value">%{item.percentage}</span>
          </div>
        ))}
      </div>
    );
  }

  function renderOpenEndedState(message = "Bu soru icin henuz gosterilecek dagilim yok.") {
    return (
      <div className="operation-mini-empty">
        {message}
      </div>
    );
  }

  return (
    <SectionCard
      action={(
        <button type="button" className="button-secondary compact-button" onClick={() => void handleExportPdf()} disabled={isExportingPdf}>
          {isExportingPdf ? "PDF hazirlaniyor..." : "PDF indir"}
        </button>
      )}
    >
      <div ref={analyticsExportRef}>
        {renderBody()}
      </div>
    </SectionCard>
  );
}

function buildSampleResponseKey(questionId: string, callJobId: string) {
  return `${questionId}:${callJobId}`;
}

function canSaveSampleResponse(callJobId: string) {
  return Boolean(callJobId) && !callJobId.startsWith("legacy-");
}
