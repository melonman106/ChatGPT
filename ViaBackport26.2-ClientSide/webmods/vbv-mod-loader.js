window.VBVClientMods = window.VBVClientMods || {
  version: "1.1.0",
  mods: [],
  started: false,
  register(mod) {
    this.mods.push(mod);
    try { mod.init?.(this); } catch (e) { console.error("[VBV]", mod.id, e); }
  },
  isGameStarted() { return this.started; },
  waitForGameStart(fn) {
    const check = () => {
      const canvas = [...document.querySelectorAll("canvas")].find(c => c.width > 320 && c.height > 180);
      if (canvas && document.visibilityState !== "hidden") {
        this.started = true;
        fn(canvas);
        return;
      }
      requestAnimationFrame(check);
    };
    check();
  }
};
