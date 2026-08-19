package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Renders the deliberately non-executable CVES dialogue template language. */
public final class EventTextRenderer {
    @FunctionalInterface
    public interface ResourceNameResolver {
        String resolve(String resourceId, String language);
    }

    private final ResourceNameResolver resourceNames;

    public EventTextRenderer(ResourceNameResolver resourceNames) {
        this.resourceNames = Objects.requireNonNull(resourceNames, "resourceNames");
    }

    public String render(
        JsonElement text,
        Map<String, JsonElement> locals,
        String language
    ) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(locals, "locals");
        String normalizedLanguage = normalizeLanguage(language);
        String template = selectTemplate(text, normalizedLanguage);
        StringBuilder output = new StringBuilder(template.length());
        int cursor = 0;
        while (cursor < template.length()) {
            int start = template.indexOf("${", cursor);
            if (start < 0) {
                output.append(template, cursor, template.length());
                break;
            }
            output.append(template, cursor, start);
            int end = template.indexOf('}', start + 2);
            if (end < 0) {
                throw templateError("템플릿 참조를 닫는 '}'가 필요합니다.", start);
            }
            String body = template.substring(start + 2, end);
            if (body.indexOf('$') >= 0 || body.indexOf('{') >= 0) {
                throw templateError("템플릿 안에는 중첩된 표현식을 사용할 수 없습니다.", start);
            }
            output.append(renderReference(body, locals, normalizedLanguage, start));
            cursor = end + 1;
        }
        return output.toString();
    }

    private String renderReference(
        String body,
        Map<String, JsonElement> locals,
        String language,
        int offset
    ) {
        String[] pieces = body.split("\\|", -1);
        if (pieces.length == 0 || pieces[0].isBlank()) {
            throw templateError("템플릿 변수 경로가 필요합니다.", offset);
        }
        String[] path = pieces[0].split("\\.", -1);
        for (String part : path) {
            if (!identifier(part)) {
                throw templateError("올바르지 않은 템플릿 경로입니다: " + pieces[0], offset);
            }
        }
        TemplateValue value = resolvePath(path, locals, language, offset);
        for (int index = 1; index < pieces.length; index++) {
            String filter = pieces[index];
            if (filter.isEmpty()) {
                throw templateError("빈 템플릿 필터는 사용할 수 없습니다.", offset);
            }
            int separator = filter.indexOf(':');
            String name = separator < 0 ? filter : filter.substring(0, separator);
            String argument = separator < 0 ? null : filter.substring(separator + 1);
            if (!identifier(name) || (separator >= 0 && argument.isEmpty())) {
                throw templateError("올바르지 않은 템플릿 필터입니다: " + filter, offset);
            }
            value = applyFilter(value, name, argument, language, offset);
        }
        return stringify(value, offset);
    }

    private TemplateValue resolvePath(
        String[] path,
        Map<String, JsonElement> locals,
        String language,
        int offset
    ) {
        JsonElement value = locals.get(path[0]);
        if (value == null) {
            throw templateError("정의되지 않은 템플릿 변수입니다: " + path[0], offset);
        }
        for (int index = 1; index < path.length; index++) {
            String member = path[index];
            if (value.isJsonObject() && value.getAsJsonObject().has(member)) {
                value = value.getAsJsonObject().get(member);
                continue;
            }
            if (member.equals("name") && value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                String idField = object.has("species_id") ? "species_id"
                    : object.has("resource_id") ? "resource_id" : null;
                if (idField != null) {
                    JsonElement resource = object.get(idField);
                    if (!resource.isJsonPrimitive()
                        || !resource.getAsJsonPrimitive().isString()) {
                        throw templateError(idField + "는 문자열이어야 합니다.", offset);
                    }
                    return TemplateValue.text(
                        resourceNames.resolve(resource.getAsString(), language)
                    );
                }
            }
            throw templateError("템플릿 값에 필드가 없습니다: " + member, offset);
        }
        return TemplateValue.json(value);
    }

    private TemplateValue applyFilter(
        TemplateValue value,
        String filter,
        String argument,
        String language,
        int offset
    ) {
        return switch (filter) {
            case "fallback" -> {
                requireArgument(filter, argument, offset);
                yield value.isNull() ? TemplateValue.text(argument) : value;
            }
            case "name" -> {
                requireNoArgument(filter, argument, offset);
                yield TemplateValue.text(resourceNames.resolve(stringify(value, offset), language));
            }
            case "number" -> {
                requireNoArgument(filter, argument, offset);
                yield TemplateValue.text(formatNumber(value, language, offset));
            }
            case "josa" -> {
                requireArgument(filter, argument, offset);
                yield TemplateValue.text(appendJosa(stringify(value, offset), argument, offset));
            }
            default -> throw templateError("지원하지 않는 템플릿 필터입니다: " + filter, offset);
        };
    }

    private static String selectTemplate(JsonElement text, String language) {
        if (!text.isJsonObject()) {
            throw new EventRuntimeException("text IR은 객체여야 합니다.");
        }
        JsonObject object = text.getAsJsonObject();
        String kind = requiredString(object, "kind");
        if (kind.equals("literal")) {
            return requiredString(object, "value");
        }
        if (!kind.equals("localized")) {
            throw new EventRuntimeException("지원하지 않는 text kind입니다: " + kind);
        }
        JsonElement entriesValue = object.get("entries");
        if (entriesValue == null || !entriesValue.isJsonArray()
            || entriesValue.getAsJsonArray().isEmpty()) {
            throw new EventRuntimeException("localized text에는 항목이 필요합니다.");
        }
        JsonArray entries = entriesValue.getAsJsonArray();
        List<String> preference = new ArrayList<>();
        preference.add(language);
        if (!language.equals("ko_kr")) preference.add("ko_kr");
        if (!language.equals("en_us")) preference.add("en_us");
        for (String preferred : preference) {
            for (JsonElement entryValue : entries) {
                JsonObject entry = entryValue.getAsJsonObject();
                if (normalizeLanguage(requiredString(entry, "language")).equals(preferred)) {
                    return requiredString(entry, "value");
                }
            }
        }
        return requiredString(entries.get(0).getAsJsonObject(), "value");
    }

    private static String formatNumber(TemplateValue value, String language, int offset) {
        JsonElement json = value.json();
        if (json == null || !json.isJsonPrimitive()
            || !json.getAsJsonPrimitive().isNumber()) {
            throw templateError("number 필터에는 숫자 값이 필요합니다.", offset);
        }
        BigDecimal number = json.getAsBigDecimal();
        NumberFormat format = NumberFormat.getNumberInstance(locale(language));
        format.setGroupingUsed(true);
        format.setMaximumFractionDigits(Math.max(0, number.stripTrailingZeros().scale()));
        return format.format(number);
    }

    private static String appendJosa(String value, String pair, int offset) {
        String[] particles = pair.split("/", -1);
        if (particles.length != 2 || particles[0].isEmpty() || particles[1].isEmpty()) {
            throw templateError("조사는 '받침형/무받침형'이어야 합니다: " + pair, offset);
        }
        return value + (hasFinalConsonant(value) ? particles[0] : particles[1]);
    }

    static boolean hasFinalConsonant(String value) {
        for (int index = value.length(); index > 0;) {
            int codePoint = value.codePointBefore(index);
            index -= Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) continue;
            if (codePoint >= 0xAC00 && codePoint <= 0xD7A3) {
                return (codePoint - 0xAC00) % 28 != 0;
            }
            return false;
        }
        return false;
    }

    private static String stringify(TemplateValue value, int offset) {
        if (value.text() != null) return value.text();
        JsonElement json = value.json();
        if (json == null || json.isJsonNull()) {
            throw templateError("null 값에는 fallback 필터가 필요합니다.", offset);
        }
        if (!json.isJsonPrimitive()) {
            throw templateError("객체나 배열은 대사에 직접 출력할 수 없습니다.", offset);
        }
        if (json.getAsJsonPrimitive().isBoolean()) {
            return Boolean.toString(json.getAsBoolean());
        }
        return json.getAsString();
    }

    private static void requireArgument(String filter, String argument, int offset) {
        if (argument == null || argument.isEmpty()) {
            throw templateError(filter + " 필터에는 인자가 필요합니다.", offset);
        }
    }

    private static void requireNoArgument(String filter, String argument, int offset) {
        if (argument != null) {
            throw templateError(filter + " 필터는 인자를 받지 않습니다.", offset);
        }
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive().isString()) {
            throw new EventRuntimeException("문자열 필드가 필요합니다: " + name);
        }
        return value.getAsString();
    }

    private static boolean identifier(String value) {
        if (value.isEmpty() || !(Character.isLetter(value.charAt(0)) || value.charAt(0) == '_')) {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isLetterOrDigit(character) && character != '_') return false;
        }
        return true;
    }

    private static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) return "en_us";
        return language.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static Locale locale(String language) {
        return Locale.forLanguageTag(language.replace('_', '-'));
    }

    private static EventRuntimeException templateError(String message, int offset) {
        return new EventRuntimeException(message + " (template offset " + offset + ")");
    }

    private record TemplateValue(JsonElement json, String text) {
        boolean isNull() {
            return text == null && (json == null || json.isJsonNull());
        }

        static TemplateValue json(JsonElement value) {
            return new TemplateValue(value == null ? null : value.deepCopy(), null);
        }

        static TemplateValue text(String value) {
            return new TemplateValue(null, Objects.requireNonNull(value, "value"));
        }
    }
}
