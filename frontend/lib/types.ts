import { ReactNode } from "react";

export type NavItem = {
  href: string;
  label: string;
  description: string;
  icon: ReactNode;
  children?: Array<{
    href: string;
    label: string;
    description?: string;
  }>;
};

export type Stat = {
  label: string;
  value: string;
  delta: string;
  tone: "positive" | "warning" | "danger";
  detail: string;
};

export type Kpi = {
  label: string;
  value: string;
  detail: string;
  tone: "positive" | "warning" | "danger" | "neutral";
};

export type Survey = {
  id: string;
  name: string;
  status: "Live" | "Draft" | "Archived";
  audience: string;
  completions: number;
  responseRate: string;
  updatedAt: string;
  goal: string;
  channels: string[];
  questions: number;
  owner: string;
};

export type SurveyQuestionType =
  | "short_text"
  | "long_text"
  | "yes_no"
  | "single_choice"
  | "single_choice_grid"
  | "rating_grid_1_5"
  | "rating_grid_1_10"
  | "multi_choice"
  | "dropdown"
  | "rating_1_5"
  | "rating_1_10"
  | "date"
  | "full_name"
  | "number"
  | "phone";

export type SurveyQuestionMatrixRow = {
  id: string;
  label: string;
  code?: string;
};

export type SurveyQuestionOption = {
  id: string;
  label: string;
  code?: string;
  value?: string;
};

export type SurveyImportSource = {
  provider: "GOOGLE_FORMS" | "FILE_UPLOAD";
  externalId?: string;
  fileName?: string;
  payloadJson?: string;
};

export type SurveyBuilderQuestion = {
  id: string;
  code: string;
  type: SurveyQuestionType;
  title: string;
  description: string;
  required: boolean;
  retryPrompt?: string;
  branchConditionJson?: string;
  settingsJson?: string;
  sourceExternalId?: string;
  sourcePayloadJson?: string;
  options?: SurveyQuestionOption[];
  matrixRows?: SurveyQuestionMatrixRow[];
};

export type SurveyBuilderSurvey = {
  id: string;
  name: string;
  summary: string;
  status: "Draft" | "Live" | "Archived";
  createdAt: string;
  publishedAt: string | null;
  updatedAt: string;
  questionCount: number;
  languageCode: string;
  introPrompt: string;
  closingPrompt: string;
  maxRetryPerQuestion: number;
  source?: SurveyImportSource;
  questions: SurveyBuilderQuestion[];
};

export type Operation = {
  id: string;
  name: string;
  status: "Draft" | "Ready" | "Running" | "Completed" | "Failed" | "Scheduled" | "Paused" | "Cancelled";
  sourceType: "STANDARD" | "IMPORTED_SURVEY_RESULTS";
  sourcePayloadJson: string | null;
  surveyId: string;
  survey: string;
  surveyStatus: Survey["status"] | null;
  surveyGoal: string | null;
  surveyAudience: string | null;
  surveyUpdatedAt: string | null;
  budget: string;
  reach: string;
  conversion: string;
  updatedAt: string;
  owner: string;
  channels: string[];
  summary: string;
  readiness: {
    surveyLinked: boolean;
    surveyPublished: boolean;
    contactsLoaded: boolean;
    startableState: boolean;
    readyToStart: boolean;
    blockingReasons: string[];
  };
  executionSummary: {
    totalCallJobs: number;
    pendingCallJobs: number;
    completedCallJobs: number;
    newlyPreparedCallJobs: number;
  };
  startedAt: string | null;
  completedAt: string | null;
  scheduledAt: string | null;
};

export type OperationContact = {
  id: string;
  name: string;
  phoneNumber: string;
  status: "Active" | "Completed" | "Failed" | "Retry" | "Invalid" | "Pending";
  createdAt: string;
  updatedAt: string;
};

