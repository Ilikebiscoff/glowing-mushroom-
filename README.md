# Glowing Mushroom Auto (Forge 1.8.9)

Client-side mod for Hypixel SkyBlock's Glowing Mushroom Cave. It listens for the
particles Hypixel spawns on glowing mushrooms (same technique as SkyHanni's highlighters),
outlines them, and walks a route you record while breaking each mushroom in reach.

> **Warning:** automating gameplay breaks Hypixel's rules and can get you banned. Use at your own risk.
> This code has **not been compiled or tested in-game**; expect to fix small API slips.

## Build
Needs JDK 8 and Gradle 2.14 (ForgeGradle 2.1): `gradle setupDecompWorkspace build`. Jar ends up in `build/libs`.

## Use
1. Join the cave. Stand near mushrooms and run `/gm scan`, wait ~5 s, run `/gm scan` again.
   It prints particle counts; pick the type that only appears on mushrooms and set it with
   `/gm particle <TYPE>` (default `SPELL_MOB` is a guess - I couldn't read SkyHanni's source from here).
2. Walk your loop and run `/gm add` at each corner/turn (`/gm undo`, `/gm clear`). Saved to `config/glowingmushroom_route.json`.
3. `/gm start` or press **J** to toggle. It loops the route, stops to mine any mushroom within 4.4 blocks
   (green box = tracked, red = current target, blue = waypoints), and stops itself if stuck.

## Files
- `MushroomTracker` – netty hook on `S2APacketParticles`, maps particles to nearby mushroom blocks.
- `MacroController` – waypoint walking, smooth aiming, mining, stuck detection.
- `Route`, `GmCommand`, `GlowingMushroomMod` – storage, commands, keybind and rendering.
