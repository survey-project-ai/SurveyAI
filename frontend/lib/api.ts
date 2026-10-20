const localApiBaseUrl = "http://localhost:8080";
const publicApiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL ?? inferPublicApiBaseUrl();
const internalApiBaseUrl = process.env.INTERNAL_API_BASE_URL ?? publicApiBaseUrl;

export const API_BASE_URL = typeof window === "undefined" ? internalApiBaseUrl : publicApiBaseUrl;

// Raised when an API call answers 401: the session expired or was revoked while the page was open.
export const SESSION_EXPIRED_EVENT = "surveyai:session-expired";

// fetch, with the browser's "Failed to fetch" replaced by a message the user can act on. Aborts pass through.
export async function sendRequest(input: string, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(input, init);
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw error;
    }
    throw new Error("Sunucuya bağlanılamadı. İnternet bağlantınızı kontrol edip tekrar deneyin.");
  }
}

type ApiErrorBody = {
  message?: string;
  details?: string[];
};

// The API's own message (already in Turkish), or a Turkish fallback when the body is not an API error.
export async function readApiError(response: Response, label?: string): Promise<string> {
  try {
    const payload = (await response.json()) as ApiErrorBody;
    const message = payload.message?.trim();
    if (message) {
      const details = payload.details?.filter(Boolean) ?? [];
      return details.length > 0 ? `${message}: ${details.join(" ")}` : message;
    }
  } catch {
    // Not JSON (a proxy error page, for instance): fall through to the generic message.
  }

  if (response.status >= 502 && response.status <= 504) {
    return `Sunucuya şu anda ulaşılamıyor (HTTP ${response.status}). Biraz sonra tekrar deneyin.`;
  }
  return `${label ? `${label}: ` : ""}İstek tamamlanamadı (HTTP ${response.status}).`;
}

export async function apiFetch(input: string, init?: RequestInit): Promise<Response> {
  const response = await sendRequest(input, {
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
