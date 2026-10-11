"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { PageContainer } from "@/components/layout/PageContainer";
import { usePageHeaderOverride } from "@/components/layout/PageHeaderContext";
import { EmptyState } from "@/components/ui/EmptyState";
import { SectionCard } from "@/components/ui/SectionCard";
import { fetchOperationAnalytics, fetchOperationById } from "@/lib/operations";
import type { Operation, OperationAnalytics } from "@/lib/types";

type ResearchAnalysis = {
  goal: string;
  executiveSummary: string;
  keyFindings: string[];
  segmentFindings: string[];
  recommendations: string[];
};

export default function AnalyticsDetailPage() {
  const params = useParams<{ id: string }>();
  const operationId = params?.id;
  const [operation, setOperation] = useState<Operation | null>(null);
  const [analytics, setAnalytics] = useState<OperationAnalytics | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  usePageHeaderOverride({
    title: operation ? `${operation.name} Sonuc Analizi` : "Arastirma Sonuc Analizi",
    subtitle: operation
      ? `${operation.survey} anketinin amac odakli yorumlari ve segment bulgulari.`
      : "Secilen operasyon icin amac odakli analiz hazirlaniyor.",
  });

  useEffect(() => {
    const controller = new AbortController();

    async function load() {
      if (!operationId) {
        setErrorMessage("Operasyon kimligi bulunamadi.");
        setIsLoading(false);
        return;
      }

      try {
        setIsLoading(true);
        setErrorMessage(null);
        const [nextOperation, nextAnalytics] = await Promise.all([
          fetchOperationById(operationId, undefined, { signal: controller.signal }),
          fetchOperationAnalytics(operationId, undefined, { signal: controller.signal }),
        ]);
        setOperation(nextOperation);
        setAnalytics(nextAnalytics);
      } catch (error) {
        if (controller.signal.aborted) {
          return;
        }
        setErrorMessage(error instanceof Error ? error.message : "Analiz detayi yuklenemedi.");
      } finally {
        if (!controller.signal.aborted) {
          setIsLoading(false);
        }
      }
    }

    void load();
    return () => controller.abort();
  }, [operationId]);

  const result = useMemo(() => {
    if (!operation || !analytics) {
      return null;
    }
    return buildResearchAnalysis(operation, analytics);
  }, [operation, analytics]);

  return (
    <PageContainer hideBackRow>
      <div className="analytics-detail-top-row">
        <Link href="/analytics" className="button-secondary compact-button">Analitik listeye don</Link>
        {operation ? <Link href={`/operations/${operation.id}`} className="button-secondary compact-button">Operasyon detayi</Link> : null}
      </div>

      {errorMessage ? <EmptyState title="Analiz yuklenemedi" description={errorMessage} tone="danger" /> : null}
      {isLoading ? <EmptyState title="Sonuc analizi hazirlaniyor" description="Soru ve segment bulgulari isleniyor." /> : null}

      {!isLoading && !errorMessage && !result ? (
        <EmptyState title="Yeterli veri yok" description="Bu operasyon icin yorumlanabilir sonuc analizi olusturmak adina yanit sayisi yetersiz." />
      ) : null}

      {!isLoading && !errorMessage && result ? (
        <>
          <SectionCard title="Arastirma Amaci ve Yonetici Ozeti" description="Anketin hedefi ile elde edilen temel sonucu tek bakista goruntuleyin.">
            <div className="analytics-ai-layout">
              <div className="analytics-ai-block">
                <span className="eyebrow">Arastirma Amaci</span>
                <p>{result.goal}</p>
              </div>
              <div className="analytics-ai-summary-card">
                <strong>Yonetici Ozeti</strong>
                <p>{result.executiveSummary}</p>
              </div>
            </div>
          </SectionCard>

          <section className="analytics-ai-grid">
            <article className="analytics-ai-card">
              <h3>Kritik Bulgular</h3>
              <ul className="analytics-ai-list">
                {result.keyFindings.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </article>

            <article className="analytics-ai-card">
              <h3>Segment Yorumlari</h3>
              <ul className="analytics-ai-list">
                {result.segmentFindings.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </article>

            <article className="analytics-ai-card">
              <h3>Odak Aksiyonlari</h3>
              <ul className="analytics-ai-list">
                {result.recommendations.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </article>
          </section>
        </>
      ) : null}
    </PageContainer>
  );
}

function buildResearchAnalysis(operation: Operation, analytics: OperationAnalytics): ResearchAnalysis | null {
  if (analytics.totalResponses <= 0 || analytics.questionSummaries.length === 0) {
    return null;
  }

  const goal = operation.surveyGoal?.trim() || `${operation.survey} anketi icin secmen beklenti ve tercihlerini anlamak.`;
  const strongestQuestions = analytics.questionSummaries
    .filter((item) => item.answeredCount > 0 && item.breakdown.length > 0)
    .sort((a, b) => b.responseRate - a.responseRate)
    .slice(0, 2);
  const openEnded = analytics.questionSummaries
    .filter((item) => item.chartKind === "OPEN_ENDED" && item.breakdown.length > 0)
    .slice(0, 1);
  const topAudienceBreakdowns = analytics.audienceBreakdowns
    .filter((item) => item.breakdown.length > 0)
    .slice(0, 3);

  const keyFindings: string[] = [];
  for (const item of strongestQuestions) {
    const top = item.breakdown[0];
    if (!top) {
      continue;
    }
    keyFindings.push(
      `${item.questionTitle} sorusunda en guclu sinyal "${top.label}" (%${top.percentage.toFixed(1)} / ${top.count} kisi).`,
    );
  }
  if (openEnded[0]?.breakdown[0]) {
    const topTheme = openEnded[0].breakdown[0];
    keyFindings.push(
      `Acik uclu yorumlarda en cok tekrar eden tema "${topTheme.label}" (%${topTheme.percentage.toFixed(1)}).`,
    );
  }
  if (keyFindings.length === 0) {
    keyFindings.push("Bu operasyon icin yorumlanabilir dagilim sinyali sinirli; daha fazla tamamlanmis yanit gerekli.");
  }

  const segmentFindings: string[] = [];
  for (const segment of topAudienceBreakdowns) {
    const top = segment.breakdown[0];
    if (!top) {
      continue;
    }
    segmentFindings.push(
      `${segment.questionTitle} kiriliminda "${top.label}" grubu one cikiyor (%${top.percentage.toFixed(1)}).`,
    );
  }
  if (segmentFindings.length === 0) {
    segmentFindings.push("Demografik kirilim verisi sinirli oldugu icin segment bazli cikarsama su an dusuk guvende.");
  }

  const recommendations: string[] = [];
  if (openEnded[0]?.breakdown[0]) {
    recommendations.push(
      `Mesajlamada "${openEnded[0].breakdown[0].label}" temasini birincil vaat basligina alin.`,
    );
  }
  if (topAudienceBreakdowns[0]?.breakdown[0]) {
    recommendations.push(
      `Saha iletisim planinda once "${topAudienceBreakdowns[0].breakdown[0].label}" segmentine odakli icerik cikarin.`,
    );
  }
  if (analytics.responseRate < 35) {
    recommendations.push("Yanitsiz segmentlerde ek arama penceresi acarak orneklem temsil gucunu artirin.");
  } else {
    recommendations.push("Mevcut orneklemle aday/tema testlerini mikro segmentlerde A/B mesaj denemesine tasiyin.");
  }

  const topQuestion = strongestQuestions[0];
  const topSignal = topQuestion?.breakdown[0];
  const executiveSummary = topQuestion && topSignal
    ? `Arastirma amacina en guclu katkida bulunan bulgu "${topQuestion.questionTitle}" sorusundaki "${topSignal.label}" egilimi oldu. Bu sinyalin hedef segment kirilimlariyla beraber mesaj prioritesine alinmasi onerilir.`
    : "Arastirma amacina yonelik sinyal olusuyor; daha net kararlar icin soru bazli tamamlanmis yanit hacmini biraz daha artirmak faydali olur.";

  return {
    goal,
    executiveSummary,
    keyFindings: keyFindings.slice(0, 4),
    segmentFindings: segmentFindings.slice(0, 4),
    recommendations: recommendations.slice(0, 4),
  };
}
