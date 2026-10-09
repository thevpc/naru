package net.thevpc.naru.ext.skills;

import net.thevpc.nuts.io.NPath;

import java.util.Objects;

/**
 * One place skills are read from, in the ordered root model (WP6).
 * <p>
 * A root is a directory that holds either flat {@code <name>.md} files or
 * {@code <name>/SKILL.md} folders. Roots are ordered by {@link #precedence()} (lower is
 * stronger) so that resolution is a simple "first copy of a name wins". NARU-native roots
 * always have a lower precedence than foreign ones, which is how "NARU-native always
 * wins" falls out without a special case.
 * <p>
 * Foreign roots are read only when {@link #requiresTrust()} is true and the root has been
 * trusted; a root that needs trust but has not been trusted is still listed (marked) but
 * contributes no skills.
 */
public final class NaruSkillRoot {
    private final NaruSkillRootKind kind;
    private final NPath path;
    private final String label;
    private final int precedence;
    private final boolean trusted;

    public NaruSkillRoot(NaruSkillRootKind kind, NPath path, String label, int precedence, boolean trusted) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.path = path;
        this.label = label == null ? kind.family() : label;
        this.precedence = precedence;
        this.trusted = trusted || !kind.foreign();
    }

    public NaruSkillRootKind kind() {
        return kind;
    }

    public NPath path() {
        return path;
    }

    /** The concrete origin family of a foreign root ({@code claude}, {@code agents},
     * {@code opencode}); {@code naru} for native roots. */
    public String label() {
        return label;
    }

    /** Lower is stronger. Native roots are {@code < 100}, foreign roots {@code >= 100}. */
    public int precedence() {
        return precedence;
    }

    /** True when this root is foreign and therefore opt-in. */
    public boolean requiresTrust() {
        return kind.foreign();
    }

    /** True when the root may be read: native roots always, foreign roots once trusted. */
    public boolean trusted() {
        return trusted;
    }

    public NaruSkillRoot asTrusted() {
        return trusted ? this : new NaruSkillRoot(kind, path, label, precedence, true);
    }

    /** True when the root directory exists on disk. */
    public boolean exists() {
        return path != null && path.isDirectory();
    }

    @Override
    public String toString() {
        return kind.id() + ":" + path + (requiresTrust() ? (trusted ? " (trusted)" : " (untrusted)") : "");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NaruSkillRoot that)) {
            return false;
        }
        return Objects.equals(kind, that.kind) && Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, path);
    }
}
