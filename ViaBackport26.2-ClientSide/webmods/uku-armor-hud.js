/*
 * uku's Armor HUD — 26.2 behavior.
 *
 * The upstream 26.2 defaults are kept: HOTBAR anchor, LEFT side,
 * HORIZONTAL orientation, HOTBAR style, NOT_EMPTY widget, icons enabled,
 * durability BAR, and warning enabled.
 *
 * The browser layer only supplies the Eagler runtime bridge. It does not
 * intentionally redesign uku's widget.
 */
VBVClientMods.register({
  id: "ukus-armor-hud",
  name: "uku's Armor HUD",
  init(api) {
    api.waitForGameStart(() => {
      const host = document.createElement("div");
      host.id = "vbv-uku-armor-hud";

      Object.assign(host.style, {
        position: "fixed",
        left: "50%",
        bottom: "32px",
        transform: "translateX(-50%)",
        zIndex: "2147483646",
        display: "flex",
        flexDirection: "row",
        gap: "0px",
        pointerEvents: "none",
        imageRendering: "pixelated",
        visibility: "hidden"
      });

      const slots = ["helmet", "chestplate", "leggings", "boots"];
      const elements = {};

      for (const slot of slots) {
        const el = document.createElement("div");
        Object.assign(el.style, {
          width: "20px",
          height: "20px",
          boxSizing: "border-box",
          position: "relative",
          display: "flex",
          alignItems: "center",
          justifyContent: "center"
        });
        elements[slot] = el;
        host.appendChild(el);
      }

      document.body.appendChild(host);

      window.VBVArmorHud = {
        provider: null,
        enabled: true,

        setProvider(provider) {
          this.provider = provider;
        },

        openConfig() {
          let box = document.getElementById("vbv-uku-armor-config");
          if (box) { box.style.display = "block"; return; }
          box = document.createElement("div");
          box.id = "vbv-uku-armor-config";
          Object.assign(box.style, {
            position:"fixed", left:"50%", top:"50%", transform:"translate(-50%,-50%)",
            width:"360px", maxWidth:"calc(100vw - 32px)", padding:"14px",
            background:"rgba(15,15,15,.98)", color:"white", border:"2px solid #777",
            borderRadius:"4px", fontFamily:"sans-serif", zIndex:"2147483647"
          });
          const title = document.createElement("div");
          title.textContent = "uku's Armor HUD";
          title.style.cssText = "font-size:20px;font-weight:bold;margin-bottom:10px";
          box.appendChild(title);
          const label = document.createElement("label");
          const toggle = document.createElement("input");
          toggle.type = "checkbox";
          toggle.checked = this.enabled;
          toggle.onchange = () => {
            this.enabled = toggle.checked;
            host.style.visibility = this.enabled && host.dataset.hasItems === "1" ? "visible" : "hidden";
          };
          label.append(toggle, document.createTextNode(" Enabled"));
          box.appendChild(label);
          const close = document.createElement("button");
          close.textContent = "Close";
          close.style.marginTop = "12px";
          close.onclick = () => { box.style.display = "none"; };
          box.appendChild(close);
          document.body.appendChild(box);
        },

        update(items) {
          let shown = 0;

          for (const slot of slots) {
            const item = items?.[slot];
            const el = elements[slot];

            el.replaceChildren();
            el.style.visibility = item ? "visible" : "hidden";

            if (!item) continue;
            shown++;

            if (item.icon instanceof Node) {
              item.icon.style.width = "16px";
              item.icon.style.height = "16px";
              el.appendChild(item.icon);
            } else if (item.iconUrl) {
              const img = new Image(16, 16);
              img.src = item.iconUrl;
              el.appendChild(img);
            }

            if (
              item.durability != null &&
              item.maxDurability > 0 &&
              item.durability < item.maxDurability
            ) {
              const bar = document.createElement("div");
              const pct = Math.max(0, Math.min(1, item.durability / item.maxDurability));
              Object.assign(bar.style, {
                position:"absolute", left:"2px", right:"2px", bottom:"1px",
                height:"2px", background:"rgba(0,0,0,.55)", overflow:"hidden"
              });
              const fill = document.createElement("div");
              Object.assign(fill.style, {
                height:"100%",
                width:(pct * 100) + "%",
                background:"rgb(" + Math.round(255 * (1 - pct)) + "," + Math.round(255 * pct) + ",0)"
              });
              bar.appendChild(fill);
              el.appendChild(bar);
            }

            if (item.low) {
              const warning = document.createElement("span");
              warning.textContent = "!";
              Object.assign(warning.style, {
                position:"absolute", top:"0", right:"0",
                font:"bold 8px sans-serif", color:"#ff5555", textShadow:"1px 1px #000"
              });
              el.appendChild(warning);
            }
          }

          host.dataset.hasItems = shown ? "1" : "0";
          host.style.visibility = this.enabled && shown ? "visible" : "hidden";
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
