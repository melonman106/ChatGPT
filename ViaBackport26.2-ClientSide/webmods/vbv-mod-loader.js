window.VBVClientMods = window.VBVClientMods || {
  version: "1.0.0",
  mods: [],
  register(mod) {
    this.mods.push(mod);
    try { mod.init?.(this); } catch (e) { console.error("[VBV]", mod.id, e); }
  }
};
