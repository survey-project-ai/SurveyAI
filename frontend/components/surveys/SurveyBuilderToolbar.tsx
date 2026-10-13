"use client";

import { type ReactNode } from "react";
import { PlusIcon } from "@/components/ui/Icons";
import { StatusBadge } from "@/components/ui/StatusBadge";
import type { BuilderSaveAction } from "@/lib/survey-builder-api";
import type { SurveyBuilderSurvey } from "@/lib/types";

type SurveyBuilderToolbarProps = {
  survey: SurveyBuilderSurvey;
  mode: "create" | "edit";
  onAddQuestion: () => void;
  onPersist: (action: BuilderSaveAction) => void;
  activeAction: BuilderSaveAction | null;
  feedbackMessage: string | null;
  feedbackTone: "success" | "error" | null;
  readOnly?: boolean;
  leading?: ReactNode;
};

export function SurveyBuilderToolbar({
  survey,
  mode,
  onAddQuestion,
  onPersist,
  activeAction,
  feedbackMessage,
  feedbackTone,
  readOnly = false,
  leading,
}: SurveyBuilderToolbarProps) {
  const isBusy = activeAction !== null;
  const isPublished = survey.status === "Live";
  const disableMutations = isBusy || readOnly;
  const statusLabel = isPublished ? "Yayında" : survey.status === "Draft" ? "Taslak" : survey.status;
  const contextLabel = readOnly ? "Önizleme" : mode === "create" ? "Çalışma Alanı" : "Düzenleme";
  const title = readOnly
    ? "Anketin salt-okunur önizlemesi"
    : isPublished
      ? "Yayınlanmış anket görüntüleniyor"
      : mode === "create"
        ? "Yeni anket tasarımı üzerinde çalışıyorsunuz"
        : "Anket akışı güncelleniyor";

  return (
    <section className="builder-toolbar panel-card ops-builder-toolbar">
      <div className="ops-builder-toolbar-main">
        {leading ? <div className="builder-toolbar-leading">{leading}</div> : null}

        <div className="builder-toolbar-context">
          <div className="builder-toolbar-copy">
            <span className="builder-panel-kicker">{contextLabel}</span>
            <strong>{title}</strong>
          </div>

          <div className="builder-toolbar-meta ops-builder-toolbar-meta">
            <StatusBadge status={survey.status} label={statusLabel} />
            <span>{survey.questions.length} soru</span>
            <span>{survey.updatedAt}</span>
            {feedbackMessage ? (
              <span className={["ops-builder-feedback-pill", feedbackTone === "error" ? "is-error" : "is-success"].join(" ")}>
                {feedbackTone === "error" ? `Hata: ${feedbackMessage}` : feedbackMessage}
              </span>
            ) : null}
          </div>
        </div>
      </div>

      {readOnly ? null : (
        <div className="builder-toolbar-actions ops-builder-toolbar-actions">
          <button type="button" className="button-secondary compact-button" onClick={onAddQuestion} disabled={disableMutations}>
            <PlusIcon className="nav-icon" />
            Soru ekle
          </button>
          <button
            type="button"
            className="button-secondary compact-button"
            onClick={() => onPersist("draft")}
            disabled={disableMutations}
          >
            {activeAction === "draft" ? "Taslak kaydediliyor..." : "Taslak kaydet"}
          </button>
          <button
            type="button"
            className="button-secondary compact-button"
            onClick={() => onPersist("save")}
            disabled={disableMutations}
          >
            {activeAction === "save" ? "Kaydediliyor..." : "Değişiklikleri kaydet"}
          </button>
          <button
            type="button"
            className="button-primary compact-button"
            onClick={() => onPersist("publish")}
            disabled={isBusy || isPublished}
          >
            {isPublished ? "Yayında" : activeAction === "publish" ? "Yayınlanıyor..." : "Yayınla"}
          </button>
          <button
            type="button"
            className="button-secondary compact-button"
            disabled
            title="Kopyalayarak yeni taslak oluşturma akışı yakında eklenecek."
          >
            Kopya yakında
          </button>
        </div>
      )}
    </section>
  );
}
