VBVClientMods.register({
  id: "ukus-armor-hud",
  name: "uku's Armor HUD",
  init() {
    const host = document.createElement("div");
    host.id = "vbv-uku-armor-hud";
    Object.assign(host.style, {
      position:"fixed", left:"12px", bottom:"58px", zIndex:"2147483646",
      display:"flex", flexDirection:"column", gap:"2px",
      pointerEvents:"none", imageRendering:"pixelated"
    });
    document.body.appendChild(host);

    const slots = ["helmet","chestplate","leggings","boots"];
    const makeSlot = () => {
      const el = document.createElement("div");
      Object.assign(el.style, {
        width:"32px", height:"32px", boxSizing:"border-box",
        background:"rgba(0,0,0,.38)", border:"1px solid rgba(255,255,255,.18)",
        display:"flex", alignItems:"center", justifyContent:"center",
        color:"#fff", font:"10px sans-serif", textShadow:"1px 1px #000"
      });
      return el;
    };
    const elements = Object.fromEntries(slots.map(s => [s, makeSlot()]));
    for (const s of slots) host.appendChild(elements[s]);

    /*
     * The original uku design is item icons plus a low-durability warning.
     * The immutable compiled Eagler HTML does not expose a documented
     * Java inventory object to page JavaScript, so this bridge looks for
     * an explicitly exported inventory provider first. No fake armor state
     * is invented when the provider is unavailable.
     */
    window.VBVArmorHud = {
      setProvider(provider) { this.provider = provider; },
      update(items) {
        for (const s of slots) {
          const item = items?.[s];
          const el = elements[s];
          el.textContent = item?.label || "";
          el.style.borderColor = item?.low ? "#ff3030" : "rgba(255,255,255,.18)";
          el.title = item?.durability != null
            ? String(item.durability)
            : "";
        }
      }
    };

    const tick = () => {
      try {
        const provider = window.VBVArmorHud.provider;
        if (typeof provider === "function") {
          const items = provider();
          if (items) window.VBVArmorHud.update(items);
        }
      } catch (e) {}
      requestAnimationFrame(tick);
    };
    tick();
  }
});
