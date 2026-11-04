package net.thevpc.naru.ext.skills;

import java.util.Collection;
import java.util.Set;

/**
 * How far a foreign skill root may act, and what a single declared {@code allowed-tools}
 * token needs.
 *
 * <p>Trust used to be a boolean: "may this root be read". The user-visible model now
 * distinguishes three capabilities, because a skill from someone else's root only
 * advertises instructions plus a list of the tools it wants to call, and those tools have
 * very different costs:
 *
 * <ul>
 *   <li>{@link #READ} -- the skill body may be read and injected into context. This is the
 *       baseline: reading Markdown is inert.</li>
 *   <li>{@link #WRITE} -- on top of reading, declared tools that mutate the workspace
 *       (write/edit/… ) are honoured.</li>
 *   <li>{@link #EXEC} -- on top of writing, declared shell / command-execution tools are
 *       honoured.</li>
 * </ul>
 *
 * <p>{@code /skills trust <root>} grants one level ({@code --read} by default,
 * {@code --write}, {@code --exec}); {@code /skills untrust} revokes all. A skill whose
 * declared {@code allowed-tools} need more than its root grants is refused at load time
 * with an explicit message, and {@code /skills doctor} reports the same thing before any
 * load happens.
 *
 * <p>Since NARU has no formal capability taxonomy for tools, the token-to-level mapping is
 * a deliberately named heuristic on the tool base name (capability syntax like
 * {@code Bash(git:*)} is stripped to {@code Bash}): known shell families classify as
 * {@link #EXEC}, an explicit list of file-mutating names as {@link #WRITE}, and everything
 * else stays {@link #READ}.
 */
public enum NaruSkillTrustLevel {
    /** Not trusted at all: nothing may be read. */
    NONE,
    /** The skill body may be read. */
    READ,
    /** Reading plus file-mutating declared tools. */
    WRITE,
    /** Reading, writing, and shell/command-execution declared tools. */
    EXEC;

    public boolean atLeast(NaruSkillTrustLevel other) {
        return other != null && ordinal() >= other.ordinal();
    }

    /**
     * The level the declared {@code allowed-tools} tokens of a skill collectively need.
     * A skill with no tokens needs only {@link #READ} (its body is being read).
     */
    public static NaruSkillTrustLevel requiredBy(Collection<String> allowedTools) {
        NaruSkillTrustLevel need = READ;
        if (allowedTools != null) {
            for (String token : allowedTools) {
                NaruSkillTrustLevel t = requiredByToken(token);
                if (t.ordinal() > need.ordinal()) {
                    need = t;
                }
            }
        }
        return need;
    }

    /**
     * The level a single {@code allowed-tools} token needs, classified from its base name
     * (the part before an optional {@code (…)} capability suffix).
     */
    public static NaruSkillTrustLevel requiredByToken(String token) {
        String base = token == null ? "" : token.trim();
        int paren = base.indexOf('(');
        if (paren > 0) {
            base = base.substring(0, paren).trim();
        }
        String t = base.toLowerCase();
        // shell families: an exec capability, named so a tool called like one (bash, sh,
        // shell, run_command, execute …, powershell, terminal) is classified as execution
        for (String exec : EXEC_TOOLS) {
            if (t.equals(exec) || t.startsWith(exec + "_")) {
                return EXEC;
            }
        }
        // file mutation: write/edit/patch and the filesystem verbs
        for (String write : WRITE_TOOLS) {
            if (t.equals(write) || t.startsWith(write + "_")) {
                return WRITE;
            }
        }
        return READ;
    }

    private static final Set<String> EXEC_TOOLS = Set.of(
            "bash", "sh", "shell", "exec", "execute", "run", "terminal",
            "command", "cmd", "powershell", "nushell", "zsh", "fish", "ssh"
    );

    private static final Set<String> WRITE_TOOLS = Set.of(
            "write", "edit", "patch", "create", "mkdir", "rmdir",
            "delete", "remove", "move", "copy", "rename", "append",
            "modify", "insert", "upload", "touch", "truncate"
    );
}