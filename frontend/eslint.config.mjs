import nextCoreWebVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";

// Next 16 removed `next lint` and ships native ESLint 9 flat configs. This
// composes the same presets that used to live in .eslintrc.json so `eslint .`
// works again.
const eslintConfig = [
  ...nextCoreWebVitals,
  ...nextTypescript,
  {
    ignores: [
      ".next/**",
      ".next-dev/**",
      "node_modules/**",
      "tests/**",
      "playwright-report/**",
      "test-results/**",
      "playwright.config.ts",
    ],
  },
];

export default eslintConfig;
