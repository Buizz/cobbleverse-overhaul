"""Guard the standalone and embedded CVES editor's shared light theme contract."""
from pathlib import Path
import re
import unittest


WEB = Path(__file__).parents[1] / "web"


def luminance(color: str) -> float:
    channels = [int(color[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    linear = [c / 12.92 if c <= .04045 else ((c + .055) / 1.055) ** 2.4 for c in channels]
    return sum(c * weight for c, weight in zip(linear, (.2126, .7152, .0722)))


class CvesWebThemeTests(unittest.TestCase):
    def test_tree_connectors_branch_to_each_row_and_stop_at_last_child(self):
        css = (WEB / "cves-editor.css").read_text(encoding="utf-8")
        children = css.split(".node-children {", 1)[1].split("}", 1)[0]
        self.assertIn("display: grid", children)
        self.assertNotIn("border-left", children)
        self.assertIn(".node-children > ::after", css)
        self.assertIn("border-top: 2px solid var(--studio-line)", css)
        self.assertIn(".node-children > :last-child::before { bottom: auto; height: 22px; }", css)
        self.assertIn("pointer-events: none", css)

    def test_multiline_and_expanded_native_controls_follow_the_theme(self):
        css = (WEB / "form-controls.css").read_text(encoding="utf-8")
        # Localized fields are direct children of .locale-entry, not .field.
        self.assertIn("body :is(textarea, .field textarea) {", css)
        self.assertIn("body .source-panel textarea {", css)
        self.assertIn("scrollbar-color: var(--control-muted)", css)
        self.assertIn("@supports (appearance: base-select)", css)
        self.assertIn("select:not([multiple]):not([size])::picker(select)", css)
        self.assertIn("select:not([multiple]):not([size]) option:checked", css)
        self.assertIn("select option[hidden]", css)
        self.assertIn("select option:disabled", css)
        self.assertIn("max-block-size: min(360px, 50dvh)", css)

    def test_all_web_pages_load_shared_controls_after_page_styles(self):
        for page in WEB.glob("*.html"):
            with self.subTest(page=page.name):
                markup = page.read_text(encoding="utf-8")
                styles = re.findall(r'<link[^>]+rel="stylesheet"[^>]+href="([^"]+)"', markup)
                self.assertEqual("/form-controls.css", styles[-1])
        server = (WEB.parent / "content_manager.py").read_text(encoding="utf-8")
        self.assertIn('"/form-controls.css": web_root / "form-controls.css"', server)

    def test_shared_controls_preserve_layout_and_native_listboxes(self):
        css = (WEB / "form-controls.css").read_text(encoding="utf-8")
        textarea = css.split("body :is(textarea, .field textarea) {", 1)[1].split("}", 1)[0]
        self.assertNotRegex(textarea, r"(?<!-)\b(?:width|height|min-height):")
        self.assertNotIn(".cves-workspace", css)
        self.assertIn("select:not([multiple]):not([size]) option {", css)
        self.assertIn("font-family: var(--font-mono)", css)

    def test_editor_uses_shared_tokens_without_private_palette(self):
        css = (WEB / "cves-editor.css").read_text(encoding="utf-8")
        shell = (WEB / "studio-tool-shell.css").read_text(encoding="utf-8")
        typography = (WEB / "typography.css").read_text(encoding="utf-8")
        declared = set(re.findall(r"(--[\w-]+)\s*:", shell + typography))
        used = set(re.findall(r"var\((--[\w-]+)\)", css))
        self.assertTrue(used)
        self.assertEqual(set(), used - declared)
        self.assertNotRegex(css, r"#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(")
        self.assertNotIn("color-scheme: dark", css)

    def test_text_surface_pairs_have_normal_text_contrast(self):
        shell = (WEB / "studio-tool-shell.css").read_text(encoding="utf-8")
        colors = dict(re.findall(r"--studio-([\w-]+):\s*(#[0-9a-fA-F]{6});", shell))
        pairs = [(text, surface) for text in ("text", "label", "muted")
                 for surface in ("panel", "panel-soft", "selected", "input")]
        pairs += [(role, f"{role}-bg") for role in ("success", "warning", "danger", "info", "special")]
        pairs += [("code-text", "code-bg")]
        for foreground, background in pairs:
            with self.subTest(foreground=foreground, background=background):
                a, b = sorted((luminance(colors[foreground]), luminance(colors[background])))
                self.assertGreaterEqual((b + .05) / (a + .05), 4.5)

    def test_embedded_editor_keeps_document_controls_and_responsive_layout(self):
        markup = (WEB / "cves.html").read_text(encoding="utf-8")
        css = (WEB / "cves-editor.css").read_text(encoding="utf-8")
        self.assertLess(markup.index('/studio-tool-shell.css'), markup.index('/cves-editor.css'))
        self.assertIn('class="cves-workspace"', markup)
        self.assertRegex(css, r"html\.is-embedded body\.cves-workspace > \.studio-header\s*\{\s*display: flex;")
        self.assertIn('id="save-script"', markup)
        self.assertIn('id="validate-ast"', markup)
        self.assertIn("@media (max-width: 1190px)", css)
        self.assertIn("@media (max-width: 720px)", css)
        self.assertIn("white-space: pre-wrap", css)
        self.assertNotRegex(css, r"font-size:\s*(?:[0-9]|1[01])px")


if __name__ == "__main__":
    unittest.main()
