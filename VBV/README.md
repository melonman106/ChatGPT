# ViaBackportVisuals 26.2 Client Side

Native Eaglercraft 26.2 u1 client-side mod suite with a Herz-style client API adapter.

This is not a Fabric Loader/API mod and does not embed ModMenu. It provides a native installed-mod browser and client features directly inside the Eagler client.

## Herz API layer

VBV now uses a small 26.2-native Herz adapter for mod registration and lifecycle events:

- `HerzMod` — common client-mod contract.
- `HerzModRegistry` — central mod registry.
- `HerzClient` — initialize/tick lifecycle.
- `HerzVBVBridge` — exposes existing VBV mods through the API.

The upstream Herz-Core project currently targets Eaglercraft 1.8.8/u53, so its runtime is not copied into this 26.2 client. The adapter is deliberately isolated so it can be replaced by a real 26.2 Herz implementation when one is available.

## Included

- Native mod viewer and mod details screens.
- Uku's Armor HUD.
- Simple HUD Enhanced.
- Mouse Tweaks.
- AppleSkin.
- Panorama Selector.
- Dark UI.
- Connected Glass.
- Client-only explored-chunk world map and waypoints.
- GitHub Actions build/patch pipeline.

## Intentionally removed

- 26.3 texture recreation.
- 26.3 blockstate/model recreation.
- Server-side 26.3 texture compatibility from the client project.
- Better Clouds.
- Low Fire.

The client project no longer replaces normal 26.2 textures with 26.3 placeholders.
