import type { SurveyBuilderQuestion, SurveyQuestionOption, SurveyQuestionType } from "@/lib/types";

type JsonObject = Record<string, unknown>;

export type BranchMode = "none" | "skipIf" | "askIf";

export type QuestionRuleFormState = {
  questionCode: string;
  groupCode: string;
  groupTitle: string;
  rowLabel: string;
  branchMode: BranchMode;
  branchGroupCode: string;
  branchQuestionCode: string;
  branchRowCode: string;
  branchSameRowCode: boolean;
  branchSelectedOptionCodes: string;
  codingCategoriesText: string;
  optionAliasesText: string;
  specialAnswersText: string;
};

export function parseQuestionRuleForm(
  question: SurveyBuilderQuestion,
  referenceQuestion?: SurveyBuilderQuestion,
): QuestionRuleFormState {
  const settings = parseJsonObject(question.settingsJson);
  const branchRoot = parseJsonObject(question.branchConditionJson);
  const branchMode: BranchMode = isRecord(branchRoot.skipIf)
    ? "skipIf"
    : isRecord(branchRoot.askIf)
      ? "askIf"
      : "none";
  const branchRule = branchMode === "skipIf"
    ? asObject(branchRoot.skipIf)
    : branchMode === "askIf"
      ? asObject(branchRoot.askIf)
      : {};

  return {
    questionCode: question.code ?? "",
    groupCode: readString(settings.groupCode),
    groupTitle: readString(settings.groupTitle),
    rowLabel: readString(settings.rowLabel),
    branchMode,
    branchGroupCode: readString(branchRule.groupCode),
    branchQuestionCode: readString(branchRule.questionCode),
    branchRowCode: readString(branchRule.rowCode),
    branchSameRowCode: readBoolean(branchRule.sameRowCode),
    branchSelectedOptionCodes: formatBranchSelectedOptions(referenceQuestion ?? question, readStringArray(branchRule.selectedOptionCodes)),
    codingCategoriesText: formatCodingCategories(settings),
    optionAliasesText: formatStringArrayMap(asObject(settings.aliases)),
    specialAnswersText: formatStringArrayMap(asObject(settings.specialAnswers)),
  };
}

export function applyQuestionRuleForm(
  question: SurveyBuilderQuestion,
  nextState: QuestionRuleFormState,
  referenceQuestion?: SurveyBuilderQuestion,
): SurveyBuilderQuestion {
  const settings = parseJsonObject(question.settingsJson);
  const existingBranchRoot = parseJsonObject(question.branchConditionJson);
  const existingBranchRule = nextState.branchMode === "skipIf"
    ? asObject(existingBranchRoot.skipIf)
    : nextState.branchMode === "askIf"
      ? asObject(existingBranchRoot.askIf)
      : {};
  const nextSettings = { ...settings };

  const questionCode = nextState.questionCode.trim();
  const currentGroupCode = readString(settings.groupCode).trim();
  const groupCode = nextState.groupCode.trim() || currentGroupCode || slugify(nextState.groupTitle.trim() || question.title.trim());
  const groupTitle = nextState.groupTitle.trim();
  const rowLabel = nextState.rowLabel.trim();

  if (groupCode) {
    nextSettings.groupCode = groupCode;
    nextSettings.groupTitle = groupTitle || question.title.trim() || "Grup";
    nextSettings.rowLabel = rowLabel || question.title.trim() || "Satır";
    const rowCode = slugify(rowLabel || question.title);
    nextSettings.rowCode = rowCode;
    nextSettings.rowKey = rowCode;
    nextSettings.matrixType = inferMatrixType(question.type);
  } else {
    delete nextSettings.groupCode;
    delete nextSettings.groupTitle;
    delete nextSettings.rowLabel;
    delete nextSettings.rowCode;
    delete nextSettings.rowKey;
    delete nextSettings.matrixType;
    delete nextSettings.optionSetCode;
  }

  const codingCategories = parseCodingCategoriesText(nextState.codingCategoriesText);
  if (Object.keys(codingCategories).length > 0) {
    const codingNode = isRecord(nextSettings.coding) ? { ...(nextSettings.coding as JsonObject) } : {};
    codingNode.categories = codingCategories;
    nextSettings.coding = codingNode;
  } else if (isRecord(nextSettings.coding)) {
    const codingNode = { ...(nextSettings.coding as JsonObject) };
    delete codingNode.categories;
    if (Object.keys(codingNode).length === 0) {
      delete nextSettings.coding;
    } else {
      nextSettings.coding = codingNode;
    }
  }

  const optionAliases = parseStringArrayMapText(nextState.optionAliasesText);
  if (Object.keys(optionAliases).length > 0) {
    nextSettings.aliases = optionAliases;
  } else {
    delete nextSettings.aliases;
  }

  const specialAnswers = parseStringArrayMapText(nextState.specialAnswersText);
  if (Object.keys(specialAnswers).length > 0) {
    nextSettings.specialAnswers = specialAnswers;
  } else {
    delete nextSettings.specialAnswers;
  }

  const nextBranch = buildBranchCondition(question, nextState, existingBranchRoot, existingBranchRule, referenceQuestion);

  return {
    ...question,
    code: questionCode || question.code,
    settingsJson: JSON.stringify(nextSettings),
    branchConditionJson: JSON.stringify(nextBranch),
  };
}

