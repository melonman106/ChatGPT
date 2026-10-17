VBVClientMods.register({
  id: "vbv-resource-pack-menu",
  name: "Resource Packs",
  init() {
    const KEY = "vbv.resource-packs";
    const packs = JSON.parse(localStorage.getItem(KEY) || "[]");
    const save = () => localStorage.setItem(KEY, JSON.stringify(packs));

    window.VBVResourcePacks = {
      list: () => packs.slice(),
      async importFile(file) {
        if (!file || !/\.zip$/i.test(file.name)) throw new Error("Select a .zip resource pack.");
        const bytes = new Uint8Array(await file.arrayBuffer());
        let binary = "";
        for (let i = 0; i < bytes.length; i += 0x8000)
          binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
        const entry = {
          name: file.name,
          size: file.size,
          type: file.type || "application/zip",
          data: btoa(binary)
        };
        const old = packs.findIndex(p => p.name === entry.name);
        if (old >= 0) packs[old] = entry; else packs.push(entry);
        save();
        return entry;
      },
      remove(name) {
        const i = packs.findIndex(p => p.name === name);
        if (i >= 0) packs.splice(i, 1);
        save();
      },
      openUI() {
        let ui = document.getElementById("vbv-resource-packs-ui");
        if (ui) { ui.style.display = "block"; render(); return; }

        ui = document.createElement("div");
        ui.id = "vbv-resource-packs-ui";
        Object.assign(ui.style, {
          position:"fixed", left:"50%", top:"50%", transform:"translate(-50%,-50%)",
          width:"420px", maxWidth:"calc(100vw - 32px)", maxHeight:"70vh", overflow:"auto",
          padding:"14px", background:"rgba(15,15,15,.98)", color:"white",
          border:"2px solid #777", borderRadius:"4px", fontFamily:"sans-serif",
          zIndex:"2147483647"
        });
        document.body.appendChild(ui);

        function render() {
          ui.replaceChildren();
          const h = document.createElement("div");
          h.textContent = "Resource Packs";
          h.style.cssText = "font-size:20px;font-weight:bold;margin-bottom:10px";
          ui.appendChild(h);

          for (const p of packs) {
            const row = document.createElement("div");
            row.style.cssText = "display:flex;align-items:center;gap:8px;margin:6px 0;padding:8px;background:#303030";
            const label = document.createElement("span");
            label.textContent = p.name;
            label.style.flex = "1";
            const del = document.createElement("button");
            del.textContent = "Remove";
            del.onclick = () => { window.VBVResourcePacks.remove(p.name); render(); };
            row.append(label, del);
            ui.appendChild(row);
          }

          const input = document.createElement("input");
          input.type = "file";
          input.accept = ".zip,application/zip";
          input.style.marginTop = "10px";
          input.onchange = async () => {
            try { if (input.files?.[0]) await window.VBVResourcePacks.importFile(input.files[0]); render(); }
            catch (e) { alert(e.message || e); }
          };
          ui.appendChild(input);

          const close = document.createElement("button");
          close.textContent = "Close";
          close.style.marginTop = "10px";
          close.onclick = () => { ui.style.display = "none"; };
          ui.appendChild(close);
        }
        render();
      }
    };

    window.VBVModMenu = window.VBVModMenu || { providers: [] };
    window.VBVModMenu.providers.push({
      id: "vbv-resource-packs",
      name: "Resource Packs",
      open() { window.VBVResourcePacks.openUI(); }
    });
  }
});
