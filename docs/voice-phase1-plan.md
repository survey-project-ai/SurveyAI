# Aşama 1: LiveKit ses ajanını backend'e bağlama

Hazırlanma: 15 Ekim 2026. Önceki adım: `docs/voice-pipeline-prototype-plan.md` (Aşama 0, prototip ve ölçümler).

## 1. Amaç

`voice-agent/` prototipi bugün soruları ve cevap kurallarını kendi içinde tutuyor; sonuçlar yalnızca geliştirme
makinesindeki `logs/` klasörüne gidiyor. Bu aşamanın sonunda:

- Bir operasyon başlatıldığında aramalar LiveKit ajanıyla yapılır (`SURVEYAI_CALLING_ACTIVE_PROVIDER=LIVEKIT`).
- Sorular, onay akışı, cevap yorumlama, dallanma ve kayıt mevcut backend görüşme motorundan gelir; operasyon
  ekranları ve analizler hiçbir değişiklik olmadan çalışır.
- Ajan AB'de bir sunucuda sürekli çalışır; geliştirme makinesine bağlı değildir.
- ElevenLabs yedek sağlayıcı olarak kalır; tek bir ortam değişkeniyle geri dönülür.

Kapsam dışı (Aşama 2): telesekreter algılama, ses kayıtlarının kalıcı saklanması ve KVKK rızası, kontrol
bekleyen cevaplar için panel ekranı, eşzamanlı arama kapasitesi ve LiveKit ücretli paketi.

## 2. Bugün backend'in ElevenLabs'e bağlı olduğu yerler

| Yer | Durum |
|---|---|
| `CallProvider` enum ve `call_attempt.provider` CHECK kısıtı (V11) | `LIVEKIT` yok |
| `VoiceExecutionProperties`, `VoiceProviderConfigurationResolver` | yalnızca `mock` ve `elevenlabs` tanımlı |
| Görüşme araçları `ElevenLabsInterviewToolController` (`/api/v1/provider-tools/elevenlabs/interviews/*`) | gizli anahtar ElevenLabs ayarından okunuyor, karşılaştırma sabit süreli değil |
| `CallInterviewOrchestrationServiceImpl.validateProvider` ve `resolveCallAttempt` | ElevenLabs dışındaki aramaları reddediyor |
| `ProviderWebhookIngestionServiceImpl:183` | döküm anahtarı `inline://elevenlabs/...` olarak sabit |
| `SurveyResponseIngestionServiceImpl` | sağlayıcının sonuç eşleyicisi yoksa hata fırlatıp tüm webhook işlemini geri alıyor; LiveKit için eşleyici şart |
| `CallStatusReconciliationService` | yalnızca etkin sağlayıcıyı izliyor; geçişte açık kalan ElevenLabs aramaları 1 saatlik zaman aşımıyla kapanır |

Görüşme motorunun ajanın doğrudan seslendireceği metinlerle ilgili üç sorunu var. ElevenLabs'te araya bir dil modeli
girdiği için fark edilmiyordu:

- Tekrar isteğinde dönen "Soruyu yavaşça bir kez daha sor ve henüz ilerleme." ve anlaşılmayan cevaptan sonra dönen
  "Yanıtın net anlaşılmadığını kısaca belirt, nazikçe devam et ve sonraki soruyu sor." metinleri dil modeline
  verilen talimatlar; konuşma metni değil.
- Puan sorusunda hatalı cevapta Türkçe anketlerde de İngilizce "Rating answer must be between 1 and 5" cümlesi dönüyor.
- `normalizePromptPacing` içindeki "LÃ¼tfen" bozuk yazıldığı için hiç eşleşmiyor.

