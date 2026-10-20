"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useMemo, useState } from "react";
import { usePageHeaderOverride } from "@/components/layout/PageHeaderContext";
import { PageBackButton } from "@/components/navigation/PageBackButton";
import { EmptyBuilderState } from "@/components/surveys/EmptyBuilderState";
import { QuestionCard } from "@/components/surveys/QuestionCard";
import { SurveyBuilderToolbar } from "@/components/surveys/SurveyBuilderToolbar";
import { SurveyPreviewPanel } from "@/components/surveys/SurveyPreviewPanel";
import { SectionCard } from "@/components/ui/SectionCard";
import { StatCard } from "@/components/ui/StatCard";
import { AnalyticsIcon, EyeIcon, PlayIcon, PlusIcon, SurveyIcon } from "@/components/ui/Icons";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { useLanguage } from "@/lib/i18n/LanguageContext";
import { applyQuestionRuleForm, parseQuestionRuleForm } from "@/lib/survey-question-rules";
import { saveSurveyBuilderSurvey, type BuilderSaveAction } from "@/lib/survey-builder-api";
import { useUnsavedChangesGuard } from "@/lib/unsaved-changes";
import { buildDependentQuestion, createQuestion, getQuestionTypeLabels, isChoiceQuestion, isMatrixQuestion } from "@/lib/survey-builder";
import type { SurveyBuilderQuestion, SurveyBuilderSurvey, SurveyQuestionType } from "@/lib/types";

type ReadinessRow = {
  key: string;
  title: string;
  detail: string;
  label: string;
  status: "Ready" | "Warning" | "Pending";
};

