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
        for (let i=0; i<bytes.length; i+=0x8000)
          binary += String.fromCharCode(...bytes.subarray(i, i+0x8000));
        const entry = {name:file.name,size:file.size,type:file.type||"application/zip",data:btoa(binary)};
        const old = packs.findIndex(p => p.name === entry.name);
        if (old >= 0) packs[old] = entry; else packs.push(entry);
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
     * Register a Mod Menu configuration provider. The compiled Mod Menu
     * remains untouched; when its JS bridge is exposed, it can consume this
     * provider. Until then, the provider remains inert rather than drawing
     * another menu over the game.
     */
    window.VBVModMenu = window.VBVModMenu || {providers:[]};
    window.VBVModMenu.providers.push({
      id:"vbv-resource-packs",
      name:"Resource Packs",
      open() { return window.VBVResourcePacks; }
    });
  }
});
