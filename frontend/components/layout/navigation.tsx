import { AnalyticsIcon, OperationIcon, DashboardIcon, SurveyIcon } from "@/components/ui/Icons";
import type { NavItem } from "@/lib/types";

type Translate = (path: string) => string;

export function getNavigationItems(t: Translate): NavItem[] {
  return [
    {
      href: "/",
      label: t("shell.navigation.dashboard.label"),
      description: t("shell.navigation.dashboard.description"),
      icon: <DashboardIcon className="nav-icon" />,
    },
    {
      href: "/surveys",
      label: t("shell.navigation.surveys.label"),
      description: t("shell.navigation.surveys.description"),
      icon: <SurveyIcon className="nav-icon" />,
      children: [
        {
          href: "/surveys",
          label: "Anketler Dashboard",
          description: "Tüm anketleri gör",
        },
        {
          href: "/surveys/new",
          label: "Yeni Anket Oluştur",
          description: "Sıfırdan yeni anket kur",
        },
      ],
    },
    {
      href: "/operations",
      label: t("shell.navigation.operations.label"),
      description: t("shell.navigation.operations.description"),
      icon: <OperationIcon className="nav-icon" />,
      children: [
        {
          href: "/operations",
          label: "Operasyon Dashboard",
          description: "Tüm operasyonları gör",
        },
        {
          href: "/operations/new",
          label: "Yeni Operasyon",
          description: "Yeni arama akışı başlat",
        },
      ],
    },
    {
      href: "/analytics",
      label: t("shell.navigation.analytics.label"),
      description: t("shell.navigation.analytics.description"),
      icon: <AnalyticsIcon className="nav-icon" />,
    },
  ];
}
