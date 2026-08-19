package dev.buizz.cobbleventure.adventure.event.client;

import java.util.List;
import java.util.Objects;

/** Tick-driven, Unicode-safe typewriter and page progression state. */
final class DialoguePlayback {
    enum AdvanceResult { REVEALED, NEXT_PAGE, COMPLETED }

    private final List<String> pages;
    private int pageIndex;
    private int revealedCodePoints;
    private int punctuationDelay;

    DialoguePlayback(List<String> pages) {
        Objects.requireNonNull(pages, "pages");
        if (pages.isEmpty() || pages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("대화 페이지가 필요합니다.");
        }
        this.pages = List.copyOf(pages);
    }

    void tick() {
        if (pageRevealed()) return;
        if (punctuationDelay > 0) {
            punctuationDelay--;
            return;
        }
        String page = currentPage();
        int codePoint = page.codePointAt(page.offsetByCodePoints(0, revealedCodePoints));
        revealedCodePoints++;
        punctuationDelay = delayFor(codePoint);
    }

    AdvanceResult advance() {
        if (!pageRevealed()) {
            revealPage();
            return AdvanceResult.REVEALED;
        }
        if (pageIndex + 1 < pages.size()) {
            pageIndex++;
            revealedCodePoints = 0;
            punctuationDelay = 0;
            return AdvanceResult.NEXT_PAGE;
        }
        return AdvanceResult.COMPLETED;
    }

    void revealPage() {
        revealedCodePoints = currentPage().codePointCount(0, currentPage().length());
        punctuationDelay = 0;
    }

    String visibleText() {
        String page = currentPage();
        return page.substring(0, page.offsetByCodePoints(0, revealedCodePoints));
    }

    String currentPage() {
        return pages.get(pageIndex);
    }

    boolean pageRevealed() {
        String page = currentPage();
        return revealedCodePoints >= page.codePointCount(0, page.length());
    }

    boolean lastPage() {
        return pageIndex + 1 == pages.size();
    }

    int pageNumber() {
        return pageIndex + 1;
    }

    int pageCount() {
        return pages.size();
    }

    private static int delayFor(int codePoint) {
        return switch (codePoint) {
            case '.', '!', '?', 0x3002, 0xFF01, 0xFF1F -> 3;
            case ',', ';', ':', 0x2026 -> 2;
            default -> 0;
        };
    }
}
