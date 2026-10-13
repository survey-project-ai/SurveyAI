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
    title: operation ? `${operation.name} Sonuç Analizi` : "Araştırma Sonuç Analizi",
    subtitle: operation
      ? `${operation.survey} anketinin amaç odaklı yorumları ve segment bulguları.`
      : "Seçilen operasyon için amaç odaklı analiz hazırlanıyor.",
  });

  useEffect(() => {
    const controller = new AbortController();

    async function load() {
      if (!operationId) {
        setErrorMessage("Operasyon kimliği bulunamadı.");
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
        setErrorMessage(error instanceof Error ? error.message : "Analiz detayı yüklenemedi.");
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
        <Link href="/analytics" className="button-secondary compact-button">Analitik listeye dön</Link>
        {operation ? <Link href={`/operations/${operation.id}`} className="button-secondary compact-button">Operasyon detayı</Link> : null}
      </div>

      {errorMessage ? <EmptyState title="Analiz yüklenemedi" description={errorMessage} tone="danger" /> : null}
      {isLoading ? <EmptyState title="Sonuç analizi hazırlanıyor" description="Soru ve segment bulguları işleniyor." /> : null}

      {!isLoading && !errorMessage && !result ? (
        <EmptyState title="Yeterli veri yok" description="Bu operasyon için yorumlanabilir sonuç analizi oluşturmak adına yanıt sayısı yetersiz." />
      ) : null}

      {!isLoading && !errorMessage && result ? (
        <>
          <SectionCard title="Araştırma Amacı ve Yönetici Özeti" description="Anketin hedefi ile elde edilen temel sonucu tek bakışta görüntüleyin.">
            <div className="analytics-ai-layout">
              <div className="analytics-ai-block">
                <span className="eyebrow">Araştırma Amacı</span>
                <p>{result.goal}</p>
              </div>
              <div className="analytics-ai-summary-card">
                <strong>Yönetici Özeti</strong>
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
              <h3>Segment Yorumları</h3>
              <ul className="analytics-ai-list">
                {result.segmentFindings.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </article>

            <article className="analytics-ai-card">
              <h3>Odak Aksiyonları</h3>
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

  const goal = operation.surveyGoal?.trim() || `${operation.survey} anketi için seçmen beklenti ve tercihlerini anlamak.`;
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
      `${item.questionTitle} sorusunda en güçlü sinyal "${top.label}" (%${top.percentage.toFixed(1)} / ${top.count} kişi).`,
    );
  }
  if (openEnded[0]?.breakdown[0]) {
    const topTheme = openEnded[0].breakdown[0];
    keyFindings.push(
      `Açık uçlu yorumlarda en çok tekrar eden tema "${topTheme.label}" (%${topTheme.percentage.toFixed(1)}).`,
    );
  }
  if (keyFindings.length === 0) {
    keyFindings.push("Bu operasyon için yorumlanabilir dağılım sinyali sınırlı; daha fazla tamamlanmış yanıt gerekli.");
  }

  const segmentFindings: string[] = [];
  for (const segment of topAudienceBreakdowns) {
    const top = segment.breakdown[0];
    if (!top) {
      continue;
    }
    segmentFindings.push(
      `${segment.questionTitle} kırılımında "${top.label}" grubu öne çıkıyor (%${top.percentage.toFixed(1)}).`,
    );
  }
  if (segmentFindings.length === 0) {
    segmentFindings.push("Demografik kırılım verisi sınırlı olduğu için segment bazlı çıkarsama şu an düşük güvende.");
  }

  const recommendations: string[] = [];
  if (openEnded[0]?.breakdown[0]) {
    recommendations.push(
      `Mesajlamada "${openEnded[0].breakdown[0].label}" temasını birincil vaat başlığına alın.`,
    );
  }
  if (topAudienceBreakdowns[0]?.breakdown[0]) {
    recommendations.push(
      `Saha iletişim planında önce "${topAudienceBreakdowns[0].breakdown[0].label}" segmentine odaklı içerik çıkarın.`,
    );
  }
  if (analytics.responseRate < 35) {
    recommendations.push("Yanıtsız segmentlerde ek arama penceresi açarak örneklem temsil gücünü artırın.");
  } else {
    recommendations.push("Mevcut örneklemle aday/tema testlerini mikro segmentlerde A/B mesaj denemesine taşıyın.");
  }

  const topQuestion = strongestQuestions[0];
  const topSignal = topQuestion?.breakdown[0];
  const executiveSummary = topQuestion && topSignal
    ? `Araştırma amacına en güçlü katkıda bulunan bulgu "${topQuestion.questionTitle}" sorusundaki "${topSignal.label}" eğilimi oldu. Bu sinyalin hedef segment kırılımlarıyla beraber mesaj prioritesine alınması önerilir.`
    : "Araştırma amacına yönelik sinyal oluşuyor; daha net kararlar için soru bazlı tamamlanmış yanıt hacmini biraz daha artırmak faydalı olur.";

  return {
    goal,
    executiveSummary,
    keyFindings: keyFindings.slice(0, 4),
    segmentFindings: segmentFindings.slice(0, 4),
    recommendations: recommendations.slice(0, 4),
  };
}
