# ViaBackportVisuals 26.3 -> 26.2 isolation architecture

## Why the old resource-pack-only approach failed

A 26.3 block translated to a real 26.2 placeholder has the correct collision shape, but a normal 26.2 resource pack cannot tell whether that placeholder was sent because it is a genuine 26.2 block or because ViaBackportVisuals is representing a 26.3 block.

Therefore a resource-pack override such as stone_bricks.json, yellow_bed.json, or a copper stair/slab blockstate is global. It changes genuine 26.2 blocks too.

The previous note_block and redstone_wire markers are also rejected: their collision/interaction shapes do not match stairs, slabs, or beds.

## Target architecture

1. ViaBackwards remains responsible for protocol translation.
2. ViaBackportVisuals maps new 26.3 stair/slab blocks to genuine 26.2 stair/slab states. This keeps client collision geometry correct.
3. No vanilla placeholder blockstate/model is globally overridden by the companion pack. Genuine 26.2 copper, deepslate, stone brick, blackstone, beds, etc. retain their normal appearance.
4. The server sends a small custom payload to a companion 26.2 Fabric client mod when a translated/backported block is placed. The payload identifies the block position and the 26.3 visual identifier.
5. The 26.2 client mod keeps a position -> backport-visual map and uses Fabric's 26.2 block-model loading/rendering API to select the backported model only for marked positions. The underlying world state remains the genuine 26.2 placeholder, so collision and gameplay stay vanilla.
6. Removing/breaking a marked block removes its client marker. Chunk/connection resynchronisation must clear stale markers.
7. ViaBackwards item mapping is used for item identity. Its 26.3 -> 26.2 mapping data can expose the original future-item identity/custom model data; this is useful for deciding which 26.3 item was placed, but item NBT cannot by itself tag an ordinary placed block.

## Important limitation

A resource-pack JSON cannot implement a new arbitrary predicate such as vb_item on a normal vanilla block. The predicate must be implemented by client code. Likewise, a placed bed has no normal block-entity NBT channel that the vanilla block-model system can use as a per-position visual marker.

## Rendering implementation

Minecraft 26.2's Fabric API exposes position-aware block-model geometry through FabricBlockStateModel.emitQuads(..., BlockPos, ...). The client implementation should wrap the placeholder's baked model and, when the current BlockPos is present in the marker map, emit the corresponding backported model; otherwise it must delegate to the normal vanilla model.

This is preferable to mixins that replace collision or alter the actual world state: the server/client block state remains a real stair/slab/bed.

## Current placeholder policy

- Wool stairs/slabs: real 26.2 stair/slab placeholders.
- Concrete stairs/slabs: real 26.2 stair/slab placeholders.
- Straw bed: real 26.2 yellow bed placeholder until the client visual layer handles the straw model.
- Do not use redstone wire or note blocks as collision placeholders.
- Do not globally override genuine copper/deepslate/stone-brick/blackstone/purpur/etc. blockstates.
- Do not repurpose oak/spruce/jungle leaf blockstates globally.
- Existing poplar-log behaviour is left unchanged.

## Testing requirements

On a 26.2 Fabric client with the companion client mod:

- Genuine placeholder blocks still look vanilla.
- Backported wool/concrete stairs have the correct wool/concrete texture and normal stair collision.
- Backported wool/concrete slabs have the correct texture and normal half/double slab collision.
- Straw beds render as hay-based beds while remaining functional beds.
- Breaking/replacing a marked block clears its visual marker.
- Reconnecting or changing worlds clears stale markers.
- No black/purple missing models.
- No global texture changes to genuine 26.2 blocks.

## References

- Fabric 26.2 networking uses CustomPacketPayload, PayloadTypeRegistry.clientboundPlay(), and ServerPlayNetworking.send().
- Fabric 26.2 exposes FabricBlockStateModel.emitQuads(..., BlockPos, ...) for position-aware geometry.
- ViaBackwards 5.12.0 provides the 26.3 server translation layer and original future-item mapping data.
