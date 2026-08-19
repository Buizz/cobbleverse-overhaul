package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Text-independent runtime representation of the closed CVES location_ref union. */
public sealed interface EventLocationRef permits
    EventLocationRef.Relative, EventLocationRef.Position, EventLocationRef.Resource {

    JsonObject toJson();

    static EventLocationRef fromJson(JsonElement encoded) {
        if (encoded == null || !encoded.isJsonObject()) {
            throw new IllegalArgumentException("location_ref는 객체여야 합니다.");
        }
        JsonObject value = encoded.getAsJsonObject();
        String kind = requiredString(value, "kind");
        return switch (kind) {
            case "relative" -> new Relative(
                requiredNumber(value, "x"),
                requiredNumber(value, "y"),
                requiredNumber(value, "z")
            );
            case "position" -> new Position(
                requiredString(value, "dimension"),
                requiredNumber(value, "x"),
                requiredNumber(value, "y"),
                requiredNumber(value, "z"),
                optionalFloat(value, "yaw"),
                optionalFloat(value, "pitch")
            );
            case "anchor", "settlement", "route", "dimension", "space" ->
                new Resource(
                    Resource.Kind.valueOf(kind.toUpperCase(Locale.ROOT)),
                    requiredString(value, "resource_id"),
                    optionalString(value, "anchor")
                );
            default -> throw new IllegalArgumentException(
                "지원하지 않는 location_ref kind입니다: " + kind
            );
        };
    }

    record Relative(double x, double y, double z) implements EventLocationRef {
        public Relative {
            finite(x, "x");
            finite(y, "y");
            finite(z, "z");
        }

        @Override
        public JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("kind", "relative");
            value.addProperty("x", x);
            value.addProperty("y", y);
            value.addProperty("z", z);
            return value;
        }
    }

    record Position(
        String dimension, double x, double y, double z, Float yaw, Float pitch
    ) implements EventLocationRef {
        public Position {
            resource(dimension, "dimension");
            finite(x, "x");
            finite(y, "y");
            finite(z, "z");
            if (yaw != null) finite(yaw, "yaw");
            if (pitch != null) finite(pitch, "pitch");
        }

        @Override
        public JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("kind", "position");
            value.addProperty("dimension", dimension);
            value.addProperty("x", x);
            value.addProperty("y", y);
            value.addProperty("z", z);
            if (yaw != null) value.addProperty("yaw", yaw);
            if (pitch != null) value.addProperty("pitch", pitch);
            return value;
        }
    }

    record Resource(Kind kind, String resourceId, String anchor) implements EventLocationRef {
        public enum Kind { ANCHOR, SETTLEMENT, ROUTE, DIMENSION, SPACE }

        public Resource {
            Objects.requireNonNull(kind, "kind");
            resource(resourceId, "resourceId");
            if (anchor != null && anchor.isBlank()) {
                throw new IllegalArgumentException("anchor는 비어 있을 수 없습니다.");
            }
        }

        @Override
        public JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("kind", kind.name().toLowerCase(java.util.Locale.ROOT));
            value.addProperty("resource_id", resourceId);
            if (anchor != null) value.addProperty("anchor", anchor);
            return value;
        }
    }

    private static void finite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " 좌표는 유한한 숫자여야 합니다.");
        }
    }

    private static void resource(String value, String name) {
        if (value == null || ResourceLocation.tryParse(value) == null) {
            throw new IllegalArgumentException(name + "는 리소스 ID여야 합니다: " + value);
        }
    }

    private static String requiredString(JsonObject value, String name) {
        JsonElement element = value.get(name);
        if (element == null || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("location_ref " + name + " 문자열이 필요합니다.");
        }
        return element.getAsString();
    }

    private static String optionalString(JsonObject value, String name) {
        JsonElement element = value.get(name);
        if (element == null || element.isJsonNull()) return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("location_ref " + name + " 문자열이 필요합니다.");
        }
        return element.getAsString();
    }

    private static double requiredNumber(JsonObject value, String name) {
        JsonElement element = value.get(name);
        if (element == null || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("location_ref " + name + " 숫자가 필요합니다.");
        }
        return element.getAsDouble();
    }

    private static Float optionalFloat(JsonObject value, String name) {
        JsonElement element = value.get(name);
        if (element == null || element.isJsonNull()) return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("location_ref " + name + " 숫자가 필요합니다.");
        }
        return element.getAsFloat();
    }
}
