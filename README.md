# Hoppy VTuber (Android)

Přední kamera → MediaPipe Face Landmarker → hlava, oči a pusa postavy v reálném čase.
Spodní panel: 🙌 Ruce, 👋 Mávat, 😊 Radost, 😳 Blush, 🎨 Pozadí (levandule / chroma-green pro OBS / tmavé / bílé), 🎯 Kalibrovat a velké tlačítko Nahrát video (MP4 do Movies/HoppyVTuber, se zvukem z mikrofonu).
Při nahrávání se schová panel i náhled kamery. Klepnutím na postavu panel zase ukážeš.

## 👤 Facecam a obličej
- **👤 Facecam** (výchozí): kamera ukazuje hlavu s ušima a ramena. Vypnutím uvidíš celou postavu. Záběr se sám přizpůsobí velikosti okna i panelu.
- **Mluvení**: když otevřeš pusu, Hoppy otevře pusu taky – čím víc otevřeš, tím víc.
- **Úsměv**: velký úsměv = šťastné zavřené oči (jako 😊 Radost).
- **Mrkání** a **😳 Blush** (na tvářích pod očima).

## 🎙️ Hlas Hoppy (ElevenLabs)
Měnič hlasu: **mluvíš ty** (tvoje slova i intonace), ale ozve se **hlas z ElevenLabs**. Žádné MP3 ani psaní textu – převádí se tvůj živý hlas (Speech-to-Speech, model `eleven_multilingual_sts_v2`, umí česky).
1. Tlačítko **🎙️ Hlas** → vlož API klíč (elevenlabs.io → Profile → API Keys) → **Uložit klíč a načíst hlasy**.
2. Vyber hlas (i klonovaný nebo z Voice Library – stačí ho mít v účtu).
3. Posuvníky: stabilita, podobnost, styl, citlivost mikrofonu, odstranění šumu.
4. **🧪 Test**: Nahrát 5 s → Přehrát originál → Přehrát Hoppy hlasem (ukáže i zpoždění).
5. Zapni **Měnič hlasu** – běží v aplikaci i v bublině (tlačítko 🎙 v mini panelu). Pusa postavy se hýbe podle Hoppy hlasu.

Jak to funguje: aplikace pozná, kdy domluvíš větu (ticho ~0,65 s), pošle ji do ElevenLabs a přehraje výsledek. Zpoždění je proto ~1–2 s po větě. Používej **sluchátka**, jinak mikrofon slyší přehrávaný hlas. Spotřebovává kredity ElevenLabs (podle délky řeči). Klíč je uložený jen v telefonu.

## 🫧 Bublina – Hoppy nad hrou
Tlačítko **🫧 Bublina** pošle postavu do malého plovoucího okna nad ostatní aplikace (hry, YouTube…).
Kamera dál sleduje obličej (běží jako služba s notifikací). Hoppy je v **okýnku** s rámečkem a pozadím – ve Facecam je okýnko čtvercové, takže kolem není prázdné místo.
- Poprvé Android požádá o povolení **Zobrazovat přes jiné aplikace**.
- **Táhni** bublinu kamkoli, **klepni** na ni → mini panel: 👋 mávat, 😊 radost, 😳 blush, 👤 facecam, 🎙 měnič hlasu, 🎨 pozadí okýnka, 🎯 kalibrovat, ⤢ velikost (S/M/L), ✕ zavřít.
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
- `HoppyAvatar.kt`: `MOUTH_OPEN` / `MOUTH_CLOSE` – citlivost pusy při mluvení, `SMILE_HAPPY` – jak velký úsměv zavře oči do „happy“, `BLUSH_X/Y/SCALE` – poloha a velikost blush, `applyFraming()` – záběr Facecam / celá postava.
- `OverlayService.kt`: `SIZES_DP` – velikosti bubliny.

## Struktura kódu
- `FaceTracker.kt` – přední kamera + MediaPipe → natočení hlavy, mrkání, pusa.
- `HoppyAvatar.kt` – model a jeho pohyb (sdílené aplikací i bublinou).
- `MainActivity.kt` – hlavní obrazovka, tlačítka, nahrávání.
- `OverlayService.kt` – plovoucí bublina nad hrami.
- `ScreenRecordService.kt` – nahrávání videa.

## Postava
`app/src/main/assets/models/hoppy.glb`: odstraněné roblox oči z textury hlavy + nový blush (Face_BlushLeft/Right, skryté přes scale jako ostatní obličejové meshe).
