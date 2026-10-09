package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.spawn.NaruToolTagExpression;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * A named block of instructions loaded from markdown under
 * {@code <project>/.naru/skills} (public) or {@code <project>/.naru/local/skills} (private).
 * <p>
 * A value is an immutable <em>snapshot</em>: name, description, origin root, visibility,
 * base directory, the parsed front-matter map, the content hash of the file it was read
 * from, and the optional {@code requires} expression. Once built it does not re-read the
 * disk — {@code /skill reload} rebuilds it and {@code /skill doctor} compares its hash to
 * the current file.
 * <p>
 * Part of the optional {@code naru-skills} extension: the core neither knows this type nor
 * injects it into prompts.
 */
public interface NaruSkill {
    NaruVisibility getVisibility();

    String getName();

    /** True when both a public and a private copy define this name (private wins). */
    boolean isShadowed();

    NaruSkillLayout getLayout();

    /**
     * Where the content came from, for provenance in {@code /context} listings: the path of
     * the single file that supplied it (the private copy when one shadows a public one).
     */
    String getSourceName();

    /**
     * The skill root directory that supplied the content: {@code .../.naru/skills} for a
     * public skill, {@code .../.naru/local/skills} for a private one.
     */
    String getOriginRoot();

    /**
     * The root this copy was read from, with its kind, precedence and trust state (WP6).
     * Never null.
     */
    NaruSkillRoot getRoot();

    /** True when the supplying root is foreign (opt-in, untrusted by default). */
    default boolean isForeign() {
        return getRoot() != null && getRoot().kind().foreign();
    }

    /** True when the supplying root is readable (native, or a trusted foreign one). */
    default boolean isTrusted() {
        return getRoot() == null || getRoot().trusted();
    }

    /**
     * The directory holding the skill file: the skill root for a flat {@code <name>.md}, or
     * {@code <root>/<name>} for the open-standard folder form.
     */
    String getBaseDir();

    /**
     * The human-readable description: the front-matter {@code description} when present,
     * otherwise the flat file's first paragraph. Empty when neither exists.
     */
    String getDescription();

    /**
     * The parsed YAML front-matter, {@code ---}-delimited. Scalar values are strings, the
     * {@code metadata} value is a map from string to string, and keys NARU does not know
     * (including the standard's {@code license} and {@code compatibility}) survive as-is.
     * Unmodifiable; never null.
     */
    Map<String, Object> getFrontMatter();

    /**
     * Lowercase hex SHA-256 of the raw file bytes at snapshot time. {@code /skill doctor}
     * compares it to the current file's hash to report a silently changed skill.
     */
    String getContentHash();

    /**
     * The {@code allowed-tools} front-matter entries, whitespace-delimited, or empty.
     */
    Set<String> getAllowedTools();

    List<String> getLines();

    List<String> getLines(IntPredicate lineFilter);

    String getFormattedText();

    boolean isEmpty();

    /**
     * The tag expression this skill requires of the task using it, parsed from the
     * front-matter {@code requires} key (e.g. {@code requires: "fs & !write"}), or null.
     */
    NaruToolTagExpression getRequires();

    /** The positive tag names required by {@link #getRequires()}, empty when none. */
    Set<String> getRequiredTags();

    /**
     * Lenient-validation warnings raised while reading this skill (malformed front-matter,
     * missing {@code name} or {@code description}, a folder name that disagrees with the
     * declared one, ...). Never fatal: a warning means "loads, but look here".
     */
    List<String> getWarnings();
}