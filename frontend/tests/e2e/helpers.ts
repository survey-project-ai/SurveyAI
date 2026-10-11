import type { Page, Request, ConsoleMessage } from "@playwright/test";

export const TEST_EMAIL = process.env.E2E_EMAIL ?? "owner@acme-research.test";
export const TEST_PASSWORD = process.env.E2E_PASSWORD ?? "change-me-123";

// Backend host the frontend is expected to talk to. Defaults to the Railway
// production backend; override with E2E_BACKEND_HOST when testing a local stack
// (e.g. E2E_BACKEND_HOST=localhost:8080).
export const EXPECTED_BACKEND_HOST = process.env.E2E_BACKEND_HOST ?? "surveyai-backend-production.up.railway.app";

export type Captured = {
  consoleErrors: string[];
  consoleWarnings: string[];
  pageErrors: string[];
  failedRequests: { url: string; method: string; failure: string }[];
  badResponses: { url: string; method: string; status: number }[];
  apiRequests: { url: string; method: string }[];
  localhostRequests: string[];
};

/**
 * Attaches listeners that record console errors, uncaught exceptions, failed
 * network requests, non-2xx/3xx API responses and any request that leaks a
 * localhost / 127.0.0.1 base URL. Returns the mutable capture buffer.
 */
export function captureDiagnostics(page: Page): Captured {
  const c: Captured = {
    consoleErrors: [],
    consoleWarnings: [],
    pageErrors: [],
    failedRequests: [],
    badResponses: [],
    apiRequests: [],
    localhostRequests: [],
  };

  page.on("console", (msg: ConsoleMessage) => {
    const text = msg.text();
    if (msg.type() === "error") c.consoleErrors.push(text);
    if (msg.type() === "warning") c.consoleWarnings.push(text);
  });

  page.on("pageerror", (err) => {
    c.pageErrors.push(err.message);
  });

  page.on("requestfailed", (req: Request) => {
    c.failedRequests.push({
      url: req.url(),
      method: req.method(),
      failure: req.failure()?.errorText ?? "unknown",
    });
  });

  page.on("request", (req: Request) => {
    const url = req.url();
    if (/localhost|127\.0\.0\.1/.test(url)) {
      c.localhostRequests.push(`${req.method()} ${url}`);
    }
    if (url.includes("/api/v1/")) {
      c.apiRequests.push({ url, method: req.method() });
    }
  });

  page.on("response", (res) => {
    const url = res.url();
    if (url.includes("/api/v1/") && res.status() >= 400) {
      c.badResponses.push({ url, method: res.request().method(), status: res.status() });
    }
  });

  return c;
}

/** Console errors that are noise rather than real app defects. */
const IGNORABLE_CONSOLE = [
  /Failed to load resource: the server responded with a status of 401/i, // expected pre-login /auth/me
  /Failed to load resource: the server responded with a status of 403/i,
  /favicon/i,
];

export function meaningfulConsoleErrors(errors: string[]): string[] {
  return errors.filter((e) => !IGNORABLE_CONSOLE.some((re) => re.test(e)));
}

/** Performs a UI login and waits for redirect away from /login. Throws on failure. */
export async function login(page: Page): Promise<void> {
  const result = await tryLogin(page);
  if (!result.success) {
    throw new Error(result.error ?? "Login did not redirect away from /login");
  }
}

/**
 * Attempts a UI login and resolves with the outcome instead of throwing, so
 * callers can distinguish "frontend broken" from "credentials rejected".
 * Returns { success:false, error } when the backend rejects the credentials
 * (the inline "Oturum acilamadi" error appears) or the redirect never happens.
 */
export async function tryLogin(page: Page): Promise<{ success: boolean; error: string | null }> {
  await page.goto("/login", { waitUntil: "domcontentloaded" });
  await page.getByLabel("E-posta").fill(TEST_EMAIL);
  await page.getByLabel("Sifre").fill(TEST_PASSWORD);
  await page.getByRole("button", { name: /Calisma alanini ac/i }).click();

  const redirected = page
    .waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 20_000 })
    .then(() => "redirected" as const)
    .catch(() => null);
  const rejected = page
    .getByText(/Oturum acilamadi/i)
    .waitFor({ state: "visible", timeout: 20_000 })
    .then(() => "rejected" as const)
    .catch(() => null);

  const outcome = await Promise.race([redirected, rejected]);
  if (outcome === "redirected") {
    return { success: true, error: null };
  }

  // Capture the inline backend error message if present.
  const detail = await page
    .locator(".operation-inline-message.is-danger span")
    .first()
    .textContent()
    .catch(() => null);
  return { success: false, error: detail?.trim() || "Login was rejected (no redirect)." };
}
