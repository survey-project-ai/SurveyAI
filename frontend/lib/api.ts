const localApiBaseUrl = "http://localhost:8080";
const publicApiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL ?? inferPublicApiBaseUrl();
const internalApiBaseUrl = process.env.INTERNAL_API_BASE_URL ?? publicApiBaseUrl;

export const API_BASE_URL = typeof window === "undefined" ? internalApiBaseUrl : publicApiBaseUrl;

// Raised when an API call answers 401: the session expired or was revoked while the page was open.
export const SESSION_EXPIRED_EVENT = "surveyai:session-expired";

export async function apiFetch(input: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(input, {
    ...init,
    credentials: "include",
    cache: "no-store",
  });

  if (response.status === 401 && typeof window !== "undefined") {
    window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
  }

  return response;
}

function inferPublicApiBaseUrl(): string {
  if (typeof window === "undefined") {
    return localApiBaseUrl;
  }

  const { protocol, hostname } = window.location;

  if (hostname === "localhost" || hostname === "127.0.0.1") {
    return localApiBaseUrl;
  }

  if (hostname.includes("-frontend-")) {
    return `${protocol}//${hostname.replace("-frontend-", "-backend-")}`;
  }

  if (hostname.includes("frontend")) {
    return `${protocol}//${hostname.replace("frontend", "backend")}`;
  }

  return localApiBaseUrl;
}
