package net.thevpc.naru.ext.skills;

import net.thevpc.nuts.io.NPath;

import java.util.Objects;

/**
 * One place skills are read from, in the ordered root model (WP6).
 * <p>
 * A root is a directory that holds {@code <name>/SKILL.md} folders. Roots are ordered by
 * {@link #precedence()} (lower is
 * stronger) so that resolution is a simple "first copy of a name wins". NARU-native roots
 * always have a lower precedence than foreign ones, which is how "NARU-native always
 * wins" falls out without a special case.
 * <p>
 * Foreign roots are opt-in and carry a {@link NaruSkillTrustLevel}: they are read only
 * when the level is at least {@link NaruSkillTrustLevel#READ}, and how much of a skill's
 * declared {@code allowed-tools} is honoured depends on how high the level goes. A root
 * that needs trust but has not been trusted is still listed (marked) but contributes no
 * skills.
 */
public final class NaruSkillRoot {
    private final NaruSkillRootKind kind;
    private final NPath path;
    private final String label;
    private final int precedence;
    private final NaruSkillTrustLevel trustLevel;

    public NaruSkillRoot(NaruSkillRootKind kind, NPath path, String label, int precedence, boolean trusted) {
        this(kind, path, label, precedence,
                trusted || !kind.foreign() ? NaruSkillTrustLevel.READ : NaruSkillTrustLevel.NONE);
    }

    public NaruSkillRoot(NaruSkillRootKind kind, NPath path, String label, int precedence,
                         NaruSkillTrustLevel trustLevel) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.path = path;
        this.label = label == null ? kind.family() : label;
        this.precedence = precedence;
        // a native root is always readable: its trust never goes below READ
        this.trustLevel = !kind.foreign() && trustLevel == NaruSkillTrustLevel.NONE
                ? NaruSkillTrustLevel.READ
                : trustLevel;
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
        return trustLevel.atLeast(NaruSkillTrustLevel.READ);
    }

    /** The granted trust level; {@link NaruSkillTrustLevel#NONE} for an untrusted root. */
    public NaruSkillTrustLevel trustLevel() {
        return trustLevel;
    }

    /** A copy of this root at the given trust level. */
    public NaruSkillRoot withTrust(NaruSkillTrustLevel level) {
        return level == trustLevel ? this : new NaruSkillRoot(kind, path, label, precedence, level);
    }

    public NaruSkillRoot asTrusted() {
        return withTrust(NaruSkillTrustLevel.READ);
    }

    /** True when the root directory exists on disk. */
    public boolean exists() {
        return path != null && path.isDirectory();
    }

    @Override
    public String toString() {
        if (!requiresTrust()) {
            return kind.id() + ":" + path + " (native)";
        }
        return kind.id() + ":" + path + " (trust: " + trustLevel.name().toLowerCase() + ")";
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