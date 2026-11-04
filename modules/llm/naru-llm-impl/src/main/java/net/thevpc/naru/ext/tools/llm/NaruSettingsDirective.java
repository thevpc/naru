package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruTaskConfig;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCompleteCandidate;
import net.thevpc.nuts.cmdline.NArgCompletePosition;
import net.thevpc.nuts.cmdline.NArgCompleteResult;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NStringBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads and writes <b>config</b> keys: the values that live in the two files under
 * {@code .naru} and outlive the session.
 *
 * <pre>
 * /settings                          list the settings this build understands
 * /settings model.thinking            show one key, its value and where it came from
 * /settings model.thinking=false      write it privately (the default)
 * /settings --public model.noStream=true
 * /settings model.thinking null       remove it, restoring the default
 * </pre>
 *
 * <p>Scope is exactly one thing here: which of the two config files.
 *
 * <ul>
 *   <li>{@code --private} (default) -- {@code .naru/local/config/env.tson}. Untracked by
 *       git, so it is where a personal preference belongs and the one that cannot
 *       surprise a colleague who clones the project.</li>
 *   <li>{@code --public} -- {@code .naru/config/env.tson}. Checked in, and therefore how
 *       a project ships a default to everyone who opens it.</li>
 * </ul>
 *
 * <p>These are not two scopes. They are the visibility axis of one store, which is why
 * they are flags on a single command rather than separate ones. Private shadows public,
 * so a public write can look like it did nothing while a private value sits over it --
 * hence {@code /settings <key>} naming the file that actually answered.
 *
 * <p><b>This deliberately does not touch the task or session env.</b> {@code /set} owns
 * both: it is the one that evaluates expressions, does {@code +=} and {@code ++}, and
 * reaches the session and task stores. Overlapping it here would give one value two
 * spellings that disagree, and {@code /settings --task x=1} would look like it worked
 * while quietly bypassing the config files.
 *
 * <p>{@code model.thinking} is the setting worth knowing about: {@code false} both stops
 * reasoning being requested from the model and removes the {@code think} tool from the
 * tool schema, so "thinking off" does not come back as narrated tool calls.
 */
public class NaruSettingsDirective extends NaruDirectiveBase {

