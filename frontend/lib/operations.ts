import { API_BASE_URL, apiFetch, readApiError } from "@/lib/api";
import { requireCompanyId, requireCurrentUserId } from "@/lib/auth";
import { fetchCompanySurveys } from "@/lib/surveys";
import { CallJob, CallJobAttempt, CallJobDetail, CallJobSurveyResponse, CallJobSurveyResponseAnswer, CallJobSurveyResponseAnswerOption, DashboardSummary, Operation, OperationAnalytics, OperationAnalyticsAudienceBreakdown, OperationAnalyticsBreakdownItem, OperationAnalyticsInsightItem, OperationAnalyticsQuestionGroupRow, OperationAnalyticsQuestionGroupSeries, OperationAnalyticsQuestionGroupSummary, OperationAnalyticsQuestionSummary, OperationAnalyticsSampleResponse, OperationAnalyticsTrendPoint, OperationContact } from "@/lib/types";

type SurveyReference = {
  id: string;
  name: string;
  status: Operation["surveyStatus"];
  goal: string | null;
  audience: string | null;
  updatedAt: string | null;
};

type OperationApiStatus = "DRAFT" | "READY" | "RUNNING" | "COMPLETED" | "FAILED" | "SCHEDULED" | "PAUSED" | "CANCELLED";

type OperationApiResponse = {
  id: string;
  companyId: string;
  surveyId: string;
  name: string;
  status: OperationApiStatus;
  sourceType: Operation["sourceType"];
  sourcePayloadJson: string | null;
  scheduledAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
  createdByUserId: string | null;
  createdAt: string;
  updatedAt: string;
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
};

type OperationContactApiResponse = {
  id: string;
  companyId: string;
  operationId: string;
  name: string;
  phoneNumber: string;
  status: "PENDING" | "CALLING" | "COMPLETED" | "FAILED" | "RETRY" | "INVALID";
  createdAt: string;
  updatedAt: string;
};

type OperationContactStatusApi = OperationContactApiResponse["status"];

type OperationContactPageApiResponse = {
  items: OperationContactApiResponse[];
  totalItems: number;
  totalPages: number;
  page: number;
  size: number;
};

type OperationContactSummaryApiResponse = {
  totalContacts: number;
  statusCounts: Array<{
    status: OperationContactStatusApi;
    count: number;
  }>;
  latestContacts: OperationContactApiResponse[];
};

type CallJobApiStatus = "QUEUED" | "IN_PROGRESS" | "COMPLETED" | "FAILED" | "SKIPPED";

type CallJobApiResponse = {
  id: string;
  companyId: string;
  operationId: string;
  operationContactId: string;
  personName: string;
  phoneNumber: string;
  status: CallJobApiStatus;
  rawStatus: string;
  attemptCount: number;
  maxAttempts: number;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
  answerCount: number;
  lastResultSummary: string | null;
  createdAt: string;
  updatedAt: string;
};

type CallJobPageApiResponse = {
  items: CallJobApiResponse[];
  totalItems: number;
  totalPages: number;
  page: number;
  size: number;
};

type CallJobSurveyResponseApiResponse = {
  id: string;
  status: "PARTIAL" | "COMPLETED" | "INVALID" | "ABANDONED";
  completionPercent: number;
  answerCount: number;
  validAnswerCount: number;
  usableResponse: boolean;
  startedAt: string;
  completedAt: string | null;
  aiSummaryText: string | null;
  transcriptText: string | null;
  answers?: CallJobSurveyResponseAnswerApiResponse[];
};

type CallJobSurveyResponseAnswerOptionApiResponse = {
  id: string;
  code: string;
  label: string;
  value: string;
  optionOrder: number;
};

type CallJobSurveyResponseAnswerApiResponse = {
  answerId: string | null;
  questionId: string;
  questionCode: string;
  questionOrder: number;
  questionTitle: string;
  questionType: CallJobSurveyResponseAnswer["questionType"];
  required: boolean;
  answerText: string | null;
  answerNumber: number | null;
  selectedOptionId: string | null;
  selectedOptionIds: string[] | null;
  valid: boolean;
  invalidReason: string | null;
  rawInputText: string | null;
  displayValue: string;
  manuallyEdited: boolean;
  options: CallJobSurveyResponseAnswerOptionApiResponse[];
};

type CallJobAttemptApiResponse = {
  id: string;
  attemptNumber: number;
  latest: boolean;
  provider: string;
  providerCallId: string | null;
  status: string;
  dialedAt: string | null;
  connectedAt: string | null;
  endedAt: string | null;
  durationSeconds: number | null;
  hangupReason: string | null;
  failureReason: string | null;
  transcriptStorageKey: string | null;
  surveyResponse: CallJobSurveyResponseApiResponse | null;
};

type CallJobDetailApiResponse = {
  id: string;
  companyId: string;
  operationId: string;
  operationName: string;
  surveyId: string;
  surveyName: string;
  operationContactId: string;
  personName: string;
  phoneNumber: string;
  status: CallJobApiStatus;
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
  surveyResponse: CallJobSurveyResponseApiResponse | null;
  createdAt: string;
  updatedAt: string;
  attempts: CallJobAttemptApiResponse[];
};
type OperationAnalyticsBreakdownItemApiResponse = {
  key: string;
  label: string;
  count: number;
  percentage: number;
};

