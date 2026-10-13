"use client";

import { useEffect, useMemo, useRef, useState, useSyncExternalStore, type KeyboardEvent } from "react";
import { useRouter } from "next/navigation";
import { SearchIcon } from "@/components/ui/Icons";
import { OPERATION_STATUS_BADGE_CONFIG } from "@/lib/operation-detail";
import { fetchCompanyOperations } from "@/lib/operations";
import { fetchCompanySurveys } from "@/lib/surveys";
import type { NavItem, Survey } from "@/lib/types";

const RESULTS_PER_GROUP = 5;
// Surveys and operations are loaded when the search opens and reused for this long.
const INDEX_MAX_AGE_MS = 60_000;

const surveyStatusLabels: Record<Survey["status"], string> = {
  Live: "Yayında",
  Draft: "Taslak",
  Archived: "Arşivlenmiş",
};

type SearchGroup = "Sayfalar" | "Anketler" | "Operasyonlar";

type SearchItem = {
  id: string;
  group: SearchGroup;
  title: string;
  detail: string;
  href: string;
  searchWords: string[];
};

type RecordIndex = {
  items: SearchItem[];
  loadedAt: number;
  failed: boolean;
};

function subscribeToNothing(): () => void {
  return () => {};
}

// Lower-cases with Turkish rules and drops diacritics, so "kisi" finds "Kişi" and "IST" finds "İstanbul".
function foldForSearch(value: string): string {
  return value
    .toLocaleLowerCase("tr")
    .replace(/ı/g, "i")
    .normalize("NFD")
    .replace(/\p{M}/gu, "");
}

function toSearchItem(item: Omit<SearchItem, "searchWords">): SearchItem {
  return { ...item, searchWords: foldForSearch(`${item.title} ${item.detail}`).split(/[^\p{L}\p{N}]+/u).filter(Boolean) };
}

function buildPageItems(navigationItems: NavItem[]): SearchItem[] {
  // A child that repeats its parent's link ("Anketler Dashboard") keeps the parent's shorter name.
  const pages = new Map<string, SearchItem>();
  for (const item of navigationItems) {
    const entries = [item, ...(item.children ?? [])];
    for (const entry of entries) {
      if (!pages.has(entry.href)) {
        pages.set(
          entry.href,
          toSearchItem({
            id: `page-${entry.href}`,
            group: "Sayfalar",
            title: entry.label,
            detail: entry.description ?? "",
            href: entry.href,
          }),
        );
      }
    }
  }
  return [...pages.values()];
}

