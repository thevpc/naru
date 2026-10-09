package net.thevpc.naru.ext.skills;

import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Persists the one-time trust decisions for foreign skill roots (WP6).
 * <p>
 * A foreign root is read only after it was trusted. Trust is scoped like the root itself:
 * a project-level foreign root ({@code <project>/.claude/skills}) is persisted under the
 * project at {@code .naru/local/skills-trust.tson}, and a user-level one
 * ({@code ~/.claude/skills}) under the user home at {@code ~/.naru/skills-trust.tson}. The
 * key is the root's label plus its path relative to the scope base, so a project that is
 * moved (or cloned to another machine) keeps the trust it declared.
 * <p>
 * Nothing here reads skill content: the store only answers "may this root be read".
 */
final class NaruSkillTrustStore {
    private static final String FILE = "skills-trust.tson";

    private final NPath projectDir;
    private final NPath userHome;
    private final NPath projectFile;
    private final NPath userFile;
    private final Set<String> projectTrusted = new LinkedHashSet<>();
    private final Set<String> userTrusted = new LinkedHashSet<>();

    NaruSkillTrustStore(NPath projectDir, NPath userHome) {
        this.projectDir = projectDir == null ? null : projectDir.normalize();
        this.userHome = userHome == null ? null : userHome.normalize();
        this.projectFile = this.projectDir == null ? null : this.projectDir.resolve(".naru/local/" + FILE);
        this.userFile = this.userHome == null ? null : this.userHome.resolve(".naru/" + FILE);
        load();
    }

    boolean isTrusted(NaruSkillRoot root) {
        if (root == null || !root.requiresTrust()) {
            return true;
        }
        return scope(root).contains(key(root));
    }

    /** Records a trust decision. Returns true when the persisted state actually changed. */
    boolean setTrusted(NaruSkillRoot root, boolean trusted) {
        if (root == null || !root.requiresTrust()) {
            return false;
        }
        String key = key(root);
        Set<String> scope = scope(root);
        boolean changed = trusted ? scope.add(key) : scope.remove(key);
        if (changed) {
            save(root.kind().userLevel());
        }
        return changed;
    }

    Set<String> trustedKeys(boolean userLevel) {
        return Set.copyOf(userLevel ? userTrusted : projectTrusted);
    }

    // ── keying and scope ────────────────────────────────────────────────────

    private Set<String> scope(NaruSkillRoot root) {
        return root.kind().userLevel() ? userTrusted : projectTrusted;
    }

    private String key(NaruSkillRoot root) {
        NPath base = root.kind().userLevel() ? userHome : projectDir;
        String rel = null;
        if (base != null && root.path() != null && root.path().startsWith(base)) {
            NOptional<String> r = base.relativize(root.path());
            rel = r.orNull();
        }
        if (NBlankable.isBlank(rel)) {
            rel = String.valueOf(root.path());
        }
        return root.label() + "|" + rel;
    }

    // ── persistence ─────────────────────────────────────────────────────────

    private void load() {
        read(projectFile, projectTrusted);
        read(userFile, userTrusted);
    }

    private static void read(NPath file, Set<String> into) {
        if (file == null || !file.isRegularFile()) {
            return;
        }
        try {
            String text = file.readString(StandardCharsets.UTF_8);
            if (NBlankable.isBlank(text)) {
                return;
            }
            NElement el = NElementReader.ofTson().read(text);
            NObjectElement obj = el == null ? null : el.asObject().orNull();
            NArrayElement arr = obj == null ? null : obj.getArray("trusted").orNull();
            if (arr == null) {
                return;
            }
            for (NElement e : arr.children()) {
                String s = e.asStringValue().orNull();
                if (!NBlankable.isBlank(s)) {
                    into.add(s);
                }
            }
        } catch (Exception e) {
            // a corrupt trust file is treated as "nothing trusted" rather than failing the
            // session: the worst case is one extra prompt, never a crash
        }
    }

    private void save(boolean userLevel) {
        NPath file = userLevel ? userFile : projectFile;
        Set<String> values = userLevel ? userTrusted : projectTrusted;
        if (file == null) {
            return;
        }
        try {
            if (values.isEmpty()) {
                if (file.exists()) {
                    file.delete();
                }
                return;
            }
            file.mkParentDirs();
            NElement payload = NElement.ofObjectBuilder()
                    .set("trusted", NElement.ofStringArray(values.toArray(new String[0])))
                    .build();
            NElementWriter.ofTson().write(payload, file);
        } catch (Exception e) {
            // persistence is best effort; an unwritable trust file must not break a load
        }
    }
}
