package dev.buizz.cobbleventure.adventure.event.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/** Splits resolved plain text into stable pages using the active font's line count. */
final class DialoguePaginator {
    private DialoguePaginator() {}

    static List<String> paginate(
        String text, int maximumLines, ToIntFunction<String> lineCount
    ) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(lineCount, "lineCount");
        if (maximumLines < 1) throw new IllegalArgumentException("maximumLines는 1 이상이어야 합니다.");
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isEmpty()) return List.of("");

        List<String> pages = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            while (start < normalized.length() && normalized.charAt(start) == '\n') start++;
            if (start >= normalized.length()) break;
            int low = start;
            int high = normalized.length();
            while (low < high) {
                int middle = codePointSafeMiddle(normalized, low, high);
                if (middle <= low) middle = normalized.offsetByCodePoints(low, 1);
                if (Math.max(1, lineCount.applyAsInt(normalized.substring(start, middle)))
                    <= maximumLines) {
                    low = middle;
                } else {
                    high = previousCodePoint(normalized, middle);
                }
            }
            int end = Math.max(normalized.offsetByCodePoints(start, 1), low);
            if (end < normalized.length()) {
                int wordBreak = lastWhitespace(normalized, start, end);
                if (wordBreak > start + (end - start) / 2) end = wordBreak;
            }
            String page = normalized.substring(start, end).stripTrailing();
            pages.add(page.isEmpty() ? normalized.substring(start, end) : page);
            start = end;
            while (start < normalized.length() && Character.isWhitespace(normalized.codePointAt(start))) {
                start = normalized.offsetByCodePoints(start, 1);
            }
        }
        return pages.isEmpty() ? List.of("") : List.copyOf(pages);
    }

    private static int codePointSafeMiddle(String value, int low, int high) {
        int middle = low + (high - low + 1) / 2;
        if (middle < value.length() && middle > 0
            && Character.isLowSurrogate(value.charAt(middle))) {
            middle++;
        }
        return Math.min(middle, high);
    }

    private static int previousCodePoint(String value, int index) {
        if (index <= 0) return 0;
        return value.offsetByCodePoints(index, -1);
    }

    private static int lastWhitespace(String value, int start, int end) {
        int cursor = end;
        while (cursor > start) {
            int previous = value.offsetByCodePoints(cursor, -1);
            if (Character.isWhitespace(value.codePointAt(previous))) return previous;
            cursor = previous;
        }
        return -1;
    }
}
