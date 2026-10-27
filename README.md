# Kine Clipper pro Android

Klipy z her na telefonu - stejná myšlenka jako Kine Clipper do PC: appka drží
posledních N sekund obrazovky (se zvukem hry, mikrofonem a kamerou v obraze),
klepnutím na plovoucí bublinu z nich udělá klip do Galerie a klip jde nahrát na
Kine (www.playkine.com) pod stejným účtem a se stejnými pravidly jako z PC.

Jen Android 10 a novější. Bez knihoven - používá jen to, co má každý telefon
v sobě (MediaProjection, Camera2, MediaCodec, AudioRecord, OpenGL ES,
HttpURLConnection). iPhone zatím ne - viz dole.

## Jak to funguje

- **Obrazovka**: `MediaProjection` -> `VirtualDisplay`. Bez kamery kreslí rovnou do
  vstupního povrchu kodéru H.264 (`VideoEncoder`) - žádné OpenGL, nejmíň práce.
- **Kamera** (přední / zadní, `Camera2`): jen když je zapnutá - `GlCompositor`
  (OpenGL ES 2) složí obrazovku a kameru (velikost, roh, kruh / obdélník, zrcadlo,
  otočení) do kodéru. Když OpenGL na telefonu nejde, nahrává se bez kamery.
- **Kodér** (`Device.videoPlans`): rozměr, snímky a datový tok podle toho, co kodér
  opravdu umí (meze, zarovnání 16 px, výkon), hardwarový napřed; strop snímků
  (displej 120 Hz nekóduje 120 snímků), opakování snímku při klidu, bez B-snímků.
- **Zvuk**: zvuk hry přes `AudioPlaybackCapture` (co telefon hraje - hra to
  může zakázat, pak je ticho) + mikrofon, smíchané do jedné stopy AAC
  (`AudioCapture`, `AudioEncoder`); 48 kHz, starší mikrofony 44,1 kHz.
- **Zásobník**: kodéry sypou do `SegmentWriter`, který každé 4 s (na klíčovém
  snímku) vymění soubor MP4; starší než délka klipu + rezerva se mažou.
- **Klip**: `ClipMaker` slepí hotové kousky bez překódování (MediaExtractor ->
  MediaMuxer, časy přepočtené na společné hodiny), usekne začátek na klíčovém
  snímku a uloží do Galerie (MediaStore, Movies/Kine). Knihovna appky (`Library`)
  si pamatuje odkaz, délku a stav nahrání.
- **Otočení**: po otočení telefonu (hra na šířku) se kodér přenastaví na nový
  rozměr (`VirtualDisplay.resize`), klip se lepí jen z kousků se stejným rozměrem.
- **Kine**: přihlášení přes web (`/connect?app=mobile` -> `kine://link`,
  jednorázový token -> vlastní relace Supabase), nahrání jako z PC (lístek
  `/api/videos/create-upload-url`, tus do Cloudflare Stream, `/api/videos/confirm`),
  hlavička `X-Kine-App: android/<verze>` (web ji zná, `ANDROID_MIN_VERSION`
  ve Vercelu může starou verzi odmítnout).
- **Služby**: `CaptureService` (popředí: mediaProjection | camera | microphone;
  veškerá těžká práce na vlákně `kine-pipe`), `UploadService` (popředí: dataSync).
  Bublina = okno přes jiné aplikace (`Bubble`).

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
2. Záložka **Nahrávání** -> **Spustit** -> povolit mikrofon / oznámení, „Zobrazení
   přes jiné aplikace“ (bublina) a nahrávání obrazovky. Android 14+: v okně systému
   zvol **Jedna aplikace** -> hra - bublina ani oznámení pak v klipu nejsou vůbec.
3. Bublina se ukáže hned (oranžově bliká, dokud se nahrávání nerozjede, pak je
   tyrkysová). Přepni do hry, klepni na bublinu -> zabliká zeleně = klip je
   v Galerii (Movies/Kine) a v záložce **Klipy** (přehrát, nahrát na Kine,
   sdílet, smazat).