type OperationAnalyticsQuestionSummaryApiResponse = {
  questionId: string;
  questionCode: string;
  questionOrder: number;
  questionTitle: string;
  questionType: OperationAnalyticsQuestionSummary["questionType"];
  chartKind: OperationAnalyticsQuestionSummary["chartKind"];
  respondedContactCount: number;
  answeredCount: number;
  responseRate: number;
  dropOffCount: number;
  dropOffRate: number;
  averageRating: number | null;
  emptyStateMessage: string | null;
  reviewCount?: number;
  reviewGroupOptions?: string[];
  breakdown: OperationAnalyticsBreakdownItemApiResponse[];
  specialAnswerBreakdown: OperationAnalyticsBreakdownItemApiResponse[];
  sampleResponses: Array<OperationAnalyticsSampleResponseApiResponse | string>;
  rawResponses?: Array<OperationAnalyticsSampleResponseApiResponse | string>;
};

type OperationAnalyticsSampleResponseApiResponse = {
  callJobId: string;
  respondentName: string;
  capturedAt: string | null;
  responseText: string;
  rawResponseText?: string | null;
  codedThemes?: string[];
  reviewReason?: string | null;
};

type OperationAnalyticsQuestionGroupRowApiResponse = {
  questionId: string;
  questionCode: string;
  questionOrder: number;
  rowKey: string;
  rowLabel: string;
  answeredCount: number;
  responseRate: number;
};

type OperationAnalyticsQuestionGroupSeriesApiResponse = {
  key: string;
  label: string;
  data: number[];
};

type OperationAnalyticsQuestionGroupSummaryApiResponse = {
  groupCode: string;
  groupTitle: string;
  chartKind: OperationAnalyticsQuestionGroupSummary["chartKind"];
  optionSetCode: string | null;
  respondedContactCount: number;
  answeredRowCount: number;
  emptyStateMessage: string | null;
  rows: OperationAnalyticsQuestionGroupRowApiResponse[];
  series: OperationAnalyticsQuestionGroupSeriesApiResponse[];
};

type OperationAnalyticsInsightItemApiResponse = {
  key: string;
  title: string;
  detail: string;
  tone: OperationAnalyticsInsightItem["tone"];
};

type OperationAnalyticsTrendPointApiResponse = {
  label: string;
  count: number;
};

type DashboardSummaryApiResponse = {
  totals: {
    contactStatusCounts: Array<{ status: OperationContactStatusApi; count: number }>;
    totalContacts: number;
    respondedContacts: number;
    responseTrend: OperationAnalyticsTrendPointApiResponse[];
    totalCallJobs: number;
    queuedJobs: number;
    inProgressJobs: number;
    completedCallJobs: number;
    failedCallJobs: number;
    totalCallAttempts: number;
  };
  recentCallJobs: CallJobApiResponse[];
};

type OperationAnalyticsAudienceBreakdownApiResponse = {
  key: string;
  label: string;
  questionCode: string;
  questionTitle: string;
  answeredCount: number;
  breakdown: OperationAnalyticsBreakdownItemApiResponse[];
};

type OperationAnalyticsApiResponse = {
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
  insightItems: OperationAnalyticsInsightItemApiResponse[];
  outcomeBreakdown: OperationAnalyticsBreakdownItemApiResponse[];
  consentBreakdown: OperationAnalyticsBreakdownItemApiResponse[];
  audienceBreakdowns: OperationAnalyticsAudienceBreakdownApiResponse[];
  questionSummaries: OperationAnalyticsQuestionSummaryApiResponse[];
  questionGroups: OperationAnalyticsQuestionGroupSummaryApiResponse[];
  responseTrend: OperationAnalyticsTrendPointApiResponse[];
};

type CreateOperationRequest = {
  name: string;
  surveyId: string;
  scheduledAt: string | null;
  createdByUserId?: string | null;
};

type CreateOperationContactsRequest = {
  contacts: Array<{
    name: string;
    phoneNumber: string;
  }>;
};

export type OperationContactStatusSummary = {
  status: OperationContact["status"];
  count: number;
};

export type OperationContactSummary = {
  totalContacts: number;
  statusCounts: OperationContactStatusSummary[];
  latestContacts: OperationContact[];
};

export type OperationContactPage = {
  items: OperationContact[];
  totalItems: number;
  totalPages: number;
  page: number;
  size: number;
};

export type CallJobPage = {
  items: CallJob[];
  totalItems: number;
  totalPages: number;
  page: number;
  size: number;
};

export async function fetchOperationCallJobDetail(
  operationId: string,
  callJobId: string,
  companyId?: string,
  init?: RequestInit,
): Promise<CallJobDetail> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const response = await fetchJson<CallJobDetailApiResponse>(
    `${API_BASE_URL}/api/v1/operations/${operationId}/jobs/${callJobId}?companyId=${resolvedCompanyId}`,
    init,
    "Çağrı işi",
  );

  return mapCallJobDetailDto(response);
}

export async function retryOperationCallJob(
  operationId: string,
  callJobId: string,
  companyId?: string,
): Promise<CallJobDetail> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const response = await fetchJson<CallJobDetailApiResponse>(
    `${API_BASE_URL}/api/v1/operations/${operationId}/jobs/${callJobId}/retry?companyId=${resolvedCompanyId}`,
    {
      method: "POST",
    },
    "Tekrar deneme",
  );

  return mapCallJobDetailDto(response);
}

