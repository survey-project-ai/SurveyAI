# Ses altyapısı prototipi: ElevenLabs'e karşı AB'de kendi hattımız

Durum: plan (Ekim 2026). Karar, Aşama 0'ın ölçüm sonuçlarına göre verilecek.

## 1. Neyi karar vermeye çalışıyoruz

Telefon anketlerinde her cevaptan sonraki bekleme süresini en aza indirmek istiyoruz. Aramaların büyük kısmı Türkiye'de, bir kısmı ileride Avrupa'da olacak. Soru şu: ElevenLabs Agents'ta mı kalalım, yoksa Avrupa'da (Frankfurt/Amsterdam) çalışan, akışı tamamen bizim kontrol ettiğimiz bir ses hattına mı geçelim?

Bu karar varsayımla değil, aynı anket ve aynı ses ile yapılan gerçek aramaların ölçümüyle verilecek.

## 2. Bugünkü durum (ElevenLabs, ilk deneme araması, 13 Ekim 2026)

Arayan kişi sustuktan sonra agent'ın konuşmaya başlaması **1,2–1,7 sn** sürdü. ElevenLabs'in görüşme metriklerinden dağılım:

| Adım | Süre |
|---|---|
| Dil modeli: araç çağırmaya karar verme (1. tur) | 0,3–0,4 sn |
| Backend aracı `/answer` (ElevenLabs → Railway Amsterdam → Neon Frankfurt) | ~0,32 sn |
| Dil modeli: konuşulacak cümleyi üretme (2. tur) | 0,3–0,5 sn |
| Ses üretimi (ElevenLabs Flash v2.5) | ~0,1 sn |
| Ölçülemeyen kısım (telefon hattı, ses trafiği, ağ) | 0,6–0,8 sn |

Gözlemler:
- Dil modeli her turda iki kez çalışıyor. Hazır platformlarda (ElevenLabs, Vapi) bu yapı değişmiyor.
- Bizim mimaride ne söyleneceğine backend karar veriyor; `/answer` yanıtındaki `prompt` zaten konuşulacak metin. İkinci model turu aslında bu metni tekrar ürettiriyor.
- ElevenLabs Agents verisini ve işlemesini varsayılan olarak ABD'de tutuyor. AB bölgesi yalnızca Enterprise planda var ve telefon trafiğinin nerede işlendiği belgelenmemiş.

## 3. Hedef mimari

```
Netgsm SIP (+90 322 911 03 47)
        │  SIP/RTP
        ▼
LiveKit (AB bölgesi; prototipte LiveKit Cloud)
        │  WebRTC
        ▼
Ses agent'ı (Python, LiveKit Agents, AB)
   ├─ Konuşma tanıma (STT): Soniox veya Deepgram, Türkçe, akışlı
   ├─ Sinyal sınıflandırma: yalnızca gerektiğinde küçük bir LLM çağrısı
   ├─ SurveyAI backend: /start, /answer, /finish  (Railway, Amsterdam)
   └─ Ses üretimi (TTS): ElevenLabs Flash v2.5, aynı ses (Gözde)
```

Temel fikir: backend'in döndürdüğü `prompt` doğrudan sese çevrilir. Dil modelinin ikinci turu kalkar. Birinci tur da her cevapta çalışmak zorunda değil:
- Cevap metni backend'e `signal=ANSWER` ile gider; backend zaten konuşma dilindeki cevapları yorumluyor (sayılar, olumsuzluk, seçenek eşleştirme, özel cevaplar).
- Yalnızca "tekrar eder misiniz", "kimsiniz", "neden soruyorsunuz", "istemiyorum" gibi meta cümleler için küçük bir sınıflandırıcı gerekiyor. Bunu kural tabanlı bir ön filtre ve gerektiğinde tek bir hızlı LLM çağrısı (JSON çıktılı) ile yaparız. Bu çağrı STT tamamlanırken paralel başlatılabilir.

Beklenen kazanç (doğrulanacak): tur başına 0,3–0,5 sn (ikinci model turu) + AB içi ağ kazancı. Hedef: medyan **≤ 0,9 sn**.

## 4. Bileşen seçimleri

