package net.thevpc.naru.desktop.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Token;
import org.junit.jupiter.api.Test;

import javax.swing.UIManager;
import java.awt.Color;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plan editor's Markdown colours: the six heading levels must be distinguishable, and the hues
 * must move when the window moves from a dark look-and-feel to a light one.
 */
class MarkdownSyntaxTest {

    @Test
    void everyHeadingLevelGetsItsOwnTokenAndColour() {
        RSyntaxTextArea area = area("# one\n## two\n### three\n#### four\n##### five\n###### six\n");
        RSyntaxDocument document = (RSyntaxDocument) area.getDocument();

        Set<Integer> types = new HashSet<>();
        Set<Color> colours = new HashSet<>();
        for (int line = 0; line < 6; line++) {
            Token heading = firstPaintable(document.getTokenListForLine(line));
            assertEquals(MarkdownSyntax.headingType(line + 1), heading.getType(),
                    "line " + (line + 1) + " is a level-" + (line + 1) + " heading");
            types.add(heading.getType());
            colours.add(area.getForegroundForTokenType(heading.getType()));
        }

        assertEquals(6, types.size(), "the grammar must not collapse every level into one token");
        assertEquals(6, colours.size(), "each heading level is a different colour");
    }

    @Test
    void headingHuesFollowTheBackground() {
        RSyntaxTextArea area = area("## a heading\n");
        Color old = UIManager.getColor("Panel.background");
        try {
            UIManager.put("Panel.background", new Color(0x3C, 0x3F, 0x41));
            MarkdownSyntax.applyPalette(area);
            Color onDark = area.getForegroundForTokenType(MarkdownSyntax.HEADING_2);

            UIManager.put("Panel.background", new Color(0xF2, 0xF2, 0xF2));
            MarkdownSyntax.applyPalette(area);
            Color onLight = area.getForegroundForTokenType(MarkdownSyntax.HEADING_2);

            assertNotEquals(onLight, onDark, "a hue chosen for white must not be painted on grey");
            assertTrue(luminance(onDark) > luminance(onLight),
                    "the dark-theme heading has to be lighter than the light-theme one");
        } finally {
            if (old == null) {
                UIManager.getDefaults().remove("Panel.background");
            } else {
                UIManager.put("Panel.background", old);
            }
        }
    }

    @Test
    void bodyTextKeepsTheEditorForegroundAndHasNoHeadingToken() {
        RSyntaxTextArea area = area("plain prose with a # in the middle\n");
        Token first = firstPaintable(((RSyntaxDocument) area.getDocument()).getTokenListForLine(0));
        assertNotEquals(MarkdownSyntax.HEADING_1, first.getType());
        assertEquals(area.getForeground(), area.getForegroundForTokenType(first.getType()),
                "plain Markdown text is painted with the widget's own foreground");
    }

    private static RSyntaxTextArea area(String markdown) {
        RSyntaxTextArea area = TextAreas.plain(8, 30);
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
        MarkdownSyntax.install(area);
        area.setText(markdown);
        return area;
    }

    private static Token firstPaintable(Token token) {
        while (token != null && !token.isPaintable()) {
            token = token.getNextToken();
        }
        return token;
    }

    private static double luminance(Color colour) {
        return 0.299 * colour.getRed() + 0.587 * colour.getGreen() + 0.114 * colour.getBlue();
    }
}
