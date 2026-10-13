import { Operation, OperationAnalytics, OperationAnalyticsQuestionSummary } from "@/lib/types";

type OperationLifecycleStatus = Extract<
  Operation["status"],
  "Draft" | "Ready" | "Scheduled" | "Running" | "Paused" | "Completed" | "Failed" | "Cancelled"
>;

export const OPERATION_STATUS_BADGE_CONFIG: Record<
  OperationLifecycleStatus,
  { tone: "neutral" | "ready" | "live" | "warning" | "success" | "danger"; label: string }
> = {
  Draft: { tone: "neutral", label: "Taslak" },
  Ready: { tone: "ready", label: "Hazır" },
  Scheduled: { tone: "ready", label: "Planlandı" },
  Running: { tone: "live", label: "Yürütülüyor" },
  Paused: { tone: "warning", label: "Duraklatıldı" },
  Completed: { tone: "success", label: "Tamamlandı" },
  Failed: { tone: "danger", label: "Başarısız" },
  Cancelled: { tone: "warning", label: "İptal edildi" },
};

export function getOperationStatusConfig(operation: Operation | null, contactCount: number) {
  const status = (operation?.status ?? "Draft") as OperationLifecycleStatus;
  const badge = OPERATION_STATUS_BADGE_CONFIG[status];

  switch (status) {
    case "Draft":
      return {
        badge,
        title: "Hazırlık tamamlanmadı",
        summary: "Bu operasyon henüz yürütmeye açılacak noktada değil.",
        nextStepTitle: contactCount > 0 ? "Eksik önkoşulları tamamla" : "Kişi yükle",
        nextStepText: !operation?.readiness.surveyPublished
          ? "Bağlı anketin yayın durumunu netleştirin. Yayınlanmamış anketle akış başlatılmaz."
          : contactCount === 0
            ? "Bir sonraki mantıklı adım kişi yüklemek. En az bir kişi olmadan akış başlatılamaz."
            : "Hazırlık listesinde kalan blokajları kapatarak operasyonu Hazır durumuna taşıyın.",
      };
    case "Ready":
      return {
        badge,
        title: "Operasyon başlatmaya hazır",
        summary: "Tüm temel önkoşullar tamam. Bu sayfa artık operasyonun başlatma ve ilk izleme yüzeyi.",
        nextStepTitle: "Akışı başlat",
        nextStepText: "Akış başlatıldığında çağrı işleri hazırlanır ve cevap analitiği bu sayfada canlı güncellenir.",
      };
    case "Scheduled":
      return {
        badge,
        title: "Operasyon yeniden başlatılmaya hazır",
        summary: "Geçmiş çağrı verileri temizlendi. Kişi listesi korunarak akış sıfırlandı.",
        nextStepTitle: "Akışı yeniden başlat",
        nextStepText: "Başlattığınızda kişiler için yeni çağrı işleri oluşturulur ve operasyon baştan ilerler.",
      };
    case "Running":
      return {
        badge,
        title: "Canlı operasyon yürütülüyor",
        summary: "Bu operasyon aktif çalışıyor. İlk bakışta durum, iş akışı ve dönen cevaplar görünmeli.",
        nextStepTitle: "Yürütmeyi izle",
        nextStepText: "Başlatma yeniden açılmaz. Bu aşamada işleri ve canlı sonuç dağılımlarını takip edin.",
      };
    case "Completed":
      return {
        badge,
        title: "Operasyon tamamlandı",
        summary: "Yürütme kapandı. Bu sayfa sonuç özeti ve soru bazlı içgörüler için ana kontrol yüzeyi olmaya devam eder.",
        nextStepTitle: "Sonuçları kullan",
        nextStepText: "Sonuçları inceleyin, dışa aktarım alın ve gerekirse daha detaylı analytics akışına geçin.",
      };
    case "Paused":
      return {
        badge,
        title: "Operasyon duraklatıldı",
        summary: "Aktif görüşme bittikten sonra sistem yeni çağrılara geçmez. Hazır olduğunuzda devam ettirebilirsiniz.",
        nextStepTitle: "Akışı devam ettir",
        nextStepText: "Devam ettir aksiyonu sıradaki bekleyen çağrı işini yeniden kuyruğa alır.",
      };
    case "Failed":
      return {
        badge,
        title: "Operasyon durdu",
        summary: "Yürütme sırasında bir problem yaşandı; buna rağmen toplanan veri saklandı.",
        nextStepTitle: "Güvenli inceleme",
        nextStepText: "Sorunlu işleri açın, toplanan kısmi veriyi inceleyin ve gerekirse operasyonu yeniden hazırlayın.",
      };
    case "Cancelled":
      return {
        badge,
        title: "Operasyon iptal edildi",
        summary: "Akış manuel olarak sonlandırıldı. Yeni çağrılar bu durumdayken başlamaz.",
        nextStepTitle: "Durumu gözden geçir",
        nextStepText: "Gerekirse yeni bir operasyon açın veya operasyonu yeniden planlayın.",
      };
    default:
      return {
        badge: OPERATION_STATUS_BADGE_CONFIG.Draft,
        title: "Durum bilgisi güncelleniyor",
        summary: "Operasyon durumu okunuyor.",
        nextStepTitle: "Bekleyin",
        nextStepText: "Durum bilgisi yüklenirken bu kart güvenli varsayılan içerikle gösterilir.",
      };
  }
}

