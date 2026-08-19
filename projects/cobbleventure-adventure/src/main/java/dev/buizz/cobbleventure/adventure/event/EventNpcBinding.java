package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;

/** Representation-neutral link between an entity tag and a compiled CVES script. */
public record EventNpcBinding(String bindingId, String entityTag, String scriptId) {
    public EventNpcBinding {
        requireValue(bindingId, "bindingId");
        requireValue(entityTag, "entityTag");
        requireValue(scriptId, "scriptId");
    }

    private static void requireValue(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + "가 필요합니다.");
    }
}
