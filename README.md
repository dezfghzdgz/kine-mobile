# Kine Clipper pro Android

Klipy z her na telefonu - stejná myšlenka jako Kine Clipper do PC: appka drží
posledních N sekund obrazovky (se zvukem hry, mikrofonem a kamerou v obraze),
klepnutím na plovoucí bublinu z nich udělá klip do Galerie a klip jde nahrát na
Kine (www.playkine.com) pod stejným účtem a se stejnými pravidly jako z PC.

Jen Android 10 a novější. Bez knihoven - používá jen to, co má každý telefon
v sobě (MediaProjection, Camera2, MediaCodec, AudioRecord, OpenGL ES,
HttpURLConnection). iPhone zatím ne - viz dole.

## Jak to funguje

- **Obrazovka**: `MediaProjection` -> `VirtualDisplay` kreslí do textury OpenGL.
- **Kamera** (přední / zadní, `Camera2`): druhá textura; `GlCompositor` ji
  skládá do rohu obrazu (velikost, roh, kruh / obdélník, zrcadlo, otočení) a
  výsledek kreslí do vstupního povrchu kodéru H.264 (`VideoEncoder`).
- **Zvuk**: zvuk hry přes `AudioPlaybackCapture` (co telefon hraje - hra to
  může zakázat, pak je ticho) + mikrofon, smíchané do jedné stopy AAC
  (`AudioCapture`, `AudioEncoder`).
- **Zásobník**: kodéry sypou do `SegmentWriter`, který každé 4 s (na klíčovém
  snímku) vymění soubor MP4; starší než délka klipu + rezerva se mažou.
- **Klip**: `ClipMaker` slepí hotové kousky bez překódování (MediaExtractor ->
  MediaMuxer), usekne začátek na klíčovém snímku a uloží do Galerie
  (MediaStore, Movies/Kine). Knihovna appky (`Library`) si pamatuje odkaz,
  délku a stav nahrání.
- **Kine**: přihlášení přes web (`/connect?app=mobile` -> `kine://link`,
  jednorázový token -> vlastní relace Supabase), nahrání jako z PC (lístek
  `/api/videos/create-upload-url`, tus do Cloudflare Stream, `/api/videos/confirm`),
  hlavička `X-Kine-App: android/<verze>` (web ji zná, `ANDROID_MIN_VERSION`
  ve Vercelu může starou verzi odmítnout).
- **Služby**: `CaptureService` (popředí: mediaProjection | camera | microphone),
  `UploadService` (popředí: dataSync). Bublina = okno přes jiné aplikace.

## Sestavení

GitHub Actions (`.github/workflows/build.yml`) postaví při každém pushi
testovací `app-debug.apk` (artefakt běhu) a obnoví „plovoucí“ vydání
**android-latest**: `Kine-Clipper.apk` (podepsané, když je klíč; jinak testovací
sestavení pod stejným názvem), `Kine-Clipper-debug.apk` a `latest.json` (verze,
datum, velikost). Na ten soubor odkazuje web Kine - stránka `/download` (záložka
Android, na telefonu předvybraná) a přesměrování `/download/android`:
`https://github.com/<repo>/releases/download/android-latest/Kine-Clipper.apk`.
Značka `v*` udělá navíc pojmenované vydání. Když jsou v repu stejné R2 proměnné
jako v kine-desktop (`DESKTOP_DOWNLOAD_BASE` + `R2_*` secrets), nahraje se APK i
do úložiště do složky `android/` (pak na Vercelu
`NEXT_PUBLIC_ANDROID_DOWNLOAD_BASE=<base>/android`).
Ostré podepsané APK vznikne, když jsou v Settings -> Secrets:
`KEYSTORE_BASE64` (keystore zakódovaný base64), `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`. Klíč se dělá jednou:

    keytool -genkeypair -v -keystore kine.keystore -alias kine -keyalg RSA -keysize 2048 -validity 10000
    base64 -w0 kine.keystore   # tohle do KEYSTORE_BASE64

Lokálně: Android Studio nebo `gradle :app:assembleDebug` (Gradle 8.11, JDK 17,
Android SDK 35).

## Test na telefonu

1. Na telefonu otevři playkine.com/download (záložka Android) a stáhni
   `Kine-Clipper.apk` - nebo `app-debug.apk` z běhu Actions (Artifacts). Nainstaluj
   (povolit „instalovat neznámé aplikace“ pro prohlížeč / správce souborů).
2. Otevři Kine Clipper -> Přihlásit přes Kine (prohlížeč, jedno klepnutí).
3. Zapni kameru (přední), bublinu, Spustit -> povolit nahrávání obrazovky.
   Android 14+: v okně systému zvol **Jedna aplikace** (hra) - bublina ani
   oznámení pak v klipu nejsou vůbec; kameru skládá appka sama, ta zůstává.
4. Přepni do hry, hraj, klepni na bublinu -> klip je v Galerii (Movies/Kine)
   a v appce; Nahrát -> na Kine.
5. Když je kamera na boku: Otočení kamery (0 / 90 / 180 / 270).

### Tlačítko, které v klipu není vidět (0.2.0)

- **Nenápadná bublina** (výchozí): malá tečka, po 1,5 s klidu zprůhlední na čtvrtinu
  a po dotyku se zase rozsvítí - v klipu je sotva vidět, prst ji trefí (plocha 56 dp).
  „Plné tlačítko“ je v nastavení.
- **Zatřesení = klip** (nastavení, výchozí vypnuto): dvě rázná trhnutí telefonem
  během 0,7 s uloží klip; na obrazovce není nic, v klipu není nic. Akcelerometr
  běží jen při snímání.
- **Jen hra** (Android 14+): systém nahrává jen jednu aplikaci, překryvy jiných
  appek (bublina, oznámení) v záznamu nejsou. Starší Android umí jen celou
  obrazovku - tam pomůže nenápadná bublina nebo zatřesení.

### Nenáročnost (0.2.0)

Výchozí 720p / 30 fps / 5 Mb/s (1080p a 60 fps jdou zapnout), kamera nejvýš 480p
(v obraze je malá), obraz se skládá jen když se něco změní (nejvýš fps za sekundu)
a kodéry jsou hardwarové. Klip = překopírování posledních sekund (bez překódování).

## Co zatím chybí / omezení

- Otočení telefonu během nahrávání: obraz se nahrává v orientaci, ve které
  se spustilo (otočený obsah se do něj zmenší). Spusť nahrávání až v hře
  (na šířku), nebo zastav a spusť znovu.
- Úpravy klipu (zkrácení, text, překryvy) - zatím jen na PC / webu.
- iPhone: nahrávání jiné appky jde jen přes systémové „Nahrávání obrazovky“
  s rozšířením (ReplayKit), bez kamery přes hru a bez bubliny - jiný projekt.
