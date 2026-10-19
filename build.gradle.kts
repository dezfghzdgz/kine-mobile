// Kine Clipper pro Android. Žádné knihovny mimo Android (bez AndroidX) - appka
// používá jen to, co má každý telefon v sobě: MediaProjection (obrazovka),
// Camera2 (kamera), MediaCodec + MediaMuxer (video), AudioRecord (zvuk hry +
// mikrofon), OpenGL ES (skládání kamery do obrazu), HttpURLConnection (Kine).
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
