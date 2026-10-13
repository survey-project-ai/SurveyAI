"use client";

import Link from "next/link";
import { notFound, useParams } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { usePageHeaderOverride } from "@/components/layout/PageHeaderContext";
import { PageContainer } from "@/components/layout/PageContainer";
import { SurveyBuilderShell } from "@/components/surveys/SurveyBuilderShell";
import { fetchSurveyBuilderSurvey } from "@/lib/survey-builder-api";
import type { SurveyBuilderSurvey } from "@/lib/types";

export default function SurveyEditPage() {
  const params = useParams<{ id: string }>();
  const surveyId = params.id;
  const [survey, setSurvey] = useState<SurveyBuilderSurvey | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isMissing, setIsMissing] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!surveyId) {
      return;
    }

    const controller = new AbortController();

    async function loadSurvey() {
      try {
        setIsLoading(true);
        setErrorMessage(null);
        setIsMissing(false);

        const result = await fetchSurveyBuilderSurvey(surveyId, undefined, { signal: controller.signal });
        setSurvey(result);
      } catch (error) {
        if (controller.signal.aborted) {
          return;
        }

        const message = error instanceof Error ? error.message : "Anket düzenleme alanı yüklenemedi.";
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

    void loadSurvey();
    return () => controller.abort();
  }, [surveyId]);

  if (isMissing) {
    notFound();
  }

  const headerAction = useMemo(
    () => (
      <Link href={`/surveys/${surveyId}`} className="button-secondary compact-button">
        Ankete dön
      </Link>
    ),
    [surveyId],
  );

  usePageHeaderOverride({
    title: survey?.name.trim() || "Anket Düzenle",
    subtitle: "Taslak anketin soru akışını, açılış metinlerini ve yayın hazırlığını tek ekranda güncelleyin.",
    action: headerAction,
  });

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

      {isLoading || !survey ? (
        <section className="panel-card">
          <div className="list-item">
            <div>
              <strong>Anket düzenleme alanı yükleniyor</strong>
              <span>Taslak akış ve soru yapısı hazırlanıyor.</span>
            </div>
          </div>
        </section>
      ) : (
        <SurveyBuilderShell initialSurvey={survey} mode="edit" />
      )}
    </PageContainer>
  );
}