4. Když něco nejde, appka to napíše v kartě stavu (a tlačítko, které to spraví);
   **Poslat deník** (Nastavení -> Appka) pošle, co se dělo.

### Bublina (0.3.0)

- **Tlačítko** (výchozí): kulaté 52 dp, dobře vidět. Tažení = posun, po puštění
  přiskočí k okraji (pozice se pamatuje, i po otočení), podržení = otevřít appku.
  Barvy: oranžová = spouští se / ukládá, tyrkysová = připraveno, zelená = uloženo,
  červená = nepovedlo se.
- **Proužek u okraje**: tenká čárka u okraje obrazovky (plocha na dotyk je větší,
  ale průhledná) - v klipu skoro není vidět.
- **Zatřesení = klip** (výchozí vypnuto): dvě rázná trhnutí během 0,7 s.
- **Jedna aplikace** (Android 14+): překryvy (bublina, oznámení) v záznamu nejsou.

### Starší telefony (0.3.0)

- Třída telefonu (`Device.tier`): málo paměti (≤ 3 GB), Android Go nebo ≤ 4 jádra
  = „starší“ -> kvalita **Auto** dá 480p / 30 fps / ~2 Mb/s, kamera 360p / 24 fps,
  zvuk 96 kb/s. Ostatní 720p / 30 fps / ~4 Mb/s. Ručně 480p / 720p / 1080p, 30 / 60 fps.
- Hlavní vlákno nikdy nečeká (start, otočení, stop, deník na disk - vše jinde);
  start i stop hlídá časovač, appka nikdy nezůstane viset na „Spouští se…“.
- Spadlý kodér (systém si ho vezme pro jinou appku) se obnoví; na horký telefon
  appka upozorní (viz Teplo), kvalitu sama nemění.
- Seznam klipů recykluje řádky a náhledy načítá na pozadí (max. ~6 MB v paměti).

### Méně tepla (0.4.0)

- **Starší telefon** (`Device.older`: Android 11 a starší, nebo bez výkonnostní třídy
  médií a s pamětí „4 GB“ a míň) dostane v Auto **540p** místo 720p; telefon s 8 GB,
  ale starý (Android 11), už nedostane nastavení „nového“.
- **Šetrný režim** (Nastavení -> Klip, jen když si ho kdo zapne - od 0.4.1 ani na
  slabém telefonu sám ne): 480p / 24 fps / ~1,2 Mb/s, kamera 15 fps, zvuk 96 kb/s.
- Kodér jede tempem snímků (`KEY_OPERATING_RATE`), ne naplno.

### Teplo: jen upozornění (0.4.1)

Kvalitu si každý nastaví sám - appka ji při horku sama nemění (lepší telefon by
zbytečně přišel o kvalitu), jen upozorní:

- **Před startem** (`SizeMath.loadLevel`): zátěž = pixely × snímky (+25 % s kamerou)
  proti tomu, co telefon zvládá bez většího hřátí (slabý 480p30, starší 540p30,
  běžný 720p60, rychlý 1080p60). Od 1,3× „může víc hřát“, od 1,75× „asi se bude
  přehřívat a může běžet na méně snímků/s“ - pod kvalitou v Nastavení a na hlavní
  obrazovce (tlačítko OK ho pro tohle nastavení schová). Pod kvalitou je i to, co
  kodér telefonu opravdu nezvládne (např. „v 1080p jen 30 snímků/s“, „nejvýš 720p“,
  „jen softwarový kodér“) - ověřené přes `Device.videoPlans`.
