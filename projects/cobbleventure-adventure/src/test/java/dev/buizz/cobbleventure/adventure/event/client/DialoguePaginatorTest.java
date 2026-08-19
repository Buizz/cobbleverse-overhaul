package dev.buizz.cobbleventure.adventure.event.client;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DialoguePaginatorTest {
    @Test
    void wrapsIntoDeterministicPagesWithoutLosingWords() {
        List<String> pages = DialoguePaginator.paginate(
            "하나 둘 셋 넷 다섯 여섯", 2,
            value -> (value.length() + 5) / 6
        );

        assertTrue(pages.size() >= 2);
        assertEquals("하나 둘 셋 넷 다섯 여섯", String.join(" ", pages));
        assertEquals(
            pages,
            DialoguePaginator.paginate(
                "하나 둘 셋 넷 다섯 여섯", 2,
                value -> (value.length() + 5) / 6
            )
        );
    }

    @Test
    void preservesSupplementaryUnicodeAcrossSmallPages() {
        List<String> pages = DialoguePaginator.paginate(
            "가😀나다", 1, value -> value.codePointCount(0, value.length())
        );

        assertEquals("가😀나다", String.join("", pages));
    }
}
