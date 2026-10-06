/*
 * VBV browser bridge for the immutable Eagler HTML client.
 *
 * This does two things:
 *  1. exposes the two VBV client mods through one browser-side Mods panel;
 *  2. uses the real EaglerForge ModAPI metadata hook when that API exists.
 *
 * It never changes the embedded WASM/EPK payloads.
 */
(function () {
  const mods = [
    {
      id: "vbv-resource-packs",
      name: "Resource Packs",
      description: "Import and manage local ZIP resource packs.",
      open() { window.VBVResourcePacks?.openUI?.(); }
    },
    {
      id: "ukus-armor-hud",
      name: "uku's Armor HUD",
      description: "Minimal vanilla-style armor HUD for 26.2.",
      open() { window.VBVArmorHud?.openConfig?.(); }
    }
  ];

  window.VBVModMenu = window.VBVModMenu || {};
  window.VBVModMenu.mods = mods;

  function registerMetadata() {
    try {
      const api = window.ModAPI || window.PluginAPI;
      if (!api || !api.meta) return;
      api.meta.title("VBV Client Mods");
      api.meta.description("Resource Packs and uku's Armor HUD");
    } catch (_) {}
  }

  function makePanel() {
    if (document.getElementById("vbv-mod-menu")) return;

    const panel = document.createElement("div");
    panel.id = "vbv-mod-menu";
    Object.assign(panel.style, {
      position: "fixed", left: "50%", top: "50%",
      transform: "translate(-50%,-50%)", width: "420px",
      maxWidth: "calc(100vw - 32px)", maxHeight: "calc(100vh - 32px)",
      overflow: "auto", padding: "14px", boxSizing: "border-box",
      background: "rgba(15,15,15,.96)", color: "white",
      border: "2px solid #777", borderRadius: "4px",
      fontFamily: "sans-serif", zIndex: "2147483647", display: "none"
    });

    const title = document.createElement("div");
    title.textContent = "Mods";
    Object.assign(title.style, {fontSize:"20px",fontWeight:"bold",marginBottom:"12px"});
    panel.appendChild(title);

    for (const mod of mods) {
      const row = document.createElement("button");
      row.type = "button";
      Object.assign(row.style, {
        display:"block", width:"100%", textAlign:"left", margin:"6px 0",
        padding:"10px", background:"#303030", color:"white",
        border:"1px solid #666", borderRadius:"3px", cursor:"pointer"
      });
      const name = document.createElement("div");
      name.textContent = mod.name;
      name.style.fontWeight = "bold";
      const desc = document.createElement("div");
      desc.textContent = mod.description;
      desc.style.fontSize = "12px";
      desc.style.opacity = ".75";
      row.append(name, desc);
      row.addEventListener("click", () => mod.open());
      panel.appendChild(row);
    }

    const close = document.createElement("button");
    close.type = "button";
    close.textContent = "Close";
    Object.assign(close.style, {marginTop:"10px",padding:"7px 14px",cursor:"pointer"});
    close.onclick = () => { panel.style.display = "none"; };
    panel.appendChild(close);

    document.body.appendChild(panel);
    window.VBVModMenu.open = () => { panel.style.display = "block"; };
    window.VBVModMenu.close = () => { panel.style.display = "none"; };
  }

  function start() {
    makePanel();
    registerMetadata();
    window.addEventListener("keydown", (e) => {
      if (e.repeat || e.ctrlKey || e.altKey || e.metaKey) return;
      if (e.key.toLowerCase() === "m" && document.activeElement?.tagName !== "INPUT") {
        const panel = document.getElementById("vbv-mod-menu");
        if (panel) panel.style.display = panel.style.display === "none" ? "block" : "none";
      }
      if (e.key === "Escape") window.VBVModMenu.close?.();
    }, true);
  }

  if (window.VBVClientMods) {
    window.VBVClientMods.waitForGameStart(start);
  } else {
    const timer = setInterval(() => {
      if (window.VBVClientMods) { clearInterval(timer); window.VBVClientMods.waitForGameStart(start); }
    }, 100);
  }
})();
