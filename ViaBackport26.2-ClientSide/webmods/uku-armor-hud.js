VBVClientMods.register({
  id: "ukus-armor-hud",
  name: "uku's Armor HUD",
  init(api) {
    api.waitForGameStart(() => {
      const host = document.createElement("div");
      host.id = "vbv-uku-armor-hud";
      Object.assign(host.style, {
        position:"fixed", left:"50%", bottom:"4px", transform:"translateX(-50%)",
        zIndex:"2147483646", display:"flex", flexDirection:"row-reverse",
        gap:"0px", pointerEvents:"none", imageRendering:"pixelated",
        visibility:"hidden"
      });

      const slots = ["helmet","chestplate","leggings","boots"];
      const elements = {};
      for (const slot of slots) {
        const el = document.createElement("div");
        Object.assign(el.style, {
          width:"36px", height:"36px", boxSizing:"border-box",
          display:"flex", alignItems:"center", justifyContent:"center",
          background:"rgba(0,0,0,0.18)",
          border:"1px solid rgba(0,0,0,0.35)",
          borderRadius:"2px",
          color:"white", font:"10px sans-serif",
          textShadow:"1px 1px 0 #000, -1px 0 #000",
          position:"relative"
        });
        elements[slot] = el;
        host.appendChild(el);
      }

      document.body.appendChild(host);

      /*
       * uku's Armor HUD is intentionally minimalist: armor item icons only,
       * with durability shown only when damaged and a low-durability warning.
       * No permanent text/numbers are rendered.
       */
      window.VBVArmorHud = {
        provider:null,
        setProvider(provider) { this.provider = provider; },
        update(items) {
          let any = false;
          for (const slot of slots) {
            const item = items?.[slot];
            const el = elements[slot];
            el.textContent = "";
            el.style.visibility = item ? "visible" : "hidden";
            if (!item) continue;
            any = true;

            if (item.icon instanceof HTMLImageElement || item.icon instanceof HTMLCanvasElement) {
              item.icon.style.width = "32px";
              item.icon.style.height = "32px";
              el.appendChild(item.icon);
            } else if (item.iconUrl) {
              const img = new Image();
              img.width = 32; img.height = 32;
              img.src = item.iconUrl;
              el.appendChild(img);
            }

            if (item.durability != null && item.durability < item.maxDurability) {
              const pct = Math.max(0, Math.min(1, item.durability / item.maxDurability));
              const text = document.createElement("span");
              text.textContent = Math.max(0, Math.ceil(pct * 100)) + "%";
              Object.assign(text.style, {
                position:"absolute", right:"1px", bottom:"0px",
                font:"9px monospace", color:pct <= 0.2 ? "#ff5555" : "#ffffff",
                textShadow:"1px 1px #000"
              });
              el.appendChild(text);
            }
            el.style.borderColor = item.low ? "#ff5555" : "rgba(0,0,0,0.35)";
          }
          host.style.visibility = any ? "visible" : "hidden";
        }
      };

      const tick = () => {
        try {
          const provider = window.VBVArmorHud.provider;
          if (typeof provider === "function") window.VBVArmorHud.update(provider());
        } catch (_) {}
        requestAnimationFrame(tick);
      };
      tick();
    });
  }
});
