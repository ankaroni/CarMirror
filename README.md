# CarMirror

**Android telefonunun ekranını Android Auto üzerinden araç ekranına yansıtır ve araç ekranından dokunarak kontrol etmeni sağlar.**

*English: CarMirror mirrors an Android phone's screen to a car head unit through Android Auto and lets you control the phone by touching the car screen. Sideload only (not on Google Play).*

Arabada duran yedek bir telefonu "AI Box"a çevirmek için yazıldı: YouTube, Spotify, Haritalar, Chrome gibi Android Auto'da normalde olmayan uygulamaları araç ekranından açıp kullanırsın.

> ⚠️ Bu proje **kişisel/deneysel** bir araçtır. Android Auto'nun resmi olarak desteklemediği bir yöntem kullanır. **Sürüş sırasında video izlemek yasa dışı ve tehlikeli olabilir.** Kullanım ve sorumluluk sana aittir.

## Ne işe yarar?

- Araç ekranında kendi uygulama menüsü (varsayılan: YouTube, Spotify, Haritalar, Chrome). Menüyü telefondan değiştirebilirsin.
- Telefon ekranı araç ekranına yansır. Araç ekranına dokunmak telefona dokunmak gibi çalışır.
- Araç ekranında **Menü, Geri, Ana ekran** tuşları.
- Telefonu arabaya bağlayınca **otomatik başlar**. Yansıtma durursa kendini yeniden başlatır.
- Yansıtma sırasında telefon ekranını **karartır** (ısınmayı azaltır), ekranı açık tutar.
- Yüksek çözünürlük (en az 1080p), 60 fps (isteğe bağlı 30 fps ısı tasarrufu).
- Görüntüyü araç ekranına sığdırır. İstersen "doldur" modu açılır (kenarlardan kırpar).

## Nasıl çalışıyor?

Android Auto'da serbest çizim sadece navigasyon uygulamalarına izinlidir. CarMirror bu yüzden **Car App Library** ile *navigasyon uygulaması* olarak kendini tanıtır ve araç ekranının yüzeyini (`Surface`) alır.

1. `MediaProjection` ile telefon ekranı yakalanır, `VirtualDisplay` ile bir doku (texture) üzerine çizilir.
2. OpenGL ile bu görüntü araç ekranının yüzeyine ölçeklenerek çizilir.
3. Araç ekranındaki dokunma ve kaydırma, `AccessibilityService.dispatchGesture` ile telefona dokunuş olarak uygulanır. Geri ve Ana ekran `performGlobalAction` ile yapılır.
4. Arka planda bir servis Android Auto bağlantısını izler ve yansıtmayı kendiliğinden başlatır.

## Gereksinimler

- Android 8.0 (API 26) veya üstü telefon. Android 14/15/17'de test edildi (Samsung).
- Android Auto kurulu ve araç ile çalışıyor.
- Android Auto → geliştirici modu → **Bilinmeyen kaynaklar** açık (Android Auto, Play Store dışından yüklenen uygulamaları buna göre gösterir).
- Telefonda **Erişilebilirlik** izni (dokunma ve onay penceresini otomatik geçmek için).

## Kurulum

```bash
git clone https://github.com/ankaroni/CarMirror.git
cd CarMirror
# Android SDK (platform 35) ve JDK 17+ gerekir. local.properties içine sdk.dir yaz.
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

İsteğe bağlı (erişilebilirlik iznini kendiliğinden açması ve ekran karartma için, `adb` ile bir kerelik):

```bash
adb shell pm grant com.carmirror.app android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.carmirror.app WRITE_SETTINGS allow
```

Sonra telefonda CarMirror'ı aç, izinleri yeşile çevir (uygulama içinde tek dokunuşla ilgili ayara gider).

### Bilgisayarda test (DHU)

Telefonda Android Auto → geliştirici ayarları → "Ana birim sunucusunu başlat". Sonra:

```bash
adb forward tcp:5277 tcp:5277
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```

## Bilinen sınırlamalar

- **Apple CarPlay desteklenmez.** CarPlay, bağlanan cihazın Apple'ın MFi güvenlik çipini taşımasını ister. Android telefon bunu taklit edemez.
- Netflix gibi **DRM korumalı** içerikler siyah görünür.
- Telefon kilit ekranı ve bildirimler de yansır.
- Araç ekranındaki Menü/Geri/Ana ekran tuşlarının boyutunu ve alt çubuğu Android Auto belirler, uygulama değiştiremez.
- Dikey çalışan uygulamalar araç ekranında ortada, iki yanı siyah görünür. "Yatayı zorla" ayarı var ama dikey uygulamaları yan yatırabilir, bu yüzden varsayılan kapalıdır.
- Araç ekranındaki kare hızını Android Auto ve araç belirler (çoğu araçta 30 fps).
- Telefon yalnızca DHU (masaüstü Android Auto test aracı) ile denendi, **gerçek araçta test edilmedi**. Siyah şerit otomatik kırpma özelliği deneyseldir.
- Samsung gibi cihazlarda pil optimizasyonu arka plan servisini kapatabilir; CarMirror için "Pil: sınırsız" öner.

## Proje yapısı

| Dosya | Görev |
|---|---|
| `CarMirrorService.kt` | Car App Library oturumu, araç menüsü, yansıtma ekranı, dokunma/kaydırma |
| `MirrorService.kt` | Ön plan servisi, `MediaProjection` ve `VirtualDisplay` |
| `MirrorRenderer.kt` | OpenGL ile görüntüyü araç yüzeyine çizer, dokunma koordinat dönüşümü |
| `MirrorAccessibilityService.kt` | Dokunma, Geri, Ana ekran, izin onayı, ekran karartma |
| `ConnectionService.kt` | Android Auto bağlantısını izler, otomatik başlatır |
| `MainActivity.kt` | Ayarlar ve izin durumu ekranı |

## Lisans

Henüz bir lisans dosyası eklenmedi.
