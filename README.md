# Oyun Altyazı / Oyun Dublaj (v0.2)

İki uygulama:
- **tv/** (Android TV): HDMI girişini gösterir, üstüne altyazı basar, dublaj sesini çalar ve çalarken oyun sesini kısar.
- **phone/** (Android): Remote Play ekranını yakalar, OCR ile okur, Türkçeye çevirir, Yazgı TTS ile dublaj üretir ve TV'ye yollar. Çeviri ve ses önbelleklidir.

## Derleme
`main` dalına push -> GitHub Actions -> iki APK artifact (tv ve phone).

## Kurulum
1. TV: `adb connect TV_IP:5555` ardından `adb install tv-debug.apk`
2. Telefon: phone APK'yı kur.
3. PC: Yazgı TTS sunucusunu çalıştır (`python -m yazgi_tts.serve --port 8000`), güvenlik duvarında 8000'e izin ver.
4. PS4'ü TV'nin HDMI girişine bağla, TV uygulamasını aç (kod + IP ekranda).
5. Telefonda PS Remote Play'i aç, oyunu başlat (orijinal altyazı açık). Oyun Dublaj'a dönüp alanları doldur, Başlat, yakalama iznini ver, Remote Play'e geç.

## Protokol (telefon -> TV, TCP 8765, satır başına JSON)
hello / subtitle / audio (base64 WAV) / clear / ping
