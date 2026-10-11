import { test, expect } from "@playwright/test";
import { captureDiagnostics, meaningfulConsoleErrors } from "./helpers";

/**
 * Regression guards that do not require authentication: unknown routes, no
 * hydration mismatches, and no broken JS on the public surface.
 */
test.describe("Regression (public surface)", () => {
  test("unknown route returns a 404 (not a 500)", async ({ page }) => {
    const res = await page.goto("/this-route-does-not-exist-" + "e2e", { waitUntil: "domcontentloaded" });
    expect(res, "navigation response present").toBeTruthy();
    console.log("Unknown route status:", res!.status());
    // Next.js App Router serves the not-found page with a 404 status.
    expect(res!.status(), "unknown route is 404, not a server error").toBe(404);
  });

  test("login page has no hydration mismatch or uncaught errors", async ({ page }) => {
    const diag = captureDiagnostics(page);
    await page.goto("/login", { waitUntil: "domcontentloaded" });
    await page.waitForTimeout(2500);

    const hydrationWarnings = [...diag.consoleErrors, ...diag.consoleWarnings].filter((m) =>
      /hydrat|did not match|server rendered|text content does not match/i.test(m),
    );
    console.log("Hydration-related messages:", JSON.stringify(hydrationWarnings, null, 2));
    console.log("Page errors:", JSON.stringify(diag.pageErrors, null, 2));
    console.log("Failed requests:", JSON.stringify(diag.failedRequests, null, 2));

    expect(hydrationWarnings, "no hydration mismatch on /login").toEqual([]);
    expect(diag.pageErrors, "no uncaught exceptions on /login").toEqual([]);
    expect(meaningfulConsoleErrors(diag.consoleErrors), "no meaningful console errors on /login").toEqual([]);
  });

  test("unauthenticated protected route redirects to /login", async ({ page }) => {
    await page.goto("/operations", { waitUntil: "domcontentloaded" });
    // AppShell pushes unauthenticated visitors to /login.
    await page.waitForURL((url) => url.pathname.startsWith("/login"), { timeout: 15_000 }).catch(() => {});
    console.log("Protected route landed on:", page.url());
    expect(new URL(page.url()).pathname).toContain("/login");
  });
});
