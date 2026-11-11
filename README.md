# Hoppy VTuber (Android)

Přední kamera → MediaPipe Face Landmarker → hlava, oči a pusa postavy v reálném čase.
Spodní panel: 🙌 Ruce, 👋 Mávat, 😊 Radost, 😳 Blush, 🎨 Pozadí (levandule / chroma-green pro OBS / tmavé / bílé), 🎯 Kalibrovat a velké tlačítko Nahrát video (MP4 do Movies/HoppyVTuber, se zvukem z mikrofonu).
Nahrává se jen 3D scéna s postavou: tlačítka (emoce, efekty…) zůstávají na obrazovce a fungují, ale ve videu nejsou. Nahoře běží 🔴 REC s časem.

## 👤 Facecam a obličej
- **👤 Facecam** (výchozí): kamera ukazuje hlavu s ušima a ramena. Vypnutím uvidíš celou postavu. Záběr se sám přizpůsobí velikosti okna i panelu.
- **Mluvení**: když otevřeš pusu, Hoppy otevře pusu taky – čím víc otevřeš, tím víc.
- **Úsměv**: velký úsměv = šťastné zavřené oči (jako 😊 Radost).
- **Mrkání** a **😳 Blush** (na tvářích pod očima).

## ✨ Efekty (vlastní 3D modely)
Druhá řada tlačítek v aplikaci, v bublině tlačítko **✨** otevře samostatný plovoucí panel efektů (přesuneš ho za ⠿ a mačkáš vedle hry):
🤧 Kýchnutí · 🤩 Nadšení · 🤒 Nemocná (přepínač) · 🥕 Mrkvička · 🤮 Zvracení · 😍 Zamilovaná · 🩸 Krev z nosu · 🥺 UwU
Rekvizity (mrkev, srdíčka, hvězdičky, kapky, teploměr…) jsou v `app/src/main/assets/models/props/`, generuje je `python3 tools/make_props.py`. Chování efektů je v `Effects.kt`.

## 🐰 Pohyb jako v originále
Postava pořád hraje animaci **Idle** z modelu (houpání uší, ocásku a těla). 👋 Mávat a 😊 Radost spouští animace **Wave** a **Happy** z modelu (plynulé prolnutí). Uši a ocásek jsou navíc **pružné**: dobíhají a houpou se, když hneš hlavou. Ladění: `EAR_FLOP`, `EAR_STIFF`, `EAR_DAMP`, `TAIL_WAG`, `BODY_FOLLOW` v `HoppyAvatar.kt`.

## 🖼️ Vlastní pozadí
**🖼️ Vlastní pozadí** → vyber obrázek z galerie. Vyplní celý záběr (i v bublině). **🎨 Pozadí** přepne zpět na barvy; v bublině 🎨 projde barvy a pak tvůj obrázek.

## 🎙️ Hlas Hoppy (měnič hlasu v telefonu)
**Mluvíš ty a slyšíš se hned**, jen jiným hlasem. Hlas se mění přímo v telefonu: nic se negeneruje, neposílá na internet a nespotřebovává kredity.
ElevenLabs slouží jen jako **knihovna hlasů**: aplikace stáhne krátkou veřejnou ukázku vybraného hlasu a podle ní nastaví výšku a barvu tvého hlasu.
1. **🎙️ Hlas** → vlož API klíč (jen pro seznam hlasů) → **Načíst hlasy** → vyber hlas → **▶ Poslechnout ukázku**.
2. **⏺ Nahrát 5 s** (mluv normálně) → **🎯 Napodobit vybraný hlas**: spočítá výšku (Hz) tvou i cílovou a nastaví posuvníky.
3. Dolaď **Výšku** (půltóny) a **Barvu** → **▶ originál** vs. **🐰 Hoppy hlas**.
4. Zapni **Měnič hlasu**: běží v aplikaci i v bublině (🎙 v mini panelu). Pusa se hýbe podle hlasu.

⚠️ Používej **sluchátka**, jinak reproduktor pískne zpětnou vazbou. Měnič mění výšku a barvu hlasu. Úplně stejný hlas jako ElevenLabs (klon) bez generování v cloudu udělat nejde.

## 🫧 Bublina – Hoppy nad hrou
Tlačítko **🫧 Bublina** pošle postavu do malého plovoucího okna nad ostatní aplikace (hry, YouTube…).
Kamera dál sleduje obličej (běží jako služba s notifikací). Hoppy je v **okýnku** s rámečkem a pozadím – ve Facecam je okýnko čtvercové, takže kolem není prázdné místo.
- Poprvé Android požádá o povolení **Zobrazovat přes jiné aplikace**.
- **Táhni** bublinu kamkoli, **klepni** na ni → mini panel: 👋 mávat, 😊 radost, 😳 blush, 👤 facecam, 🎙 měnič hlasu, 🎨 pozadí okýnka, 🎯 kalibrovat, ⤢ velikost (S/M/L), ✕ zavřít.
- Funguje i na šířku (tracking se otáčí s displejem).
- Návrat do aplikace (nebo klepnutí na notifikaci) bublinu zavře a postava se vrátí do aplikace.
- Poloha, velikost, výrazy i kalibrace se pamatují.
- **⏺ Nahrávání v bublině**: v mini panelu, v panelu efektů ✨ nebo v notifikaci. Nahrává se jen Hoppy (video 720p+), v okýnku běží 🔴 čas – klepnutím na něj zastavíš. Zastavit jde vždy i z notifikace; při zavření bubliny se video uloží samo.
- Panel efektů ✨ má všechno: 👋 🙌 😊 😳 + všechny efekty + ⏺.
- Xiaomi/HyperOS: povol „Zobrazovat vyskakovací okna při běhu na pozadí“ a baterii „Bez omezení“ (aplikace na to jednou upozorní).

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