export function getAnalyticsEmptyState(status: Operation["status"], contactCount: number) {
  switch (status) {
    case "Draft":
      return {
        title: "Henüz çağrı sonucu yok",
        description: "Analitik paneller, yürütme başlayıp görüşme yanıtları gelmeye başladığında burada görünür.",
      };
    case "Ready":
    case "Scheduled":
      return {
        title: "Yürütme öncesi görünüm",
        description: `${contactCount} kişi için operasyon hazır. Cevap grafikleri akış başladıktan sonra dolar.`,
      };
    case "Running":
      return {
        title: "Canlı veri bekleniyor",
        description: "İlk yanıtlar geldikçe durum dağılımları ve soru bazlı paneller otomatik güncellenir.",
      };
    case "Failed":
      return {
        title: "Kısmi veri yok",
        description: "Bu hata durumunda henüz gösterilecek cevap verisi birikmedi.",
      };
    case "Cancelled":
      return {
        title: "Operasyon iptal edildi",
        description: "İptal edilen operasyonlarda yeni veri akışı olmaz.",
      };
    case "Paused":
      return {
        title: "Akış duraklatıldı",
        description: "Mevcut görüşme tamamlandıktan sonra yeni çağrıya geçilmez. Devam ettirdiğinizde veri akışı sürer.",
      };
    case "Completed":
    default:
      return {
        title: "Sonuç verisi yok",
        description: "Bu operasyon tamamlanmış olsa da cevap kaydı bulunmuyor.",
      };
  }
}

export function getPrimaryAction(operation: Operation | null, isStarting: boolean) {
  if (!operation) {
    return { label: "Akışı başlat", disabled: true, hint: "Operasyon bilgisi yükleniyor.", intent: "start" as const };
  }

  if (operation.status === "Ready") {
    return {
      label: isStarting ? "Akış başlatılıyor..." : "Akışı başlat",
      disabled: isStarting,
      hint: "Operasyon hazır. Akışı bu aksiyondan başlatabilirsiniz.",
      intent: "start" as const,
    };
  }

  if (operation.status === "Scheduled") {
    return {
      label: isStarting ? "Akış başlatılıyor..." : "Akışı yeniden başlat",
      disabled: isStarting,
      hint: "Operasyon sıfırlandı. Kişi listesi korunarak akışı yeniden başlatabilirsiniz.",
      intent: "start" as const,
    };
  }

  if (operation.status === "Draft") {
    return {
      label: "Akışı başlat",
      disabled: true,
      hint: operation.readiness.blockingReasons[0]
        ?? (operation.readiness.contactsLoaded ? "Başlatmadan önce önkoşulları tamamlayın." : "Başlatmadan önce kişi yükleyin."),
      intent: "start" as const,
    };
  }

  if (operation.status === "Running") {
    return {
      label: "Durdur",
      disabled: false,
      hint: "Mevcut görüşme tamamlandıktan sonra yeni çağrılar başlatılmaz.",
      intent: "pause" as const,
    };
  }

  if (operation.status === "Paused") {
    return {
      label: "Devam ettir",
      disabled: false,
      hint: "Duraklatılan operasyonu sıradaki bekleyen çağrıdan devam ettirir.",
      intent: "resume" as const,
    };
  }

  if (operation.status === "Completed") {
    return {
      label: "Akış tamamlandı",
        disabled: true,
      hint: "Tamamlanan operasyonlarda başlatma aksiyonu yeniden açılmaz.",
      intent: "start" as const,
    };
  }

  if (operation.status === "Failed") {
    return {
      label: "Akış durdu",
      disabled: true,
      hint: "Operasyon hata ile durdu. Detayları inceleyip yeniden hazırlamanız gerekir.",
      intent: "start" as const,
    };
  }

  if (operation.status === "Cancelled") {
    return {
      label: "Operasyon iptal edildi",
      disabled: true,
      hint: "İptal edilen operasyon bu ekrandan yeniden başlatılamaz.",
      intent: "start" as const,
    };
  }

  return {
    label: "Akışı başlat",
    disabled: true,
    hint: "Bu durumdayken akış başlatılamaz.",
    intent: "start" as const,
  };
}