| Parça | Prototip seçimi | Alternatif / not |
|---|---|---|
| Medya ve SIP | LiveKit Cloud, AB bölgesine sabitlenmiş SIP | İleride Frankfurt'ta kendi LiveKit sunucumuz (VM gerekir; Railway UDP girişini desteklemiyor, doğrulanacak) |
| Agent çalıştırma | LiveKit Agents (Python) | Railway Amsterdam'da ya da LiveKit Cloud'un agent barındırmasında. Railway'den giden WebRTC trafiği doğrulanacak. |
| STT | Soniox ve Deepgram, ikisi de denenecek | Türkçe, 8 kHz telefon sesi, sayılar ve özel adlar üzerinde karşılaştırma |
| TTS | ElevenLabs Flash v2.5, Gözde sesi | Adil karşılaştırma için aynı ses. Alternatif: Cartesia Sonic (Türkçe destekli). |
| Sinyal sınıflandırma | Kural ön filtresi + `claude-haiku-5-5` veya `gemini-2.5-flash-lite`, JSON çıktı | Yalnızca meta cümlelerde çağrılır |
| Konuşma sırası (turn) | Silero VAD + LiveKit turn detector | Türkçe desteği doğrulanacak; yoksa VAD + bitiş gecikmesi ayarı |

## 5. Aşamalar

### Aşama 0: Gecikme denemesi (backend'e dokunmadan), tahmini 2–3 gün

Amaç yalnızca ölçmek.
1. LiveKit Cloud hesabı (AB), Netgsm için giden SIP trunk: `sip.netgsm.com.tr`, TCP, kullanıcı adı ve şifre.
2. Repoda `voice-agent/` klasöründe küçük bir Python agent'ı:
   - Aramayı açar, "Alo"yu bekler.
   - Sabit 3 soruluk bir anketi (CHP memnuniyet test anketinin aynısı) sorar.
   - Her turda zaman damgalarını JSON olarak loglar.
3. Aynı test senaryosu ElevenLabs ile de oynanır; ElevenLabs tarafındaki değerler görüşme metriklerinden (`convai_ttf_audio_since_silence`) alınır.
4. STT'yi Soniox ve Deepgram ile ayrı ayrı çalıştırırız.

Başarı ölçütleri (aynı senaryo, en az 20 tur):
- Kişi sustuktan sonra ilk sese kadar geçen süre: medyan ≤ 0,9 sn, p95 ≤ 1,5 sn (ElevenLabs: 1,2–1,7 sn).
- Türkçe tanıma: test cümlelerindeki sayılar, parti adları ve ilçe adları ElevenLabs kadar ya da daha doğru tanınıyor.
- Ses kalitesi: aynı ses; dinleyen iki kişi ElevenLabs'ten daha kötü bulmuyor.

Ölçütler tutmazsa ElevenLabs'te kalırız ve Aşama 1'e geçmeyiz.

### Aşama 0 sonuçları (13 Ekim 2026)

