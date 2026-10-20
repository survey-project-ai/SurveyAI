"use client";

import { useEffect } from "react";

const LEAVE_MESSAGE = "Kaydedilmemiş değişiklikleriniz var. Sayfadan çıkarsanız kaybolacak. Devam edilsin mi?";

// Editors on the page that currently hold unsaved changes.
let dirtyEditors = 0;

// For navigation started in code (search results, back buttons): asks before leaving unsaved changes behind.
export function confirmLeavingUnsavedChanges(): boolean {
  return dirtyEditors === 0 || window.confirm(LEAVE_MESSAGE);
}

function leavesCurrentPage(event: MouseEvent): boolean {
  if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
    return false;
  }
  const anchor = event.target instanceof Element ? event.target.closest("a[href]") : null;
  if (!(anchor instanceof HTMLAnchorElement) || anchor.target === "_blank" || anchor.hasAttribute("download")) {
    return false;
  }
  const url = new URL(anchor.href, window.location.href);
  // Other sites unload the page, which the beforeunload prompt already covers.
  return url.origin === window.location.origin
    && (url.pathname !== window.location.pathname || url.search !== window.location.search);
}

// While isDirty: the browser asks before the tab closes or reloads, and a link that leaves the page asks first.
// Next.js has no hook to block its own navigation, so link clicks are caught before React sees them.
export function useUnsavedChangesGuard(isDirty: boolean) {
  useEffect(() => {
    if (!isDirty) {
      return;
    }

    dirtyEditors += 1;

    function handleBeforeUnload(event: BeforeUnloadEvent) {
      event.preventDefault();
      event.returnValue = "";
    }

    function handleClick(event: MouseEvent) {
      if (leavesCurrentPage(event) && !window.confirm(LEAVE_MESSAGE)) {
        event.preventDefault();
        event.stopPropagation();
      }
    }

    window.addEventListener("beforeunload", handleBeforeUnload);
    document.addEventListener("click", handleClick, true);
    return () => {
      dirtyEditors -= 1;
      window.removeEventListener("beforeunload", handleBeforeUnload);
      document.removeEventListener("click", handleClick, true);
    };
  }, [isDirty]);
}