export function SurveyBuilderShell({
  initialSurvey,
  mode,
  showSummaryStrip = true,
  showToolbar = true,
  showHeaderPersistActions = false,
  showTopRow = true,
  showPreviewPanel = true,
  showReadinessPanel = true,
  showSurveySummaryPanel = true,
  showQuestionList = true,
  showCanvasHeader = true,
  showLanguageCodeField = false,
  compactSpacing = false,
  readOnly = false,
}: {
  initialSurvey: SurveyBuilderSurvey;
  mode: "create" | "edit";
  showSummaryStrip?: boolean;
  showToolbar?: boolean;
  showHeaderPersistActions?: boolean;
  showTopRow?: boolean;
  showPreviewPanel?: boolean;
  showReadinessPanel?: boolean;
  showSurveySummaryPanel?: boolean;
  showQuestionList?: boolean;
  showCanvasHeader?: boolean;
  showLanguageCodeField?: boolean;
  compactSpacing?: boolean;
  readOnly?: boolean;
}) {
  const router = useRouter();
  const { language } = useLanguage();
  const questionTypeLabels = getQuestionTypeLabels(language);
  const [survey, setSurvey] = useState<SurveyBuilderSurvey>(initialSurvey);
  // The survey as last loaded or saved; any difference from it is an unsaved change.
  const [savedSurvey, setSavedSurvey] = useState<SurveyBuilderSurvey>(initialSurvey);
  const [activeAction, setActiveAction] = useState<BuilderSaveAction | null>(null);
  const [isCreatingDraftCopy, setIsCreatingDraftCopy] = useState(false);
  const [feedbackMessage, setFeedbackMessage] = useState<string | null>(null);
  const [feedbackTone, setFeedbackTone] = useState<"success" | "error" | null>(null);
  const isPublished = survey.status === "Live";
  const isReadOnly = readOnly || isPublished;
  const savedSnapshot = useMemo(() => JSON.stringify(savedSurvey), [savedSurvey]);
  const currentSnapshot = useMemo(() => JSON.stringify(survey), [survey]);
  useUnsavedChangesGuard(!isReadOnly && currentSnapshot !== savedSnapshot);

  const questionStats = useMemo(() => buildQuestionStats(survey), [survey]);

  const readiness = useMemo(() => buildReadinessRows(survey, questionStats, isPublished), [isPublished, questionStats, survey]);
  const hasSideColumn = showPreviewPanel || showReadinessPanel || showSurveySummaryPanel;
  const shellClassName = ["page-container", "survey-builder-page", "ops-builder-shell", compactSpacing ? "is-compact-create" : ""]
    .filter(Boolean)
    .join(" ");
  const workspaceClassName = ["ops-two-column-layout", "ops-builder-workspace", !hasSideColumn ? "is-single-column" : ""]
    .filter(Boolean)
    .join(" ");

  function updateSurveyField<K extends keyof SurveyBuilderSurvey>(field: K, value: SurveyBuilderSurvey[K]) {
    if (isReadOnly) {
      return;
    }

    setFeedbackMessage(null);
    setFeedbackTone(null);
    setSurvey((current) => ({
      ...current,
      [field]: value,
    }));
  }

  function updateQuestion(nextQuestion: SurveyBuilderQuestion) {
    if (isReadOnly) {
      return;
    }

    setFeedbackMessage(null);
    setFeedbackTone(null);
    setSurvey((current) => ({
      ...current,
      questionCount: current.questions.length,
      questions: current.questions.map((question) => (question.id === nextQuestion.id ? nextQuestion : question)),
    }));
  }

  function addQuestion(type: SurveyQuestionType = "short_text") {
    if (isReadOnly) {
      return;
    }

    setFeedbackMessage(null);
    setFeedbackTone(null);
    setSurvey((current) => {
      const nextQuestion = createQuestion(type, current.questions.length + 1, {}, language);
      const nextQuestions = [...current.questions, nextQuestion];

      return {
        ...current,
        questionCount: nextQuestions.length,
        questions: nextQuestions,
      };
    });
  }

  function addQuestionAfter(afterId: string, type: SurveyQuestionType = "short_text") {
    if (isReadOnly) {
      return;
    }

    setFeedbackMessage(null);
    setFeedbackTone(null);
    setSurvey((current) => {
      const index = current.questions.findIndex((question) => question.id === afterId);
      if (index < 0) {
        return current;
      }

      const nextQuestion = createQuestion(type, current.questions.length + 1, {}, language);
      const nextQuestions = [...current.questions];
      nextQuestions.splice(index + 1, 0, nextQuestion);

      return {
        ...current,
        questionCount: nextQuestions.length,
        questions: nextQuestions,
      };
    });
  }

  function addDependentQuestionAfter(sourceQuestionId: string) {
    if (isReadOnly) {
      return;
    }

    setFeedbackMessage(null);
    setFeedbackTone(null);
    setSurvey((current) => {
      const index = current.questions.findIndex((question) => question.id === sourceQuestionId);
      if (index < 0) {
        return current;
      }

      const sourceQuestion = current.questions[index];
      const { question: draftQuestion, blueprint } = buildDependentQuestion(sourceQuestion, current.questions.length + 1, language);
      const shouldActivateDependency = blueprint.branchSelectedOptionCodes.trim().length > 0;
      const nextQuestion = applyQuestionRuleForm(
        draftQuestion,
        {
          ...parseQuestionRuleForm(draftQuestion, sourceQuestion),
          branchMode: shouldActivateDependency ? blueprint.branchMode : "none",
          branchQuestionCode: blueprint.branchQuestionCode,
          branchGroupCode: blueprint.branchGroupCode,
          branchSameRowCode: blueprint.branchSameRowCode,
          branchSelectedOptionCodes: blueprint.branchSelectedOptionCodes,
        },
        sourceQuestion,
      );
      const nextQuestions = [...current.questions];
      nextQuestions.splice(index + 1, 0, nextQuestion);

      return {
        ...current,
        questionCount: nextQuestions.length,
        questions: nextQuestions,
      };
    });
  }

  function reorderQuestion(id: string, direction: -1 | 1) {
    if (isReadOnly) {
      return;
    }

    setFeedbackMessage(null);
    setFeedbackTone(null);
    setSurvey((current) => {
      const index = current.questions.findIndex((question) => question.id === id);
      const target = index + direction;

      if (index < 0 || target < 0 || target >= current.questions.length) {
        return current;
      }

      const nextQuestions = [...current.questions];
      const [item] = nextQuestions.splice(index, 1);
      nextQuestions.splice(target, 0, item);

      return {
        ...current,
        questionCount: nextQuestions.length,
        questions: nextQuestions,
      };
    });
  }

  function removeQuestion(id: string) {
    if (isReadOnly) {
      return;
    }

    setFeedbackMessage(null);
    setFeedbackTone(null);
    setSurvey((current) => {
      if (current.questions.length <= 1) {
        return current;
      }

      const nextQuestions = current.questions.filter((question) => question.id !== id);

      return {
        ...current,
        questionCount: nextQuestions.length,
        questions: nextQuestions,
      };
    });
  }

  const handlePersist = useCallback(async (action: BuilderSaveAction) => {
    if (isReadOnly) {
      return;
    }

      const validationError = validateSurveyBeforePersist(survey, language);
    if (validationError) {
      setFeedbackMessage(validationError);
      setFeedbackTone("error");
      return;
    }

    setActiveAction(action);
    setFeedbackMessage(null);
    setFeedbackTone(null);

    try {
      const result = await saveSurveyBuilderSurvey(survey, action);
      setSurvey(result.survey);
      setSavedSurvey(result.survey);
      setFeedbackMessage(resolvePersistMessage(action, result.survey.status, language));
      setFeedbackTone("success");
    } catch (error) {
      setFeedbackMessage(error instanceof Error ? error.message : language === "tr" ? "Kaydetme sırasında bir hata oluştu." : "An error occurred while saving.");
      setFeedbackTone("error");
    } finally {
      setActiveAction(null);
    }
  }, [isReadOnly, language, survey]);

  const handleCreateDraftCopy = useCallback(async () => {
    try {
      setIsCreatingDraftCopy(true);
      setFeedbackMessage(null);
      setFeedbackTone(null);

      const copiedSurvey = buildDraftCopySurvey(survey);
      const result = await saveSurveyBuilderSurvey(copiedSurvey, "draft");

      router.push(`/surveys/${result.survey.id}`);
    } catch (error) {
      setFeedbackMessage(error instanceof Error ? error.message : language === "tr" ? "Taslak kopya oluşturulamadı." : "The draft copy could not be created.");
      setFeedbackTone("error");
    } finally {
      setIsCreatingDraftCopy(false);
    }
  }, [language, router, survey]);

  const headerAction = useMemo(() => {
    if (showHeaderPersistActions && !readOnly) {
      return (
        <div className="survey-header-action-cluster survey-header-action-cluster--builder">
          <div className="survey-header-action-buttons">
            <PageBackButton />
            <StatusBadge status="warning" label={readiness.label} tone="warning" />
          </div>

          <div className="survey-header-action-buttons">
          {feedbackMessage ? (
            <span className={["ops-builder-feedback-pill", feedbackTone === "error" ? "is-error" : "is-success"].join(" ")}>
              {feedbackTone === "error" ? `${language === "tr" ? "Hata" : "Error"}: ${feedbackMessage}` : feedbackMessage}
            </span>
          ) : null}
          <button
            type="button"
            className="button-secondary compact-button survey-header-button"
            onClick={() => void handlePersist("save")}
            disabled={activeAction !== null}
          >
            {activeAction === "save" ? (language === "tr" ? "Kaydediliyor..." : "Saving...") : (language === "tr" ? "Değişiklikleri kaydet" : "Save changes")}
          </button>
          <button
            type="button"
            className="button-primary compact-button survey-header-button"
            onClick={() => void handlePersist("publish")}
            disabled={activeAction !== null || isPublished}
          >
            {activeAction === "publish" ? (language === "tr" ? "Yayınlanıyor..." : "Publishing...") : (language === "tr" ? "Kaydet ve yayınla" : "Save and publish")}
          </button>
          </div>
        </div>
      );
    }

    if (mode === "edit" && isPublished && !readOnly) {
      return (
        <div className="survey-header-action-cluster">
          <div className="survey-header-action-buttons">
            <Link href="/surveys/new" className="button-secondary compact-button survey-header-button is-new">
              <PlusIcon className="nav-icon" />
              Yeni anket
            </Link>
            <button
              type="button"
              className="button-secondary compact-button survey-header-button is-copy"
              onClick={() => void handleCreateDraftCopy()}
              disabled={isCreatingDraftCopy}
            >
              <SurveyIcon className="nav-icon" />
              {isCreatingDraftCopy ? (language === "tr" ? "Kopya hazırlanıyor..." : "Preparing copy...") : (language === "tr" ? "Taslak Kopya Oluştur" : "Create draft copy")}
            </button>
          </div>
          <div className="survey-header-notice" role="note" aria-label="Yayın uyarısı">
            <SurveyIcon className="nav-icon" />
            <div>
              <strong>{language === "tr" ? "Yayınlandı" : "Published"}</strong>
              <span>{language === "tr" ? "Yayınlanmış anketlerde değişiklik yapılamaz. Devam etmek için bu anketin taslak bir kopyası üzerinden ilerleyebilirsiniz." : "Published surveys cannot be edited. To continue, work on a draft copy of this survey."}</span>
            </div>
          </div>
        </div>
      );
    }

    return null;
  }, [
    activeAction,
    feedbackMessage,
    feedbackTone,
    handleCreateDraftCopy,
    handlePersist,
    language,
    readiness.label,
    isCreatingDraftCopy,
    isPublished,
    mode,
    readOnly,
    showHeaderPersistActions,
  ]);

  usePageHeaderOverride({
    title: mode === "create" ? (language === "tr" ? "Yeni Anket Tasarımı" : "New Survey Design") : survey.name.trim() || (language === "tr" ? "Anket Tasarımı" : "Survey Design"),
    subtitle:
      mode === "create"
        ? (language === "tr" ? "Soruları, görüşme metinlerini ve yayın hazırlığını tek çalışma alanında yönetin." : "Manage questions, call scripts, and publishing readiness in one workspace.")
        : survey.summary.trim()
          ? survey.summary.trim()
          : language === "tr"
            ? "Anket yapısını ve operasyon bağlamını aynı akışta güncelleyin."
            : "Update the survey structure and operational context in the same flow.",
    action: headerAction,
  });

  return (
    <div className={shellClassName}>
      {showTopRow ? (
        <div className="ops-create-top-row ops-builder-top-row">
          <PageBackButton />
          <StatusBadge status={readiness.status} label={readiness.label} />
        </div>
      ) : null}

      {showSummaryStrip ? (
        <section className="ops-summary-strip ops-builder-summary-strip">
          <StatCard
            label="Anket durumu"
            value={resolveStatusLabel(survey.status, language)}
            detail={mode === "create" ? "Yeni tasarım akışı taslak olarak başlar." : "Mevcut anketin yaşam durumu."}
            icon={<SurveyIcon className="nav-icon" />}
          />
          <StatCard
            label="Soru mimarisi"
            value={questionStats.total}
            detail={
              questionStats.total > 0
                ? `${questionStats.distinctTypes} farklı tip, ${questionStats.required} zorunlu soru`
                : "İlk soruyu ekleyerek akış mimarisini başlatın."
            }
            icon={<AnalyticsIcon className="nav-icon" />}
          />
          <StatCard
            label="Dil ve tekrar"
            value={`${(survey.languageCode || "tr").toUpperCase()} / ${survey.maxRetryPerQuestion}`}
            detail="Soru başı tekrar sayısı sesli görüşmedeki ikinci deneme sayısını belirler."
            icon={<PlayIcon className="nav-icon" />}
          />
          <StatCard
            label="Önizleme hazırlığı"
            value={survey.introPrompt.trim() && survey.closingPrompt.trim() ? "Hazır" : "Eksik"}
            detail="Açılış ve kapanış mesajları canlı görüşme ritmini belirler."
            icon={<EyeIcon className="nav-icon" />}
          />
        </section>
      ) : null}

      <div className={workspaceClassName}>
        <div className="ops-builder-main">
          <div className="ops-builder-question-area">
            {!isReadOnly ? (
              <SectionCard
                eyebrow=""
                title=""
                description=""
              >
                <div className="ops-builder-brief-grid">
                  <label className="builder-field ops-builder-field-full">
                    <strong>{language === "tr" ? "Anket başlığı" : "Survey title"}</strong>
                    <input
                      type="text"
                      value={survey.name}
                      onChange={(event) => updateSurveyField("name", event.target.value)}
                      placeholder={language === "tr" ? "Örnek: Elazığ Gündem Araştırması Nisan 2026" : "Example: Elazığ Public Agenda Survey April 2026"}
                      disabled={activeAction !== null}
                    />
                    <span>{language === "tr" ? "Liste ekranında, operasyon seçimlerinde ve görüşme ön izlemesinde görünür." : "Shown in lists, operation selection, and call preview."}</span>
                  </label>

                  <label className="builder-field ops-builder-field-full">
                    <strong>{language === "tr" ? "Kullanım özeti" : "Usage summary"}</strong>
                    <textarea
                      rows={3}
                      value={survey.summary}
                      onChange={(event) => updateSurveyField("summary", event.target.value)}
                      placeholder={language === "tr" ? "Bu anketin hangi operasyonel amaç için kullanıldığını kısaca yazın." : "Briefly describe the operational purpose of this survey."}
                      disabled={activeAction !== null}
                    />
                    <span>{language === "tr" ? "Ekiplerin anketin bağlamını hızla anlaması için kısa bir operasyon notu ekleyin." : "Add a short operational note so teams can quickly understand the survey context."}</span>
                  </label>
                </div>

                <div className="ops-builder-script-grid">
                  <label className="builder-field ops-builder-field-full">
                    <strong>{language === "tr" ? "First message / açılış mesajı" : "First message / opening message"}</strong>
                    <textarea
                      rows={4}
                      value={survey.introPrompt}
                      onChange={(event) => updateSurveyField("introPrompt", event.target.value)}
                      placeholder={language === "tr" ? "Merhaba, ben SurveyAI adına arıyorum. Kısa bir anket için uygun musunuz?" : "Hello, this is SurveyAI calling. Is now a good time for a short survey?"}
                      disabled={activeAction !== null}
                    />
                    <span>{language === "tr" ? "Agent çağrının başında önce bu metni söyler, sonra ilk soruya geçer." : "The agent says this text first at the start of the call, then moves to the first question."}</span>
                  </label>

                  <label className="builder-field ops-builder-field-full">
                    <strong>{language === "tr" ? "Kapanış mesajı" : "Closing message"}</strong>
                    <textarea
                      rows={3}
                      value={survey.closingPrompt}
                      onChange={(event) => updateSurveyField("closingPrompt", event.target.value)}
                      placeholder={language === "tr" ? "Teşekkür ederiz, iyi günler dileriz." : "Thank you very much. Have a nice day."}
                      disabled={activeAction !== null}
                    />
                    <span>{language === "tr" ? "Anket tamamlandığında ya da backend kapanış döndüğünde okunur." : "Read when the survey is completed or when the backend returns a closing message."}</span>
                  </label>

                  {showLanguageCodeField ? (
                    <label className="builder-field">
                      <strong>Dil kodu</strong>
                      <input
                        type="text"
                        value={survey.languageCode}
                        onChange={(event) => updateSurveyField("languageCode", event.target.value)}
                        placeholder="tr"
                        maxLength={10}
                        disabled={activeAction !== null}
                      />
                      <span>Genelde `tr` yeterlidir. ElevenLabs first message fallback dili buradan belirlenir.</span>
                    </label>
                  ) : null}

                </div>
              </SectionCard>
            ) : null}

            {showToolbar ? (
              <SurveyBuilderToolbar
                survey={survey}
                mode={mode}
                onAddQuestion={() => addQuestion()}
                onPersist={handlePersist}
                activeAction={activeAction}
                feedbackMessage={feedbackMessage}
                feedbackTone={feedbackTone}
                readOnly={isReadOnly}
              />
            ) : null}

            {showQuestionList ? (
              <section className="builder-canvas-shell panel-card ops-builder-canvas-shell">
                {showCanvasHeader ? (
                  <div className="builder-canvas-header ops-builder-canvas-head">
                    {!isReadOnly ? (
                      <div className="builder-quick-add">
                        {([
                          "short_text",
                          "single_choice",
                          "single_choice_grid",
                          "rating_grid_1_5",
                          "multi_choice",
                          "dropdown",
                          "rating_1_5",
                          "date",
                        ] as SurveyQuestionType[]).map((type) => (
                          <button
                            key={type}
                            type="button"
                            className="builder-quick-chip"
                            onClick={() => addQuestion(type)}
                            disabled={activeAction !== null}
                          >
                            <PlusIcon className="nav-icon" />
                            {questionTypeLabels[type]}
                          </button>
                        ))}
                      </div>
                    ) : null}
                  </div>
                ) : null}

                {survey.questions.length > 0 ? (
                  <div className="builder-question-list">
                    {survey.questions.map((question, index) => (
                      <QuestionCard
                        key={question.id}
                        index={index}
                        question={question}
                        branchTargets={survey.questions.slice(0, index)}
                        isFirst={index === 0}
                        isLast={index === survey.questions.length - 1}
                        canRemove={survey.questions.length > 1}
                        readOnly={isReadOnly}
                        onUpdate={updateQuestion}
                        onMoveUp={() => reorderQuestion(question.id, -1)}
                        onMoveDown={() => reorderQuestion(question.id, 1)}
                        onRemove={() => removeQuestion(question.id)}
                        onAddBelow={() => addQuestionAfter(question.id)}
                        onAddDependent={() => addDependentQuestionAfter(question.id)}
                      />
                    ))}
                  </div>
                ) : (
                  <EmptyBuilderState onAdd={() => addQuestion()} disabled={isReadOnly || activeAction !== null} />
                )}

                {!isReadOnly ? (
                  <div className="builder-bottom-actions ops-builder-bottom-actions">
                    <button
                      type="button"
                      className="button-secondary compact-button"
                      onClick={() => addQuestion()}
                      disabled={activeAction !== null}
                    >
                      <PlusIcon className="nav-icon" />
                      {language === "tr" ? "Yeni soru ekle" : "Add new question"}
                    </button>
                  </div>
                ) : null}
              </section>
            ) : null}
          </div>
        </div>

        {hasSideColumn ? (
          <aside className="ops-builder-side">
            {showPreviewPanel ? <SurveyPreviewPanel survey={survey} /> : null}

            {showReadinessPanel ? (
              <SectionCard
                eyebrow="Kontrol"
                title="Yayın Hazırlığı"
                description="Sistem, anketin sahaya çıkmadan önce dikkat edilmesi gereken noktalarını burada toplar."
                action={<StatusBadge status={readiness.status} label={readiness.label} />}
              >
                <div className="ops-builder-governance-list">
                  {readiness.rows.map((row) => (
                    <div key={row.key} className="ops-builder-governance-row">
                      <div className="ops-builder-governance-copy">
                        <strong>{row.title}</strong>
                        <span>{row.detail}</span>
                      </div>
                      <StatusBadge status={row.status} label={row.label} />
                    </div>
                  ))}
                </div>
              </SectionCard>
            ) : null}

            {showSurveySummaryPanel ? (
              <SectionCard
                eyebrow="Özet"
                title="Anket Özeti"
                description="Bu panel, operasyon ekiplerinin sahaya neyin çıkacağını tek bakışta kontrol etmesini sağlar."
              >
                <div className="ops-builder-summary-grid">
                  <div className="ops-builder-summary-item">
                    <span>Son güncelleme</span>
                    <strong>{survey.updatedAt}</strong>
                  </div>
                  <div className="ops-builder-summary-item">
                    <span>Zorunlu soru</span>
                    <strong>{questionStats.required}</strong>
                  </div>
                  <div className="ops-builder-summary-item">
                    <span>Seçimli soru</span>
                    <strong>{questionStats.choice}</strong>
                  </div>
                  <div className="ops-builder-summary-item">
                    <span>Ölçek sorusu</span>
                    <strong>{questionStats.rating}</strong>
                  </div>
                </div>

                <div className="ops-builder-notes">
                  <div className="ops-builder-note-card">
                    <strong>Kullanım bağlamı</strong>
                    <p>{survey.summary.trim() || "Bu anket için operasyon bağlamı henüz yazılmadı."}</p>
                  </div>
                  <div className="ops-builder-note-card">
                    <strong>Akış kapsamı</strong>
                    <p>
                      {survey.questions.length > 0
                        ? `Açılış, ${survey.questions.length} soru ve kapanışla ilerleyen bir görüşme akışı tanımlı.`
                        : "Açılış ve kapanış mesajlarını ekledikten sonra ilk soru ile akışı başlatabilirsiniz."}
                    </p>
                  </div>
                </div>
              </SectionCard>
            ) : null}
          </aside>
        ) : null}
      </div>
    </div>
  );
}

