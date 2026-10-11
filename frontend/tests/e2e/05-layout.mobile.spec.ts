import { test, expect } from "@playwright/test";
import { captureDiagnostics } from "./helpers";

/**
 * Mobile viewport checks (runs under the chromium-mobile project, Pixel 7).
 * Verifies the public login screen is not visually broken on small screens
 * (no horizontal overflow) and the form is usable.
 */
test.describe("Mobile layout", () => {
  test("login page fits the mobile viewport (no horizontal scroll)", async ({ page }) => {
    const diag = captureDiagnostics(page);
    await page.goto("/login", { waitUntil: "domcontentloaded" });
    await page.waitForTimeout(1500);

    await expect(page.getByRole("heading", { name: /Oturum ac/i })).toBeVisible();
    await expect(page.getByLabel("E-posta")).toBeVisible();

    const overflow = await page.evaluate(() => {
      const el = document.documentElement;
      return { scrollWidth: el.scrollWidth, clientWidth: el.clientWidth };
    });
    console.log("Mobile login overflow metrics:", JSON.stringify(overflow));
    // Allow a 2px tolerance for sub-pixel rounding.
    expect(overflow.scrollWidth, "no horizontal overflow on mobile login").toBeLessThanOrEqual(overflow.clientWidth + 2);

    expect(diag.pageErrors, "no uncaught exceptions on mobile login").toEqual([]);
  });
});
