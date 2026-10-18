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
        // the two things the user came for.
        // markdown=false is the documented §4.3 deviation: neither thevpc-common-md nor
        // nmarkdown can emit HTML (only AsciiDoctor/Docusaurus/plain writers), so enabling the
        // flag here would render literal <p> tags through the Swing HTML reader. MarkdownHtml
        // is in place and fliping this boolean is the whole remaining step, which Phase 2 does
        // behind a comparison rather than as a silent default change.
        STYLES.put(NaruLogMode.AGENT_RESPONSE, style("Agent", new Color(0x2E7D32), false, false, false, false));
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
