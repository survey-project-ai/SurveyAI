"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useSyncExternalStore, type ReactNode } from "react";
import { defaultLanguage, enabledLanguages, languageStorageKey, languages, translations, type Language } from "@/lib/i18n";

const languageChangeEvent = "surveyai:language-change";

function subscribeToLanguage(onChange: () => void): () => void {
  window.addEventListener("storage", onChange);
  window.addEventListener(languageChangeEvent, onChange);
  return () => {
    window.removeEventListener("storage", onChange);
    window.removeEventListener(languageChangeEvent, onChange);
  };
}

function readStoredLanguage(): Language {
  // A language chosen while it was enabled (e.g. "en") falls back to the default once it is switched off.
  const savedLanguage = window.localStorage.getItem(languageStorageKey);
  return savedLanguage && isLanguage(savedLanguage) && enabledLanguages.includes(savedLanguage) ? savedLanguage : defaultLanguage;
}

type LanguageContextValue = {
  language: Language;
  setLanguage: (language: Language) => void;
  t: (path: string, values?: Record<string, string | number>) => string;
  tm: <T>(path: string) => T;
};

const LanguageContext = createContext<LanguageContextValue | null>(null);

export function LanguageProvider({ children }: { children: ReactNode }) {
  // Read the persisted language from an external store (localStorage) so the
  // server renders the default and the client reconciles after hydration
  // without a mismatch — and without setting state inside an effect.
  const language = useSyncExternalStore(subscribeToLanguage, readStoredLanguage, () => defaultLanguage);

  const setLanguage = useCallback((nextLanguage: Language) => {
    window.localStorage.setItem(languageStorageKey, nextLanguage);
    window.dispatchEvent(new Event(languageChangeEvent));
  }, []);

  useEffect(() => {
    document.documentElement.lang = language;
  }, [language]);

  const value = useMemo<LanguageContextValue>(() => {
    return {
      language,
      setLanguage,
      t: (path, values) => formatTranslation(readTranslation(path, translations[language] as Record<string, unknown>), values),
      tm: <T,>(path: string) => readTranslation(path, translations[language] as Record<string, unknown>) as T,
    };
  }, [language, setLanguage]);

  return <LanguageContext.Provider value={value}>{children}</LanguageContext.Provider>;
}

export function useLanguage() {
  const context = useContext(LanguageContext);

  if (!context) {
    throw new Error("useLanguage must be used within LanguageProvider.");
  }

  return {
    language: context.language,
    setLanguage: context.setLanguage,
  };
}

export function useTranslations() {
  const context = useContext(LanguageContext);

  if (!context) {
    throw new Error("useTranslations must be used within LanguageProvider.");
  }

  return context;
}

function isLanguage(value: string): value is Language {
  return languages.includes(value as Language);
}

function readTranslation(path: string, tree: Record<string, unknown>): unknown {
  return path.split(".").reduce<unknown>((current, segment) => {
    if (!current || typeof current !== "object" || !(segment in current)) {
      throw new Error(`Missing translation key: ${path}`);
    }

    return (current as Record<string, unknown>)[segment];
  }, tree);
}

function formatTranslation(value: unknown, replacements?: Record<string, string | number>): string {
  if (typeof value !== "string") {
    throw new Error("Translation value is not a string.");
  }

  if (!replacements) {
    return value;
  }

  return Object.entries(replacements).reduce(
    (message, [key, replacement]) => message.replaceAll(`{{${key}}}`, String(replacement)),
    value,
  );
}
