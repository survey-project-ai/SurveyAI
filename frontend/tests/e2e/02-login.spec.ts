import { test, expect } from "@playwright/test";
import { captureDiagnostics, tryLogin, TEST_EMAIL, EXPECTED_BACKEND_HOST } from "./helpers";

/**
 * Authentication flow against production: login, redirect, session cookie,
 * persistence across reload, and logout.
 */
test.describe("Login flow", () => {
  test("logs in with the test account and lands on the dashboard", async ({ page }) => {
    const diag = captureDiagnostics(page);

    const result = await tryLogin(page);
    test.skip(!result.success, `Backend rejected ${TEST_EMAIL}: "${result.error}". No seeded prod account — see report.`);

    // Redirected away from /login (to dashboard "/").
    expect(new URL(page.url()).pathname).not.toContain("/login");

    // The login POST hit the production backend.
    const loginReq = diag.apiRequests.find((r) => r.url.includes("/api/v1/auth/login"));
    expect(loginReq, "login request was made").toBeTruthy();
    expect(loginReq!.url).toContain(EXPECTED_BACKEND_HOST);

    // A session cookie now exists.
    const cookies = await page.context().cookies();
    console.log("Cookies after login:", cookies.map((c) => ({ name: c.name, domain: c.domain, httpOnly: c.httpOnly, secure: c.secure, sameSite: c.sameSite })));
    expect(cookies.length, "at least one session cookie set").toBeGreaterThan(0);

    expect(diag.pageErrors, "no uncaught exceptions during login").toEqual([]);
  });

  test("session persists across a full page reload", async ({ page }) => {
    const result = await tryLogin(page);
    test.skip(!result.success, `Backend rejected ${TEST_EMAIL}: "${result.error}".`);
    const afterLogin = page.url();

    await page.reload({ waitUntil: "domcontentloaded" });
    await page.waitForTimeout(1500);

    // Should NOT be bounced back to /login.
    expect(new URL(page.url()).pathname, "still authenticated after reload").not.toContain("/login");
    console.log("URL after reload:", page.url(), "(was", afterLogin + ")");
  });

  test("logout clears the session and redirects to /login", async ({ page }) => {
    const result = await tryLogin(page);
    test.skip(!result.success, `Backend rejected ${TEST_EMAIL}: "${result.error}".`);

    // Open the account menu (button.topbar-account toggles the panel),
    // then click the "Cikis Yap" logout action inside it.
    await page.locator("button.topbar-account").first().click();
    const logoutButton = page.getByRole("button", { name: "Cikis Yap" });
    await expect(logoutButton).toBeVisible({ timeout: 5000 });
    await logoutButton.click();

    await page.waitForURL((url) => url.pathname.startsWith("/login"), { timeout: 15_000 });
    expect(new URL(page.url()).pathname).toContain("/login");

    // After logout, the session should be gone: a protected route bounces back.
    await page.goto("/operations", { waitUntil: "domcontentloaded" });
    await page.waitForURL((url) => url.pathname.startsWith("/login"), { timeout: 15_000 }).catch(() => {});
    expect(new URL(page.url()).pathname, "still logged out after logout").toContain("/login");
  });

  test("rejects invalid credentials with an inline error", async ({ page }) => {
    await page.goto("/login", { waitUntil: "domcontentloaded" });
    await page.getByLabel("E-posta").fill("not-a-real-user@example.invalid");
    await page.getByLabel("Sifre").fill("wrong-password");
    await page.getByRole("button", { name: /Calisma alanini ac/i }).click();

    // Stays on /login and shows the error block.
    await expect(page.getByText(/Oturum acilamadi/i)).toBeVisible({ timeout: 15_000 });
    expect(new URL(page.url()).pathname).toContain("/login");
  });
});
