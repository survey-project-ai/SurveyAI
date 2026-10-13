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
.venv/Scripts/python setup_trunk.py        # Netgsm giden SIP hattını LiveKit'te bir kez tanımlar
.venv/Scripts/python agent.py dev          # agent'ı başlatır (açık kalmalı)
.venv/Scripts/python call.py +905xxxxxxxxx # numarayı arar
```

Her arama `logs/<oda>.jsonl` dosyasına tur tur kaydedilir.

## Notlar

- Ses: Gözde Arıkan (ElevenLabs kütüphane sesi). API ile kullanmak için ElevenLabs hesabında kredi gerekir.
- Konuşma tanıma: ElevenLabs Scribe v2 realtime, 0,5 sn sessizlikte cümleyi kesinleştirir (server VAD).
- `common.py` isim çözümlemeyi IPv4'e zorlar: geliştirme makinesinde IPv6 DNS sunucuları cevap vermediği
  için her sorgu 12 sn bekliyordu.
