export const en = {
  shell: {
    brandSubtitle: "Research Operations",
    sidebar: {
      expand: "Expand",
      collapse: "Collapse",
      navigation: "Navigation",
      dailyFocus: "Daily Focus",
      readinessTitle: "Operational readiness",
      readinessDescription:
        "Use the dashboard to triage issues, then move into surveys, operations, contacts, and calling workflows to execute.",
      closeNavigation: "Close navigation",
    },
    topbar: {
      menu: "Menu",
      searchPlaceholder: "Search surveys, operations, contacts...",
      notifications: "Notifications",
      createSurvey: "Create Survey",
      languageLabel: "Language",
      tr: "TR",
      en: "EN",
    },
    pageMeta: {
      dashboard: {
        title: "Operations Overview",
        subtitle:
          "Track operation health, survey readiness, contact flow, and operational issues from one control layer.",
      },
      surveys: {
        title: "Surveys",
        subtitle: "Manage survey drafts, publishing status, and live program inventory.",
      },
      operations: {
        title: "Operations",
        subtitle: "Monitor launch readiness, active pacing, and cross-channel execution.",
      },
      analytics: {
        title: "Analytics",
        subtitle: "Review completions, response lift, and delivery performance across the portfolio.",
      },
      surveyCreate: {
        title: "New Survey",
        subtitle: "Prepare the title, description, and question flow in a single editing surface.",
      },
      surveyDetail: {
        title: "Survey Detail",
        subtitle: "Inspect survey health, response posture, and operational notes.",
      },
      operationCreate: {
        title: "New Operation",
        subtitle: "Select a published survey and prepare a new operation for the calling flow.",
      },
      operationDetail: {
        title: "Operation Detail",
        subtitle: "Review pacing, channel mix, and lifecycle movement in one view.",
      },
      operationJobs: {
        title: "Call Job Monitor",
        subtitle: "Track prepared call jobs, statuses, and recent execution outcomes.",
      },
    },
    navigation: {
      dashboard: { label: "Dashboard", description: "Operations overview" },
      surveys: { label: "Surveys", description: "Drafts and live studies" },
      operations: { label: "Operations", description: "Launch and pacing" },
      analytics: { label: "Analytics", description: "Performance and trends" },
    },
    common: {
      viewAll: "View all",
    },
    status: {
      live: "Live",
      draft: "Draft",
      archived: "Archived",
      active: "Active",
      ready: "Ready",
      running: "Running",
      scheduled: "Scheduled",
      paused: "Paused",
      completed: "Completed",
      cancelled: "Cancelled",
      failed: "Failed",
      retry: "Retry",
      invalid: "Invalid",
      pending: "Pending",
      queued: "Queued",
      inProgress: "In progress",
      skipped: "Skipped",
    },
  },
  dashboard: {
    kpis: {
      surveysDetail: "Survey records currently available across the company.",
      operationsDetail: "Operation inventory currently surfaced in the dashboard.",
      contactsDetail: "Contacts loaded from operation records.",
    },
    hero: {
      eyebrow: "Product Overview",
      title: "A clean dashboard built from live product records",
      description:
        "This overview only surfaces backend-backed inventory for surveys, operations, and contacts so the dashboard stays useful without drifting into demo metrics.",
      openSurveys: "Open Surveys",
      openOperations: "Open Operations",
      openContacts: "Open Contacts",
      surveysSynced: "Surveys",
      operationsSynced: "Operations",
      contactsSynced: "Contacts",
      syncedSuffix: "synced",
    },
    unavailable: {
      title: "Some data is temporarily unavailable",
      description: "Only successfully loaded backend sections are shown below.",
      nowUnavailable: "Unavailable right now",
      single: "{{section}} could not be loaded from the backend.",
      multiple: "{{sections}} could not be fully loaded from the backend.",
      surveys: "Surveys",
      operations: "Operations",
      contacts: "Contacts",
    },
    sections: {
      recentSurveys: {
        title: "Recent Surveys",
        description: "Latest survey records returned by the backend.",
        emptyTitle: "No surveys yet",
        emptyDescription: "Survey records will appear here as soon as they exist in the backend.",
      },
      recentOperations: {
        title: "Recent Operations",
        description: "Current operation inventory backed by the backend API.",
        emptyTitle: "No operations yet",
        emptyDescription: "Operation records will show up here once they are created in the backend.",
      },
      recentContacts: {
        title: "Recent Contacts",
        description: "Contacts loaded from operation contact records already stored in the backend.",
        action: "Open contacts",
        emptyTitle: "No contacts yet",
        emptyDescription: "Uploaded operation contacts will appear here when backend records are available.",
      },
      operationalAnalytics: {
        title: "Operational Analytics",
        description: "Reserved for backend-backed throughput and quality metrics.",
        emptyTitle: "Not available yet",
        emptyDescription: "Completion, alerting, and call-operation metrics stay hidden until the backend exposes real aggregates.",
      },
    },
  },
  surveys: {
    hero: {
      eyebrow: "Survey Programs",
      title: "A polished inventory for every survey workflow in the platform.",
      description:
        "Use this page as the core foundation for list management, monitoring, and drill-down navigation. The structure is intentionally reusable for future filtering, search, and backend data hooks.",
      createSurvey: "Create Survey",
      importBlueprint: "Import Blueprint",
      chips: ["Live status badges", "Reusable table sections", "Responsive cards + tables"],
    },
    table: {
      title: "Survey portfolio",
      description: "Live survey inventory powered by the backend API.",
      columns: {
        survey: "Survey",
        status: "Status",
        audience: "Audience",
        completions: "Completions",
        responseRate: "Response rate",
        action: "Open",
      },
      filters: {
        all: "All",
        live: "Live",
        draft: "Draft",
        archived: "Archived",
      },
      states: {
        errorTitle: "Unable to load surveys",
        loadingTitle: "Loading surveys",
        loadingDescription: "Fetching the latest survey inventory from the backend.",
        emptyTitle: "No surveys yet",
        emptyDescription: "No survey records were returned for this company.",
        synced: "{{count}} surveys / synced from backend",
        export: "Export List",
        viewDetail: "View detail",
      },
    },
    extras: {
      momentumTitle: "Portfolio momentum",
      momentumDescription: "Placeholder chart for completions and response lift.",
      chartTitle: "Weekly survey activity",
      chartSubtitle: "Live completion throughput across active programs",
      designNotesTitle: "Design notes",
      designNotesDescription: "Frontend-specific guidance reflected in this foundation.",
      notes: [
        "Rounded large panels with subtle glow and premium depth.",
        "Mobile-first layout behavior for tables and stacked content.",
        "Clean dark palette that avoids generic admin-dashboard defaults.",
        "Reusable component surfaces ready for backend wiring later.",
      ],
    },
  },
  operations: {
    hero: {
      eyebrow: "Operation Engine",
      title: "Cross-channel delivery and pacing in one premium control surface.",
      description:
        "The operations view is tuned for modern analytics workflows: high signal density, clean hierarchy, and reusable structures for future automation layers.",
      launchOperation: "Create Operation",
      segmentBuilder: "Segment Builder",
      chips: ["Voice AI outreach", "Email + SMS orchestration", "Status-aware detail views"],
    },
    table: {
      title: "Operation inventory",
      description: "Live operation inventory powered by the backend API.",
      columns: {
        operation: "Operation",
        status: "Status",
        survey: "Survey",
        reach: "Reach",
        conversion: "Conversion",
        action: "Open",
      },
      states: {
        errorTitle: "Unable to load operations",
        loadingTitle: "Loading operations",
        loadingDescription: "Fetching the latest operation inventory from the backend.",
        emptyTitle: "No operations yet",
        emptyDescription: "No operation records were returned for this company.",
        synced: "{{count}} operations / synced from backend",
        viewDetail: "View detail",
      },
      filters: {
        allStages: "All stages",
        active: "Active",
        paused: "Paused",
      },
    },
    extras: {
      reachTitle: "Reach trajectory",
      reachDescription: "Channel performance placeholder with consistent visual treatment.",
      reachChartTitle: "Delivery volume",
      reachChartSubtitle: "Weekly multi-channel trajectory",
      channelMixTitle: "Channel mix",
      channelMixDescription: "Future-ready card area for real channel analytics.",
      channelMix: [
        ["Voice AI", "Highest conversion efficiency this week"],
        ["Email", "Best reach for enterprise nurture"],
        ["SMS", "Strongest reminder performance in short windows"],
      ],
    },
  },
  analytics: {
    hero: {
      eyebrow: "Analytics",
      title: "Portfolio performance in one view",
      description:
        "Compare completion rates, delivery output, and operation conversion trends before drilling into survey and operation detail pages.",
      openOperations: "Open Operations",
      openSurveys: "Open Surveys",
    },
    sections: {
      performanceTitle: "Performance Trend",
      performanceDescription: "Daily throughput across active research operations.",
      performanceChartTitle: "Completion throughput",
      performanceChartSubtitle: "Active studies over the last 12 sessions",
      priorityTitle: "Priority Reads",
      priorityDescription: "Signals to check before end-of-day reporting.",
      reads: [
        ["Completion rate softened", "Mobile-assisted sessions are below weekly target.", "Paused"],
        ["Enterprise operation lift", "CX Activation Spring 2026 is outperforming baseline.", "Active"],
        ["Contact quality risk", "Validation failures are skewing call-job readiness.", "Pending"],
      ],
    },
  },
  common: {
    weekdaysShort: ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat"],
    fallback: {
      unavailable: "Unavailable",
    },
  },
} as const;