export function getAnalyticsKpis(operation: Operation | null, analytics: OperationAnalytics | null) {
  if (!operation || !analytics) {
    return [];
  }

  const isFailed = operation.status === "Failed";
  const hasResponses = analytics.totalResponses > 0;

  return [
    {
      label: "Toplam kişi",
      value: String(analytics.totalContacts),
      detail: "Operasyona yüklenen kişiler",
      tone: "neutral" as const,
    },
    {
      label: "Toplam çağrı işi",
      value: String(analytics.totalCallJobs),
      detail: `${analytics.totalPreparedJobs} hazır iş kaydı`,
      tone: "neutral" as const,
    },
    {
      label: "Aranan kişi",
      value: String(analytics.totalCallsAttempted),
      detail: `${analytics.totalCallAttempts} çağrı denemesi`,
      tone: "neutral" as const,
    },
    {
      label: "Tamamlanan çağrı",
      value: String(analytics.totalCompletedCalls),
      detail: "Başarıyla kapanan aramalar",
      tone: "positive" as const,
    },
    {
      label: "Başarısız çağrı",
      value: String(analytics.failedCallJobs),
      detail: "Takip gerektiren çağrı işleri",
      tone: isFailed ? "danger" as const : "warning" as const,
    },
    {
      label: "Toplam yanıt",
      value: String(analytics.respondedContacts),
      detail: `${analytics.completedResponses} tam yanıt, ${analytics.partialResponses} kısmi yanıt`,
      tone: hasResponses ? "positive" as const : "neutral" as const,
    },
    {
      label: "Tamamlanma oranı",
      value: `%${analytics.completionRate}`,
      detail: hasResponses ? "Tamamlanan yanıt / toplam yanıt" : "Henüz yanıt yok",
      tone: hasResponses ? "positive" as const : "neutral" as const,
    },
    {
      label: "Cevap oranı",
      value: `%${analytics.personResponseRate}`,
      detail: "Yanıt veren kişi / hedef kişi",
      tone: isFailed ? "warning" as const : "positive" as const,
    },
  ];
}

export function getAnalyticsKpisCompact(operation: Operation | null, analytics: OperationAnalytics | null) {
  if (!operation || !analytics) {
    return [];
  }

  const isFailed = operation.status === "Failed";
  const hasResponses = analytics.totalResponses > 0;
  const riskLoad = analytics.failedCallJobs + analytics.skippedCallJobs;

  return [
    {
      label: "Hedef kişi",
      value: String(analytics.totalContacts),
      detail: "",
      tone: "neutral" as const,
    },
    {
      label: "Aranan kişi",
      value: String(analytics.totalCallsAttempted),
      detail: `${analytics.totalCallAttempts} çağrı denemesi, ${analytics.queuedJobs} kuyrukta, ${analytics.inProgressJobs} aktif`,
      tone: "neutral" as const,
    },
    {
      label: "Yanıt veren kişi",
      value: String(analytics.respondedContacts),
      detail: `${analytics.completedResponses} tam yanıt, ${analytics.partialResponses} kısmi yanıt`,
      tone: hasResponses ? "positive" as const : "neutral" as const,
    },
    {
      label: "Cevap oranı",
      value: `%${analytics.personResponseRate}`,
      detail: hasResponses
        ? `Yanıt veren kişi / hedef kişi, %${analytics.completionRate} tamamlanan yanıt`
        : "Yanıt veren kişi / hedef kişi",
      tone: isFailed || riskLoad > 0 ? "warning" as const : "positive" as const,
    },
  ];
}

export function getQuestionChartPresentation(summary: OperationAnalyticsQuestionSummary) {
  switch (summary.chartKind) {
    case "RATING":
      return {
        eyebrow: "Puan dağılımı",
        empty: summary.emptyStateMessage ?? "Puan verisi geldikçe dağılım oluşur.",
      };
    case "NUMBER":
      return {
        eyebrow: "Sayısal dağılım",
        empty: summary.emptyStateMessage ?? "Sayısal veriler geldikçe dağılım oluşur.",
      };
    case "BINARY":
      return {
        eyebrow: "Evet / Hayır dağılımı",
        empty: summary.emptyStateMessage ?? "İkili cevap dağılımı henüz oluşmadı.",
      };
    case "MULTI_CHOICE":
      return {
        eyebrow: "Çoklu seçim dağılımı",
        empty: summary.emptyStateMessage ?? "Çoklu seçim dağılımı henüz oluşmadı.",
      };
    case "OPEN_ENDED":
      return {
        eyebrow: "Açık uçlu yanıtlar",
        empty: summary.emptyStateMessage ?? "Bu soru için henüz gösterilecek dağılım yok.",
      };
    case "CHOICE":
    default:
      return {
        eyebrow: "Seçenek dağılımı",
        empty: summary.emptyStateMessage ?? "Seçenek dağılımı henüz oluşmadı.",
      };
  }
}
