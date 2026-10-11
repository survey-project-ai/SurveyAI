import { defineConfig, devices } from "@playwright/test";

/**
 * Minimal Playwright setup for SurveyAI frontend E2E / smoke testing.
 *
 * Target environment is selected with the BASE_URL env var. Defaults to the
 * Railway production frontend so the suite can be run as a production smoke
 * test out of the box:
 *
 *   npx playwright test                       # production (default)
 *   BASE_URL=http://localhost:3000 npx playwright test   # local
 *
 * Test account (provided for E2E only):
 *   E2E_EMAIL / E2E_PASSWORD env vars override the defaults below.
 */
const BASE_URL = process.env.BASE_URL ?? "https://surveyai-frontend-production.up.railway.app";

export default defineConfig({
  testDir: "./tests/e2e",
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-report" }]],
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: BASE_URL,
    trace: "on",
    screenshot: "on",
    video: "retain-on-failure",
    actionTimeout: 20_000,
    navigationTimeout: 30_000,
  },
  projects: [
    {
      name: "chromium-desktop",
      use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } },
      testIgnore: /.*mobile\.spec\.ts/,
    },
    {
      name: "chromium-mobile",
      use: { ...devices["Pixel 7"] },
      testMatch: /.*mobile\.spec\.ts/,
    },
  ],
});