function buildBranchCondition(
  question: SurveyBuilderQuestion,
  nextState: QuestionRuleFormState,
  existingBranchRoot: JsonObject,
  existingBranchRule: JsonObject,
  referenceQuestion?: SurveyBuilderQuestion,
): JsonObject {
  if (nextState.branchMode === "none") {
    return {};
  }

  const rule: JsonObject = { ...existingBranchRule };
  const groupCode = nextState.branchGroupCode.trim();
  const questionCode = nextState.branchQuestionCode.trim();
  const rowCode = nextState.branchRowCode.trim();
  const selectedOptionCodes = resolveBranchSelectedOptions(referenceQuestion ?? question, nextState.branchSelectedOptionCodes);
  const answerTagsAnyOf = inferBranchAnswerTags(referenceQuestion ?? question, selectedOptionCodes);

  delete rule.groupCode;
  delete rule.questionCode;
  delete rule.rowCode;
  delete rule.sameRowCode;
  delete rule.selectedOptionCodes;
  delete rule.answerTagsAnyOf;

  if (groupCode) {
    rule.groupCode = groupCode;
  }
  if (questionCode) {
    rule.questionCode = questionCode;
  }
  if (rowCode) {
    rule.rowCode = rowCode;
  }
  if (nextState.branchSameRowCode) {
    rule.sameRowCode = true;
  }
  if (selectedOptionCodes.length > 0) {
    rule.selectedOptionCodes = selectedOptionCodes;
  }
  if (answerTagsAnyOf.length > 0) {
    rule.answerTagsAnyOf = answerTagsAnyOf;
  }

  if (Object.keys(rule).length === 0) {
    return {};
  }

  const nextRoot: JsonObject = {};
  if (typeof existingBranchRoot.operator === "string" && existingBranchRoot.operator.trim()) {
    nextRoot.operator = existingBranchRoot.operator;
  }
  if (nextState.branchMode === "skipIf") {
    nextRoot.skipIf = rule;
  } else {
    nextRoot.askIf = rule;
  }
  return nextRoot;
}

function formatBranchSelectedOptions(question: SurveyBuilderQuestion, selectedOptionCodes: string[]): string {
  if (selectedOptionCodes.length === 0) {
    return "";
  }

  const options = question.options ?? [];
  return selectedOptionCodes
    .map((entry) => resolveOptionDisplayValue(options, entry) ?? entry)
    .join(", ");
}

function resolveBranchSelectedOptions(question: SurveyBuilderQuestion, value: string): string[] {
  const options = question.options ?? [];
  return splitCommaSeparatedValues(value).map((entry) => resolveOptionCode(options, entry) ?? slugify(entry));
}

function resolveOptionDisplayValue(options: SurveyQuestionOption[], value: string): string | null {
  const normalizedValue = normalizeComparableValue(value);
  if (!normalizedValue) {
    return null;
  }

  const match = options.find((option) => matchesOption(option, normalizedValue));
  return match?.label?.trim() || null;
}

function resolveOptionCode(options: SurveyQuestionOption[], value: string): string | null {
  const normalizedValue = normalizeComparableValue(value);
  if (!normalizedValue) {
    return null;
  }

  const match = options.find((option) => matchesOption(option, normalizedValue));
  if (!match) {
    return null;
  }

  return normalizeBranchOptionCode(match);
}

function inferBranchAnswerTags(question: SurveyBuilderQuestion, selectedOptionCodes: string[]): string[] {
  const options = question.options ?? [];
  const tags = new Set<string>();

  selectedOptionCodes.forEach((selectedCode) => {
    const normalizedCode = normalizeComparableValue(selectedCode);
    const matchedOption = options.find((option) => normalizeComparableValue(normalizeBranchOptionCode(option)) === normalizedCode);

    addBranchSemanticTags(tags, selectedCode);
    if (matchedOption) {
      addBranchSemanticTags(tags, matchedOption.label);
      addBranchSemanticTags(tags, matchedOption.code);
      addBranchSemanticTags(tags, matchedOption.value);
    }
  });

  return Array.from(tags);
}

