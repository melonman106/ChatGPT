VBVClientMods.register({
  id: "vbv-fullbright",
  name: "Fullbright",
  init() {
    const KEY = "vbv.fullbright";
    let enabled = localStorage.getItem(KEY) !== "false";
    const style = document.createElement("style");
    style.id = "vbv-fullbright-style";
    document.head.appendChild(style);

    const apply = () => {
      /*
       * This is deliberately an overlay-layer mod. It does not rewrite the
       * immutable Eagler WASM or its shader payloads.
       *
       * The renderer's real lightmap/shader state is not exposed as a stable
       * JavaScript API by the compiled HTML, so the browser-safe fallback is
       * a canvas brightness boost. It can be disabled at any time.
       */
      style.textContent = enabled
        ? "canvas:not(#vbv-mod-canvas){filter:brightness(1.55) !important;}"
        : "";
    };

    const toggle = () => {
      enabled = !enabled;
      localStorage.setItem(KEY, String(enabled));
      apply();
      window.dispatchEvent(new CustomEvent("vbv-fullbright", {detail:{enabled}}));
    };

    window.VBVFullbright = { toggle, isEnabled: () => enabled };
    window.addEventListener("keydown", e => {
      if (e.code === "KeyB" && !e.ctrlKey && !e.altKey && !e.metaKey && !e.shiftKey) toggle();
    });
    apply();
  }
});
