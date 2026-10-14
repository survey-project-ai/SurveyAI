# Ses altyapısı prototipi (Aşama 0)

`docs/voice-pipeline-prototype-plan.md` içindeki Aşama 0: LiveKit Cloud (AB) üzerinden Netgsm hattıyla
arayan, sabit 3 soruluk bir anketi dil modeli kullanmadan yürüten ve her turun sürelerini kaydeden agent.
Backend'e bağlı değildir; anket akışı `agent.py` içindeki basit kurallarla çalışır.

## Kurulum

```
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt
```

Anahtarlar repo dışındaki bir dosyadan okunur (`SURVEYAI_KEYS_FILE`, varsayılan `~/Desktop/elevenlabs-keys.env`):
`LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `NEW_ELEVENLABS_API_KEY`, `NETGSM_SIP_USERNAME`, `NETGSM_SIP_PASSWORD`.

## Çalıştırma

```
.venv/Scripts/python setup_models.py       # cinsiyet modelini models/ altına indirir (95 MB, Apache-2.0)
.venv/Scripts/python setup_trunk.py        # Netgsm giden SIP hattını LiveKit'te bir kez tanımlar
.venv/Scripts/python agent.py dev          # agent'ı başlatır (açık kalmalı)
.venv/Scripts/python call.py +905xxxxxxxxx # numarayı arar
```

Her arama `logs/<oda>.jsonl` dosyasına tur tur kaydedilir.

## Notlar

- Ses: Duygu Eris (ElevenLabs kütüphane sesi), model `eleven_v4_turbo`. API ile kullanmak için ElevenLabs
  hesabında kredi gerekir. Gözde cümle içinde ses perdesini çok oynatıyordu; dinleme testinde Duygu seçildi.
- Ses yüksekliği: `leveler.py` ajanın sesini telefona gitmeden önce dengeler (v4 Turbo kısık ve dalgalı
  geliyordu). Arkada hafif ofis sesi çalar (`PROTOTYPE_AMBIENT_VOLUME`, 0 kapatır).
- Konuşma tanıma: ElevenLabs Scribe v2 realtime, 0,5 sn sessizlikte cümleyi kesinleştirir (server VAD).
- Cinsiyet hiç sorulmaz: kişinin her konuşma turu ses modeliyle puanlanır, görüşme sonunda konuşma
  süresine göre ağırlıklı ortalama 0,8'in üstündeyse kaydedilir, değilse "belirsiz" (`gender.py`).
- Ajanın gönderdiği ses her arama için `logs/<oda>.agent.wav` dosyasına yazılır.
- Ses: sıkıştırılmamış PCM ve cevap başına tek parça üretim (`auto_mode=False`); eklentinin varsayılanı
  32 kbps MP3 ve cümle cümle üretimdi, ses yüksekliği cümleler arasında dalgalanıyordu.
- DNS: geliştirme makinesinde modemin IPv6 DNS'i cevap vermiyordu. Python tarafı için `common.py` isim
  çözümlemeyi IPv4'e zorlar; LiveKit'in ses bağlantısı (Rust) bundan etkilenmez ve 24 sn gecikip hiç ses
  taşımamıştı. Çözüm Windows'ta Wi-Fi DNS'ini çalışan sunuculara ayarlamaktı.
