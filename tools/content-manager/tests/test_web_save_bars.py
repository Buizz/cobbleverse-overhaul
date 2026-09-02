"""Every document save action must live in a shared sticky heading."""
from html.parser import HTMLParser
from pathlib import Path
import unittest

WEB = Path(__file__).parents[1] / "web"
HEADINGS = {"panel-heading", "section-heading", "world-map-heading", "editor-head", "studio-header"}


class SaveMarkup(HTMLParser):
    def __init__(self, text):
        super().__init__()
        self.stack = []
        self.saves = []
        self.ids = []
        self.feed(text)

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        if attrs.get("id"):
            self.ids.append(attrs["id"])
        if tag == "button" and (attrs.get("id", "").startswith("save-") or attrs.get("id") == "save-script"):
            headers = [item for item in self.stack if HEADINGS.intersection(item[1].get("class", "").split()) or "data-save-bar" in item[1]]
            self.saves.append((attrs["id"], headers[-1] if headers else None))
        if tag not in {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}:
            self.stack.append((tag, attrs))

    def handle_endtag(self, tag):
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index][0] == tag:
                del self.stack[index:]
                break


class WebSaveBarTests(unittest.TestCase):
    def test_all_pages_load_common_save_bar_styles(self):
        for page in WEB.glob("*.html"):
            with self.subTest(page=page.name):
                self.assertIn('href="/save-bars.css"', page.read_text(encoding="utf-8"))
        server = (WEB.parent / "content_manager.py").read_text(encoding="utf-8")
        self.assertIn('"/save-bars.css": web_root / "save-bars.css"', server)

    def test_every_document_save_button_has_a_sticky_heading(self):
        total = 0
        for page in WEB.glob("*.html"):
            document = SaveMarkup(page.read_text(encoding="utf-8"))
            for button, heading in document.saves:
                with self.subTest(page=page.name, button=button):
                    self.assertIsNotNone(heading)
                    self.assertEqual(1, document.ids.count(button))
                total += 1
        self.assertGreaterEqual(total, 30)

    def test_nested_dungeon_plan_tracks_outer_heading_height(self):
        app = (WEB / "app.js").read_text(encoding="utf-8")
        css = (WEB / "save-bars.css").read_text(encoding="utf-8")
        self.assertIn("dungeonSaveHeaderObserver.observe(dungeonSaveHeader)", app)
        self.assertIn('--dungeon-save-bar-height', app)
        self.assertIn("var(--dungeon-save-bar-height, 0px)", css)

    def test_system_npc_card_save_action_is_in_header(self):
        app = (WEB / "app.js").read_text(encoding="utf-8")
        header = next(line for line in app.splitlines() if 'system-npc-lock${' in line)
        self.assertIn("<header data-save-bar>", header)
        self.assertIn('data-save-system-npc="${index}"', header)
        self.assertNotIn('<footer><button type="button" class="button secondary" data-edit-system-npc', app)

    def test_common_styles_fix_scroll_container_without_fixed_positioning(self):
        css = (WEB / "save-bars.css").read_text(encoding="utf-8")
        self.assertIn("position: sticky", css)
        self.assertIn("overflow: clip", css)
        self.assertNotIn("position: fixed", css)
        self.assertIn("@media (max-width: 760px)", css)
        self.assertIn("@media print", css)


if __name__ == "__main__":
    unittest.main()