function buildReadinessRows(survey: SurveyBuilderSurvey, questionStats: ReturnType<typeof buildQuestionStats>, isPublished: boolean) {
  const rows: ReadinessRow[] = [
    {
      key: "brief",
      title: "Anket brifi",
      detail: survey.name.trim() && survey.summary.trim()
        ? "Başlık ve kullanım özeti operasyon ekipleri için okunabilir durumda."
        : "Başlık ve özet alanı eksiksiz doldurulursa anket yeniden kullanımda daha net olur.",
      label: survey.name.trim() && survey.summary.trim() ? "Hazır" : "Eksik",
      status: survey.name.trim() && survey.summary.trim() ? "Ready" : "Warning",
    },
    {
      key: "scripts",
      title: "Açılış ve kapanış metinleri",
      detail: survey.introPrompt.trim() && survey.closingPrompt.trim()
        ? "Görüşme ritmi için açılış ve kapanış metinleri tanımlı."
        : "En az bir akış metni eksik; görüşmenin tonu sahada net olmayabilir.",
      label: survey.introPrompt.trim() && survey.closingPrompt.trim() ? "Hazır" : "Eksik",
      status: survey.introPrompt.trim() && survey.closingPrompt.trim() ? "Ready" : "Warning",
    },
    {
      key: "questions",
      title: "Soru bütünlüğü",
      detail:
        questionStats.total > 0 && questionStats.blankTitles === 0
          ? `${questionStats.total} soru başlıklandırıldı ve akış okunabilir durumda.`
          : "En az bir soru ekleyin ve her soruya operasyonel bir başlık verin.",
      label: questionStats.total > 0 && questionStats.blankTitles === 0 ? "Hazır" : "Bekliyor",
      status: questionStats.total > 0 && questionStats.blankTitles === 0 ? "Ready" : "Pending",
    },
    {
      key: "options",
      title: "Seçenek tutarlılığı",
      detail:
        questionStats.optionGaps === 0
          ? "Seçimli soru tiplerinde boş veya eksik seçenek görünmüyor."
          : `${questionStats.optionGaps} seçimli soruda seçenek kontrolü gerekiyor.`,
      label: questionStats.optionGaps === 0 ? "Hazır" : "Riskli",
      status: questionStats.optionGaps === 0 ? "Ready" : "Warning",
    },
  ];

  const readyCount = rows.filter((row) => row.status === "Ready").length;
  const status = isPublished ? "Active" : readyCount >= 4 ? "Ready" : readyCount >= 2 ? "Warning" : "Pending";
  const label = isPublished ? "Yayında" : readyCount >= 4 ? "Hazır" : readyCount >= 2 ? "Kısmen hazır" : "Hazırlıkta";

  return {
    rows,
    status,
    label,
  };
}

