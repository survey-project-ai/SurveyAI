import { API_BASE_URL, apiFetch, readApiError } from "@/lib/api";
import { requireCompanyId } from "@/lib/auth";
import { Survey } from "@/lib/types";

type SurveyApiResponse = {
  id: string;
  companyId: string;
  name: string;
  description: string | null;
  status: "DRAFT" | "PUBLISHED" | "ARCHIVED";
  languageCode: string;
  introPrompt: string | null;
  closingPrompt: string | null;
  maxRetryPerQuestion: number | null;
  createdByUserId: string | null;
  createdAt: string;
  updatedAt: string;
};

type ImportGoogleFormRequest = {
  formUrl: string;
  accessToken: string;
  languageCode?: string;
  introPrompt?: string;
  closingPrompt?: string;
  maxRetryPerQuestion?: number;
};

type ImportGoogleFormResponse = {
  survey: SurveyApiResponse;
  importedQuestionCount: number;
};

export async function fetchCompanySurveys(
  companyId?: string,
  init?: RequestInit,
): Promise<Survey[]> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const response = await apiFetch(`${API_BASE_URL}/api/v1/companies/${resolvedCompanyId}/surveys`, {
    ...init,
    headers: {
      Accept: "application/json",
      ...init?.headers,
    },
  });

  if (!response.ok) {
    throw new Error(await readApiError(response, "Anketler"));
  }

  const data = (await response.json()) as SurveyApiResponse[];
  return data.map(mapSurveyDtoToSurvey);
}

// Only a draft survey that no operation uses and that has no responses can be deleted; the API says why not.
export async function deleteSurvey(surveyId: string, companyId?: string): Promise<void> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const response = await apiFetch(`${API_BASE_URL}/api/v1/companies/${resolvedCompanyId}/surveys/${surveyId}`, {
    method: "DELETE",
    headers: { Accept: "application/json" },
  });

  if (!response.ok) {
    throw new Error(await readApiError(response, "Anket silme"));
  }
}

export async function importGoogleForm(
  request: ImportGoogleFormRequest,
  companyId?: string,
): Promise<{ surveyId: string; importedQuestionCount: number }> {
  const resolvedCompanyId = requireCompanyId(companyId);
  const response = await apiFetch(`${API_BASE_URL}/api/v1/companies/${resolvedCompanyId}/surveys/imports/google-forms`, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(await readApiError(response));
  }

  const data = (await response.json()) as ImportGoogleFormResponse;
  return {
    surveyId: data.survey.id,
    importedQuestionCount: data.importedQuestionCount,
  };
}

function mapSurveyDtoToSurvey(dto: SurveyApiResponse): Survey {
  return {
    id: dto.id,
    name: dto.name,
    goal: dto.description?.trim() || "Henüz açıklama eklenmedi.",
    status: mapSurveyStatus(dto.status),
    audience: dto.languageCode.toUpperCase(),
    completions: 0,
    responseRate: "0%",
    updatedAt: formatUpdatedAt(dto.updatedAt),
    channels: [],
    questions: 0,
    owner: "Atanmadı",
  };
}

function mapSurveyStatus(status: SurveyApiResponse["status"]): Survey["status"] {
  switch (status) {
    case "PUBLISHED":
      return "Live";
    case "ARCHIVED":
      return "Archived";
    case "DRAFT":
    default:
      return "Draft";
  }
}

function formatUpdatedAt(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "Az önce güncellendi";
  }

  return new Intl.DateTimeFormat("tr-TR", {
    month: "short",
    day: "numeric",
    year: "numeric",
  }).format(date);
}

