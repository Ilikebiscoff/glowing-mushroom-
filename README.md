# Glowing Mushroom Auto (Fabric, Minecraft Java 26.1.2)

Client-side Fabric mod for Hypixel SkyBlock's Glowing Mushroom Cave. It reads the particles
Hypixel spawns on glowing mushrooms (the signal SkyHanni's highlighters use), then walks a route you
record and breaks each mushroom that comes within reach.

> **Warning:** automating gameplay breaks Hypixel's rules and can get you banned. Use at your own risk.
> This code has **not been compiled or tested in-game**. I couldn't reach the Fabric docs while writing it,
> so Mojang-mapped names and the loader version (`gradle.properties`) may need small fixes.

## Build
Needs JDK 25 and Gradle 9.4+ (Loom 1.15, plugin `net.fabricmc.fabric-loom`, no mappings - 26.1 is unobfuscated):
`gradle build` -> `build/libs/`. Requires Fabric API `0.145.4+26.1.2`.

## Use
Also needs a hotbar item matching `moo?by.*shears` (change with `/gm tool <regex>`) and, for speed, one
matching `rogue sword` (`/gm sword <regex>`). Every ~25 s it reads `Speed:` from the tab list (enable the
Speed tab widget in SkyBlock; `/gm speed` prints what it sees) and, if below 400, swaps to the sword, right-clicks, swaps back.
Mouse movement uses eased, curved, sensitivity-quantised motion with reaction delay and overshoot (`HumanAim`).
Marker particles default to the potion set (`entity_effect`, `ambient_entity_effect`, `effect`, `instant_effect`).

1. Join the cave, stand near mushrooms, run `/gm scan`, wait ~5 s, run `/gm scan` again. It lists particle counts;
   set the one that only appears on mushrooms with `/gm particle <id>` (default is the potion set; `/gm particle potion` restores it).
2. Walk your loop and `/gm add` at each corner (`/gm undo`, `/gm clear`). Saved to `config/glowingmushroom_route.json`.
3. `/gm start` / `/gm stop`. It loops the route, stops to mine tracked mushrooms within 4.4 blocks, and stops itself if stuck.
