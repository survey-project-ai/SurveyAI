"use client";

import Link from "next/link";
import { PageContainer } from "@/components/layout/PageContainer";
import { usePageHeaderOverride } from "@/components/layout/PageHeaderContext";
import { EmptyState } from "@/components/ui/EmptyState";

export default function NotFound() {
  usePageHeaderOverride({
    title: "Sayfa bulunamadı",
    subtitle: "Aradığınız sayfa taşınmış, silinmiş ya da hiç var olmamış olabilir.",
  });

  return (
    <PageContainer hideBackRow>
      <EmptyState
        title="Bu adreste bir sayfa yok"
        description="Bağlantı eski olabilir ya da kayıt silinmiş olabilir. Ana sayfadan veya listelerden devam edebilirsiniz."
        action={
          <div className="survey-header-action-buttons">
            <Link href="/" className="button-primary compact-button">
              Ana sayfaya dön
            </Link>
            <Link href="/operations" className="button-secondary compact-button">
              Operasyonlar
            </Link>
            <Link href="/surveys" className="button-secondary compact-button">
              Anketler
            </Link>
          </div>
        }
      />
    </PageContainer>
  );
}