export async function redialOperationCallJob(
  operationId: string,
  callJobId: string,
  companyId?: string,
): Promise<CallJobDetail> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const response = await fetchJson<CallJobDetailApiResponse>(
    `${API_BASE_URL}/api/v1/operations/${operationId}/jobs/${callJobId}/redial?companyId=${resolvedCompanyId}`,
    {
      method: "POST",
    },
    "Yeniden arama",
  );

  return mapCallJobDetailDto(response);
}

export async function updateOperationCallJobSurveyResponse(
  operationId: string,
  callJobId: string,
  answers: Array<{
    questionId: string;
    answerText?: string | null;
    answerNumber?: number | null;
    selectedOptionId?: string | null;
    selectedOptionIds?: string[];
    codedThemes?: string[];
  }>,
  companyId?: string,
): Promise<CallJobDetail> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const response = await fetchJson<CallJobDetailApiResponse>(
    `${API_BASE_URL}/api/v1/operations/${operationId}/jobs/${callJobId}/survey-response?companyId=${resolvedCompanyId}`,
    {
      method: "PATCH",
      body: JSON.stringify({ answers }),
    },
    "Cevap güncelleme",
  );

  return mapCallJobDetailDto(response);
}

export async function fetchCompanyOperations(
  companyId?: string,
  init?: RequestInit,
): Promise<Operation[]> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const [operationsResponse, surveys] = await Promise.all([
    fetchJson<OperationApiResponse[]>(`${API_BASE_URL}/api/v1/operations?companyId=${resolvedCompanyId}`, init, "Operasyonlar"),
    fetchCompanySurveys(resolvedCompanyId, init),
  ]);

  return operationsResponse.map((dto) => mapOperationDtoToOperation(dto, surveys));
}

export async function fetchOperationById(
  operationId: string,
  companyId?: string,
  init?: RequestInit,
): Promise<Operation> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const [operationResponse, surveys] = await Promise.all([
    fetchJson<OperationApiResponse>(`${API_BASE_URL}/api/v1/operations/${operationId}?companyId=${resolvedCompanyId}`, init, "Operasyon"),
    fetchCompanySurveys(resolvedCompanyId, init),
  ]);

  return mapOperationDtoToOperation(operationResponse, surveys);
}

export async function fetchOperationAnalytics(
  operationId: string,
  companyId?: string,
  init?: RequestInit,
): Promise<OperationAnalytics> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const response = await fetchJson<OperationAnalyticsApiResponse>(
    `${API_BASE_URL}/api/v1/operations/${operationId}/analytics?companyId=${resolvedCompanyId}`,
    init,
    "Operasyon analitiği",
  );

  const respondedContacts =
    response.respondedContacts
    ?? response.totalResponses
    ?? ((response.completedResponses ?? 0) + (response.partialResponses ?? 0));

  if (response.respondedContacts === undefined) {
    console.warn("analytics contract incomplete: respondedContacts missing", {
      operationId,
      totalResponses: response.totalResponses,
      completedResponses: response.completedResponses,
      partialResponses: response.partialResponses,
    });
  }

  const mapSampleResponse = (
    questionId: string,
    sample: OperationAnalyticsSampleResponseApiResponse | string,
    sampleIndex: number,
  ): OperationAnalyticsSampleResponse => {
    if (typeof sample === "string") {
      return {
        callJobId: `legacy-${questionId}-${sampleIndex}`,
        respondentName: "Katılımcı",
        capturedAt: null,
        responseText: sample,
        rawResponseText: sample,
        codedThemes: [],
        reviewReason: null,
      };
    }

    return {
      callJobId: sample.callJobId,
      respondentName: sample.respondentName,
      capturedAt: sample.capturedAt ? formatDateTime(sample.capturedAt) : null,
      responseText: sample.responseText ?? "",
      rawResponseText: sample.rawResponseText ?? sample.responseText ?? "",
      codedThemes: sample.codedThemes ?? [],
      reviewReason: sample.reviewReason ?? null,
    };
  };

  return {
    operationId: response.operationId,
    totalContacts: response.totalContacts,
    totalCallJobs: response.totalCallJobs,
    totalPreparedJobs: response.totalPreparedJobs,
    totalCallsAttempted: response.totalCallsAttempted,
    totalCallAttempts: response.totalCallAttempts ?? 0,
    totalCompletedCalls: response.totalCompletedCalls,
    queuedJobs: response.queuedJobs,
    inProgressJobs: response.inProgressJobs,
    completedCallJobs: response.completedCallJobs,
    failedCallJobs: response.failedCallJobs,
    skippedCallJobs: response.skippedCallJobs,
    totalResponses: response.totalResponses,
    respondedContacts,
    completedResponses: response.completedResponses,
    partialResponses: response.partialResponses,
    abandonedResponses: response.abandonedResponses,
    invalidResponses: response.invalidResponses,
    completionRate: response.completionRate,
    responseRate: response.responseRate,
    personResponseRate: response.personResponseRate ?? response.responseRate,
    contactReachRate: response.contactReachRate,
    participationRate: response.participationRate,
    averageCompletionPercent: response.averageCompletionPercent,
    partialData: response.partialData,
    insightSummary: response.insightSummary,
    insightItems: (response.insightItems ?? []).map((item) => ({
      key: item.key,
      title: item.title,
      detail: item.detail,
      tone: item.tone,
    })),
    outcomeBreakdown: (response.outcomeBreakdown ?? []).map(mapAnalyticsBreakdown),
    consentBreakdown: (response.consentBreakdown ?? []).map(mapAnalyticsBreakdown),
    audienceBreakdowns: (response.audienceBreakdowns ?? []).map((item): OperationAnalyticsAudienceBreakdown => ({
      key: item.key,
      label: item.label,
      questionCode: item.questionCode,
      questionTitle: item.questionTitle,
      answeredCount: item.answeredCount,
      breakdown: (item.breakdown ?? []).map(mapAnalyticsBreakdown),
    })),
    questionSummaries: (response.questionSummaries ?? []).map((item) => ({
      questionId: item.questionId,
      questionCode: item.questionCode,
      questionOrder: item.questionOrder,
      questionTitle: item.questionTitle,
      questionType: item.questionType,
      chartKind: item.chartKind,
      respondedContactCount: item.respondedContactCount ?? respondedContacts,
      answeredCount: item.answeredCount,
      responseRate: item.responseRate,
      dropOffCount: item.dropOffCount ?? 0,
      dropOffRate: item.dropOffRate ?? 0,
      averageRating: item.averageRating,
      emptyStateMessage: item.emptyStateMessage,
      reviewCount: item.reviewCount ?? 0,
      reviewGroupOptions: item.reviewGroupOptions ?? [],
      breakdown: (item.breakdown ?? []).map(mapAnalyticsBreakdown),
      specialAnswerBreakdown: (item.specialAnswerBreakdown ?? []).map(mapAnalyticsBreakdown),
      sampleResponses: (item.sampleResponses ?? []).map((sample, sampleIndex) => mapSampleResponse(item.questionId, sample, sampleIndex)),
      rawResponses: (item.rawResponses ?? []).map((sample, sampleIndex) => mapSampleResponse(item.questionId, sample, sampleIndex)),
    })),
    questionGroups: (response.questionGroups ?? []).map((item): OperationAnalyticsQuestionGroupSummary => ({
      groupCode: item.groupCode,
      groupTitle: item.groupTitle,
      chartKind: item.chartKind,
      optionSetCode: item.optionSetCode,
      respondedContactCount: item.respondedContactCount ?? respondedContacts,
      answeredRowCount: item.answeredRowCount ?? 0,
      emptyStateMessage: item.emptyStateMessage,
      rows: (item.rows ?? []).map((row): OperationAnalyticsQuestionGroupRow => ({
        questionId: row.questionId,
        questionCode: row.questionCode,
        questionOrder: row.questionOrder,
        rowKey: row.rowKey,
        rowLabel: row.rowLabel,
        answeredCount: row.answeredCount,
        responseRate: row.responseRate,
      })),
      series: (item.series ?? []).map((series): OperationAnalyticsQuestionGroupSeries => ({
        key: series.key,
        label: series.label,
        data: series.data ?? [],
      })),
    })),
    responseTrend: (response.responseTrend ?? []).map(mapTrendPoint),
  };
}