function buildQuestionStats(survey: SurveyBuilderSurvey) {
  const required = survey.questions.filter((question) => question.required).length;
  const choice = survey.questions.filter((question) => isChoiceQuestion(question.type)).length;
  const rating = survey.questions.filter((question) => question.type === "rating_1_5" || question.type === "rating_1_10").length;
  const blankTitles = survey.questions.filter((question) => !question.title.trim()).length;
  const optionGaps = survey.questions.filter((question) => {
    if (!isChoiceQuestion(question.type)) {
      return false;
    }

    if ((question.options?.length ?? 0) === 0) {
      return true;
    }

    return (question.options ?? []).some((option) => !option.label.trim());
  }).length;

  return {
    total: survey.questions.length,
    required,
    choice,
    rating,
    blankTitles,
    optionGaps,
    distinctTypes: new Set(survey.questions.map((question) => question.type)).size,
  };
}

function resolveStatusLabel(status: SurveyBuilderSurvey["status"], language: "tr" | "en") {
  switch (status) {
    case "Live":
      return language === "tr" ? "Yayında" : "Published";
    case "Archived":
      return language === "tr" ? "Arşivde" : "Archived";
    case "Draft":
    default:
      return language === "tr" ? "Taslak" : "Draft";
  }

}

function resolvePersistMessage(action: BuilderSaveAction, status: SurveyBuilderSurvey["status"], language: "tr" | "en") {
  if (action === "publish" || status === "Live") {
    return language === "tr" ? "Anket yayınlandı." : "The survey has been published.";
  }

  if (action === "draft") {
    return language === "tr" ? "Taslak kaydedildi." : "Draft saved.";
  }

  return language === "tr" ? "Değişiklikler kaydedildi." : "Changes saved.";
}

