# Ses ajanı

LiveKit Cloud (AB) üzerinden Netgsm hattıyla arayan, cevapları dil modeli kullanmadan doğrudan seslendiren ve
her turun sürelerini kaydeden ajan. İki tür arama yürütür:

- **Backend'in başlattığı aramalar** (`docs/voice-phase1-plan.md`): görev bilgisinde `callAttemptId` vardır.
  Ne söyleneceğine backend'deki görüşme motoru karar verir (`backend.py`). Ajan aramanın durumunu imzalı
  olaylarla backend'e bildirir.
- **`call.py` ile başlatılan deneme aramaları**: Aşama 0 prototipinin 3 soruluk anketi (`prototype_flow.py`).

## Kurulum

```
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt
```

Anahtarlar repo dışındaki bir dosyadan okunur (`SURVEYAI_KEYS_FILE`, varsayılan `~/Desktop/elevenlabs-keys.env`):
`LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `NEW_ELEVENLABS_API_KEY`, `NETGSM_SIP_USERNAME`, `NETGSM_SIP_PASSWORD`.

Backend'in başlattığı aramalar için ortam değişkenleri (backend'dekilerle aynı adlar ve değerler):
`SURVEYAI_API_URL` (varsayılan canlı backend), `LIVEKIT_TOOL_API_SECRET`, `LIVEKIT_WEBHOOK_SECRET`,
`LIVEKIT_AGENT_NAME`. Ajan bu adla kaydolur, backend de aramayı bu ada gönderir:
- Railway'deki ajan: `survey-agent` (backend'in varsayılanı).
- Değişken verilmeden başlatılan ajan: `survey-agent-dev`. Geliştirme makinesindeki ajan böylece canlı
  ajanın aramalarını almaz. Yerel backend'le denemede backend'e de `LIVEKIT_AGENT_NAME=survey-agent-dev`
  verilir.

## Railway

Backend ile aynı projede ayrı bir servis: `SurveyAI-VoiceAgent`. Cinsiyet modeli derleme sırasında
imaja indirilir. Ajan `agent.py start` ile çalışır.

Railway yeni servislerde `railway.toml` kabul etmediği için ayarlar servisin kendisindedir:
- kök dizin `/voice-agent`, `Dockerfile`;
- izlenen yol `/voice-agent/**`; `feature/yusuf` dalından dağıtılır;
- bölge europe-west4 (Amsterdam);
- yeniden başlatma "her zaman": LiveKit'e uzun süre ulaşamayan ajan normal çıkış yapar;
- yeniden dağıtımda eski ajan, süren aramalar bitsin diye 300 sn bekletilir.

Ortam değişkenleri: `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `ELEVEN_API_KEY`,
`LIVEKIT_AGENT_NAME=survey-agent`, `SURVEYAI_API_URL`, `LIVEKIT_TOOL_API_SECRET`, `LIVEKIT_WEBHOOK_SECRET`,
`SURVEYAI_FORCE_IPV4=0`.

`logs/` ve `cache/` konteynerin geçici diskinde durur: her dağıtımda sıfırlanır. Ses önbelleği ilk
aramalarda yeniden dolar.

## Çalıştırma

```
.venv/Scripts/python setup_models.py       # cinsiyet modelini models/ altına indirir (95 MB, Apache-2.0)
.venv/Scripts/python setup_trunk.py        # Netgsm giden SIP hattını LiveKit'te bir kez tanımlar
.venv/Scripts/python agent.py dev          # agent'ı başlatır (açık kalmalı)
.venv/Scripts/python verify_watch.py       # biten aramaların cevaplarını kontrol eder (açık kalmalı)
.venv/Scripts/python call.py +905xxxxxxxxx # numarayı prototip anketiyle arar
.venv/Scripts/python -m unittest test_backend  # backend akışının testleri
```

Her arama `logs/` altına yazılır: turlar (`<oda>.jsonl`), iki tarafın sesi (`<oda>.agent.wav`,
`<oda>.callee.wav`) ve kontrol edilecek cevaplar (prototipte `<oda>.answers.jsonl`, backend aramalarında
`<oda>.checks.json`). `verify_watch.py` cevapları kayıttan iki modelle yeniden dinler:
- prototip aramalarında her sorunun son cevabını kendisi belirleyip `<oda>.final.json` dosyasına yazar;
- backend aramalarında metinleri backend'e gönderir. Backend bunları görüşmedeki kurallarla okuyup cevaplara
  işler (doğrulandı, otomatik düzeltme ya da insan kontrolü); sonuçları `<oda>.final.json` dosyasına yazılır.

Railway'de izleyici ajanla aynı konteynerde, ayrı bir süreç olarak çalışır ve durursa yeniden başlatılır.

## Notlar

- Ses: Gökçe Deniz (ElevenLabs kütüphane sesi), model `eleven_v4_turbo`. API ile kullanmak için ElevenLabs
  hesabında kredi gerekir. Gözde cümle içinde ses perdesini çok oynatıyordu; sonra Duygu, 8 sesin
  dinlendiği testten sonra Gökçe Deniz seçildi. Ajanın cümleleri `cache/tts/` altında saklanır; ilk aramada
  telefon çalarken üretilir.
- Ses yüksekliği: `leveler.py` ajanın sesini telefona gitmeden önce dengeler (v4 Turbo kısık ve dalgalı
  geliyordu). Arkada hafif ofis sesi çalar (`PROTOTYPE_AMBIENT_VOLUME`, 0 kapatır).
- Konuşma tanıma: Speechmatics Linden-1 (LiveKit Inference üzerinden). Test aramalarının kayıtlarındaki 15
  önemli cevabın hepsini doğru anladı; önceki ElevenLabs Scribe v2 realtime 13'ünü
  (`PROTOTYPE_STT_MODEL=scribe_v2_realtime` ile geri dönülür).
- `agent_realtime.py`: aynı anket OpenAI'nin konuşan modeliyle (`python call.py <numara> openai/gpt-realtime-2.1-mini`).
  Bir test aramasında turlar 2–7 sn sürdü ve model metne kendi cümlelerini ekledi; karşılaştırma için duruyor.
- Cinsiyet hiç sorulmaz: kişinin her konuşma turu ses modeliyle puanlanır, görüşme sonunda konuşma
  süresine göre ağırlıklı ortalama 0,8'in üstündeyse kaydedilir, değilse "belirsiz" (`gender.py`).
- Ajanın gönderdiği ses her arama için `logs/<oda>.agent.wav` dosyasına yazılır.
- Ses: sıkıştırılmamış PCM ve cevap başına tek parça üretim (`auto_mode=False`); eklentinin varsayılanı
  32 kbps MP3 ve cümle cümle üretimdi, ses yüksekliği cümleler arasında dalgalanıyordu.
- DNS: geliştirme makinesinde modemin IPv6 DNS'i cevap vermiyordu. Python tarafı için `common.py` isim
  çözümlemeyi IPv4'e zorlar; LiveKit'in ses bağlantısı (Rust) bundan etkilenmez ve 24 sn gecikip hiç ses
  taşımamıştı. Çözüm Windows'ta Wi-Fi DNS'ini çalışan sunuculara ayarlamaktı.
