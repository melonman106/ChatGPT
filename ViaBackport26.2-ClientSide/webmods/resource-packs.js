VBVClientMods.register({
  id: "vbv-resource-pack-tools",
  name: "Resource Pack Tools",
  init() {
    const KEY = "vbv.resource-packs";
    const packs = JSON.parse(localStorage.getItem(KEY) || "[]");

    const save = () => localStorage.setItem(KEY, JSON.stringify(packs));

    window.VBVResourcePacks = {
      list: () => packs.slice(),
      async importFile(file) {
        if (!file || !/\.zip$/i.test(file.name)) throw new Error("Select a .zip resource pack.");
        const buffer = await file.arrayBuffer();
        let binary = "";
        const bytes = new Uint8Array(buffer);
        const chunk = 0x8000;
        for (let i = 0; i < bytes.length; i += chunk)
          binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
        const entry = {
          name: file.name,
          size: file.size,
          type: file.type || "application/zip",
          data: btoa(binary)
        };
        const index = packs.findIndex(p => p.name === entry.name);
        if (index >= 0) packs[index] = entry; else packs.push(entry);
        save();
        return entry;
      },
      remove(name) {
        const i = packs.findIndex(p => p.name === name);
        if (i >= 0) packs.splice(i, 1);
        save();
      }
    };

    /*
     * Eagler 26.2 already contains its native resource-pack/shader-pack
     * system. This mod adds persistent browser-side pack storage without
     * replacing that system. A future native bridge can consume the stored
     * ZIP bytes without changing the base WASM.
     */
    window.dispatchEvent(new CustomEvent("vbv-resource-packs-ready"));
  }
});