Prototip (`voice-agent/`): LiveKit Cloud "Germany 2" bölgesi, Netgsm giden SIP hattı, ElevenLabs Scribe v2 realtime (0,5 sn sessizlikte kesinleştirme), ElevenLabs Flash v2.5 ve Gözde sesi (ElevenLabs agent'ıyla aynı ses ve ayarlar), dil modeli yok. Agent geliştirme makinesinde (Türkiye) çalıştı; backend yerine backend'in sağlık uç noktası çağrıldı.

| Tur | Konuşmanın bitişinden agent sesine |
|---|---|
| "Sesim geliyor mu?" | 0,91 sn |
| "Tabii, buyurun." | 0,71 sn |
| "Cinsiyetim erkek." | 0,80 sn |
| "Eee, 27 yaşındayım." | 0,72 sn |
| "Bir." | 0,71 sn |

- Medyan 0,72 sn, en kötü 0,91 sn. ElevenLabs agent'ı: 1,2–1,7 sn. Başarı ölçütü (medyan ≤ 0,9, p95 ≤ 1,5) karşılandı.
- En büyük kalem konuşma tanımanın 0,5 sn'lik sessizlik eşiği (kesinleştirme ~0,48 sn). Kalanı: backend gidiş-dönüşü ~0,07 sn, TTS ilk ses ~0,15 sn.
- Gerçek `/answer` veritabanı sorguları nedeniyle ~0,1–0,2 sn ekleyecek; agent AB'de çalışınca Türkiye–Frankfurt gidiş-dönüşü düşecek.
- Türkçe tanıma iyi; bir hata: "Sesim" → "Resim".
- Karşılaşılan sorunlar ve çözümleri: Scribe realtime yalnızca commit'te kesin sonuç veriyor (server VAD açıldı); ücretsiz ElevenLabs planı kütüphane seslerini API'de kullandırmıyor (kredi yüklendi); Scribe sessizlikten sahte "Evet." üretebiliyor (önceki cevap çalınmadan gelen kısa sesler yok sayılıyor).
- Cinsiyet sesten tahmin ediliyor: Apache-2.0 lisanslı wav2vec2 tabanlı model (int8 ONNX, 95 MB). Telefon kalitesine indirilmiş 21 seste 20 doğru, 1 "emin değil" (güven < 0,85 ise soru sorulur). Gerçek aramada erkek sesi 1,28 sn konuşmadan 0,999 güvenle bulundu, 0,2 sn sürdü. audeering modelleri ticari kullanıma kapalı (CC-BY-NC-SA) olduğu için elendi; ses perdesi yöntemi telefon bandında başarısız (21'de 8).
- Ses kalitesi: eklenti varsayılanı olan 32 kbps MP3 ve cümle cümle üretim yerine PCM ve tek parça üretim. TTS ilk ses 0,15 → ~0,2 sn; son aramada turlar 0,74–0,99 sn.
- Henüz denenmedi: "neden soruyorsunuz", "kimsiniz", sessizlik senaryoları; v4 Turbo ses modeli; daha düşük sessizlik eşiği.

### Aşama 1: Backend entegrasyonu, tahmini 1–2 hafta

- `CallProvider.LIVEKIT` ve migration V18. `call_attempt.provider` CHECK kısıtı şu an `ELEVENLABS, MOCK, TWILIO, SIP, MANUAL` değerlerini kabul ediyor.
- `LiveKitVoiceExecutionProvider`, mevcut `VoiceExecutionProvider` arayüzünü uygular:
  - `dispatchCallJob`: LiveKit'e agent görevi gönderir. Metadata: `call_attempt_id`, telefon numarası.
  - `fetchCallStatus`, `cancelCall`, `validateConfiguration`.
  - `verifyWebhook` ve `parseWebhook`: agent'ın arama sonunda gönderdiği sonuç (durum, süre, transkript).
- Görüşme araçlarının uç noktaları sağlayıcıdan bağımsız hale getirilir. Şu an `/api/v1/provider-tools/elevenlabs/interviews/*` yalnızca ElevenLabs aramalarını kabul ediyor (`validateProvider`) ve gizli anahtarı ElevenLabs ayarından okuyor.
- Agent, backend'in `/start`, `/answer` ve `/finish` uçlarını çağırır; görüşme motoru aynen kullanılır.
- `SURVEYAI_CALLING_ACTIVE_PROVIDER=LIVEKIT` ile geçiş yapılır. ElevenLabs yedek sağlayıcı olarak kalır.

### Aşama 2: Üretime hazırlık, tahmini 2–3 hafta

- Sesli mesaj ve telesekreter algılama.
- Arama kaydı ve saklama süresi (KVKK).
- Kişinin agent'ın sözünü kesmesine (barge-in) ve sessizlik davranışına ince ayar.
- Netgsm eşzamanlı kanal sınırı ve kuyruk eşzamanlılık ayarı.
- Tur başına gecikme metriklerinin izlenmesi, yük testi, dakika başı maliyet hesabı.
- Hacim büyürse Frankfurt'ta kendi LiveKit sunucumuz.

## 6. Ölçüm yöntemi

Her tur için agent şu zaman damgalarını loglar: kişinin konuşmasının bitişi (VAD/STT final), STT sonucu, backend isteğinin başlangıcı ve bitişi, TTS ilk ses baytı, sesin hatta çıkışı.

Test senaryosu sabit:
- "Alo" ile açılış.
- Sayı ("on beş", "bin dokuz yüz seksen beş").
- Olumsuzluk ("tabii ki hayır").
- Parti adı ve ilçe adı.
- "Neden soruyorsunuz?" itirazı.
- Bir soruda sessiz kalma.
- "Kimsiniz?" sorusu.

Her yapılandırma için en az 20 tur; medyan ve p95 raporlanır.

## 7. Riskler ve açık sorular

- Railway'den giden WebRTC/UDP trafiği: agent'ı Railway'de çalıştırabilir miyiz, yoksa LiveKit Cloud agent barındırması mı gerekir?
- LiveKit turn detector'ün Türkçe desteği.
- Netgsm SIP: kod çözücü (PCMA/PCMU), TCP taşıma, eşzamanlı kanal sayısı. Aynı hattın ElevenLabs ile paralel kullanımı.
- KVKK: ses ve transkriptin AB'de işlenmesi yurt dışına veri aktarımıdır; hukuki değerlendirme gerekir.
- Bakım yükü: hazır platformun çözdüğü işleri (kesme, sessizlik, sesli mesaj) biz yöneteceğiz.
- Maliyet: LiveKit, STT, TTS ve LLM ayrı ayrı ücretleniyor; ElevenLabs'in dakika ücretiyle karşılaştırılacak.

## 8. Başlamak için gerekenler

- LiveKit Cloud hesabı (AB bölgesi) ve API anahtarı.
- Soniox ve Deepgram API anahtarları (ikisinin de deneme kredisi var).
- Netgsm SIP bilgileri (mevcut) ve ElevenLabs API anahtarı (mevcut, TTS için).
- Test için bir ya da iki telefon numarası ve dinleyip puanlayacak iki kişi.
