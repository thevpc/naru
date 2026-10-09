package net.thevpc.naru.api.spawn;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a spawn target by name: a routine (name or path) whose lines become the child
 * statements and whose front-matter contract is validated, or an agent {@code .md} whose
 * front-matter carries the contract.
 * <p>
 * Shared by {@code /start}, the {@code delegate_to_model} tool and the spawn tests, so a
 * target resolves exactly the same way no matter who spawns it.
 */
public final class NaruSpawnTargets {

    private NaruSpawnTargets() {
    }

    /**
     * The resolution of a named spawn target.
     *
     * @param statements the task statements to run the child with (empty for an agent)
     * @param contract   the target contract to validate, or null when the target declares none
     * @param spawnKind  {@code "routine"} or {@code "agent"}
     * @param agentPath  the agent {@code .md} path for agent targets, else null
     */
    public record Resolved(List<String> statements, NaruSpawnContract contract,
                           String spawnKind, NPath agentPath) {

        public boolean isAgent() {
            return agentPath != null;
        }
    }

    /**
     * Resolves a target by name.
     *
     * @return empty when the name matches neither a routine nor an agent {@code .md}. An
     *         agent file that exists but declares no contract resolves to an agent result
     *         with a null contract, so the caller can report the missing contract itself
     *         (a spawn that silently skipped contract validation would be a silent
     *         degradation).
     */
    public static NOptional<Resolved> resolve(NaruSession session, NaruTask task, String name) {
        if (name == null || name.isBlank()) {
            return NOptional.ofEmpty();
        }
        NaruRoutine rtn = session.routine(name, task, false).orNull();
        if (rtn != null) {
            List<String> stmts = new ArrayList<>();
            rtn.getIndexedLines().forEach(l -> stmts.add(l.command()));
            return NOptional.of(new Resolved(stmts, rtn.getContract(), "routine", null));
        }
        NPath agentMd = findAgentMd(task, name);
        if (agentMd != null) {
            return NOptional.of(new Resolved(List.of(), NaruSpawnContract.parse(readFrontMatter(agentMd)), "agent", agentMd));
        }
        return NOptional.ofEmpty();
    }

    /**
     * Resolves an agent {@code .md} by name in the conventional agent dirs: the task's own
     * {@code .naru/agent} and {@code .naru/local/agent}, the project's, then the user
     * home's, plus a direct path when the name looks like one. Returns null when not found.
     */
    private static NPath findAgentMd(NaruTask task, String name) {
        String fileName = name.endsWith(".md") ? name : name + ".md";
        if (name.startsWith("/") || name.startsWith(".") || name.contains("/")) {
            NPath direct = NPath.of(name.endsWith(".md") ? name : name + ".md").toAbsolute(task.workingDir());
            if (direct.exists()) {
                return direct;
            }
        }
        List<NPath> dirs = new ArrayList<>();
        if (task.workingDir() != null) {
            dirs.add(task.workingDir().resolve(".naru").resolve("agent"));
            dirs.add(task.workingDir().resolve(".naru").resolve("local").resolve("agent"));
        }
        if (task.projectDir() != null && !task.projectDir().equals(task.workingDir())) {
            dirs.add(task.projectDir().resolve(".naru").resolve("agent"));
            dirs.add(task.projectDir().resolve(".naru").resolve("local").resolve("agent"));
        }
        dirs.add(NPath.ofUserHome().resolve(".naru").resolve("agent"));
        for (NPath dir : dirs) {
            NPath candidate = dir.resolve(fileName);
            if (candidate.exists()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Reads the TSON front-matter of an agent {@code .md} ({@code --- key: value ---}) as a
     * key/value map, using the same conventions as the core's front-matter loader so the
     * contract a spawn validates is the same data the model context merges into env.
     */
    private static Map<String, NElement> readFrontMatter(NPath source) {
        Map<String, NElement> out = new LinkedHashMap<>();
        try {
            String str = source.readString(StandardCharsets.UTF_8);
            if (str == null) {
                return out;
            }
            str = str.trim();
            if (!str.startsWith("---")) {
                return out;
            }
            int x = str.indexOf("---", 3);
            if (x <= 0) {
                return out;
            }
            String h = str.substring(3, x).trim();
            if (h.isEmpty()) {
                return out;
            }
            NElement el = NElementReader.ofTson().read(h);
            if (el == null) {
                return out;
            }
            NObjectElement obj = el.asObject().orNull();
            if (obj != null) {
                for (NElement pair : obj.children()) {
                    out.put(pair.asPair().get().key().asStringValue().orNull(), pair.asPair().get().value());
                }
            }
        } catch (Exception ignore) {
            // a broken front-matter yields no contract; the caller reports the missing contract
        }
        return out;
    }
}