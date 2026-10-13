"use client";

import { useEffect, useMemo, useRef, useState, type ChangeEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { usePageHeaderOverride } from "@/components/layout/PageHeaderContext";
import { PageContainer } from "@/components/layout/PageContainer";
import { SectionCard } from "@/components/ui/SectionCard";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { createOperation, createOperationContacts } from "@/lib/operations";
import {
  ACCEPTED_OPERATION_CONTACT_FILE_TYPES,
  OPERATION_CONTACT_IMPORT_PREVIEW_LIMIT,
  buildPreviewRows,
  createEmptyImportSummary,
  downloadOperationContactsTemplate,
  readOperationContactImportRows,
  type ImportPreviewRow,
  type ImportSummary,
} from "@/lib/operation-contact-import";
import { fetchSurveyBuilderSurvey } from "@/lib/survey-builder-api";
import { fetchCompanySurveys } from "@/lib/surveys";
import type { Survey, SurveyBuilderSurvey } from "@/lib/types";

type ContactMode = "later" | "now";

type FormErrors = {
  name?: string;
  surveyId?: string;
};

type SubmitIntent = "draft" | "create";
type SubmitPhase = "idle" | "creating" | "importing";

type PreparationItem = {
  key: string;
  title: string;
  detail: string;
  status: "Ready" | "Warning" | "Pending";
  label: string;
};

export default function NewOperationPage() {
  const router = useRouter();
  const [operationName, setOperationName] = useState("");
  const [operationNote, setOperationNote] = useState("");
  const [selectedSurveyId, setSelectedSurveyId] = useState("");
  const [contactMode, setContactMode] = useState<ContactMode>("later");
  const [surveys, setSurveys] = useState<Survey[]>([]);
  const [selectedSurveyDetail, setSelectedSurveyDetail] = useState<SurveyBuilderSurvey | null>(null);
  const [isLoadingSurveys, setIsLoadingSurveys] = useState(true);
  const [isLoadingSurveyDetail, setIsLoadingSurveyDetail] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitIntent, setSubmitIntent] = useState<SubmitIntent | null>(null);
  const [submitPhase, setSubmitPhase] = useState<SubmitPhase>("idle");
  const [loadError, setLoadError] = useState<string | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [errors, setErrors] = useState<FormErrors>({});
  const [selectedFileName, setSelectedFileName] = useState<string | null>(null);
  const [importRows, setImportRows] = useState<ImportPreviewRow[]>([]);
  const [importSummary, setImportSummary] = useState<ImportSummary>(createEmptyImportSummary());
  const [importError, setImportError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  usePageHeaderOverride({
    title: "Yeni Operasyon Tasarımı",
    subtitle: "Operasyon kimliğini, bağlı anketi ve kişi planını aynı karar ekranında netleştirin.",
  });

  const publishedSurveys = useMemo(() => surveys.filter((survey) => survey.status === "Live"), [surveys]);

  const selectedSurvey = useMemo(
    () => publishedSurveys.find((survey) => survey.id === selectedSurveyId) ?? null,
    [publishedSurveys, selectedSurveyId],
  );

  const previewRows = useMemo(() => importRows.slice(0, OPERATION_CONTACT_IMPORT_PREVIEW_LIMIT), [importRows]);
  const validImportRows = useMemo(() => importRows.filter((row) => row.isValid), [importRows]);

  useEffect(() => {
    const controller = new AbortController();

    async function loadSurveys() {
      try {
        setIsLoadingSurveys(true);
        setLoadError(null);
        const nextSurveys = await fetchCompanySurveys(undefined, { signal: controller.signal });
        setSurveys(nextSurveys);
      } catch (error) {
        if (controller.signal.aborted) {
          return;
        }

        setLoadError(error instanceof Error ? error.message : "Anketler yüklenemedi.");
      } finally {
        if (!controller.signal.aborted) {
          setIsLoadingSurveys(false);
        }
      }
    }

    void loadSurveys();

    return () => controller.abort();
  }, []);

  useEffect(() => {
    const controller = new AbortController();

    async function loadSurveyDetail() {
      if (!selectedSurveyId) {
        setSelectedSurveyDetail(null);
        return;
      }

      try {
        setIsLoadingSurveyDetail(true);
        const detail = await fetchSurveyBuilderSurvey(selectedSurveyId, undefined, { signal: controller.signal });
        setSelectedSurveyDetail(detail);
      } catch (error) {
        if (controller.signal.aborted) {
          return;
        }

        setSelectedSurveyDetail(null);
        setSubmitError(error instanceof Error ? error.message : "Seçilen anket özeti yüklenemedi.");
      } finally {
        if (!controller.signal.aborted) {
          setIsLoadingSurveyDetail(false);
        }
      }
    }

    void loadSurveyDetail();

    return () => controller.abort();
  }, [selectedSurveyId]);

  async function handleFileSelection(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];

    setImportError(null);
    setImportRows([]);
    setImportSummary(createEmptyImportSummary());
    setSelectedFileName(file?.name ?? null);

    if (!file) {
      return;
    }

    try {
      const rows = await readOperationContactImportRows(file);
      const { previewRows: nextImportRows, summary } = buildPreviewRows(rows);
      setImportRows(nextImportRows);
      setImportSummary(summary);

      if (summary.totalRows === 0 && summary.ignoredRows === 0) {
        setImportError("Dosyada veri satırı bulunamadı.");
      }
    } catch (error) {
      setImportError(error instanceof Error ? error.message : "Dosya okunamadı.");
    } finally {
      event.target.value = "";
    }
  }

  async function submitOperation(intent: SubmitIntent) {
    const nextErrors: FormErrors = {};
    const trimmedName = operationName.trim();

    if (!trimmedName) {
      nextErrors.name = "Operasyon adı gerekli.";
    }

    if (!selectedSurveyId) {
      nextErrors.surveyId = "Yayınlanmış bir anket seçin.";
    }

    setErrors(nextErrors);
    setSubmitError(null);

    if (Object.keys(nextErrors).length > 0) {
      return;
    }

    try {
      setIsSubmitting(true);
      setSubmitIntent(intent);
      setSubmitPhase("creating");

      const createdOperation = await createOperation({
        name: trimmedName,
        surveyId: selectedSurveyId,
        scheduledAt: null,
        createdByUserId: null,
      });

      const searchParams = new URLSearchParams();
      searchParams.set("created", "1");

      if (contactMode === "now") {
        searchParams.set("importRequested", "1");

        if (validImportRows.length > 0) {
          setSubmitPhase("importing");

          try {
            const importedContacts = await createOperationContacts(createdOperation.id, {
              contacts: validImportRows.map((row) => ({
                name: row.name,
                phoneNumber: row.normalizedPhoneNumber,
              })),
            });

            if (importedContacts.length > 0) {
              searchParams.set("imported", String(importedContacts.length));
            }
          } catch (error) {
            const message = error instanceof Error ? error.message : "Geçerli kişiler operasyona aktarılamadı.";
            searchParams.set("importError", message);
          }
        }

        if (importSummary.invalidRows > 0) {
          searchParams.set("invalid", String(importSummary.invalidRows));
        }

        if (importSummary.ignoredRows > 0) {
          searchParams.set("ignored", String(importSummary.ignoredRows));
        }
      } else {
        searchParams.set("contacts", "skipped");
      }

      router.push(`/operations/${createdOperation.id}?${searchParams.toString()}`);
    } catch (error) {
      setSubmitError(error instanceof Error ? error.message : "Operasyon oluşturulamadı.");
      setIsSubmitting(false);
      setSubmitIntent(null);
      setSubmitPhase("idle");
    }
  }

  const trimmedOperationName = operationName.trim();
  const surveyQuestionCount = selectedSurveyDetail
    ? `${selectedSurveyDetail.questions.length} soru`
    : isLoadingSurveyDetail && selectedSurveyId
      ? "Soru bilgisi yükleniyor"
      : "Soru özeti hazır değil";
  const surveyLanguage = selectedSurveyDetail?.languageCode?.toUpperCase() ?? selectedSurvey?.audience ?? "-";
  const surveyStatus = selectedSurveyDetail?.status ?? selectedSurvey?.status ?? "Live";
  const isSubmitDisabled = isSubmitting || isLoadingSurveys || publishedSurveys.length === 0;
  const isImportVisible = contactMode === "now";

  const preparationItems = useMemo<PreparationItem[]>(
    () => [
      {
        key: "name",
        title: "Operasyon kimliği",
        detail: trimmedOperationName
          ? "Operasyon adı ekipler tarafından ayırt edilebilir durumda."
          : "Operasyonun ekip içinde tanıyacağı net bir ada ihtiyacı var.",
        status: trimmedOperationName ? "Ready" : "Warning",
        label: trimmedOperationName ? "Hazır" : "Eksik",
      },
      {
        key: "survey",
        title: "Yayınlanmış anket bağlantısı",
        detail: selectedSurvey
          ? `${selectedSurvey.name} operasyon için seçildi.`
          : "Operasyonu başlatmak için yayınlanmış bir anket seçilmeli.",
        status: selectedSurvey ? "Ready" : "Warning",
        label: selectedSurvey ? "Bağlandı" : "Seçim bekliyor",
      },
      {
        key: "contacts",
        title: "Kişi stratejisi",
        detail:
          contactMode === "later"
            ? "Kişi listesi daha sonra bağlanacak; bu seçim operasyon oluşturmaya engel değil."
            : validImportRows.length > 0
              ? `${validImportRows.length} kişi ilk import için geçerli görünüyor.`
              : selectedFileName
                ? "Dosya seçildi ancak geçerli satır sayısı netleşmedi."
                : "Toplu import dosyası bekleniyor.",
        status:
          contactMode === "later" || validImportRows.length > 0
            ? "Ready"
            : selectedFileName
              ? "Pending"
              : "Pending",
        label:
          contactMode === "later"
            ? "Planlandı"
            : validImportRows.length > 0
              ? "Hazır"
              : "Bekliyor",
      },
      {
        key: "note",
        title: "Hazırlık notu",
        detail: operationNote.trim()
          ? "Bağlam notu ekip içi iletişim için hazır."
          : "Bağlam notu isteğe bağlı; operasyon kapsamında netlik sağlayabilir.",
        status: operationNote.trim() ? "Ready" : "Pending",
        label: operationNote.trim() ? "Girildi" : "İsteğe bağlı",
      },
    ],
    [contactMode, operationNote, selectedFileName, selectedSurvey, trimmedOperationName, validImportRows.length],
  );

  const readiness = useMemo(() => {
    const completed = preparationItems.filter((item) => item.status === "Ready").length;

    if (completed >= 3) {
      return {
        label: "Hazır",
        status: "Ready" as const,
        detail: "Operasyon tanımı, anket bağlantısı ve ilk uygulama kararı netleşti.",
      };
    }

    if (completed >= 2) {
      return {
        label: "Kısmen hazır",
        status: "Warning" as const,
        detail: "Operasyon oluşturulabilir; yine de hazırlık maddelerinin bir bölümü takibe ihtiyaç duyuyor.",
      };
    }

    return {
      label: "Hazırlıkta",
      status: "Pending" as const,
      detail: "Temel tanım ve anket seçimi tamamlanmadan operasyon kalıcı olarak açılmamalı.",
    };
  }, [preparationItems]);

  return (
    <PageContainer hideBackRow>
      <div className="ops-create-shell">
        <div className="ops-create-top-row">
          <Link href="/operations" className="button-secondary compact-button">
            Operasyonlara dön
          </Link>
          <StatusBadge status={readiness.status} label={readiness.label} />
        </div>

        <div className="ops-create-centered">
          <div className="ops-create-main">
            <SectionCard eyebrow="Brif" title="Operasyon Kimliği" description="Operasyonu net, izlenebilir ve ekip diline uygun bir çerçevede tanımlayın.">
              <div className="survey-form-fields">
                <label className="builder-field">
                  <strong>Operasyon adı</strong>
                  <input
                    value={operationName}
                    onChange={(event) => {
                      setOperationName(event.target.value);
                      if (errors.name) {
                        setErrors((current) => ({ ...current, name: undefined }));
                      }
                    }}
                    placeholder="Örn. Mart 2026 memnuniyet arama akışı"
                    aria-invalid={Boolean(errors.name)}
                  />
                  <span>Kısa, operasyonel ve ekiplerin anında tanıyacağı bir isim kullanın.</span>
                  {errors.name ? <span className="field-error-message">{errors.name}</span> : null}
                </label>

                <label className="builder-field">
                  <strong>Açıklama / Not</strong>
                  <textarea
                    rows={4}
                    value={operationNote}
                    onChange={(event) => setOperationNote(event.target.value)}
                    placeholder="Kapsam, segment veya ekip için karar notunu ekleyin."
                  />
                  <span>Bu alan şu an backend create isteğine gitmez; ekip içi hazırlık bağlamı olarak kullanılır.</span>
                </label>
              </div>
            </SectionCard>

            <SectionCard
              eyebrow="Bağlantı"
              title="Anket Bağlantısı"
              description="Operasyon yalnızca yayınlanmış anketlerle çalışabilir. Taslaklar burada seçilemez."
              action={
                <Link href="/surveys/new" className="button-secondary compact-button">
                  Yeni Anket
                </Link>
              }
            >
              <div className="survey-form-fields">
                {loadError ? (
                  <div className="operation-inline-message is-danger">
                    <strong>Anketler yüklenemedi</strong>
                    <span>{loadError}</span>
                  </div>
                ) : isLoadingSurveys ? (
                  <div className="operation-inline-message">
                    <strong>Yayınlanmış anketler yükleniyor</strong>
                    <span>Operasyona bağlanabilir şablonlar şirket havuzundan getiriliyor.</span>
                  </div>
                ) : publishedSurveys.length === 0 ? (
                  <div className="operation-inline-message">
                    <strong>Kullanılabilir yayınlanmış anket yok</strong>
                    <span>Yeni bir operasyon açmadan önce en az bir anketi yayınlamanız gerekiyor.</span>
                  </div>
                ) : (
                  <label className="builder-field">
                    <strong>Yayınlanmış anket</strong>
                    <select
                      value={selectedSurveyId}
                      onChange={(event) => {
                        setSelectedSurveyId(event.target.value);
                        setSubmitError(null);
                        if (errors.surveyId) {
                          setErrors((current) => ({ ...current, surveyId: undefined }));
                        }
                      }}
                      aria-invalid={Boolean(errors.surveyId)}
                    >
                      <option value="">Anket seçin</option>
                      {publishedSurveys.map((survey) => (
                        <option key={survey.id} value={survey.id}>
                          {survey.name}
                        </option>
                      ))}
                    </select>
                    <span>Liste sadece backend tarafında `PUBLISHED` durumda olan anketleri gösterir.</span>
                    {errors.surveyId ? <span className="field-error-message">{errors.surveyId}</span> : null}
                  </label>
                )}

                {selectedSurvey ? (
                  <div className="operation-survey-summary ops-create-survey-summary">
                    <div className="operation-survey-summary-head">
                      <div>
                        <strong>{selectedSurvey.name}</strong>
                        <span>Operasyona bağlanacak yayınlanmış anket</span>
                      </div>
                      <StatusBadge status={surveyStatus} />
                    </div>

                    <div className="operation-summary-metrics">
                      <div className="mini-metric">
                        <span>Soru sayısı</span>
                        <strong>{surveyQuestionCount}</strong>
                      </div>
                      <div className="mini-metric">
                        <span>Dil</span>
                        <strong>{surveyLanguage}</strong>
                      </div>
                      <div className="mini-metric">
                        <span>Durum</span>
                        <strong>{surveyStatus}</strong>
                      </div>
                    </div>

                    <p className="ops-create-inline-note">
                      {selectedSurvey.goal || "Bu anketin kısa operasyon bağlamı henüz eklenmedi."}
                    </p>
                  </div>
                ) : null}
              </div>
            </SectionCard>

            <SectionCard eyebrow="Kişiler" title="Kişi Yükleme Stratejisi" description="Kişi listesini şimdi bağlayabilir veya operasyonu açıp sonrasında tamamlayabilirsiniz.">
              <div className="survey-form-fields">
                <div className="operation-choice-grid" role="radiogroup" aria-label="Kişi hazırlığı seçimi">
                  <button
                    type="button"
                    className={contactMode === "later" ? "operation-choice-card is-active" : "operation-choice-card"}
                    onClick={() => setContactMode("later")}
                    aria-pressed={contactMode === "later"}
                  >
                    <strong>Kişileri daha sonra ekle</strong>
                    <span>Operasyonu önce oluşturun, kişi listesini sonraki adımda bağlayın.</span>
                  </button>

                  <button
                    type="button"
                    className={contactMode === "now" ? "operation-choice-card is-active" : "operation-choice-card"}
                    onClick={() => {
                      setContactMode("now");
                      if (!selectedFileName) {
                        setTimeout(() => fileInputRef.current?.click(), 0);
                      }
                    }}
                    aria-pressed={contactMode === "now"}
                  >
                    <strong>Kişileri şimdi yükle</strong>
                    <span>CSV veya XLSX dosyasını şimdi yükleyin; geçerli kişiler operasyon oluştuktan hemen sonra bağlanır.</span>
                  </button>
                </div>

                {isImportVisible ? (
                  <div className="operation-import-compact">
                    <input
                      ref={fileInputRef}
                      type="file"
                      style={{ position: "absolute", width: 1, height: 1, opacity: 0, pointerEvents: "none" }}
                      accept={ACCEPTED_OPERATION_CONTACT_FILE_TYPES}
                      onChange={(event) => void handleFileSelection(event)}
                    />

                    <div className="operation-import-file-bar">
                      {selectedFileName ? (
                        <>
                          <svg className="import-file-icon" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
                            <path d="M9 1H3a1 1 0 0 0-1 1v12a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1V6L9 1z" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round"/>
                            <path d="M9 1v5h5" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round"/>
                          </svg>
                          <span className="import-file-name">{selectedFileName}</span>
                          <button type="button" className="import-file-change" onClick={() => fileInputRef.current?.click()}>
                            Değiştir
                          </button>
                        </>
                      ) : (
                        <>
                          <button type="button" className="import-file-select" onClick={() => fileInputRef.current?.click()}>
                            Dosya seç
                          </button>
                          <span className="import-file-hint">.csv veya .xlsx</span>
                          <button type="button" className="import-template-link" onClick={downloadOperationContactsTemplate}>
                            Şablon indir
                          </button>
                        </>
                      )}
                    </div>

                    {(importSummary.totalRows > 0 || importSummary.ignoredRows > 0) && !importError ? (
                      <div className="operation-import-stats-inline">
                        <span><strong>{importSummary.totalRows}</strong> satır</span>
                        <span className="stat-dot" />
                        <span className="is-valid-text"><strong>{importSummary.validRows}</strong> geçerli</span>
                        {importSummary.invalidRows > 0 && (
                          <>
                            <span className="stat-dot" />
                            <span className="is-invalid-text"><strong>{importSummary.invalidRows}</strong> geçersiz</span>
                          </>
                        )}
                        {importSummary.ignoredRows > 0 && (
                          <>
                            <span className="stat-dot" />
                            <span><strong>{importSummary.ignoredRows}</strong> boş</span>
                          </>
                        )}
                      </div>
                    ) : null}

                    {importRows.length > 0 ? (
                      <div className="operation-import-preview">
                        <div className="operation-import-preview-head">
                          <strong>Önizleme</strong>
                          <span>İlk {Math.min(importRows.length, OPERATION_CONTACT_IMPORT_PREVIEW_LIMIT)} satır gösteriliyor.</span>
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
                                <tr key={row.rowNumber} className={row.isValid ? "is-valid" : "is-invalid"}>
                                  <td>{row.rowNumber}</td>
                                  <td>{row.name || "-"}</td>
                                  <td>{row.phoneNumber || "-"}</td>
                                  <td>{row.isValid ? "Hazır" : row.reason}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    ) : null}

                    {importSummary.invalidRows > 0 && !importError ? (
                      <div className="operation-inline-message is-danger compact">
                        <strong>Geçersiz satırlar import edilmeyecek</strong>
                        <span>Sadece geçerli satırlar yeni operasyona bağlanır.</span>
                      </div>
                    ) : null}

                    {importError ? (
                      <div className="operation-inline-message is-danger compact">
                        <strong>Yükleme hatası</strong>
                        <span>{importError}</span>
                      </div>
                    ) : null}
                  </div>
                ) : (
                  <div className="operation-inline-message">
                    <strong>Kişi listesi daha sonra eklenebilir</strong>
                    <span>Bu tercih operasyonu hemen açar. Kişi importu, operasyon detayındaki mevcut akıştan daha sonra devam edebilir.</span>
                  </div>
                )}
              </div>
            </SectionCard>
          </div>
        </div>

        <div className="operation-action-bar panel-card ops-create-action-bar">
          <Link href="/operations" className="button-secondary compact-button">
            İptal
          </Link>
          {submitError ? (
            <span className="ops-create-action-error">{submitError}</span>
          ) : null}
          <div className="operation-action-group">
            <button
              type="button"
              className="button-secondary compact-button"
              onClick={() => void submitOperation("draft")}
              disabled={isSubmitDisabled}
            >
              {isSubmitting && submitIntent === "draft"
                ? submitPhase === "importing"
                  ? "Taslak oluştu, kişiler bağlanıyor..."
                  : "Taslak oluşturuluyor..."
                : "Taslak olarak oluştur"}
            </button>
            <button
              type="button"
              className="button-primary compact-button"
              onClick={() => void submitOperation("create")}
              disabled={isSubmitDisabled}
            >
              {isSubmitting && submitIntent === "create"
                ? submitPhase === "importing"
                  ? "Operasyon oluştu, kişiler bağlanıyor..."
                  : "Operasyon oluşturuluyor..."
                : "Operasyonu oluştur"}
            </button>
          </div>
        </div>
      </div>
    </PageContainer>
  );
}
