# ViaBackportVisuals 26.2 Client Side

Native Eaglercraft 26.2 u1 client-side patch for ViaBackportVisuals.

This is not a Fabric Loader/API mod and does not embed ModMenu. It provides a native ModMenu-style installed-mod browser inside the Eagler client.

## Included
- Client-side ViaBackportVisuals registry.
- Per-block visual override registry.
- Native Mods screen and details screen.
- Built-in registration API.
- GitHub Actions build/patch pipeline.

The patch is applied to generated Eaglercraft source by the build workflow.

## Current mappings
white_wool_stairs, white_wool_slab, straw_bed, and the known poplar family are registered initially. More mappings can be added without globally replacing genuine 26.2 textures.
