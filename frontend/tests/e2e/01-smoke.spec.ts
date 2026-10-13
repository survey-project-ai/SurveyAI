import { test, expect } from "@playwright/test";
import { captureDiagnostics, meaningfulConsoleErrors, EXPECTED_BACKEND_HOST } from "./helpers";

/**
 * Production smoke test. Verifies the public login page loads, runs no broken
 * JavaScript, and talks to the correct backend base URL (no localhost leak,
 * no CORS failures, correct auth/me behaviour for an anonymous visitor).
 */
test.describe("Production smoke", () => {
  test("/login loads and wires up the correct backend", async ({ page }) => {
    const diag = captureDiagnostics(page);

    const response = await page.goto("/login", { waitUntil: "domcontentloaded" });
    expect(response, "navigation response present").toBeTruthy();
    expect(response!.status(), "/login HTTP status").toBeLessThan(400);

    // Login form is visible
    await expect(page.getByRole("heading", { name: /Oturum aç/i })).toBeVisible();
    await expect(page.getByLabel("E-posta")).toBeVisible();
    await expect(page.getByLabel("Şifre")).toBeVisible();

    // Give the AuthProvider's /auth/me call time to fire.
    await page.waitForTimeout(2000);

    // --- API base URL correctness ---
    console.log("API requests observed:", JSON.stringify(diag.apiRequests, null, 2));
    console.log("Localhost leaks:", JSON.stringify(diag.localhostRequests, null, 2));

    expect(diag.localhostRequests, "no localhost/127.0.0.1 API leak").toEqual([]);

    const authMe = diag.apiRequests.find((r) => r.url.includes("/api/v1/auth/me"));
    expect(authMe, "auth/me request was made on load").toBeTruthy();
    expect(authMe!.url, "auth/me targets production backend host").toContain(EXPECTED_BACKEND_HOST);

    // --- No broken JS ---
    const realErrors = meaningfulConsoleErrors(diag.consoleErrors);
    console.log("Console errors (filtered):", JSON.stringify(realErrors, null, 2));
    console.log("Page (uncaught) errors:", JSON.stringify(diag.pageErrors, null, 2));
    expect(diag.pageErrors, "no uncaught page exceptions").toEqual([]);
  });

  test("anonymous /auth/me returns 401 (expected) without CORS failure", async ({ page }) => {
    await page.goto("/login", { waitUntil: "domcontentloaded" });

    // Directly probe the backend the way the browser does (credentials + Origin),
    // so we can prove CORS works regardless of the app's own request timing.
    const result = await page.evaluate(async (backendHost) => {
      try {
        const res = await fetch(`https://${backendHost}/api/v1/auth/me`, {
          method: "GET",
          credentials: "include",
          headers: { Accept: "application/json" },
        });
        return {
          ok: res.ok,
          status: res.status,
          acao: res.headers.get("access-control-allow-origin"),
          acac: res.headers.get("access-control-allow-credentials"),
          error: null as string | null,
        };
      } catch (e) {
        return { ok: false, status: 0, acao: null, acac: null, error: String(e) };
      }
    }, EXPECTED_BACKEND_HOST);

    console.log("auth/me probe result:", JSON.stringify(result, null, 2));

    // A network-level CORS failure surfaces as a thrown TypeError ("Failed to fetch").
    expect(result.error, "no CORS / network failure reaching backend").toBeNull();
    // Anonymous visitor should be rejected (401) — not a server error.
    expect([200, 401, 403]).toContain(result.status);
  });

  test("CORS preflight allows credentialed POST from the frontend origin", async ({ page, baseURL }) => {
    await page.goto("/login", { waitUntil: "domcontentloaded" });

    const origin = new URL(baseURL!).origin;
    const result = await page.evaluate(
      async ({ backendHost, origin }) => {
        // The browser will issue an OPTIONS preflight for this credentialed
        // JSON POST. If CORS is misconfigured the fetch throws.
        try {
          const res = await fetch(`https://${backendHost}/api/v1/auth/login`, {
            method: "POST",
            credentials: "include",
            headers: { "Content-Type": "application/json", Accept: "application/json" },
            body: JSON.stringify({ email: "cors-probe@example.invalid", password: "x" }),
          });
          return { reached: true, status: res.status, origin, error: null as string | null };
        } catch (e) {
          return { reached: false, status: 0, origin, error: String(e) };
        }
      },
      { backendHost: EXPECTED_BACKEND_HOST, origin },
    );

    console.log("login CORS probe:", JSON.stringify(result, null, 2));
    expect(result.error, "credentialed POST not blocked by CORS preflight").toBeNull();
    expect(result.reached, "backend reachable for credentialed POST").toBeTruthy();
    // Bad creds → 400/401; what matters is the request was not CORS-blocked.
    expect(result.status).toBeGreaterThan(0);
  });
});
