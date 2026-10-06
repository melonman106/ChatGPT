/*
 * uku's Armor HUD — 26.2 behavior.
 *
 * Upstream defaults are preserved: HOTBAR anchor, LEFT side, HORIZONTAL
 * orientation, HOTBAR style, NOT_EMPTY widget, icons enabled, durability
 * BAR, and warning enabled.
 *
 * The only Eagler-specific adaptation is the runtime provider hook because
 * the compiled Eagler WASM does not expose Java Player/ItemStack directly.
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

        setProvider(provider) {
          this.provider = provider;
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

            // uku default: durability BAR.
            if (
              item.durability != null &&
              item.maxDurability > 0 &&
              item.durability < item.maxDurability
            ) {
              const bar = document.createElement("div");
              const pct = Math.max(
                0,
                Math.min(1, item.durability / item.maxDurability)
              );

              Object.assign(bar.style, {
                position: "absolute",
                left: "2px",
                right: "2px",
                bottom: "1px",
                height: "2px",
                background: "rgba(0,0,0,.55)",
                overflow: "hidden"
              });

              const fill = document.createElement("div");
              Object.assign(fill.style, {
                height: "100%",
                width: (pct * 100) + "%",
                background: "rgb(" +
                  Math.round(255 * (1 - pct)) + "," +
                  Math.round(255 * pct) + ",0)"
              });

              bar.appendChild(fill);
              el.appendChild(bar);
            }

            // uku default: warning enabled.
            if (item.low) {
              const warning = document.createElement("span");
              warning.textContent = "!";
              Object.assign(warning.style, {
                position: "absolute",
                top: "0",
                right: "0",
                font: "bold 8px sans-serif",
                color: "#ff5555",
                textShadow: "1px 1px #000"
              });
              el.appendChild(warning);
            }
          }

          host.style.visibility = shown ? "visible" : "hidden";
        }
      };

      const tick = () => {
        try {
          const provider = window.VBVArmorHud.provider;
          if (typeof provider === "function") {
            window.VBVArmorHud.update(provider());
          }
        } catch (_) {}
        requestAnimationFrame(tick);
      };

      tick();
    });
  }
});