- **Během hraní** (`Heat.warning`): stav teploty od systému (Android 10+), jeho
  předpověď na 30 s (`getThermalHeadroom`, Android 11+) a teplota baterie každých
  20 s. Teplý telefon (baterie od 40 °C - při hraní běžné) se neohlašuje; horký (od
  43 °C / SEVERE, nebo předpověď ≥ 0,95) = „hra i klip můžou zpomalit“; velmi horký
  (od 46 °C / CRITICAL) = „Android může nahrávání zpomalit nebo zastavit“. Text je
  v appce i v oznámení (celý po rozbalení), na bublině oranžová / červená tečka a přes
  hru jednou krátká hláška (znovu nejdřív po 5 min, nebo když je hůř; při nahrávání
  celé obrazovky je ~3 s vidět i v klipu, s „Jedna aplikace“ ne).
- **Při zahřátí snížit kvalitu** (Nastavení -> Klip, výchozí vypnuto): kdo chce, ať
  appka šetří sama - teplý = datový tok 75 %; horký = o stupeň menší obraz
  (720 -> 540), 24 fps, kamera pauza; velmi horký = ještě menší, 20 fps, tok 60 %.
  Datový tok a kamera se vrátí po minutě klidu a o 1,5 °C níž, po stupních; menší
  obraz zůstane do konce snímání (přenastavení kodéru začíná zásobník znovu) - do té
  doby appka píše „Kvůli horku Kine ubralo kvalitu“.
- Snímky/s podle volby i na slabém telefonu (dřív Auto na slabém telefonu tiše
  stáhlo 60 na 30) - jen upozorní. Čistý výpočet `Heat` / `SizeMath` jde ověřit mimo
  telefon.

### Nahrání na Kine (0.4.2)

- **Oprava „klip je u Cloudflare, ale na Kine není“**: po nahrání se appka ptala na stav
  videa s id od Cloudflare místo id na Kine - Kine video nenašla, nikdo ho nepřepnul na
  hotové a klip visel jako „zpracovává se“ (na Kine nikde). Teď se ptá s id na Kine
  (`Clip.kineId`, u starších klipů z adresy `/watch/<id>`), po nahrání celé fronty
  (5 s, pak 15 s, nejvýš 10 min) a znovu při otevření appky (až 20 klipů). Web přijme
  i id od Cloudflare, takže to spraví i nainstalovanou 0.4.1.
- **Kdo klip uvidí**: před nahráním okno Veřejné / Jen odběratelé / Soukromé (předvybraná
  poslední volba, platí i pro automatické nahrávání). U klipu je pak „Na Kine · zpracovává
  se…“ a po zpracování „Na Kine · Veřejné“ / „· Soukromé (vidíš jen ty)“ - soukromý klip
  ostatní na Kine neuvidí.
- Kategorie `catGaming` (dřív „gaming“, Kine ji neznala) a hashtag „klip“ jako z PC;
  potvrzené video se při chybě až potom nikdy neruší, uložení na Kine se při výpadku sítě
  zkusí znovu (stejné nahrání vrátí totéž video - žádný duplikát).
- Na Kine smazaný klip = „Na Kine smazané - můžeš ho nahrát znovu“ (automatické nahrávání
  ho samo znovu neposílá); nahrávání přerušené zavřením appky už nevisí na „Nahrává se %“.
- Appka se ptá na klipy nahrané do 30 dní (počítá se od nahrání, `Clip.uploadedAt`).

## Co zatím chybí / omezení

- Klip hned po otočení telefonu obsahuje jen část od otočení (kousky s jiným
  rozměrem se nelepí - bez překódování to nejde).
- Úpravy klipu (zkrácení, text, překryvy) - zatím jen na PC / webu.
- iPhone: nahrávání jiné appky jde jen přes systémové „Nahrávání obrazovky“
  s rozšířením (ReplayKit, limit paměti 50 MB), bez bubliny přes hru - jiný projekt
  (Swift, sestavení na macOS přes GitHub Actions, placený účet Apple Developer).
- Google Play: nové appky musí mířit na Android 16 (`targetSdk 36`, od 31. 8. 2026;
  teď 34), nahrává se balík AAB podepsaný nahrávacím klíčem, v Play Console
  prohlášení k službě na popředí `mediaProjection` (popis + video). Nový osobní
  účet: uzavřený test s 12 testery 14 dní v kuse, pak žádost o produkci.