export function GlobalSearch({ collapsed, navigationItems }: { collapsed: boolean; navigationItems: NavItem[] }) {
  const router = useRouter();
  const inputRef = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState("");
  const [isOpen, setIsOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const [recordIndex, setRecordIndex] = useState<RecordIndex | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const shortcutLabel = useSyncExternalStore(
    subscribeToNothing,
    () => (/Mac|iPhone|iPad/.test(navigator.platform) ? "⌘K" : "Ctrl K"),
    () => "Ctrl K",
  );

  const pageItems = useMemo(() => buildPageItems(navigationItems), [navigationItems]);

  const results = useMemo(() => {
    const tokens = foldForSearch(query).split(/[^\p{L}\p{N}]+/u).filter(Boolean);
    if (tokens.length === 0) {
      return [];
    }

    // Every typed word has to start a word of the item, so "kisi" does not match inside "akışı".
    const matches = (item: SearchItem) => tokens.every((token) => item.searchWords.some((word) => word.startsWith(token)));
    const groups: SearchGroup[] = ["Sayfalar", "Anketler", "Operasyonlar"];
    const candidates = [...pageItems, ...(recordIndex?.items ?? [])];
    return groups.flatMap((group) =>
      candidates.filter((item) => item.group === group && matches(item)).slice(0, RESULTS_PER_GROUP),
    );
  }, [pageItems, query, recordIndex]);

  useEffect(() => {
    function handleShortcut(event: globalThis.KeyboardEvent) {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        inputRef.current?.focus();
        inputRef.current?.select();
      }
    }

    window.addEventListener("keydown", handleShortcut);
    return () => window.removeEventListener("keydown", handleShortcut);
  }, []);

  async function loadRecords() {
    if (isLoading || (recordIndex && Date.now() - recordIndex.loadedAt < INDEX_MAX_AGE_MS)) {
      return;
    }

    setIsLoading(true);
    const [surveysResult, operationsResult] = await Promise.allSettled([fetchCompanySurveys(), fetchCompanyOperations()]);
    const surveyItems =
      surveysResult.status === "fulfilled"
        ? surveysResult.value.map((survey) =>
            toSearchItem({
              id: `survey-${survey.id}`,
              group: "Anketler",
              title: survey.name,
              detail: `${surveyStatusLabels[survey.status]} · ${survey.questions} soru`,
              href: `/surveys/${survey.id}`,
            }),
          )
        : [];
    const operationItems =
      operationsResult.status === "fulfilled"
        ? operationsResult.value.map((operation) =>
            toSearchItem({
              id: `operation-${operation.id}`,
              group: "Operasyonlar",
              title: operation.name,
              detail: `${OPERATION_STATUS_BADGE_CONFIG[operation.status].label} · ${operation.survey}`,
              href: `/operations/${operation.id}`,
            }),
          )
        : [];

    setRecordIndex({
      items: [...surveyItems, ...operationItems],
      loadedAt: Date.now(),
      failed: surveysResult.status === "rejected" || operationsResult.status === "rejected",
    });
    setIsLoading(false);
  }

  function openResult(item: SearchItem) {
    setQuery("");
    setIsOpen(false);
    // The search input still has focus here (mouse down on a result does not move it).
    (document.activeElement as HTMLElement | null)?.blur();
    router.push(item.href);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Escape") {
      setIsOpen(false);
      event.currentTarget.blur();
      return;
    }
    if (results.length === 0) {
      return;
    }
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActiveIndex((index) => (index + 1) % results.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActiveIndex((index) => (index - 1 + results.length) % results.length);
    } else if (event.key === "Enter") {
      event.preventDefault();
      openResult(results[Math.min(activeIndex, results.length - 1)]);
    }
  }

  const showPanel = isOpen && query.trim().length > 0;

  return (
    <div className="global-search">
      <label
        className={["search-field", "sidebar-search", collapsed ? "is-collapsed" : ""].filter(Boolean).join(" ")}
        aria-label="Genel arama"
      >
        <SearchIcon className="nav-icon" />
        {!collapsed ? (
          <>
            <input
              ref={inputRef}
              type="search"
              placeholder="Ara"
              value={query}
              role="combobox"
              aria-expanded={showPanel}
              aria-controls="global-search-results"
              aria-activedescendant={showPanel && results.length > 0 ? `global-search-${activeIndex}` : undefined}
              onChange={(event) => {
                setQuery(event.target.value);
                setActiveIndex(0);
                setIsOpen(true);
                void loadRecords();
              }}
              onFocus={() => {
                setIsOpen(true);
                void loadRecords();
              }}
              onBlur={() => setIsOpen(false)}
              onKeyDown={handleKeyDown}
            />
            <span className="topbar-search-shortcut">{shortcutLabel}</span>
          </>
        ) : null}
      </label>

      {showPanel ? (
        <div className="global-search-panel" id="global-search-results" role="listbox" aria-label="Arama sonuçları">
          {results.length === 0 ? (
            <p className="global-search-empty">{isLoading ? "Aranıyor..." : `"${query.trim()}" için sonuç bulunamadı.`}</p>
          ) : (
            results.map((item, index) => (
              <div key={item.id}>
                {index === 0 || results[index - 1].group !== item.group ? (
                  <span className="global-search-group-title">{item.group}</span>
                ) : null}
                <button
                  type="button"
                  id={`global-search-${index}`}
                  role="option"
                  aria-selected={index === activeIndex}
                  className={["global-search-item", index === activeIndex ? "is-active" : ""].filter(Boolean).join(" ")}
                  // Keep the input focused so the blur handler does not close the panel before the click lands.
                  onMouseDown={(event) => event.preventDefault()}
                  onMouseEnter={() => setActiveIndex(index)}
                  onClick={() => openResult(item)}
                >
                  <strong>{item.title}</strong>
                  {item.detail ? <span>{item.detail}</span> : null}
                </button>
              </div>
            ))
          )}
          {recordIndex?.failed ? (
            <p className="global-search-empty">Anket veya operasyon listesi yüklenemedi; sonuçlar eksik olabilir.</p>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
