"use client";

import Link from "next/link";
import { useEffect } from "react";
import { PageContainer } from "@/components/layout/PageContainer";
import { EmptyState } from "@/components/ui/EmptyState";

// Shown inside the app shell when a page throws while rendering.
export default function PageError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <PageContainer hideBackRow>
      <EmptyState
        tone="danger"
        title="Bu sayfa açılırken bir hata oluştu"
        description={`Sayfayı yeniden yüklemeyi deneyin. Sorun sürerse bu kodu destek ekibine iletin: ${error.digest ?? "yok"}.`}
        action={
          <div className="survey-header-action-buttons">
            <button type="button" className="button-primary compact-button" onClick={() => reset()}>
              Tekrar dene
            </button>
            <Link href="/" className="button-secondary compact-button">
              Ana sayfaya dön
            </Link>
          </div>
        }
      />
    </PageContainer>
  );
}