function buildDraftCopySurvey(survey: SurveyBuilderSurvey): SurveyBuilderSurvey {
  return {
    ...survey,
    id: `draft-copy-${globalThis.crypto.randomUUID()}`,
    name: `${survey.name.trim() || "Anket"} - Taslak Kopya`,
    status: "Draft",
    createdAt: "Henüz oluşmadı",
    publishedAt: null,
    updatedAt: "Bugün",
    questions: survey.questions.map((question, questionIndex) => ({
      ...question,
      id: `draft-question-${globalThis.crypto.randomUUID()}`,
      options: question.options?.map((option, optionIndex) => ({
        ...option,
        id: `draft-option-${questionIndex + 1}-${optionIndex + 1}-${globalThis.crypto.randomUUID()}`,
      })),
    })),
    questionCount: survey.questions.length,
  };
}

function validateSurveyBeforePersist(survey: SurveyBuilderSurvey, language: "tr" | "en"): string | null {
  const errors: string[] = [];

  if (!survey.name.trim()) {
    errors.push(language === "tr" ? "Anket başlığı gerekli." : "Survey title is required.");
  }

  if (!survey.introPrompt.trim()) {
    errors.push(language === "tr" ? "First message gerekli." : "First message is required.");
  }

  if (!survey.closingPrompt.trim()) {
    errors.push(language === "tr" ? "Kapanış mesajı gerekli." : "Closing message is required.");
  }

  if (survey.questions.length === 0) {
    errors.push(language === "tr" ? "En az bir soru ekleyin." : "Add at least one question.");
  }

  survey.questions.forEach((question, index) => {
    if (!question.title.trim()) {
      errors.push(language === "tr" ? `${index + 1}. sorunun başlığı boş olamaz.` : `Question ${index + 1} must have a title.`);
    }

    if (isMatrixQuestion(question.type)) {
      if ((question.matrixRows?.length ?? 0) === 0) {
        errors.push(language === "tr" ? `${index + 1}. tablo sorusu için en az bir satır ekleyin.` : `Add at least one row for grid question ${index + 1}.`);
      } else if ((question.matrixRows ?? []).some((row) => !row.label.trim())) {
        errors.push(language === "tr" ? `${index + 1}. tablo sorusundaki satır adları boş olamaz.` : `Row labels cannot be empty in grid question ${index + 1}.`);
      }

      if ((question.options?.length ?? 0) === 0) {
        errors.push(language === "tr" ? `${index + 1}. tablo sorusu için en az bir sütun ekleyin.` : `Add at least one column for grid question ${index + 1}.`);
      } else if ((question.options ?? []).some((option) => !option.label.trim())) {
        errors.push(language === "tr" ? `${index + 1}. tablo sorusundaki sütun adları boş olamaz.` : `Column labels cannot be empty in grid question ${index + 1}.`);
      }
      return;
    }

    if (!isChoiceQuestion(question.type)) {
      return;
    }

    if ((question.options?.length ?? 0) === 0) {
      errors.push(language === "tr" ? `${index + 1}. soru için en az bir seçenek ekleyin.` : `Add at least one option for question ${index + 1}.`);
      return;
    }

    if ((question.options ?? []).some((option) => !option.label.trim())) {
      errors.push(language === "tr" ? `${index + 1}. sorudaki seçeneklerin metni boş olamaz.` : `Option labels cannot be empty in question ${index + 1}.`);
    }
  });

  if (errors.length === 0) {
    return null;
  }

  if (errors.length <= 3) {
    return errors.join(" ");
  }

  return language === "tr"
    ? `${errors.slice(0, 3).join(" ")} +${errors.length - 3} eksik alan daha var.`
    : `${errors.slice(0, 3).join(" ")} +${errors.length - 3} more required fields remain.`;
}
