package net.thevpc.naru.desktop.ui;

import org.junit.jupiter.api.Test;

import javax.swing.text.AttributeSet;
import javax.swing.text.html.CSS;
import javax.swing.text.html.StyleSheet;
import java.awt.Font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The typefaces travel with the app, and they keep drawing when the text leaves Latin.
 * <p>
 * The regression these hold down is quiet: asking for a bundled family before it is registered
 * returns the platform default, and asking for a physical font without the composite path draws
 * a box for every emoji or CJK character. Neither throws, so nothing else notices.
 */
class TypefacesTest {

    @Test
    void bundledFacesAreRegisteredUnderTheirOwnNames() {
        Typefaces.install();
        Font ui = Typefaces.ui(Font.PLAIN, 13);
        Font mono = Typefaces.mono(Font.PLAIN, 13);
        assertEquals(Typefaces.UI, ui.getFamily());
        assertEquals(Typefaces.MONO, mono.getFamily());
        assertNotEquals(ui.getFamily(), mono.getFamily());
        assertNotEquals(Font.DIALOG, ui.getFamily());
        assertNotEquals(Font.DIALOG, mono.getFamily());
    }

    @Test
    void compositeFacesFallBackToThePlatformForMissingGlyphs() {
        Typefaces.install();
        // no bundled coding or UI face carries CJK or emoji; a physical font would draw boxes
        String beyondLatin = "Naru → 你好 😀";
        assertTrue(Typefaces.ui(Font.PLAIN, 13).canDisplayUpTo(beyondLatin) < 0);
        assertTrue(Typefaces.mono(Font.PLAIN, 13).canDisplayUpTo(beyondLatin) < 0);
    }

    @Test
    void fractionalRungsKeepTheFamilyAndTheFallback() {
        Typefaces.install();
        Font scaled = Typefaces.mono(Font.BOLD, 11.7f);
        assertEquals(Typefaces.MONO, scaled.getFamily());
        assertEquals(Font.BOLD, scaled.getStyle());
        assertEquals(11.7f, scaled.getSize2D(), 0.01f);
        assertTrue(scaled.canDisplayUpTo("你好") < 0);
    }

    @Test
    void htmlResolvesTheBundledMonospaceFamily() {
        Typefaces.install();
        StyleSheet sheet = new StyleSheet();
        AttributeSet declaration = sheet.getDeclaration("font-family:" + Typefaces.MONO);
        assertEquals(Typefaces.MONO, String.valueOf(declaration.getAttribute(CSS.Attribute.FONT_FAMILY)));
        // the HTML engine goes through StyleContext, which builds a composite font: the family
        // resolves to the bundled face and still draws scripts the face does not carry
        Font resolved = sheet.getFont(declaration);
        assertEquals(Typefaces.MONO, resolved.getFamily());
        assertTrue(resolved.canDisplayUpTo("你好 😀") < 0);
    }
}
