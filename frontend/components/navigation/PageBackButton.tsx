"use client";

import { usePathname, useRouter } from "next/navigation";
import { ArrowLeftIcon } from "@/components/ui/Icons";

type FallbackTarget = {
  href: string;
  label: string;
};

export function shouldShowPageBackButton(pathname: string): boolean {
  return pathname.startsWith("/surveys/") || pathname.startsWith("/operations/");
}

function resolveFallbackTarget(pathname: string): FallbackTarget {
  if (pathname.startsWith("/operations/") && pathname.includes("/contacts")) {
    const operationPath = pathname.split("/contacts")[0];
    return {
      href: operationPath,
      label: "Operasyona dön",
    };
  }

  if (pathname.startsWith("/operations/")) {
    return {
      href: "/operations",
      label: "Operasyonlara dön",
    };
  }

  if (pathname.startsWith("/surveys/")) {
    return {
      href: "/surveys",
      label: "Anketlere dön",
    };
  }

  if (pathname.startsWith("/contacts")) {
    return {
      href: "/",
      label: "Panele dön",
    };
  }

  if (pathname.startsWith("/analytics")) {
    return {
      href: "/",
      label: "Panele dön",
    };
  }

  if (pathname.startsWith("/calling-ops")) {
    return {
      href: "/",
      label: "Panele dön",
    };
  }

  return {
    href: "/",
    label: "Geri dön",
  };
}

export function PageBackButton() {
  const router = useRouter();
  const pathname = usePathname();

  if (!pathname || !shouldShowPageBackButton(pathname)) {
    return null;
  }

  const fallbackTarget = resolveFallbackTarget(pathname);

  function handleBack() {
    if (typeof window !== "undefined" && window.history.length > 1) {
      router.back();
      return;
    }

    router.push(fallbackTarget.href);
  }

  return (
    <button
      type="button"
      className="button-secondary compact-button page-back-button"
      onClick={handleBack}
      aria-label={fallbackTarget.label}
      title={fallbackTarget.label}
    >
      <ArrowLeftIcon className="nav-icon" />
    </button>
  );
}
