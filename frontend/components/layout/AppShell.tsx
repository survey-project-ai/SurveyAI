"use client";

import Link from "next/link";
import { useEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { usePathname, useRouter } from "next/navigation";
import { GlobalSearch } from "@/components/layout/GlobalSearch";
import { getNavigationItems } from "@/components/layout/navigation";
import { PageHeaderProvider, useResolvedPageHeader } from "@/components/layout/PageHeaderContext";
import { ArrowLeftIcon, ArrowRightIcon, BellIcon, CollapseIcon, MenuIcon } from "@/components/ui/Icons";
import { useAuth } from "@/lib/auth";
import { useNotifications } from "@/lib/notifications";
import { useLanguage, useTranslations } from "@/lib/i18n/LanguageContext";
import { enabledLanguages, type Language } from "@/lib/i18n";

const pageMetaKeys: Record<string, string> = {
  "/": "shell.pageMeta.dashboard",
  "/surveys": "shell.pageMeta.surveys",
  "/operations": "shell.pageMeta.operations",
  "/contacts": "shell.pageMeta.contacts",
  "/analytics": "shell.pageMeta.analytics",
  "/calling-ops": "shell.pageMeta.callingOps",
};

function subscribeToScroll(onChange: () => void): () => void {
  window.addEventListener("scroll", onChange, { passive: true });
  return () => window.removeEventListener("scroll", onChange);
}

export function AppShell({ children }: { children: ReactNode }) {
  return (
    <PageHeaderProvider>
      <AppShellFrame>{children}</AppShellFrame>
    </PageHeaderProvider>
  );
}

function AppShellFrame({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false);
  const [mobileNavState, setMobileNavState] = useState<{ pathname: string; open: boolean }>({ pathname, open: false });
  const [userMenuState, setUserMenuState] = useState<{ pathname: string; open: boolean }>({ pathname, open: false });
  const { language, setLanguage } = useLanguage();
  const { status, currentUser, logout } = useAuth();
  const { t } = useTranslations();
  const pageHeaderOverride = useResolvedPageHeader();
  const isPageScrolled = useSyncExternalStore(subscribeToScroll, () => window.scrollY > 0, () => false);

  const navigationItems = useMemo(() => getNavigationItems(t), [t]);
  const isAuthRoute = pathname === "/login";
  const isDashboardHome = pathname === "/";
  const isSurveysRoute = pathname === "/surveys" || pathname.startsWith("/surveys/");
  const isOperationsRoute = pathname === "/operations" || pathname.startsWith("/operations/");
  const shouldShowBackButton = !isDashboardHome;
  const isMobileNavOpen = mobileNavState.pathname === pathname && mobileNavState.open;
  const isUserMenuOpen = userMenuState.pathname === pathname && userMenuState.open;

  const { unreadCount, notifications, markAllSeen, clearNotifications } = useNotifications();
  const [isNotificationPanelOpen, setIsNotificationPanelOpen] = useState(false);
  const bellWrapperRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isNotificationPanelOpen) return;
    function handleClickOutside(event: MouseEvent) {
      if (bellWrapperRef.current && !bellWrapperRef.current.contains(event.target as Node)) {
        setIsNotificationPanelOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, [isNotificationPanelOpen]);

  const [expandedSectionState, setExpandedSectionState] = useState<{ pathname: string; values: Record<string, boolean> }>(
    { pathname, values: {} },
  );
  const expandedSections = expandedSectionState.pathname === pathname ? expandedSectionState.values : {};

  const setMobileNavOpenForPath = (open: boolean) => setMobileNavState({ pathname, open });
  const setUserMenuOpenForPath = (open: boolean) => setUserMenuState({ pathname, open });
  const toggleUserMenuForPath = () =>
    setUserMenuState((current) => ({
      pathname,
      open: current.pathname === pathname ? !current.open : true,
    }));

  useEffect(() => {
    if (status === "unauthenticated" && !isAuthRoute) {
      router.replace("/login");
    }
  }, [isAuthRoute, router, status]);

  if (isAuthRoute) {
    return <>{children}</>;
  }

  if (status !== "authenticated" || !currentUser) {
    return (
      <div className="auth-shell-loading">
        <div className="panel-card auth-shell-loading-card">
          <span className="eyebrow">Oturum</span>
          <h1>Çalışma alanı hazırlanıyor</h1>
          <p>Kullanıcı ve şirket bağlamı doğrulanıyor. Ardından panel açılacak.</p>
        </div>
      </div>
    );
  }

  const handleGoBack = () => {
    if (typeof window !== "undefined" && window.history.length > 1) {
      router.back();
      return;
    }

    if (isOperationsRoute) {
      router.push("/operations");
      return;
    }

    if (isSurveysRoute) {
      router.push("/surveys");
      return;
    }

    router.push("/");
  };

  const currentMeta = (() => {
    let key = pageMetaKeys[pathname] ?? pageMetaKeys["/"];

    if (pathname === "/surveys/new") {
      key = "shell.pageMeta.surveyCreate";
    } else if (pathname === "/operations/new") {
      key = "shell.pageMeta.operationCreate";
    } else if (pathname.startsWith("/surveys/")) {
      key = "shell.pageMeta.surveyDetail";
    } else if (pathname.startsWith("/operations/")) {
      key = "shell.pageMeta.operationDetail";
    }

    const baseMeta = {
      title: t(`${key}.title`),
      subtitle: t(`${key}.subtitle`),
    };

    return {
      title: pageHeaderOverride?.title ?? baseMeta.title,
      subtitle: pageHeaderOverride?.subtitle ?? baseMeta.subtitle,
    };
  })();
  const hasNodeSubtitle = typeof currentMeta.subtitle !== "string" && Boolean(currentMeta.subtitle);
  const renderSubtitleInCenterSlot = isOperationsRoute && hasNodeSubtitle;
  const breadcrumbs = buildBreadcrumbs(pathname, currentMeta.title, navigationItems, t);

  async function handleLogout() {
    await logout();
    router.replace("/login");
  }

  return (
    <div className={["app-shell", isSidebarCollapsed ? "is-sidebar-collapsed" : ""].filter(Boolean).join(" ")}>
      {isMobileNavOpen ? (
        <button className="mobile-overlay" aria-label={t("shell.sidebar.closeNavigation")} onClick={() => setMobileNavOpenForPath(false)} />
      ) : null}

      <aside
        className={[
          "sidebar",
          isSidebarCollapsed ? "is-collapsed" : "",
          isMobileNavOpen ? "is-open" : "",
        ]
          .filter(Boolean)
          .join(" ")}
      >
        <div className="sidebar-content">
          <div className="brand">
            <div className="brand-mark" />
            {!isSidebarCollapsed ? (
              <div className="brand-copy">
                <p className="brand-title">SurveyAI</p>
                <p className="brand-subtitle">Araştırma Operasyonları</p>
              </div>
            ) : null}
            <button
              className="sidebar-toggle desktop-only"
              aria-label={isSidebarCollapsed ? t("shell.sidebar.expand") : t("shell.sidebar.collapse")}
              onClick={() => setIsSidebarCollapsed((value) => !value)}
            >
              <CollapseIcon className="nav-icon" />
            </button>
          </div>

          <GlobalSearch collapsed={isSidebarCollapsed} navigationItems={navigationItems} />

          <nav className="nav-list">
            {navigationItems.map((item) => {
              const isActive = item.href === "/" ? pathname === item.href : pathname.startsWith(item.href);
              const isExpandable = Boolean(item.children?.length);
              const isExpanded = expandedSections[item.href] ?? false;

              return (
                <div key={item.href} className={["nav-section", isExpanded ? "is-expanded" : ""].filter(Boolean).join(" ")}>
                  {isExpandable ? (
                    <button
                      type="button"
                      className={["nav-item", "nav-item-button", isActive ? "is-active" : ""].filter(Boolean).join(" ")}
                      onClick={() =>
                        setExpandedSectionState((current) => {
                          const values = current.pathname === pathname ? current.values : {};
                          return {
                            pathname,
                            values: {
                              ...values,
                              [item.href]: !isExpanded,
                            },
                          };
                        })
                      }
                    >
                      {item.icon}
                      {!isSidebarCollapsed ? (
                        <>
                          <span className="nav-copy">
                            <span className="nav-title">{item.label}</span>
                            <span className="nav-description">{item.description}</span>
                          </span>
                          <ArrowRightIcon className={["nav-icon", "nav-expand-icon", isExpanded ? "is-expanded" : ""].join(" ")} />
                        </>
                      ) : null}
                    </button>
                  ) : (
                    <Link
                      className={["nav-item", isActive ? "is-active" : ""].filter(Boolean).join(" ")}
                      href={item.href}
                      onClick={() => setMobileNavOpenForPath(false)}
                    >
                      {item.icon}
                      {!isSidebarCollapsed ? (
                        <span className="nav-copy">
                          <span className="nav-title">{item.label}</span>
                          <span className="nav-description">{item.description}</span>
                        </span>
                      ) : null}
                    </Link>
                  )}

                  {isExpandable && !isSidebarCollapsed && isExpanded ? (
                    <div className="nav-sublist">
                      {item.children?.map((child) => {
                        const isChildActive = pathname === child.href || pathname.startsWith(`${child.href}/`);
                        return (
                          <Link
                            key={child.href}
                            className={["nav-subitem", isChildActive ? "is-active" : ""].filter(Boolean).join(" ")}
                            href={child.href}
                            onClick={() => setMobileNavOpenForPath(false)}
                          >
                            <span className="nav-subitem-label">{child.label}</span>
                            {child.description ? <span className="nav-subitem-description">{child.description}</span> : null}
                          </Link>
                        );
                      })}
                    </div>
                  ) : null}
                </div>
              );
            })}
          </nav>

          <div className="sidebar-bottom">
            <div className="sidebar-bell-wrapper" ref={bellWrapperRef}>
              <button
                type="button"
                className={["sidebar-bell-button", isNotificationPanelOpen ? "is-active" : ""].filter(Boolean).join(" ")}
                aria-label="Bildirimler"
                onClick={() => {
                  setIsNotificationPanelOpen((v) => !v);
                  if (!isNotificationPanelOpen) markAllSeen();
                }}
              >
                <span className="sidebar-bell-icon-wrap">
                  <BellIcon className="nav-icon" />
                  {unreadCount > 0 ? (
                    <span className="sidebar-bell-badge">{unreadCount > 9 ? "9+" : unreadCount}</span>
                  ) : null}
                </span>
                {!isSidebarCollapsed ? <span className="sidebar-bell-label">Bildirimler</span> : null}
              </button>

              {isNotificationPanelOpen ? (
                <>
                  <div className="notification-panel sidebar-notification-panel">
                    <div className="notification-panel-head">
                      <strong>Bildirimler</strong>
                      {notifications.length > 0 ? (
                        <button type="button" className="notification-clear-btn" onClick={clearNotifications}>
                          Temizle
                        </button>
                      ) : null}
                    </div>
                    {notifications.length === 0 ? (
                      <div className="notification-empty">Bildirim yok</div>
                    ) : (
                      <div className="notification-list">
                        {notifications.map((n) => (
                          <div key={n.id} className={`notification-item notification-item-${n.type}`}>
                            <div className="notification-item-body">
                              <strong>{n.message}</strong>
                              {n.detail ? <span>{n.detail}</span> : null}
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </>
              ) : null}
            </div>

            <div className="topbar-account-menu sidebar-account-menu">
              <button
                type="button"
                className="topbar-account sidebar-account"
                aria-expanded={isUserMenuOpen}
                onClick={toggleUserMenuForPath}
              >
                <span className="topbar-account-avatar">{currentUser.user.fullName.slice(0, 1).toUpperCase()}</span>
                {!isSidebarCollapsed ? (
                  <div className="topbar-account-copy">
                    <span className="topbar-account-label">Şirket</span>
                    <strong>{currentUser.company.name}</strong>
                    <span>{currentUser.user.fullName}</span>
                  </div>
                ) : null}
              </button>

              <div
                className={[
                  "topbar-account-panel",
                  "sidebar-account-panel",
                  isUserMenuOpen ? "is-open" : "",
                ]
                  .filter(Boolean)
                  .join(" ")}
              >
                <div className="topbar-account-panel-copy">
                  <strong>{currentUser.user.fullName}</strong>
                  <span>{currentUser.user.email}</span>
                </div>
                <div className="topbar-account-panel-copy">
                  <strong>{currentUser.company.name}</strong>
                  <span>Mevcut çalışma alanı</span>
                </div>
                <button
                  type="button"
                  className="button-secondary compact-button"
                  onClick={() => {
                    setUserMenuOpenForPath(false);
                    void handleLogout();
                  }}
                >
                  Çıkış Yap
                </button>
              </div>
            </div>

            {enabledLanguages.length > 1 ? (
              <div
                className={[
                  "language-switcher",
                  "sidebar-language-switcher",
                  isSidebarCollapsed ? "is-collapsed" : "",
                ]
                  .filter(Boolean)
                  .join(" ")}
                role="group"
                aria-label={t("shell.topbar.languageLabel")}
              >
                {enabledLanguages.map((code) => (
                  <LanguageButton key={code} current={language} code={code} label={t(`shell.topbar.${code}`)} onSelect={setLanguage} />
                ))}
              </div>
            ) : null}
          </div>
        </div>
      </aside>

      <main className="app-main">
        <div className="main-frame">
          <header
            className={[
              "topbar",
              isPageScrolled ? "is-scrolled" : "",
              isDashboardHome ? "is-dashboard-topbar" : "",
              isSurveysRoute ? "is-surveys-topbar" : "",
                  isOperationsRoute ? "is-operations-topbar" : "",
            ]
              .filter(Boolean)
              .join(" ")}
          >
            <div className="header-cluster topbar-context">
              {shouldShowBackButton ? (
                <button type="button" className="button-secondary compact-button topbar-back-button" onClick={handleGoBack} aria-label="Geri">
                  <ArrowLeftIcon className="nav-icon" />
                </button>
              ) : null}
              <button className="mobile-menu-button mobile-only" onClick={() => setMobileNavOpenForPath(true)}>
                <MenuIcon className="nav-icon" />
                Gezinme
              </button>
              <div
                className={[
                  "topbar-copy",
                  "topbar-copy-compact",
                  isDashboardHome ? "is-dashboard-copy" : "",
                  isSurveysRoute ? "is-surveys-copy" : "",
                  isOperationsRoute ? "is-operations-copy" : "",
                ]
                  .filter(Boolean)
                  .join(" ")}
              >
                {breadcrumbs.length > 0 ? (
                  <div className="topbar-breadcrumbs" aria-label="Sayfa yolu">
                    {breadcrumbs.map((crumb, index) => (
                      <span key={`${crumb}-${index}`} className="topbar-breadcrumb-item">
                        {index > 0 ? <span className="topbar-breadcrumb-separator">/</span> : null}
                        <span>{crumb}</span>
                      </span>
                    ))}
                  </div>
                ) : !isSurveysRoute ? (
                  <span className="topbar-kicker">{currentUser.company.name}</span>
                ) : null}
                <h1>{currentMeta.title}</h1>
                {typeof currentMeta.subtitle === "string"
                  ? (currentMeta.subtitle ? <p>{currentMeta.subtitle}</p> : null)
                  : (!renderSubtitleInCenterSlot && currentMeta.subtitle ? <div className="topbar-subtitle-slot">{currentMeta.subtitle}</div> : null)}
              </div>
            </div>

            {renderSubtitleInCenterSlot ? (
              <div className="topbar-center-slot">
                <div className="topbar-subtitle-slot">{currentMeta.subtitle}</div>
              </div>
            ) : null}

            {pageHeaderOverride?.action ? (
              <div className="topbar-actions page-header-actions">
                {pageHeaderOverride.action}
              </div>
            ) : null}

          </header>

          {children}
        </div>
      </main>
    </div>
  );
}

function buildBreadcrumbs(
  pathname: string,
  currentTitle: string,
  navigationItems: ReturnType<typeof getNavigationItems>,
  t: (path: string) => string,
) {
  if (pathname === "/") {
    return [currentTitle];
  }

  const findNavLabel = (href: string) => navigationItems.find((item) => item.href === href)?.label;

  if (pathname === "/surveys") {
    return [findNavLabel("/surveys") ?? currentTitle];
  }

  if (pathname === "/surveys/new") {
    return [findNavLabel("/surveys") ?? t("shell.pageMeta.surveys.title"), currentTitle];
  }

  if (pathname.startsWith("/surveys/")) {
    return [findNavLabel("/surveys") ?? t("shell.pageMeta.surveys.title"), currentTitle];
  }

  if (pathname === "/operations") {
    return [findNavLabel("/operations") ?? currentTitle];
  }

  if (pathname === "/operations/new") {
    return [findNavLabel("/operations") ?? t("shell.pageMeta.operations.title"), currentTitle];
  }

  if (pathname.startsWith("/operations/")) {
    return [findNavLabel("/operations") ?? t("shell.pageMeta.operations.title"), currentTitle];
  }

  return [currentTitle];
}

function LanguageButton({
  current,
  code,
  label,
  onSelect,
}: {
  current: Language;
  code: Language;
  label: string;
  onSelect: (language: Language) => void;
}) {
  return (
    <button
      type="button"
      className={["language-button", current === code ? "is-active" : ""].filter(Boolean).join(" ")}
      aria-pressed={current === code}
      onClick={() => onSelect(code)}
    >
      {label}
    </button>
  );
}

