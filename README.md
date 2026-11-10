# Hoppy VTuber (Android)

Přední kamera → MediaPipe Face Landmarker → hlava, oči a pusa postavy v reálném čase.
Spodní panel: 🙌 Ruce, 👋 Mávat, 😊 Radost, 😳 Blush, 🎨 Pozadí (levandule / chroma-green pro OBS / tmavé / bílé), 🎯 Kalibrovat a velké tlačítko Nahrát video (MP4 do Movies/HoppyVTuber, se zvukem z mikrofonu).
Při nahrávání se schová panel i náhled kamery. Klepnutím na postavu panel zase ukážeš.

## 🫧 Bublina – Hoppy nad hrou
Tlačítko **🫧 Bublina** pošle postavu do malého plovoucího okna nad ostatní aplikace (hry, YouTube…).
Kamera dál sleduje obličej (běží jako služba s notifikací), pozadí bubliny je průhledné.
- Poprvé Android požádá o povolení **Zobrazovat přes jiné aplikace**.
- **Táhni** bublinu kamkoli, **klepni** na ni → mini panel: 👋 mávat, 😊 radost, 😳 blush, 🎯 kalibrovat, ⤢ velikost (S/M/L), ✕ zavřít.
- Funguje i na šířku (tracking se otáčí s displejem).
- Návrat do aplikace (nebo klepnutí na notifikaci) bublinu zavře a postava se vrátí do aplikace.
- Poloha, velikost, výrazy i kalibrace se pamatují.
- Chceš to nahrát? Použij systémový záznam obrazovky – bublina bude ve videu nad hrou.

## Jak získat APK
1. Nahraj obsah složky na GitHub (nový repozitář).
2. Záložka **Actions → Build APK → Run workflow**.
3. Stáhni artefakt **HoppyVTuber-apk** → `app-debug.apk` → nainstaluj.
(Nebo Android Studio: Open → Build → Build APK(s).) Model obličeje se stáhne sám při buildu.

## Ladění
- `HoppyAvatar.kt` (nahoře): `MIRROR`, `YAW_SIGN`, `PITCH_SIGN`, `ROLL_SIGN` – když se postava otáčí na opačnou stranu; `MODEL_UNITS` – velikost postavy; `ARM_DEG` – jak vysoko jdou ruce (120° = čisté V).
- `MainActivity.kt`: `CAM_Z` – vzdálenost kamery (větší = postava menší).
- `OverlayService.kt`: `SIZES_DP` – velikosti bubliny, `BUBBLE_CAM` – záběr kamery v bublině.

## Struktura kódu
- `FaceTracker.kt` – přední kamera + MediaPipe → natočení hlavy, mrkání, pusa.
- `HoppyAvatar.kt` – model a jeho pohyb (sdílené aplikací i bublinou).
- `MainActivity.kt` – hlavní obrazovka, tlačítka, nahrávání.
- `OverlayService.kt` – plovoucí bublina nad hrami.
- `ScreenRecordService.kt` – nahrávání videa.

## Postava
`app/src/main/assets/models/hoppy.glb`: odstraněné roblox oči z textury hlavy + nový blush (Face_BlushLeft/Right, skryté přes scale jako ostatní obličejové meshe).
