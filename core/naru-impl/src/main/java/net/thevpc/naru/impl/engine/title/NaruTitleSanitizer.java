package net.thevpc.naru.impl.engine.title;

import net.thevpc.naru.api.agent.NaruSessionNames;
import net.thevpc.naru.api.model.NaruThinkingTags;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns whatever a model answered into a session title, or decides that it did not answer.
 * <p>
 * Pure and static on purpose: this is the part of titling a model can defeat with
 * punctuation, and the only way to know it handles that is to test it against the answers
 * models actually give, without a model.
 * <p>
 * The order of the steps is the order the answers arrive in. Think blocks go first, because
 * a reasoning model puts its chain of thought above the title and the first non-empty line
 * is only the title once the reasoning is gone. Prefixes go after the line is chosen,
 * because a "Title:" prefix only ever appears at the start of a line. Trailing punctuation
 * goes last, because a period or a quote at the end is what a model writes when it answered
 * in a sentence.
 */
public final class NaruTitleSanitizer {

    /**
     * Delimiters stripped when the model declares no pair of its own. These are the
     * spellings in the wild rather than one vendor's: the reasoning tags several servers
     * emit, and the older pair some templates still use.
     */
    private static final String[][] DEFAULT_BLOCK_PAIRS = {
            {"<think>", "</think>"},
            {"<thinking>", "</thinking>"},
            {"<reasoning>", "</reasoning>"},
    };

    /** "Title:", "Session title:", "Name:", with or without a markdown bold or a dash. */
    private static final Pattern PREFIX = Pattern.compile(
            "^\\s*[#*>-]*\\s*(?:title|session\\s+title|name|subject|topic)\\s*[:\\-]\\s*",
            Pattern.CASE_INSENSITIVE);

    /** A markdown heading marker, when a model answered with a heading anyway. */
    private static final Pattern HEADING = Pattern.compile("^\\s*#{1,6}\\s*");

    /**
     * Punctuation that closes a sentence rather than a title. A question mark is included
     * because "How do I resize a partition?" is a title, but "How do I resize a partition"
     * reads better in a sidebar and the question mark costs nothing to drop.
     */
    private static final String TRAILING = " \t.,;:!?-–—\"'`*";

    private NaruTitleSanitizer() {
    }

    /**
     * @param raw   the whole text a model returned, thinking included
     * @param tags  the model's configured thinking delimiters, or null for the defaults
     * @return the title, or null when nothing usable is left. Null rather than an exception
     *         because "this model gave me nothing" is one failure among several the titler
     *         reports per model, and it must be able to move on to the next one.
     */
    public static String sanitize(String raw, NaruThinkingTags tags) {
        if (raw == null) {
            return null;
        }
        String text = stripThinkBlocks(raw, tags);
        for (String line : text.split("\r\n|\r|\n")) {
            String title = asTitle(line);
            if (title != null) {
                return capAtWordBoundary(title, NaruSessionNames.MAX_LENGTH);
            }
        }
        return null;
    }

    /**
     * One line as a title, or null when it is not an answer.
     *
     * <p>A model that was told "the title only" and still wrote a preamble put the title on the
     * <em>second</em> line, so a line that ends in a colon is an introduction and gets skipped.
     * The prefix forms ("Title:", "Session title:") are the other way the same intention
     * arrives, and those are stripped rather than skipped because the title is on that line.
     */
    private static String asTitle(String line) {
        String s = line.strip();
        if (s.isEmpty() || s.endsWith(":")) {
            return null;
        }
        s = PREFIX.matcher(s).replaceFirst("");
        s = HEADING.matcher(s).replaceFirst("");
        s = s.replaceAll("[#*`]", "");
        s = s.replaceAll("\\s+", " ");
        s = trimOuterQuotes(s);
        s = s.strip();
        while (!s.isEmpty() && TRAILING.indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1);
        }
        while (!s.isEmpty() && isQuote(s.charAt(0))) {
            s = s.substring(1);
        }
        s = s.strip();
        return s.isEmpty() ? null : s;
    }

    /**
     * Removes reasoning before any of it can be mistaken for the answer. An unterminated
     * opening tag swallows the rest of the text: a model that thinks out loud and then
     * forgets to close is offering nothing to title from, and reading the tail as a title
     * would put its inner monologue in the sidebar.
     */
    static String stripThinkBlocks(String raw, NaruThinkingTags tags) {
        List<String[]> pairs = new ArrayList<>();
        if (tags != null && tags.isEnabled()) {
            pairs.add(new String[]{tags.openTag(), tags.closeTag()});
        }
        for (String[] d : DEFAULT_BLOCK_PAIRS) {
            pairs.add(d);
        }
        String out = raw;
        for (String[] pair : pairs) {
            String open = pair[0];
            String close = pair[1];
            if (open == null || close == null) {
                continue;
            }
            int i = out.indexOf(open);
            while (i >= 0) {
                int j = out.indexOf(close, i + open.length());
                if (j < 0) {
                    out = out.substring(0, i);
                    break;
                }
                out = out.substring(0, i) + " " + out.substring(j + close.length());
                i = out.indexOf(open);
            }
        }
        return out;
    }

    private static String trimOuterQuotes(String line) {
        String s = line;
        while (s.length() >= 2 && isQuote(s.charAt(0)) && isQuote(s.charAt(s.length() - 1))
                && s.charAt(0) == s.charAt(s.length() - 1)) {
            s = s.substring(1, s.length() - 1).strip();
        }
        while (!s.isEmpty() && isQuote(s.charAt(0))) {
            s = s.substring(1);
        }
        while (!s.isEmpty() && isQuote(s.charAt(s.length() - 1))) {
            s = s.substring(0, s.length() - 1);
        }
        return s.strip();
    }

    private static boolean isQuote(char c) {
        return c == '"' || c == '\'' || c == '`' || c == '\u201c' || c == '\u201d'
                || c == '\u2018' || c == '\u2019' || c == '\u00ab' || c == '\u00bb';
    }

    /**
     * Cut to the limit at a word boundary. A title that ends mid-word looks like corruption,
     * and a session name is a label rather than a document: losing the last word is the
     * lesser failure. A single unbreakable token longer than the limit is cut anyway, because
     * an empty result would fail the whole call over one long word.
     */
    static String capAtWordBoundary(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        int cut = value.lastIndexOf(' ', max);
        if (cut <= 0) {
            cut = max;
        }
        String s = value.substring(0, cut).strip();
        while (!s.isEmpty() && TRAILING.indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
