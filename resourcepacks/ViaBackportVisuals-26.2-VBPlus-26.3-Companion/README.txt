ViaBackportVisuals 26.2 — ViaBackwards-Plus 26.3 Companion

Install ViaBackwards-Plus v2.7.1 first, then put this pack ABOVE it.

This companion contains only the local 26.3 visual/placeholder layer. VB+ supplies the 26.3 item models/textures.

Server requirement:
- ViaBackportVisuals Fabric 26.3
- ViaVersion
- ViaBackwards

Included:
- 16 Wool Stairs
- 16 Wool Slabs
- corrected vanilla 26.2 stair/slab geometry via model overrides (no custom stair rotations)
- Straw Bed support from the server mapping/companion pack

The placeholder approach means the selected 26.2 placeholder can share the replacement appearance while the pack is active.


Emergency visual-mapping commands (operator level 2+):
/vbv status
/vbv disable
/vbv enable

/vbv disable restores the original ViaBackwards block-state mappings in memory. Reconnect clients after using it so their chunk visuals refresh. It does not delete or replace world blocks.


IMPORTANT ISOLATION NOTE
------------------------
The companion pack is intentionally no longer allowed to globally replace genuine 26.2 blockstates/models. Resource-pack-only predicates cannot identify a placed block's origin, so global placeholder overrides cause genuine copper/deepslate/stone-brick/beds/etc. to change texture.

The correct architecture is now:
- server: real 26.2 stair/slab/bed placeholder states for correct collision;
- server -> client: a small ViaBackportVisuals marker payload identifying translated block positions;
- client: a 26.2 Fabric companion mod that uses the position-aware block-model API to render the backported texture only at marked positions.

Do NOT re-add note_block or redstone_wire collision markers.
Do NOT add global blockstate/model overrides for genuine vanilla placeholder blocks.
See server-mods/ViaBackportVisuals/docs/PLACEMENT_ISOLATION_ARCHITECTURE.md for the implementation contract.