Ayrıca motor, 3 ve daha fazla seçenekli sorularda başlıktaki seçenek listesini (`?` sonrasını) kesiyor ve
seçenekleri hiç okumuyor. Prototipteki aday sorusu ("…kimi görmek istersiniz? Recep Tayyip Erdoğan, Ekrem İmamoğlu ya
da Mansur Yavaş?") bu haliyle isimler okunmadan sorulurdu.

## 3. İş bölümü

| Konu | Nerede | Not |
|---|---|---|
| Anket akışı, onay, soru metni, cevap yorumlama, dallanma, tekrar sınırı, kayıt, durum | Backend motoru | Tek doğruluk kaynağı; ElevenLabs ile ortak |
| Duraksamaları ("eee", "hmm") yok sayma, "Alo?" kontrolü, cümle bölünmelerini birleştirme | Ajan | Konuşma anına ait; backend'e hiç gitmez |
| Tekrar / kimlik / neden / bitirme isteklerini tanıma | Ajan → backend sinyali | `REPEAT_REQUEST`, `IDENTITY_REQUEST`, `REASON_REQUEST`, `STOP_REQUEST`, `NO_INPUT` |
| Açılış ve kapanışın kesilmeden söylenmesi, sabit cümlelerin önbelleği, ses dengeleyici, ofis sesi | Ajan | Prototipte hazır |
| Cinsiyetin sesten tahmini | Ajan → backend `/finish` | Bkz. B6 |
| Görüşme sonrası cevap kontrolü | Ajan servisi → backend | Bkz. B7 |
| Arama başlatma, durum, iptal | Backend `LiveKitVoiceExecutionProvider` | LiveKit API |
| Arama durumu bildirimleri (çalıyor, açıldı, bitti, meşgul, cevapsız) | Ajan → backend webhook | HMAC imzalı |

## 4. Backend işleri

**B1. Sağlayıcı tanımı.**
- `CallProvider.LIVEKIT` eklenecek.
- `V18__add_livekit_call_provider.sql` ile `chk_call_attempt_provider` kısıtına `LIVEKIT` eklenecek; V11 ve V17'deki
  "kısıtı düşür, yeniden ekle" kalıbı kullanılacak.
- `VoiceExecutionProperties` içine `livekit` bloğu, çözümleyiciye `LIVEKIT` dalı ve başlangıç kontrolleri eklenecek.
- Ayarlar:
  - `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`
  - `agent-name`, `sip-trunk-id`
  - `webhook-secret` (ajan → backend)
  - `tool-api-secret`

**B2. `LiveKitVoiceExecutionProvider`.**
- `dispatchCallJob`: LiveKit'in `AgentDispatchService/CreateDispatch` API'sini çağırır.
  - Oda adı: `call-<callAttemptId>`.
  - Metadata: `callAttemptId`, telefon numarası (`PhoneNumberNormalizer`), anket dili, ASR ipucu kelimeleri
    (bugün ElevenLabs'e gönderilen lexicon).
  - `providerCallId` = oda adı.
- LiveKit erişim anahtarı HS256 imzalı bir JWT. Kütüphane eklemeden `javax.crypto` ile üretilir.
- `fetchCallStatus`: oda yoksa ve terminal bildirim gelmemişse aramayı `FAILED` sayar. Asıl durum bilgisi B4'teki
  bildirimlerden gelir.
- `cancelCall`: odayı siler.
- `validateConfiguration`: zorunlu ayarları denetler.
- Mock modu ElevenLabs'teki gibi API çağırmadan sahte kimlik döner.

**B3. Sağlayıcıdan bağımsız görüşme araçları.**
- Yeni yol `/api/v1/provider-tools/interviews/{start,current-question,answer,finish}`; eski `/elevenlabs/` yolu
  aynı controller'a bağlı kalır.
- `validateProvider` ELEVENLABS ve LIVEKIT'i kabul eder.
- `providerCallId` araması denemenin kendi sağlayıcısıyla yapılır.
- Gizli anahtar, isteği yapan sağlayıcının ayarından okunur ve sabit süreli karşılaştırılır.

**B4. LiveKit bildirimleri ve sonuç eşleyici.**
- `parseWebhook` / `verifyWebhook` (HMAC, zaman damgası toleransı ElevenLabs'teki gibi) yazılır.
- Ajan aramanın her aşamasında olay gönderir. SIP durum kodları eşlenir:
  - 486 → `BUSY`,
  - 480 / 408 / zaman aşımı → `NO_ANSWER`,
  - diğer hatalar → `FAILED`.
- `LiveKitSurveyResultMapper`: cevaplar görüşme sırasında zaten kaydedildiği için yalnızca dökümü ve özeti ekler.
- `inline://elevenlabs` sabiti sağlayıcıya göre üretilir.

**B5. Seslendirilebilir metinler ve seçenek okuma.**
- Talimat cümleleri gerçek konuşma metniyle değiştirilir:
  - tekrar isteğinde soru olduğu gibi tekrarlanır;
  - anlaşılmayan cevap için "Sizi net anlayamadım, bir sonraki soruya geçiyorum." gibi bir cümle kullanılır.

  Bu ElevenLabs için de daha doğru.
- Puan hatası Türkçeleştirilir ve "LÃ¼tfen" düzeltilir.
- Soru bazında "seçenekleri oku" ayarı eklenir (bkz. Karar 2). Açıksa seçenekler soru metninin sonuna "A, B ya da C?"
  biçiminde eklenir.

**B6. Sesten cinsiyet.**
- Ayar cinsiyet sorusunun kendisinde: `settingsJson` içinde `"fromVoice"`. Varsayılan açık (bkz. Karar 3);
  `false` verilirse soru sorulur. Seçenek okuma ayarı gibi soru düzeyinde; veritabanı değişikliği gerekmedi.
- Açıksa motor, LiveKit aramalarında cinsiyet sorusunu atlar; soru tamamlanma hesabına da girmez. ElevenLabs
  ajanı sesten cinsiyet bilemediği için orada soru sorulmaya devam eder. Cinsiyet sorusu bugünkü analizlerle
  aynı kuralla tanınır: kodu ya da başlığı "gender/cinsiyet" içeren tek seçimli soru.
- Ajan `/finish` isteğine `inferredAnswers: {gender: "kadın" | "erkek"}` ekler.
- Motor değeri "Kadın"/"Erkek" seçeneğine eşleyip `answer_json.source = "voice"` ile kaydeder.
- Emin olunamadıysa soru cevapsız kalır.
- Aynı değer `OperationContact.gender` alanına da yazılır.

**B7. Görüşme sonrası kontrol sonuçları.**
- Ajan her sorunun son cevap turunu (soru kimliği, kayıttaki zamanı, canlı metin) `logs/<oda>.checks.json`
  dosyasına yazar. Ajanla aynı konteynerde çalışan `verify_watch.py` cevabın ses parçasını kayıttan keser,
  iki modelle (Linden-1, Gemini) yeniden yazıya döker ve metinleri
  `/api/v1/provider-tools/interviews/verification` ucuna gönderir.
- Backend metinleri görüşmedeki motor kurallarıyla yorumlar ve her cevabın `answer_json.verification`
  alanına durumu, gerekçeyi, kontrol metinlerini ve önerilen değeri yazar.
- Karar verilen kural uygulanır:
  - iki kontrol de kayıtla aynıysa `verified`; bunların %5'i (`random_sample`) yine insana gider;
  - iki kontrol aynı ama farklı geçerli bir değerdeyse `corrected`: cevap düzeltilir, görüşmedeki değer
    `original` olarak saklanır;
  - görüşmede cevap kaydedilmediyse (`missed_in_call`) ya da başka sorular bu cevaba göre atlanıyorsa
    (`affects_branching`) düzeltme yapılmaz, `review`;
  - kontroller farklı duyduysa (`checks_disagree`) ya da kayıtta konuşma yoksa (`no_recording`) `review`;
  - açık uçlu cevaplar karşılaştırılmaz (`not_checked`), metinler insan için saklanır.
- Panel ekranı Aşama 2'de.

**B8. Testler.**
- `LiveKitVoiceExecutionProviderTest`: ElevenLabs sağlayıcı testleri şablon alınır.
- Bildirim ve eşleyici testleri.
- Görüşme aracı controller'ında sağlayıcı ve gizli anahtar testleri.
- Motor testleri: seslendirilebilir metinler, seçenek okuma, sesten cinsiyet.
- `CallPipelinePostgresIntegrationTest`'e V18 kısıt kontrolü.
- Mevcut 65 motor testi ve tüm test paketi yeşil kalmalı.

## 5. Ajan işleri (`voice-agent/`)

**A1. Backend istemcisi.** `SurveyFlow` sınıfı kaldırılır; yerine görüşme araçlarını çağıran ince bir istemci gelir.
- Arama açılınca `/start` çağrılır.
- Her kullanıcı turunda `/answer` çağrılır (`utteranceText`, `signal`, `questionId`).
- Dönen `prompt` seslendirilir.
- `endCall` gelince kapanış kesilmeden söylenir ve hat kapatılır.
- Ardından `/finish` çağrılır.
- Backend yanıt vermezse ajan "Bir saniye lütfen" gibi bir dolgu cümlesi söyler, bir kez daha dener, olmazsa
  kibarca kapatır.

**A2. Sinyaller.** Prototipteki ifade listeleri sinyallere çevrilir:
- "tekrar eder misiniz" → `REPEAT_REQUEST`
- "kimsiniz" → `IDENTITY_REQUEST`
- "neden soruyorsunuz" → `REASON_REQUEST`
- "kapatıyorum, meşgulüm, aramayın" → `STOP_REQUEST`

Duraksamalar ve görüşme bittikten sonraki sözler backend'e hiç gönderilmez. "Alo?" ve bölünen cümleler ajanda
kalır.

**A3. Durum bildirimleri.** Ajan çalma, açılma, bitiş, meşgul ve cevapsız olaylarını HMAC imzalı olarak
`/api/v1/provider-webhooks/LIVEKIT` adresine gönderir. Dökümü de bitişte ekler.
- Biçim `LiveKitVoiceExecutionProvider` sınıfının başındaki açıklamada:
  - `event`: `ringing`, `answered`, `ended` ya da `failed`;
  - `room` ve `callAttemptId`;
  - aranan açmadıysa `sipStatusCode`;
  - bitişte `transcript`.
- İmza başlığı `X-SurveyAI-Signature: t=<unix saniye>,v1=<"t.gövde" metninin HMAC-SHA256 hex değeri>`.
- Ajan önce `/finish` çağırır, sonra `ended` gönderir, en son odayı kapatır. Oda olay gelmeden kapanırsa backend
  aramayı 60 saniye sonra `FAILED` sayar.
- Aynı cümlenin 3 saniye içinde ikinci kez gelmesi yinelenen istek sayılır. Bu yüzden `/answer` isteklerinde
  `questionId` gönderilir.

**A4. Ses tarafı.** Değişmeden kalır:
- Linden-1 konuşma tanıma,
- Gökçe Deniz sesi ve önbellek,
- ses dengeleyici, ofis sesi,
- cinsiyet modeli, kayıt.

Önbellek metin anahtarlı olduğu için backend metinleriyle de çalışır. İlk aramada üretilir, sonra hazırdır.

**A5. Ayarlar.** Anahtarlar masaüstü dosyasından değil ortam değişkenlerinden okunur. `call.py` ve `setup_trunk.py`
geliştirme araçları olarak kalır.

**A6. Barındırma.**
- Ajan için bir `Dockerfile` yazılır; cinsiyet modeli imaja gömülür.
- Ajan ve `verify_watch.py` aynı servis içinde çalışır; ayrı süreçler olarak.
- Railway'de backend ile aynı projede, AB bölgesinde (Amsterdam) ayrı bir servis olur (bkz. Karar 1).

## 6. Sıra ve geçiş

| Adım | İçerik | Canlıya etkisi |
|---|---|---|
| 1 | B1, B3, B5, B8'in ilgili kısmı | Yok; ElevenLabs aynen çalışır. B5'teki metin düzeltmeleri ElevenLabs'e de iyi gelir. |
| 2 | B2, B4 + testler | Yok; `LIVEKIT` seçilmedikçe kullanılmaz. |
| 3 | A1, A2, A3; geliştirme makinesinden yerel backend'e karşı uçtan uca deneme | Yok. |
| 4 | A5, A6; Railway'de ajan servisi | Yok. |
| 5 | B6 ve B7 | Yok. |
| 6 | Test operasyonu (kendi numaran, 1-2 kişi) ile canlıda deneme: `SURVEYAI_CALLING_ACTIVE_PROVIDER=LIVEKIT` | Etkin sağlayıcı değişir; geri dönüş tek değişken. |
| 7 | Prototip görüşmelerinden çıkan dökümlerle motor için tekrar testi (bkz. Risk 3) | Yok. |

Her adım ayrı commit; backend adımları push edildiğinde Railway yeniden dağıtır.

## 7. Kararlar (15 Ekim 2026)

1. **Ajan Railway'de, Amsterdam'da** çalışacak: backend ile aynı projede ayrı bir servis.
2. **Seçenek okuma soru bazında bir ayar** olacak. Tek seçimli ve en fazla 5 seçenekli sorularda varsayılan açık;
   anket hazırlayan kapatabilir.
3. **Cinsiyet sesten belirlenecek.** Bu, tüm anketlerde varsayılan açık bir ayar olacak; gerekirse anket bazında
   kapatılıp soru sorulabilir.
4. **Kontrol bekleyen cevaplar için panel ekranı Aşama 2'de** yapılacak. Bu aşamada işaretler yalnızca veritabanına
   yazılır.

## 8. Riskler ve açık konular

1. **Gecikme:** prototip cevabı kendi içinde ~0,1 sn'de buluyordu. Backend çağrısı (Amsterdam, veritabanı Frankfurt)
   her tura ~0,1–0,3 sn ekleyebilir. Ajanı da Amsterdam'da çalıştırmak bunu küçültür. Uçtan uca denemede ölçülecek.
2. **Cümle bölünmesi:** konuşma tanıma bazen kişi konuşurken turu bitiriyor. Ajan tarafındaki birleştirme korunuyor;
   kökten çözüm ayrıca denenecek.
3. **Motor ile prototip kuralları:** prototipte deneyle bulunan bazı durumlar backend motorunda farklı ele
   alınıyor:
   - "belli bir" → 51,
   - imkânsız yaşlar,
   - iki aday söylenince "hangisi?" diye sorma,
   - kararsız + eğilim,
   - listede olmayan ismi teyit etme.

   Test görüşmelerinin dökümleri motor testlerine dönüştürülecek. Motorda eksik kalanlar ayrı commit'lerle,
   testleriyle eklenecek.
4. **Eşzamanlı arama:** LiveKit ücretsiz paketinde aynı anda en fazla 5 ajan oturumu var. Pilot için yeterli;
   üretimde ücretli pakete geçmek gerekir (Aşama 2).
5. **Ses kayıtları:** kontrol için konuşmacı kaydı ajan servisinin diskinde geçici tutulur. Kalıcı saklama, süre ve
   rıza Aşama 2'de çözülmeden gerçek katılımcılarda kayıt açılmamalı.

## 9. Süre tahmini

- Backend (B1–B8): 5–7 iş günü
- Ajan (A1–A6): 3–4 iş günü
- Uçtan uca deneme ve düzeltmeler: 2 gün

Toplam yaklaşık 2 hafta.