export async function startOperation(
  operationId: string,
  companyId?: string,
): Promise<Operation> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const [operationResponse, surveys] = await Promise.all([
    fetchJson<OperationApiResponse>(`${API_BASE_URL}/api/v1/operations/${operationId}/start?companyId=${resolvedCompanyId}`, {
      method: "POST",
    }, "Operasyonu başlatma"),
    fetchCompanySurveys(resolvedCompanyId),
  ]);

  return mapOperationDtoToOperation(operationResponse, surveys);
}

export async function pauseOperation(
  operationId: string,
  companyId?: string,
): Promise<Operation> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const [operationResponse, surveys] = await Promise.all([
    fetchJson<OperationApiResponse>(`${API_BASE_URL}/api/v1/operations/${operationId}/pause?companyId=${resolvedCompanyId}`, {
      method: "POST",
    }, "Operasyonu duraklatma"),
    fetchCompanySurveys(resolvedCompanyId),
  ]);

  return mapOperationDtoToOperation(operationResponse, surveys);
}

export async function resumeOperation(
  operationId: string,
  companyId?: string,
): Promise<Operation> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const [operationResponse, surveys] = await Promise.all([
    fetchJson<OperationApiResponse>(`${API_BASE_URL}/api/v1/operations/${operationId}/resume?companyId=${resolvedCompanyId}`, {
      method: "POST",
    }, "Operasyonu devam ettirme"),
    fetchCompanySurveys(resolvedCompanyId),
  ]);

  return mapOperationDtoToOperation(operationResponse, surveys);
}

export async function deleteOperation(
  operationId: string,
  companyId?: string,
): Promise<void> {
  const resolvedCompanyId = requireCompanyId(companyId);
  await fetchJson<void>(`${API_BASE_URL}/api/v1/operations/${operationId}?companyId=${resolvedCompanyId}`, {
    method: "DELETE",
  }, "Operasyon silme");
}

export async function fetchOperationContacts(
  operationId: string,
  companyId?: string,
  init?: RequestInit,
): Promise<OperationContact[]> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const response = await fetchJson<OperationContactApiResponse[]>(
    `${API_BASE_URL}/api/v1/operations/${operationId}/contacts?companyId=${resolvedCompanyId}`,
    init,
    "Kişiler",
  );

  return response.map(mapOperationContactDtoToContact);
}

