# Uku's Armor HUD 26.2 dependencies

The upstream Uku's Armor HUD 26.2 project declares these dependencies:

- UkuLib `2.1.0+26.2`
- Sponge Mixin `0.17.3+mixin.0.8.7` (compile-time; supplied by the Minecraft/Fabric runtime upstream)
- MixinExtras `0.5.4` (compile-time/annotation processor upstream)
- Minecraft `26.2`

The upstream Fabric module also declares Fabric Loader `0.19.3` and an optional compile-only Bedrockify compatibility dependency.

For the Eaglercraft client these are handled differently: Eagler does not load Fabric Loader or Mixin at runtime. The Armor HUD is therefore compiled as a native VBV client feature, with the upstream HUD behavior ported into `VBVArmorHud`, `VBVArmorHudConfig`, and `VBVArmorHudConfigScreen`. The existing `extractItemHotbar` hook replaces the upstream Mixin HUD injection.

No FullBright feature is included.
