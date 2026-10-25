package net.thevpc.naru.desktop.ui;

import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.formdev.flatlaf.util.FontUtils;

import java.awt.Font;

/**
 * The two typefaces the cockpit ships with.
 * <p>
 * Resolving a family by name assumes the host has it installed, which is exactly what makes a
 * desktop app look like a different product on every machine: the UI face is Segoe UI on Windows,
 * SF on macOS and whatever the distribution picked on Linux, and a code block silently loses its
 * monospaced alignment wherever no mono face is configured. So Naru bundles both faces:
 * <ul>
 *     <li>{@link #UI} — Inter, the window chrome and the conversation prose;</li>
 *     <li>{@link #MONO} — JetBrains Mono, the composer and every surface that shows data: code
 *     fences, inline code, tool output, diffs, logs and the tool/permission lists.</li>
 * </ul>
 * Both are SIL OFL 1.1: the FlatLaf font packs that carry them (and their licence) are declared in
 * the application pom, so they travel with the app instead of being looked up on the host.
 * <p>
 * <b>Why composite fonts.</b> A physical face has no glyph fallback in Java — an emoji, a CJK
 * caption or an Arabic session name would draw as a box. Every font this class hands out is
 * therefore built through {@link FontUtils#getCompositeFont}, which combines the bundled face with
 * the platform's own fallback chain. Latin text gets Inter or JetBrains Mono; everything else still
 * gets a real glyph. Swing's HTML engine reaches the same composite path through
 * {@code StyleContext.getFont}, so a CSS {@code font-family} is covered too.
 */
public final class Typefaces {

    /** The UI and conversation-prose face. */
    public static final String UI = FlatInterFont.FAMILY;
    /** The composer and data face. */
    public static final String MONO = FlatJetBrainsMonoFont.FAMILY;

    private static volatile boolean installed;

    private Typefaces() {
    }

    /**
     * Register both families with the font system. Idempotent, and safe before any component
     * exists: the font packs only install a lazy loader, so a face is read from the classpath the
     * first time something asks for it. Call this before the look and feel is set, because that is
     * when the platform font is replaced by {@link #UI}.
     */
    public static void install() {
        if (installed) {
            return;
        }
        synchronized (Typefaces.class) {
            if (installed) {
                return;
            }
            FlatInterFont.installLazy();
            FlatJetBrainsMonoFont.installLazy();
            // Load both faces now rather than on first use. The HTML engine resolves a CSS
            // `font-family` with a plain `new Font(name, …)`, which does not run FlatLaf's lazy
            // loader: a code fence rendered before any Swing component asked for the mono face
            // would silently be laid out in the platform default. Loading here is a few
            // milliseconds and makes the family available everywhere, in every order.
            FontUtils.loadFontFamily(UI);
            FontUtils.loadFontFamily(MONO);
            installed = true;
        }
    }

    /** {@link #UI} at {@code style} and {@code size}, with platform glyph fallback. */
    public static Font ui(int style, float size) {
        return composite(UI, style, size);
    }

    /** {@link #MONO} at {@code style} and {@code size}, with platform glyph fallback. */
    public static Font mono(int style, float size) {
        return composite(MONO, style, size);
    }

    /**
     * A composite font of {@code family}. The face is requested at 12 pt and then derived to the
     * wanted size, because the composite path takes an integer size while the UI ladder works in
     * fractional ratios — and {@code deriveFont} keeps the same fallback chain.
     */
    public static Font composite(String family, int style, float size) {
        install();
        return FontUtils.getCompositeFont(family, style, 12).deriveFont(Math.max(1f, size));
    }
}