export async function fetchOperationContactsPage(
  operationId: string,
  options?: {
    companyId?: string;
    page?: number;
    size?: number;
    query?: string;
    status?: OperationContact["status"] | "All";
    init?: RequestInit;
  },
): Promise<OperationContactPage> {
  const companyId = requireCompanyId(options?.companyId);
  const searchParams = new URLSearchParams({ companyId });

  searchParams.set("page", String(options?.page ?? 0));
  searchParams.set("size", String(options?.size ?? 25));

  if (options?.query?.trim()) {
    searchParams.set("query", options.query.trim());
  }

  const status = mapOperationContactStatusToApi(options?.status);
  if (status) {
    searchParams.set("status", status);
  }

  const response = await fetchJson<OperationContactPageApiResponse>(
    `${API_BASE_URL}/api/v1/operations/${operationId}/contacts/list?${searchParams.toString()}`,
    options?.init,
    "Kişi listesi",
  );

  return {
    items: response.items.map(mapOperationContactDtoToContact),
    totalItems: response.totalItems,
    totalPages: response.totalPages,
    page: response.page,
    size: response.size,
  };
}

export async function fetchOperationCallJobsPage(
  operationId: string,
  options?: {
    companyId?: string;
    page?: number;
    size?: number;
    query?: string;
    statuses?: Array<CallJob["status"] | "All">;
    sortBy?: "createdAt" | "updatedAt";
    direction?: "asc" | "desc";
    init?: RequestInit;
  },
): Promise<CallJobPage> {
  const companyId = requireCompanyId(options?.companyId);
  const searchParams = new URLSearchParams({ companyId });

  searchParams.set("page", String(options?.page ?? 0));
  searchParams.set("size", String(options?.size ?? 25));
  searchParams.set("sortBy", options?.sortBy ?? "createdAt");
  searchParams.set("direction", options?.direction ?? "desc");

  if (options?.query?.trim()) {
    searchParams.set("query", options.query.trim());
  }

  const statuses = (options?.statuses ?? [])
    .map((status) => mapCallJobStatusToApi(status))
    .filter((status): status is CallJobApiStatus => status !== null);
  for (const status of statuses) {
    searchParams.append("status", status);
  }

  const response = await fetchJson<CallJobPageApiResponse>(
    `${API_BASE_URL}/api/v1/operations/${operationId}/jobs?${searchParams.toString()}`,
    options?.init,
    "Çağrı listesi",
  );

  return {
    items: response.items.map(mapCallJobDtoToCallJob),
    totalItems: response.totalItems,
    totalPages: response.totalPages,
    page: response.page,
    size: response.size,
  };
}

export async function fetchDashboardSummary(companyId?: string, init?: RequestInit): Promise<DashboardSummary> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const response = await fetchJson<DashboardSummaryApiResponse>(
    `${API_BASE_URL}/api/v1/dashboard/summary?companyId=${resolvedCompanyId}`,
    init,
    "Ana sayfa özeti",
  );
  const { totals } = response;

  return {
    contactStatusCounts: totals.contactStatusCounts.map((item) => ({
      status: mapOperationContactStatus(item.status),
      count: item.count,
    })),
    totalContacts: totals.totalContacts,
    respondedContacts: totals.respondedContacts,
    responseTrend: totals.responseTrend.map(mapTrendPoint),
    totalCallJobs: totals.totalCallJobs,
    queuedJobs: totals.queuedJobs,
    inProgressJobs: totals.inProgressJobs,
    completedCallJobs: totals.completedCallJobs,
    failedCallJobs: totals.failedCallJobs,
    totalCallAttempts: totals.totalCallAttempts,
    recentCallJobs: response.recentCallJobs.map(mapCallJobDtoToCallJob),
  };
}

export async function fetchOperationContactSummary(
  operationId: string,
  options?: {
    companyId?: string;
    latestLimit?: number;
    init?: RequestInit;
  },
): Promise<OperationContactSummary> {
  const companyId = requireCompanyId(options?.companyId);
  const latestLimit = options?.latestLimit ?? 5;
  const response = await fetchJson<OperationContactSummaryApiResponse>(
    `${API_BASE_URL}/api/v1/operations/${operationId}/contacts/summary?companyId=${companyId}&latestLimit=${latestLimit}`,
    options?.init,
    "Kişi özeti",
  );

  return {
    totalContacts: response.totalContacts,
    statusCounts: response.statusCounts.map((item) => ({
      status: mapOperationContactStatus(item.status),
      count: item.count,
    })),
    latestContacts: response.latestContacts.map(mapOperationContactDtoToContact),
  };
}

export async function exportOperationContacts(
  operationId: string,
  companyId?: string,
): Promise<void> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const response = await apiFetch(`${API_BASE_URL}/api/v1/operations/${operationId}/contacts/export?companyId=${resolvedCompanyId}`, {
    headers: {
      Accept: "text/csv",
    },
  });

  if (!response.ok) {
    throw new Error(await readApiError(response, "Kişi dışa aktarma"));
  }

  const blob = await response.blob();
  const disposition = response.headers.get("Content-Disposition") ?? "";
  const fileNameMatch = disposition.match(/filename="?([^\"]+)"?/i);
  const fileName = fileNameMatch?.[1] ?? `operation-${operationId}-contacts.csv`;
  const objectUrl = window.URL.createObjectURL(blob);
  const link = document.createElement("a");

  link.href = objectUrl;
  link.download = fileName;
  link.click();
  window.URL.revokeObjectURL(objectUrl);
}

