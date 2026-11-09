# Hoppy VTuber (Android)

Přední kamera → MediaPipe Face Landmarker → hlava, oči a pusa postavy v reálném čase.
Spodní panel: 🙌 Ruce, 👋 Mávat, 😊 Radost, 😳 Blush, 🎨 Pozadí (levandule / chroma-green pro OBS / tmavé / bílé), 🎯 Kalibrovat a velké tlačítko Nahrát video (MP4 do Movies/HoppyVTuber, se zvukem z mikrofonu).
Při nahrávání se schová panel i náhled kamery. Klepnutím na postavu panel zase ukážeš.

## Jak získat APK
1. Nahraj obsah složky na GitHub (nový repozitář).
2. Záložka **Actions → Build APK → Run workflow**.
3. Stáhni artefakt **HoppyVTuber-apk** → `app-debug.apk` → nainstaluj.
(Nebo Android Studio: Open → Build → Build APK(s).) Model obličeje se stáhne sám při buildu.

## Ladění v `MainActivity.kt` (nahoře)
- `MIRROR`, `YAW_SIGN`, `PITCH_SIGN`, `ROLL_SIGN` – když se postava otáčí na opačnou stranu.
- `CAM_Z` – vzdálenost kamery (větší = postava menší), `MODEL_UNITS` – velikost postavy.
- `ARM_DEG` – jak vysoko jdou ruce (120° = čisté V).

## Postava
`app/src/main/assets/models/hoppy.glb`: odstraněné roblox oči z textury hlavy + nový blush (Face_BlushLeft/Right, skryté přes scale jako ostatní obličejové meshe).
