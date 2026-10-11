# SurveyAI Frontend — E2E / Smoke Tests (Playwright)

Minimal Playwright suite for verifying the frontend against the Railway
production deployment (and, optionally, a local stack).

## Run

```bash
cd frontend
npm install
npx playwright install chromium      # one-time browser download

# Production (default target)
npm run test:e2e

# Local stack (frontend on :3000 + backend on :8080)
BASE_URL=http://localhost:3000 npx playwright test     # macOS/Linux
$env:BASE_URL="http://localhost:3000"; npx playwright test   # PowerShell

# View the HTML report (traces, screenshots, video)
npm run test:e2e:report
```

## Target / credentials (env overrides)

| Var            | Default                                                    |
| -------------- | ---------------------------------------------------------- |
| `BASE_URL`     | `https://surveyai-frontend-production.up.railway.app`      |
| `E2E_EMAIL`    | `owner@acme-research.test`                                 |
| `E2E_PASSWORD` | `change-me-123`                                            |

## Specs

- `01-smoke.spec.ts` — login page loads, correct backend base URL (no localhost
  leak), CORS / preflight, anonymous `auth/me` 401, no uncaught JS.
- `02-login.spec.ts` — login + session + reload + logout. The success-dependent
  tests **self-skip** (with the backend error message) when the test account is
  rejected, so a missing seeded account does not look like a frontend failure.
- `03-flows.spec.ts` — authenticated flows (dashboard, operations, surveys, new
  operation validation). Self-skip when login is unavailable.
- `04-regression.spec.ts` — 404 (not 500), hydration mismatch guard, auth
  redirect on protected routes.
- `05-layout.mobile.spec.ts` — mobile viewport (Pixel 7), no horizontal overflow.

Artifacts (trace/screenshot/video) land in `test-results/` and the HTML report
in `playwright-report/` (both git-ignored).