export type CallJob = {
  id: string;
  operationId: string;
  operationContactId: string;
  personName: string;
  phoneNumber: string;
  status: "Queued" | "InProgress" | "Completed" | "Failed" | "Skipped";
  rawStatus: string;
  attemptCount: number;
  maxAttempts: number;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
  answerCount: number;
  lastResultSummary: string | null;
  createdAt: string;
  updatedAt: string;
  // ISO timestamp of updatedAt, for sorting; updatedAt is already formatted for display.
  updatedAtIso: string;
};

export type CallJobSurveyResponse = {
  id: string;
  status: "PARTIAL" | "COMPLETED" | "INVALID" | "ABANDONED";
  completionPercent: number;
  answerCount: number;
  validAnswerCount: number;
  usableResponse: boolean;
  startedAt: string;
  completedAt: string;
  aiSummaryText: string | null;
  transcriptText: string | null;
  answers: CallJobSurveyResponseAnswer[];
};

export type CallJobSurveyResponseAnswerOption = {
  id: string;
  code: string;
  label: string;
  value: string;
  optionOrder: number;
};

export type CallJobSurveyResponseAnswer = {
  answerId: string | null;
  questionId: string;
  questionCode: string;
  questionOrder: number;
  questionTitle: string;
  questionType: "SINGLE_CHOICE" | "MULTI_CHOICE" | "OPEN_ENDED" | "RATING" | "NUMBER";
  required: boolean;
  answerText: string | null;
  answerNumber: number | null;
  selectedOptionId: string | null;
  selectedOptionIds: string[];
  valid: boolean;
  invalidReason: string | null;
  rawInputText: string | null;
  displayValue: string;
  manuallyEdited: boolean;
  options: CallJobSurveyResponseAnswerOption[];
};

export type CallJobAttempt = {
  id: string;
  attemptNumber: number;
  latest: boolean;
  provider: string;
  providerCallId: string | null;
  status: string;
  dialedAt: string;
  connectedAt: string;
  endedAt: string;
  durationSeconds: number | null;
  hangupReason: string | null;
  failureReason: string | null;
  transcriptStorageKey: string | null;
  surveyResponse: CallJobSurveyResponse | null;
};

export type CallJobDetail = {
  id: string;
  operationId: string;
  operationName: string;
  surveyId: string;
  surveyName: string;
  operationContactId: string;
  personName: string;
  phoneNumber: string;
  status: CallJob["status"];
  rawStatus: string;
  scheduledFor: string;
  availableAt: string;
  attemptCount: number;
  maxAttempts: number;
  firstAttempt: boolean;
  retried: boolean;
  latestProviderCallId: string | null;
  latestTranscriptStorageKey: string | null;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
  failed: boolean;
  failureReason: string | null;
  retryable: boolean;
  redialable: boolean;
  partialResponseDataExists: boolean;
  transcriptSummary: string | null;
  transcriptText: string | null;
  surveyResponse: CallJobSurveyResponse | null;
  createdAt: string;
  updatedAt: string;
  attempts: CallJobAttempt[];
};

export type Contact = {
  id: string;
  name: string;
  company: string;
  role: string;
  status: "Active" | "Paused" | "Completed";
  lastTouch: string;
  score: string;
  region: string;
};

export type ActivityItem = {
  id: string;
  title: string;
  detail: string;
  time: string;
  status: "Active" | "Draft" | "Paused" | "Completed" | "Failed" | "Pending";
};

export type AttentionItem = {
  id: string;
  title: string;
  detail: string;
  owner: string;
  status: "Live" | "Draft" | "Archived" | "Active" | "Paused" | "Failed" | "Pending";
};

export type ActionItem = {
  id: string;
  title: string;
  detail: string;
  href: string;
  cta: string;
};

export type TableColumn<T> = {
  key: string;
  label: string;
  render: (row: T) => ReactNode;
};



export type OperationAnalyticsBreakdownItem = {
  key: string;
  label: string;
  count: number;
  percentage: number;
};

