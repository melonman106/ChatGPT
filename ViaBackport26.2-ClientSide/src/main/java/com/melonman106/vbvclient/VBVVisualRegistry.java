package com.melonman106.vbvclient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class VBVVisualRegistry {
    private final Map<String, VBVVisualMapping> mappings = new LinkedHashMap<>();

    public void register(String protocolId, String modelId, String textureId) {
        mappings.put(protocolId, new VBVVisualMapping(protocolId, modelId, textureId));
    }

    public VBVVisualMapping get(String protocolId) {
        return mappings.get(protocolId);
    }

    public Map<String, VBVVisualMapping> all() {
        return Collections.unmodifiableMap(mappings);
    }
}