export async function createOperation(
  request: CreateOperationRequest,
  companyId?: string,
): Promise<OperationApiResponse> {
  const resolvedCompanyId = requireCompanyId(companyId);
  return fetchJson<OperationApiResponse>(`${API_BASE_URL}/api/v1/operations?companyId=${resolvedCompanyId}`, {
    method: "POST",
    body: JSON.stringify({
      ...request,
      createdByUserId: requireCurrentUserId(request.createdByUserId),
    }),
  }, "Operasyon");
}

const CONTACT_UPLOAD_BATCH_SIZE = 200;

type ContactUploadRow = {
  name: string;
  normalizedPhoneNumber: string;
};

export type ContactUploadResult<T> = {
  importedRows: T[];
  failedRows: Array<{ row: T; reason: string }>;
};

// Uploads contacts in batches of 200 instead of one request per row. The API saves a batch all or nothing,
// so a batch it rejects (400/409) is halved until the rejected rows stand alone and the rest is saved.
// Any other error (session, network, server) fails that batch once and the upload moves on.
export async function uploadOperationContactsInBatches<T extends ContactUploadRow>(
  operationId: string,
  rows: T[],
  onProgress?: (processedRows: number, totalRows: number) => void,
): Promise<ContactUploadResult<T>> {
  const result: ContactUploadResult<T> = { importedRows: [], failedRows: [] };
  let processedRows = 0;

  async function upload(batch: T[]): Promise<void> {
    try {
      await createOperationContacts(operationId, {
        contacts: batch.map((row) => ({ name: row.name, phoneNumber: row.normalizedPhoneNumber })),
      });
      result.importedRows.push(...batch);
    } catch (error) {
      const rejectedByApi = error instanceof ApiError && (error.status === 400 || error.status === 409);
      if (rejectedByApi && batch.length > 1) {
        const middle = Math.ceil(batch.length / 2);
        await upload(batch.slice(0, middle));
        await upload(batch.slice(middle));
        return;
      }
      const reason = error instanceof Error ? error.message : "Kişi eklenemedi.";
      result.failedRows.push(...batch.map((row) => ({ row, reason })));
    }
    processedRows += batch.length;
    onProgress?.(processedRows, rows.length);
  }

  for (let start = 0; start < rows.length; start += CONTACT_UPLOAD_BATCH_SIZE) {
    await upload(rows.slice(start, start + CONTACT_UPLOAD_BATCH_SIZE));
  }

  return result;
}

export async function createOperationContacts(
  operationId: string,
  request: CreateOperationContactsRequest,
  companyId?: string,
): Promise<OperationContact[]> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const response = await fetchJson<OperationContactApiResponse[]>(
    `${API_BASE_URL}/api/v1/operations/${operationId}/contacts?companyId=${resolvedCompanyId}`,
    {
      method: "POST",
      body: JSON.stringify(request),
    },
    "Kişiler",
  );

  return response.map(mapOperationContactDtoToContact);
}

