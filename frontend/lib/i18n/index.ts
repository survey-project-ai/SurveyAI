import { en } from "@/lib/i18n/en";
import { tr } from "@/lib/i18n/tr";

export const languages = ["tr", "en"] as const;

export type Language = (typeof languages)[number];

// Languages users can switch to. Most page texts are still written in Turkish in the components, so English
// stays off until they move to the translation files; add "en" here to bring the switch back.
export const enabledLanguages: readonly Language[] = ["tr"];

export const defaultLanguage: Language = "tr";
export const languageStorageKey = "surveyai-language";

export const translations = {
  tr,
  en,
} as const;

export type TranslationTree = (typeof translations)[typeof defaultLanguage];
