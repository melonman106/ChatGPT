package com.melonman106.vbvclient;

public final class VBVVisualMappings {
    private VBVVisualMappings() {}

    public static void register(VBVVisualRegistry r) {
        r.register("white_wool_stairs", "white_wool_stairs", "white_wool");
        r.register("white_wool_slab", "white_wool_slab", "white_wool");
        r.register("straw_bed", "straw_bed", "hay_block_side");
        r.register("poplar_planks", "poplar_planks", "poplar_planks");
        r.register("poplar_sapling", "poplar_sapling", "poplar_sapling");
        r.register("poplar_log", "poplar_log", "poplar_log");
        r.register("stripped_poplar_log", "stripped_poplar_log", "stripped_poplar_log");
        r.register("poplar_wood", "poplar_wood", "poplar_log");
        r.register("stripped_poplar_wood", "stripped_poplar_wood", "stripped_poplar_log");
        r.register("red_poplar_leaves", "red_poplar_leaves", "red_poplar_leaves");
        r.register("orange_poplar_leaves", "orange_poplar_leaves", "orange_poplar_leaves");
        r.register("yellow_poplar_leaves", "yellow_poplar_leaves", "yellow_poplar_leaves");
    }
}