// An API error that keeps the HTTP status, so callers can tell a rejected request from a failed one.
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function fetchJson<T>(
  input: string,
  init: RequestInit | undefined,
  resourceName: string,
): Promise<T> {
  const response = await apiFetch(input, {
    ...init,
    headers: {
      Accept: "application/json",
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers,
    },
  });

  if (!response.ok) {
    throw new ApiError(await readApiError(response, resourceName), response.status);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

function mapAnalyticsBreakdown(item: OperationAnalyticsBreakdownItemApiResponse): OperationAnalyticsBreakdownItem {
  return {
    key: item.key,
    label: item.label,
    count: item.count,
    percentage: item.percentage,
  };
}

function mapOperationDtoToOperation(dto: OperationApiResponse, surveys: SurveyReference[]): Operation {
  const survey = surveys.find((item) => item.id === dto.surveyId);
  const updatedAtSource = dto.completedAt ?? dto.startedAt ?? dto.scheduledAt ?? dto.updatedAt;

  return {
    id: dto.id,
    name: dto.name,
    status: mapOperationStatus(dto.status),
    sourceType: dto.sourceType,
    sourcePayloadJson: dto.sourcePayloadJson,
    surveyId: dto.surveyId,
    survey: survey?.name ?? formatSurveyFallback(dto.surveyId),
    surveyStatus: survey?.status ?? null,
    surveyGoal: survey?.goal ?? null,
    surveyAudience: survey?.audience ?? null,
    surveyUpdatedAt: survey?.updatedAt ?? null,
    budget: "Hazırlanıyor",
    reach: formatReach(dto.status, dto.startedAt, dto.completedAt),
    conversion: dto.executionSummary.totalCallJobs > 0 ? `${dto.executionSummary.pendingCallJobs} bekleyen iş` : "Henüz yok",
    updatedAt: formatDateTime(updatedAtSource),
    owner: formatOwner(dto.createdByUserId),
    channels: buildChannels(dto.status),
    summary: buildSummary(dto, survey?.name),
    readiness: dto.readiness,
    executionSummary: {
      totalCallJobs: dto.executionSummary.totalCallJobs,
      pendingCallJobs: dto.executionSummary.pendingCallJobs,
      completedCallJobs: dto.executionSummary.completedCallJobs,
      newlyPreparedCallJobs: dto.executionSummary.newlyPreparedCallJobs,
    },
    startedAt: dto.startedAt,
    completedAt: dto.completedAt,
    scheduledAt: dto.scheduledAt,
  };
}

function mapTrendPoint(item: OperationAnalyticsTrendPointApiResponse): OperationAnalyticsTrendPoint {
  return {
    label: formatAnalyticsDate(item.label),
    date: item.label,
    count: item.count,
  };
}

function mapOperationContactDtoToContact(dto: OperationContactApiResponse): OperationContact {
  return {
    id: dto.id,
    name: dto.name,
    phoneNumber: dto.phoneNumber,
    status: mapOperationContactStatus(dto.status),
    createdAt: formatDateTime(dto.createdAt),
    updatedAt: formatDateTime(dto.updatedAt),
  };
}

function mapCallJobDtoToCallJob(dto: CallJobApiResponse): CallJob {
  return {
    id: dto.id,
    operationId: dto.operationId,
    operationContactId: dto.operationContactId,
    personName: dto.personName,
    phoneNumber: dto.phoneNumber,
    status: mapCallJobStatus(dto.status),
    rawStatus: dto.rawStatus,
    attemptCount: dto.attemptCount,
    maxAttempts: dto.maxAttempts,
    lastErrorCode: dto.lastErrorCode,
    lastErrorMessage: dto.lastErrorMessage,
    answerCount: typeof dto.answerCount === "number" ? dto.answerCount : 0,
    lastResultSummary: dto.lastResultSummary,
    createdAt: formatDateTime(dto.createdAt),
    updatedAt: formatDateTime(dto.updatedAt),
    updatedAtIso: dto.updatedAt,
  };
}

function mapCallJobDetailDto(dto: CallJobDetailApiResponse): CallJobDetail {
  return {
    id: dto.id,
    operationId: dto.operationId,
    operationName: dto.operationName,
    surveyId: dto.surveyId,
    surveyName: dto.surveyName,
    operationContactId: dto.operationContactId,
    personName: dto.personName,
    phoneNumber: dto.phoneNumber,
    status: mapCallJobStatus(dto.status),
    rawStatus: dto.rawStatus,
    scheduledFor: formatDateTime(dto.scheduledFor),
    availableAt: formatDateTime(dto.availableAt),
    attemptCount: dto.attemptCount,
    maxAttempts: dto.maxAttempts,
    firstAttempt: dto.firstAttempt,
    retried: dto.retried,
    latestProviderCallId: dto.latestProviderCallId,
    latestTranscriptStorageKey: dto.latestTranscriptStorageKey,
    lastErrorCode: dto.lastErrorCode,
    lastErrorMessage: dto.lastErrorMessage,
    failed: dto.failed,
    failureReason: dto.failureReason,
    retryable: dto.retryable,
    redialable: dto.redialable,
    partialResponseDataExists: dto.partialResponseDataExists,
    transcriptSummary: dto.transcriptSummary,
    transcriptText: dto.transcriptText,
    surveyResponse: mapCallJobSurveyResponse(dto.surveyResponse),
    createdAt: formatDateTime(dto.createdAt),
    updatedAt: formatDateTime(dto.updatedAt),
    attempts: (dto.attempts ?? []).map(mapCallJobAttempt),
  };
}

function mapCallJobAttempt(dto: CallJobAttemptApiResponse): CallJobAttempt {
  return {
    id: dto.id,
    attemptNumber: dto.attemptNumber,
    latest: dto.latest,
    provider: dto.provider,
    providerCallId: dto.providerCallId,
    status: dto.status,
    dialedAt: formatDateTime(dto.dialedAt),
    connectedAt: formatDateTime(dto.connectedAt),
    endedAt: formatDateTime(dto.endedAt),
    durationSeconds: dto.durationSeconds,
    hangupReason: dto.hangupReason,
    failureReason: dto.failureReason,
    transcriptStorageKey: dto.transcriptStorageKey,
    surveyResponse: mapCallJobSurveyResponse(dto.surveyResponse),
  };
}

function mapCallJobSurveyResponse(dto: CallJobSurveyResponseApiResponse | null): CallJobSurveyResponse | null {
  if (!dto) {
    return null;
  }

  return {
    id: dto.id,
    status: dto.status,
    completionPercent: dto.completionPercent,
    answerCount: typeof dto.answerCount === "number" ? dto.answerCount : 0,
    validAnswerCount: dto.validAnswerCount,
    usableResponse: dto.usableResponse,
    startedAt: formatDateTime(dto.startedAt),
    completedAt: formatDateTime(dto.completedAt),
    aiSummaryText: dto.aiSummaryText,
    transcriptText: dto.transcriptText,
    answers: (dto.answers ?? []).map(mapCallJobSurveyResponseAnswer),
  };
}

function mapCallJobSurveyResponseAnswer(dto: CallJobSurveyResponseAnswerApiResponse): CallJobSurveyResponseAnswer {
  return {
    answerId: dto.answerId,
    questionId: dto.questionId,
    questionCode: dto.questionCode,
    questionOrder: dto.questionOrder,
    questionTitle: dto.questionTitle,
    questionType: dto.questionType,
    required: dto.required,
    answerText: dto.answerText,
    answerNumber: dto.answerNumber,
    selectedOptionId: dto.selectedOptionId,
    selectedOptionIds: dto.selectedOptionIds ?? [],
    valid: dto.valid,
    invalidReason: dto.invalidReason,
    rawInputText: dto.rawInputText,
    displayValue: dto.displayValue,
    manuallyEdited: dto.manuallyEdited,
    options: (dto.options ?? []).map(mapCallJobSurveyResponseAnswerOption),
  };
}

function mapCallJobSurveyResponseAnswerOption(dto: CallJobSurveyResponseAnswerOptionApiResponse): CallJobSurveyResponseAnswerOption {
  return {
    id: dto.id,
    code: dto.code,
    label: dto.label,
    value: dto.value,
    optionOrder: dto.optionOrder,
  };
}

function mapOperationStatus(status: OperationApiStatus): Operation["status"] {
  switch (status) {
    case "READY":
      return "Ready";
    case "RUNNING":
      return "Running";
    case "COMPLETED":
      return "Completed";
    case "FAILED":
      return "Failed";
    case "SCHEDULED":
      return "Scheduled";
    case "PAUSED":
      return "Paused";
    case "CANCELLED":
      return "Cancelled";
    case "DRAFT":
    default:
      return "Draft";
  }
}

function mapOperationContactStatus(status: OperationContactApiResponse["status"]): OperationContact["status"] {
  switch (status) {
    case "CALLING":
      return "Active";
    case "COMPLETED":
      return "Completed";
    case "FAILED":
      return "Failed";
    case "RETRY":
      return "Retry";
    case "INVALID":
      return "Invalid";
    case "PENDING":
    default:
      return "Pending";
  }
}

function mapOperationContactStatusToApi(
  status: OperationContact["status"] | "All" | undefined,
): OperationContactStatusApi | null {
  switch (status) {
    case "Active":
      return "CALLING";
    case "Completed":
      return "COMPLETED";
    case "Failed":
      return "FAILED";
    case "Retry":
      return "RETRY";
    case "Invalid":
      return "INVALID";
    case "Pending":
      return "PENDING";
    case "All":
    case undefined:
    default:
      return null;
  }
}

function mapCallJobStatus(status: CallJobApiStatus): CallJob["status"] {
  switch (status) {
    case "IN_PROGRESS":
      return "InProgress";
    case "COMPLETED":
      return "Completed";
    case "FAILED":
      return "Failed";
    case "SKIPPED":
      return "Skipped";
    case "QUEUED":
    default:
      return "Queued";
  }
}

function mapCallJobStatusToApi(
  status: CallJob["status"] | "All" | undefined,
): CallJobApiStatus | null {
  switch (status) {
    case "InProgress":
      return "IN_PROGRESS";
    case "Completed":
      return "COMPLETED";
    case "Failed":
      return "FAILED";
    case "Skipped":
      return "SKIPPED";
    case "Queued":
      return "QUEUED";
    case "All":
    case undefined:
    default:
      return null;
  }
}

function formatDateTime(value: string | null): string {
  if (!value) {
    return "Planlanmadı";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "Güncel";
  }

  return new Intl.DateTimeFormat("tr-TR", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

function formatAnalyticsDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("tr-TR", {
    day: "2-digit",
    month: "short",
  }).format(date);
}

function formatSurveyFallback(surveyId: string): string {
  return `Anket ${surveyId.slice(0, 8)}`;
}

function formatOwner(createdByUserId: string | null): string {
  if (!createdByUserId) {
    return "Atanmadı";
  }

  return `Kullanıcı ${createdByUserId.slice(0, 8)}`;
}

function formatReach(status: OperationApiStatus, startedAt: string | null, completedAt: string | null): string {
  if (status === "COMPLETED" && completedAt) {
    return "Tamamlandı";
  }

  if (status === "RUNNING" || startedAt) {
    return "Yürütülüyor";
  }

  if (status === "READY" || status === "SCHEDULED") {
    return "Hazır";
  }

  if (status === "FAILED") {
    return "Müdahale gerekli";
  }

  return "Hazırlanıyor";
}

function buildChannels(status: OperationApiStatus): string[] {
  switch (status) {
    case "RUNNING":
      return ["Ses operasyonu", "Kuyruk hazır"];
    case "READY":
      return ["Başlatmaya hazır", "Anket bağlı"];
    case "COMPLETED":
      return ["Yürütme tamamlandı"];
    case "FAILED":
      return ["Hata incelemesi"];
    case "PAUSED":
      return ["Duraklatıldı"];
    case "CANCELLED":
      return ["Kapatıldı"];
    case "SCHEDULED":
      return ["Planlandı"];
    case "DRAFT":
    default:
      return ["Hazırlık aşaması"];
  }
}

function buildSummary(dto: OperationApiResponse, surveyName?: string): string {
  const surveyLabel = surveyName ?? formatSurveyFallback(dto.surveyId);

  if (dto.status === "RUNNING") {
    return `${surveyLabel} için yürütme açıldı. ${dto.executionSummary.pendingCallJobs} iş kuyrukta bekliyor.`;
  }

  if (dto.readiness.readyToStart) {
    return `${surveyLabel} bağlı, kişiler yüklü. Operasyon başlatmaya hazır.`;
  }

  if (dto.readiness.blockingReasons.length > 0) {
    return dto.readiness.blockingReasons[0];
  }

  return `${surveyLabel} için operasyon hazırlıkta.`;
}



