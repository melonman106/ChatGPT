package com.melonman106.vbvclient;

public record VBVModInfo(
    String id,
    String name,
    String version,
    String authors,
    String description,
    String iconPath,
    boolean resourcePackMod
) {
    public VBVModInfo(String id, String name, String version, String authors, String description) {
        this(id, name, version, authors, description, "vbvclient/mods/default/pack.png", false);
    }
}
