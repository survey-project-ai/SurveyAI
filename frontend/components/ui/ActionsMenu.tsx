"use client";

import { useEffect, useRef, useState, type CSSProperties } from "react";
import { createPortal } from "react-dom";

export type ActionsMenuItem = {
  label: string;
  onSelect: () => void;
  disabled?: boolean;
  // Why the item is disabled; shown under it, since a tooltip never appears on touch screens.
  hint?: string;
  tone?: "danger";
};

// A "⋯" button with a small menu. The menu is rendered into <body> and fixed next to the button, so neither a
// table that scrolls sideways nor the header's backdrop filter (which re-anchors fixed children) can clip or
// shift it. It closes on a choice, an outside click, Escape or scrolling.
export function ActionsMenu({ items, label = "Diğer işlemler" }: { items: ActionsMenuItem[]; label?: string }) {
  const [position, setPosition] = useState<CSSProperties | null>(null);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const isOpen = position !== null;

  useEffect(() => {
    if (!isOpen) {
      return;
    }

    const close = () => setPosition(null);
    function handlePointerDown(event: MouseEvent) {
      const target = event.target as Node;
      if (!wrapperRef.current?.contains(target) && !listRef.current?.contains(target)) {
        close();
      }
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        close();
      }
    }

    document.addEventListener("mousedown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    window.addEventListener("scroll", close, true);
    window.addEventListener("resize", close);
    return () => {
      document.removeEventListener("mousedown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
      window.removeEventListener("scroll", close, true);
      window.removeEventListener("resize", close);
    };
  }, [isOpen]);

  function toggle(button: HTMLButtonElement) {
    if (isOpen) {
      setPosition(null);
      return;
    }
    const rect = button.getBoundingClientRect();
    setPosition({ position: "fixed", top: rect.bottom + 6, right: Math.max(window.innerWidth - rect.right, 8) });
  }

  return (
    <div className="actions-menu" ref={wrapperRef}>
      <button
        type="button"
        className="button-secondary compact-button actions-menu-button"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={isOpen}
        onClick={(event) => toggle(event.currentTarget)}
      >
        ⋯
      </button>
      {position
        ? createPortal(
            <div className="actions-menu-list" role="menu" style={position} ref={listRef}>
              {items.map((item) => (
                <button
                  key={item.label}
                  type="button"
                  role="menuitem"
                  className={["actions-menu-item", item.tone === "danger" ? "is-danger" : ""].filter(Boolean).join(" ")}
                  disabled={item.disabled}
                  onClick={() => {
                    setPosition(null);
                    item.onSelect();
                  }}
                >
                  {item.label}
                  {item.disabled && item.hint ? <small>{item.hint}</small> : null}
                </button>
              ))}
            </div>,
            document.body,
          )
        : null}
    </div>
  );
}
