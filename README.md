# Halo Visuals (HV) 1.2.0 — Fabric 1.21.4, client-side

Wszystkie funkcje są darmowe i odblokowane. ClickGUI: domyślnie klawisz `I` (zmiana: Client → Click GUI → Key).
Lewy klik = włącz/wyłącz moduł, prawy klik (lub strzałka) = rozwiń ustawienia, kółko myszy = przewijanie.
Konfiguracja: `.minecraft/config/halovisuals.json`.

## Jak dostać gotowy .jar (bez IDE)
### Sposób A — GitHub Actions
1. Utwórz repozytorium na github.com i wgraj całą zawartość tego folderu (razem z ukrytym `.github`).
2. Zakładka **Actions** → workflow `build` → poczekaj na zielony znaczek.
3. W przebiegu → **Artifacts** → pobierz `HaloVisuals-jar` → w środku `halovisuals-1.2.0.jar`.
### Sposób B — lokalnie
JDK 21 + Gradle 8.12+: `gradle build` → `build/libs/halovisuals-1.2.0.jar`.

## Instalacja
Fabric Loader dla 1.21.4 + Fabric API (1.21.4) + `halovisuals-1.2.0.jar` w folderze `mods`.

## Discord Rich Presence
1. https://discord.com/developers/applications → New Application (nazwa np. „Halo Visuals - Angelic Client”).
2. Skopiuj **Application ID**, wklej do `config/halovisuals.json` w polu `"_discordAppId"`.
3. Rich Presence → Art Assets: wgraj obrazy o kluczach `halo_logo` (duże) i `halo_small` (małe) — możesz użyć `assets/halovisuals/icon.png`.
4. W grze włącz Client → Discord Presence (Discord musi być uruchomiony).

## Co jest w środku (skrót)
- ClickGUI: płynne ease-in-out (skala + alpha), auto-skalowanie do GUI Scale, dźwięki kryształu (otwarcie/zamknięcie/hover/klik).
- Target ESP: Angel Rings, Soul, Square, Crystal Orbit (+ palety Golden / Soul Blue / Rose).
- Własny silnik cząsteczek (zero cząsteczek vanilla): Angel Dust, Star Sparks, Golden Petals, Crystals, Snowflakes, Crimson Sparks, Mixed.
- Target HUD: pop-up, shake po obrażeniach, ekwipunek + durability, Scale, przeciąganie myszką przy otwartym czacie.
- Halo / China Hat tylko dla lokalnego gracza; skrzydła i orbitujące gwiazdki w Cosmetics.
- Custom Sky, Atmosphere (czas/pogoda), Aspect Ratio, Swing Animations, Hitbox Customizer, Damage Numbers, Totem Pop, Death/Kill Effect, Hit Color, Cinematic Text, Discord RPC, Halo Nametag (tab lista), Performance.
- Color Saturation i Motion Blur to lekkie efekty nakładkowe (tint / smugi przy obrocie), nie prawdziwe shadery post-process.

## Jeśli gra się wywala przy starcie
Mixiny są w osobnych plikach. W `src/main/resources/halovisuals.mixins.json` usuń z listy `client` ten mixin, który wskazuje log
(np. `HeldItemRendererMixin`), i zbuduj ponownie — reszta moda działa bez niego.
