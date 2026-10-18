package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruLogMode;

import java.awt.Color;
import java.util.EnumMap;
import java.util.Map;

/**
 * How each of the nine {@link NaruLogMode} values is presented. The REPL renders these as nine
 * ANSI gutter colours and nothing else; here the mode decides the label, whether the body is
 * markdown or fixed-width, whether it is collapsed by default, and whether it is hidden until
 * the user asks for noise.
 * <p>
 * v1 requirement: every mode has a defined rendering (plan §6).
 */
public record BlockStyle(String label,
                         Color gutter,
                         Color foreground,
                         boolean markdown,
                         boolean monospace,
                         boolean collapsedByDefault,
                         boolean verboseOnly) {

    private static final Map<NaruLogMode, BlockStyle> STYLES = new EnumMap<>(NaruLogMode.class);

    static {
        // The two things the user came for, and §6's "markdown for agent answers" lands here: the
        // flag is what {@code MarkdownRenderer} reads, so flipping it is the whole change — the
        // parser ({@code MarkdownHtml}) and the cache were built and measured in Phase 1, and the
        // plain-text streaming tail is untouched.
        //
        // The library question from §4.3 is still answered the same way: neither
        // {@code thevpc-common-md} nor {@code nmarkdown} can emit HTML ({@code DefaultMdWriter}
        // writes markdown back out; the others emit AsciiDoc and Docusaurus) and adding a
        // third-party parser to a Nuts application is a packaging decision, so the subset that
        // actually appears in agent answers — fenced code, headings, lists, quotes, hr, inline
        // code/bold/italic/links — is converted here and everything else passes through escaped.
        STYLES.put(NaruLogMode.AGENT_RESPONSE, style("Agent", new Color(0x2E7D32), true, false, false, false));
        // the raw model turn stays verbatim: it is the noisy, verbose-only view of the same text,
        // and a second rendering of it would be a second place for the parser to disagree with itself
        STYLES.put(NaruLogMode.MODEL_RESPONSE, style("Model", new Color(0x455A64), false, false, true, true));
        // thinking is long, noisy and rarely the point: collapse it, keep the elapsed summary
        STYLES.put(NaruLogMode.MODEL_THINKING, style("Thinking", new Color(0x7B1FA2), false, false, true, false));
        // tool output the REPL truncates to 300 chars: full, fixed-width, scrollable
        STYLES.put(NaruLogMode.SCRIPT, style("Output", new Color(0x00695C), false, true, false, false));
        STYLES.put(NaruLogMode.RAW, style("Raw", new Color(0x37474F), false, true, true, true));
        // scheduler and progress are the reason a verbose toggle exists
        STYLES.put(NaruLogMode.PROGRESS, style("Progress", new Color(0xEF6C00), false, false, true, true));
        STYLES.put(NaruLogMode.SCHEDULER, style("Scheduler", new Color(0x607D8B), false, true, true, true));
        STYLES.put(NaruLogMode.DEBUG, style("Debug", new Color(0x795548), false, true, true, true));
        STYLES.put(NaruLogMode.TRACE, style("Trace", new Color(0x9E9E9E), false, true, true, true));
    }

    public static BlockStyle of(NaruLogMode mode) {
        BlockStyle s = STYLES.get(mode);
        return s != null ? s : style(mode.name(), Color.GRAY, false, false, true, true);
    }

    /**
     * A tool card is not a log mode: it is a block the host built from an E1 lifecycle event, and
     * it borrows {@code SCRIPT} only so the verbose filter leaves it alone. Its own style says
     * "Tool" and draws it fixed-width, which is what a JSON result wants.
     */
    public static final BlockStyle TOOL = style("Tool", new Color(0x0277BD), false, true, false, false);

    public static BlockStyle forBlock(TranscriptBlock b) {
        return b != null && b.isToolCard() ? TOOL : of(b == null ? null : b.mode());
    }

    private static BlockStyle style(String label, Color gutter, boolean markdown, boolean monospace,
                                    boolean collapsed, boolean verboseOnly) {
        // foreground follows the gutter hue so a dark PLAF still reads; the widget tints further
        return new BlockStyle(label, gutter, gutter.darker(), markdown, monospace, collapsed, verboseOnly);
    }
}
