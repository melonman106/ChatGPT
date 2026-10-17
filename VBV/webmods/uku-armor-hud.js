/*
 * uku's Armor HUD — client-side integration module.
 *
 * This file is intentionally NOT a DOM HUD. It exposes the exact uku-style
 * configuration/defaults to the client runtime bridge so the compiled
 * Minecraft HUD remains responsible for rendering the widget.
 */
(() => {
  const config = {
    enabled: true,
    anchor: "HOTBAR",
    side: "LEFT",
    offsetX: 0,
    offsetY: 0,
    style: "HOTBAR",
    orientation: "HORIZONTAL",
    widgetShown: "NOT_EMPTY",
    offhandSlotBehavior: "ADHERE",
    durabilityDisplay: "BAR",
    offHandDurability: true,
    mainHandDurability: false,
    pushBossbars: true,
    pushStatusEffectIcons: true,
    pushSubtitles: true,
    reversed: false,
    iconsShown: true,
    warningShown: true,
    playBreakSound: true,
    minDurabilityValue: 20,
    minDurabilityPercentage: 0.1,
    warningBobIntensity: 3
  };

  const api = globalThis.VBVClientMods;
  if (api) {
    api.ukusArmorHud = { id: "ukus-armor-hud", name: "uku's Armor HUD", config };
  }

  /*
   * If the existing compiled client exposes a native client-mod bridge, hand
   * the configuration to it. We deliberately do not manufacture a second
   * renderer or a fake HTML HUD.
   */
  for (const name of [
    "registerClientMod",
    "registerFabricClientMod",
    "registerMod",
    "registerHudMod"
  ]) {
    const fn = globalThis[name];
    if (typeof fn === "function") {
      try {
        fn({
          id: "ukus-armor-hud",
          name: "uku's Armor HUD",
          config,
          entrypoint: "modmenu"
        });
        return;
      } catch (_) {}
    }
  }
})();