    public NaruSettingsDirective() {
        super("settings", "ai", "read and write config keys", "setting", "config");
        this.noCommand("list");

        register(new AbstractSubCommand("", NText.ofPlain("list or set a config key"),
                new SubCommandHelp("", "list the settings this build understands, with their current value"),
                new SubCommandHelp("<key>", "show one key, its resolved value and where it came from"),
                new SubCommandHelp("<key>=<value>", "write a key; value is read as a boolean, a number or a string"),
                new SubCommandHelp("--private <key>=<value>", "write .naru/local/config/env.tson (the default)"),
                new SubCommandHelp("--public <key>=<value>", "write .naru/config/env.tson, the checked-in default"),
                new SubCommandHelp("<key> null", "remove the key, restoring the default")
        ) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                return executeSettings(context, cmdLine);
            }
        });
    }

    private NaruStmtResult executeSettings(SubCommandContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruVisibility visibility = NaruVisibility.PRIVATE;
        List<String> words = new ArrayList<>();
        while (!cmdLine.isEmpty()) {
            NArg arg = cmdLine.peek().orElse(null);
            if (arg == null) {
                break;
            }
            NaruVisibility flag = words.isEmpty() ? visibilityByFlag(arg.image()) : null;
            if (flag != null) {
                visibility = flag;
                cmdLine.next();
                continue;
            }
            words.add(cmdLine.next().get().image());
        }
        if (words.isEmpty()) {
            return list(task, context);
        }
        if (words.size() == 1 && !words.get(0).contains("=")) {
            return show(task, words.get(0), context);
        }
        String key;
        String rawValue;
        String word = words.get(0);
        if (word.contains("=")) {
            int eq = word.indexOf('=');
            key = word.substring(0, eq).trim();
            rawValue = word.substring(eq + 1).trim();
            for (int i = 1; i < words.size(); i++) {
                rawValue = (rawValue + " " + words.get(i)).trim();
            }
        } else if (words.size() >= 2) {
            key = word;
            rawValue = String.join(" ", words.subList(1, words.size()));
        } else {
            return show(task, word, context);
        }
        if (key.isEmpty()) {
            return error(task, context,"missing settings key in '%s'", word);
        }
        return write(task, visibility, key, rawValue, context);
    }

    private NaruStmtResult list(NaruTask task,SubCommandContext context) {
        List<NaruConfigKey> keys = task.session().registry().configKeys();
        List<NaruConfigKey> configured = new ArrayList<>();
        for (NaruConfigKey key : keys) {
            NOptional<NaruTaskConfig.Resolved> found = NaruTaskConfig.resolve(task, key.key());
            if (found != null && found.isPresent()) {
                configured.add(key);
            }
        }
        NMsg header = NMsg.ofC("%s settings:", configured.size());
        context.outln(header);
        for (NaruConfigKey key : configured) {
            NMsg row = NMsg.ofC("  %s%s", describe(task, key.key()),
                    key.description().isEmpty() ? "" : " — " + key.description());
            context.outln(row);
        }
        if (configured.isEmpty()) {
            NMsg empty = NMsg.ofC("  no settings are currently set");
            context.outln(empty);
        }
        NMsg hint = NMsg.ofC("  any other key is readable too: /%s <key>", name());
        context.outln(hint);
        return NaruStmtResult.ofSuccess(NElement.of(context.out().filteredText()));
    }

    private NaruStmtResult show(NaruTask task, String key,SubCommandContext context) {
        NMsg row = NMsg.ofC("%s", describe(task, key));
        context.outln( row);
        return NaruStmtResult.ofSuccess(NElement.of(row.toString()));
    }

    private NaruStmtResult write(NaruTask task, NaruVisibility visibility, String key, String rawValue,SubCommandContext context) {
        boolean remove = rawValue.isEmpty() || "null".equalsIgnoreCase(rawValue);
        NElement value = remove ? null : NaruTaskConfig.parseValue(rawValue);
        // The config env takes an NElement, which is also how the file reads it back,
        // so a boolean written here is a boolean read there. A null value removes the
        // key from the chosen file only, leaving the other visibility untouched.
        task.session().setProjectEnv(key, value, visibility);
        NMsg msg = remove
                ? NMsg.ofC("removed %s from %s", NMsg.ofStyledPrimary1(key), label(visibility))
                : NMsg.ofC("%s = %s (%s)", NMsg.ofStyledPrimary1(key), rawValue, label(visibility));
        context.outln( msg);
        return NaruStmtResult.ofSuccess(NElement.of(remove));
    }

    /**
     * Resolves a key for display, naming the store and file that answered.
     *
     * <p>Both halves are printed because both are needed: the store says where to look,
     * the visibility says which of the two files to open. A private value shadowing a
     * public one is invisible otherwise, and the chain is first-hit-wins.
     */
    private String describe(NaruTask task, String key) {
        NOptional<NaruTaskConfig.Resolved> found = NaruTaskConfig.resolve(task, key);
        if (found == null || !found.isPresent()) {
            return key + " = <unset>";
        }
        NaruTaskConfig.Resolved resolved = found.get();
        String where = resolved.visibility() == null
                ? resolved.scope()
                : resolved.scope() + ", " + label(resolved.visibility());
        return key + " = " + NaruTaskConfig.toText(resolved.value()) + " (" + where + ")";
    }

    private NaruStmtResult error(NaruTask task,SubCommandContext context, String format, Object... args) {
        NMsg msg = NMsg.ofC(format, args);
        context.outln( msg.asError());
        return NaruStmtResult.ofError();
    }

    private static String label(NaruVisibility visibility) {
        return visibility == NaruVisibility.PUBLIC ? "public" : "private";
    }

    private static NaruVisibility visibilityByFlag(String image) {
        switch (image) {
            case "--private":
            case "--local":
                return NaruVisibility.PRIVATE;
            case "--public":
                return NaruVisibility.PUBLIC;
            default:
                return null;
        }
    }

    // ── autocomplete ─────────────────────────────────────────────────────────

    @Override
    public NArgCompleteResult resolveCandidates(NCmdLine cmdLine, NArgCompletePosition pos, NaruSession session) {
        List<NArgCompleteCandidate> candidates = new ArrayList<>();
        String[] words = cmdLine.toStringArray();
        int wordIndex = pos.wordIndex();
        String currentArg = wordIndex < words.length ? words[wordIndex] : "";

        if (currentArg.startsWith("-")) {
            addCandidates(candidates, currentArg, "--private", "--public", "--local");
            return NArgCompleteResult.ofCandidates(candidates);
        }
        // word 0 is /settings; the key may be the first or a later word. The
        // candidates are the declared config keys (with their descriptions and, for
        // the keys that have a fixed vocabulary, their values once the text reaches
        // the '=') plus whatever the project config files already hold, so a custom
        // key is completed once it exists.
        List<NaruConfigKey> keys = new ArrayList<>(registeredKeys(session));
        Set<String> present = new TreeSet<>();
        for (NaruConfigKey k : keys) {
            present.add(k.key());
        }
        for (String key : sessionKeys(session)) {
            if (present.add(key)) {
                keys.add(NaruConfigKey.of(key, "key present in the session or agent env"));
            }
        }
        NaruConfigKey.addCandidates(candidates, currentArg, keys);
        return NArgCompleteResult.ofCandidates(candidates);
    }

    /**
     * The keys {@code /settings} can name: what the build declares through its
     * {@link NaruConfigKeyProvider}s, plus whatever the project config files
     * already hold, so a custom key is completed once it exists.
     */
    private static List<NaruConfigKey> registeredKeys(NaruSession session) {
        if (session == null || session.registry() == null) {
            return List.of();
        }
        try {
            return session.registry().configKeys();
        } catch (RuntimeException ignored) {
            // completion is best effort; an unreadable config must not break Tab
            return List.of();
        }
    }

    private static List<String> sessionKeys(NaruSession session) {
        if (session == null || session.agent() == null) {
            return List.of();
        }
        try {
            return new ArrayList<>(session.agent().env().entries().keySet());
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }
}