export type OperationAnalyticsAudienceBreakdown = {
  key: string;
  label: string;
  questionCode: string;
  questionTitle: string;
  answeredCount: number;
  breakdown: OperationAnalyticsBreakdownItem[];
};

export type OperationAnalyticsQuestionSummary = {
  questionId: string;
  questionCode: string;
  questionOrder: number;
  questionTitle: string;
  questionType: "SINGLE_CHOICE" | "MULTI_CHOICE" | "OPEN_ENDED" | "RATING" | "NUMBER";
  chartKind: "RATING" | "NUMBER" | "BINARY" | "CHOICE" | "MULTI_CHOICE" | "OPEN_ENDED";
  respondedContactCount: number;
  answeredCount: number;
  responseRate: number;
  dropOffCount: number;
  dropOffRate: number;
  averageRating: number | null;
  emptyStateMessage: string | null;
  reviewCount: number;
  reviewGroupOptions: string[];
  breakdown: OperationAnalyticsBreakdownItem[];
  specialAnswerBreakdown: OperationAnalyticsBreakdownItem[];
  sampleResponses: OperationAnalyticsSampleResponse[];
  rawResponses: OperationAnalyticsSampleResponse[];
};

export type OperationAnalyticsSampleResponse = {
  callJobId: string;
  respondentName: string;
  capturedAt: string | null;
  responseText: string;
  rawResponseText: string;
  codedThemes: string[];
  reviewReason: string | null;
};

export type OperationAnalyticsQuestionGroupRow = {
  questionId: string;
  questionCode: string;
  questionOrder: number;
  rowKey: string;
  rowLabel: string;
  answeredCount: number;
  responseRate: number;
};

export type OperationAnalyticsQuestionGroupSeries = {
  key: string;
  label: string;
  data: number[];
};

export type OperationAnalyticsQuestionGroupSummary = {
  groupCode: string;
  groupTitle: string;
  chartKind: "GROUPED_CHOICE" | "GROUPED_MULTI_CHOICE";
  optionSetCode: string | null;
  respondedContactCount: number;
  answeredRowCount: number;
  emptyStateMessage: string | null;
  rows: OperationAnalyticsQuestionGroupRow[];
  series: OperationAnalyticsQuestionGroupSeries[];
};

export type OperationAnalyticsTrendPoint = {
  label: string;
  // ISO date (yyyy-MM-dd) of the day; label is formatted for display.
  date: string;
  count: number;
};

export type OperationAnalyticsInsightItem = {
  key: string;
  title: string;
  detail: string;
  tone: "neutral" | "positive" | "warning";
};

export type OperationAnalytics = {
  operationId: string;
  totalContacts: number;
  totalCallJobs: number;
  totalPreparedJobs: number;
  totalCallsAttempted: number;
  totalCallAttempts: number;
  totalCompletedCalls: number;
  queuedJobs: number;
  inProgressJobs: number;
  completedCallJobs: number;
  failedCallJobs: number;
  skippedCallJobs: number;
  totalResponses: number;
  respondedContacts: number;
  completedResponses: number;
  partialResponses: number;
  abandonedResponses: number;
  invalidResponses: number;
  completionRate: number;
  responseRate: number;
  personResponseRate: number;
  contactReachRate: number;
  participationRate: number;
  averageCompletionPercent: number;
  partialData: boolean;
  insightSummary: string | null;
  insightItems: OperationAnalyticsInsightItem[];
  outcomeBreakdown: OperationAnalyticsBreakdownItem[];
  consentBreakdown: OperationAnalyticsBreakdownItem[];
  audienceBreakdowns: OperationAnalyticsAudienceBreakdown[];
  questionSummaries: OperationAnalyticsQuestionSummary[];
  questionGroups: OperationAnalyticsQuestionGroupSummary[];
  responseTrend: OperationAnalyticsTrendPoint[];
};