function addBranchSemanticTags(tags: Set<string>, value: string | undefined): void {
  const normalized = normalizeComparableValue(value);
  if (!normalized) {
    return;
  }

  if (normalized.includes("hic_duymad")) {
    tags.add("knowledge_negative");
    tags.add("knowledge_never_heard");
  }
  if (normalized.includes("duydum") && normalized.includes("tanimiyorum")) {
    tags.add("knowledge_negative");
    tags.add("knowledge_heard_but_unknown");
  }
  if ((normalized.includes("tanimiyorum") || normalized.includes("bilmiyorum")) && !normalized.includes("taniyorum")) {
    tags.add("knowledge_negative");
  }
  if ((normalized.includes("taniyorum") || normalized.includes("biliyorum")) && !normalized.includes("tanimiyorum")) {
    tags.add("knowledge_positive");
  }
  if (normalized === "evet" || normalized.includes("_evet") || normalized.includes("evet_")) {
    tags.add("yes");
  }
  if (normalized === "hayir" || normalized.includes("_hayir") || normalized.includes("hayir_")) {
    tags.add("no");
  }
}

function matchesOption(option: SurveyQuestionOption, normalizedValue: string): boolean {
  return [option.code, option.value, option.label]
    .map((entry) => normalizeComparableValue(entry))
    .filter(Boolean)
    .includes(normalizedValue);
}

function normalizeBranchOptionCode(option: SurveyQuestionOption): string {
  return option.code?.trim() || option.value?.trim() || slugify(option.label);
}

function normalizeComparableValue(value: string | undefined): string {
  if (!value?.trim()) {
    return "";
  }

  return value
    .trim()
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/_+/g, "_")
    .replace(/^_+|_+$/g, "");
}

function inferMatrixType(type: SurveyQuestionType): string {
  if (type === "rating_1_5" || type === "rating_1_10") {
    return "GRID_RATING";
  }
  if (type === "multi_choice") {
    return "GRID_MULTI_CHOICE";
  }
  return "GRID_SINGLE_CHOICE";
}

function formatCodingCategories(settings: JsonObject): string {
  const codingNode = asObject(settings.coding);
  const categories = isRecord(codingNode.categories) ? (codingNode.categories as JsonObject) : {};
  return Object.entries(categories)
    .map(([categoryCode, aliases]) => {
      const values = Array.isArray(aliases)
        ? aliases.map((entry) => String(entry).trim()).filter(Boolean)
        : [];
      return values.length > 0 ? `${categoryCode}: ${values.join(" | ")}` : "";
    })
    .filter(Boolean)
    .join("\n");
}

function parseCodingCategoriesText(value: string): JsonObject {
  return value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .reduce<JsonObject>((accumulator, line) => {
      const [rawCode, rawAliases] = line.split(":", 2);
      if (!rawCode || !rawAliases) {
        return accumulator;
      }
      const categoryCode = slugify(rawCode);
      const aliases = rawAliases
        .split("|")
        .map((entry) => entry.trim())
        .filter(Boolean);
      if (!categoryCode || aliases.length === 0) {
        return accumulator;
      }
      accumulator[categoryCode] = aliases;
      return accumulator;
    }, {});
}

function parseStringArrayMapText(value: string): JsonObject {
  return value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .reduce<JsonObject>((accumulator, line) => {
      const [rawCode, rawAliases] = line.split(":", 2);
      if (!rawCode || !rawAliases) {
        return accumulator;
      }
      const key = slugify(rawCode);
      const aliases = rawAliases
        .split("|")
        .map((entry) => entry.trim())
        .filter(Boolean);
      if (!key || aliases.length === 0) {
        return accumulator;
      }
      accumulator[key] = aliases;
      return accumulator;
    }, {});
}

function splitCommaSeparatedValues(value: string): string[] {
  return value
    .split(/[,;\n]/)
    .map((entry) => entry.trim())
    .filter(Boolean);
}

function parseJsonObject(payload: string | undefined): JsonObject {
  if (!payload?.trim()) {
    return {};
  }

  try {
    const parsed = JSON.parse(payload) as unknown;
    return isRecord(parsed) ? { ...parsed } : {};
  } catch {
    return {};
  }
}

function readString(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function readBoolean(value: unknown): boolean {
  return value === true;
}

function readStringArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.map((entry) => (typeof entry === "string" ? entry : "")).filter(Boolean)
    : [];
}

function formatStringArrayMap(map: JsonObject): string {
  return Object.entries(map)
    .map(([key, value]) => {
      const values = readStringArray(value);
      return values.length > 0 ? `${key}: ${values.join(" | ")}` : "";
    })
    .filter(Boolean)
    .join("\n");
}

function asObject(value: unknown): JsonObject {
  return isRecord(value) ? { ...(value as JsonObject) } : {};
}

function isRecord(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function slugify(value: string): string {
  return value
    .trim()
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/_+/g, "_")
    .replace(/^_+|_+$/g, "");
}
