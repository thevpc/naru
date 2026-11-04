package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.util.NaruUtils;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCompleteCandidate;
import net.thevpc.nuts.cmdline.NArgCompletePosition;
import net.thevpc.nuts.cmdline.NArgCompleteResult;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.*;

import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class NaruModelDirective extends NaruDirectiveBase {

    public NaruModelDirective() {
        super("model", "ai", "manage AI models", "models");
        noCommand("list");
        register(new AbstractSubCommand("current", NText.ofPlain("show current model")) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NMsg msg = NMsg.ofC("%s", task.model().toText());
                context.outln(msg);
                return NaruStmtResult.ofSuccess(NElement.of(task.model().toText().filteredText()),null);
            }
        });
        register(new AbstractSubCommand("use", NText.ofPlain("select model"),
                new SubCommandHelp(NText.of("<model>"), NText.ofPlain("model name, 'provider/model', registration id, or index of the last listing ('/model list [--free|--provider=x|<filter>]')"))
        ) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NOptional<NArg> n = cmdLine.next();
                if (!n.isPresent()) {
                    NMsg msg = NMsg.ofC("Error: missing model name to set.").asError();
                    context.errln(msg);
                    return NaruStmtResult.ofError();
                }
                NArg a = n.get();
                NaruSession session = task.session();
                NaruModelConfig k = session.findModel(a.image()).orNull();
                if (k == null) {
                    // a registration id is not a model: say why it did not resolve
                    // (no available model, or several models to choose from) instead
                    // of a generic 'not found'
                    NMsg regErr = registrationSelectionError(session, a.image());
                    if (regErr != null) {
                        context.errln(regErr);
                        return NaruStmtResult.ofError();
                    }
                    NMsg msg = NMsg.ofC("Error: model %s not found%s.",
                            a.image(),
                            NLiteral.of(a.image()).asInt().isPresent() ? " (see the indexes printed by '/model list')" : ""
                    ).asError();
                    context.errln(msg);
                    return NaruStmtResult.ofError();
                }
                context.task().setModel(k);
                context.outln(NMsg.ofC("Selected model : %s",
                        task.model().toText()));
                return NaruStmtResult.ofSuccess();
            }
        });
        register(new AbstractSubCommand("use-global", NText.ofPlain("use model as default globally")
                , new SubCommandHelp(NText.of("<model>"), NText.ofPlain("model name to set as default globally"))
        ) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NOptional<NArg> n = cmdLine.next();
                if (!n.isPresent()) {
                    NMsg msg = NMsg.ofC("Error: missing model name to set.").asError();
                    context.errln(msg);
                    return NaruStmtResult.ofError();
                }
                NArg a = n.get();
                NaruModelConfig k = task.session().findModel(a.image()).orNull();
                if (k == null) {
                    NMsg msg = NMsg.ofC("Error: model %s not found.",
                            a.image()).asError();
                    context.errln(msg);
                    return NaruStmtResult.ofError();
                }
                context.task().setModel(k);
                NAssert.requireNamedNonNull(k, "key");
                context.task().session().setProjectEnv("model", k.toElement(), NaruVisibility.PRIVATE);
                context.outln(NMsg.ofC("switch global model : %s",
                        task.model().toText()));
                return NaruStmtResult.ofSuccess();
            }
        });

        register(new AbstractSubCommand("list", NText.ofPlain("list available models"),
                new SubCommandHelp(NText.of("[<filter>] [--provider=<name>] [--free]"), NText.ofPlain("list available models, optionally filtered by keyword, provider or free-only. The printed indexes can be reused with '/model use <n>' until the next listing"))
        ) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                return executeList(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("", NText.ofPlain("special..."),
                new SubCommandHelp("<n>", "set model by index (as printed by the last '/model' listing)"),
                new SubCommandHelp("<filter>", "list models matching the given filter / keyword")
        ) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                // Non-numeric bare argument (e.g. /model glm) falls back to a
                // filtered listing; a pure index selects the model at that position.
                NOptional<NArg> head = cmdLine.peek();
                if (head.isPresent() && !head.get().isOption()) {
                    NOptional<Integer> b = NLiteral.of(head.get().image()).asInt();
                    if (b.isPresent()) {
                        return executeSetByNumber(context, b.get());
                    }
                }
                return executeList(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("add", NText.ofPlain("create or update a model registration"),
                new SubCommandHelp(NText.of("<id> --provider=<type> [--protocol=<wire>] [--url=…] [--model=<id>|--models=a,b] [--apiKey=sk-…|$VAR] [--temperature=… --contextLength=…] [--scope=project|user] | <id> --protocol=<wire> --url=… --models=a,b [--apiKey=…]"), NText.ofPlain("register an instance of a built-in provider type, or a generic endpoint speaking a wire protocol; with an existing id, merges the given parameters into it. --scope=user writes the machine-wide config every project then inherits"))
        ) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                return executeRegistration(context, cmdLine, "add");
            }
        });
        register(new AbstractSubCommand("update", NText.ofPlain("change a registration's parameters"),
                new SubCommandHelp(NText.of("<id> [--temperature=… --apiKey=…] [--scope=project|user]"), NText.ofPlain("merge the given parameters into an existing registration; an empty value clears the parameter. --scope=user updates the machine-wide config instead of this project's"))
        ) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                return executeRegistration(context, cmdLine, "update");
            }
        });
        register(new AbstractSubCommand("remove", NText.ofPlain("delete a registration"),
                new SubCommandHelp(NText.of("<id> [--scope=project|user]"), NText.ofPlain("delete a registration and its stored parameters from one scope; a project override that sits on a user registration is removed and the user registration survives"))
        ) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                return executeRemoveRegistration(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("registered", NText.ofPlain("list registrations (api keys masked)")) {
            @Override
            public NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine) {
                return executeRegisteredList(context, cmdLine);
            }
        });

    }

    // ── autocomplete ──────────────────────────────────────────────────────────

    private static final String[] REGISTRATION_PARAMS = {
            "provider", "protocol", "model", "models", "apiKey", "url",
            "temperature", "contextLength", "nucleusThreshold", "candidateCount",
            "maxTokens", "maxRetries", "stop", "thinkingTags", "chatPath",
            "tools", "probe", "enabled"
    };

    /**
     * Tab completion for the whole command surface: subcommand names
     * filtered by what has been typed, then each subcommand's own arguments — a
     * {@code --param=value} token with <b>values</b> for the flags that have a
     * fixed vocabulary (provider types, wire protocols, the models of a provider
     * chosen earlier in the line, booleans), registration ids for {@code add}/
     * {@code update}/{@code remove}, and model references for {@code use},
     * {@code use-global} and the bare form.
     */
    @Override
    public NArgCompleteResult resolveCandidates(NCmdLine cmdLine, NArgCompletePosition pos, NaruSession session) {
        List<NArgCompleteCandidate> candidates = new ArrayList<>();
        String[] words = cmdLine.toStringArray();
        int wordIndex = pos.wordIndex();
        if (wordIndex == 1) {
            // "/model <…>": subcommand names, plus whatever the bare form accepts
            // (an index of the pinned listing, a registration id, or a filter word
            // such as a full provider/model key)
            String prefix = currentWord(words, wordIndex);
            completeSubcommandNames(candidates, prefix);
            completeModelReference(candidates, session, prefix);
        } else if (wordIndex >= 2) {
            String sub = words.length > 1 ? words[1] : "";
            if (!subCommand(sub).isPresent()) {
                return NArgCompleteResult.ofCandidates(candidates);
            }
            switch (sub) {
                case "use":
                case "use-global":
                    completeModelReference(candidates, session, currentWord(words, wordIndex));
                    break;
                case "add":
                case "update":
                    if (wordIndex == 2) {
                        completeRegistrationId(candidates, session, currentWord(words, wordIndex));
                    } else {
                        completeRegistrationOption(candidates, session, words, wordIndex);
                    }
                    break;
                case "remove":
                    if (wordIndex == 2) {
                        completeRegistrationId(candidates, session, currentWord(words, wordIndex));
                    }
                    break;
                case "list":
                    completeListArgument(candidates, session, words, wordIndex);
                    break;
                default:
                    break;
            }
        }
        return NArgCompleteResult.ofCandidates(candidates);
    }

    private static String currentWord(String[] words, int wordIndex) {
        return wordIndex < words.length ? words[wordIndex] : "";
    }

    /** The candidate must extend what has been typed, case-insensitively. */
    private static boolean extendsWord(String typed, String candidate) {
        return candidate != null && candidate.toLowerCase().startsWith(typed.toLowerCase());
    }

    private void completeSubcommandNames(List<NArgCompleteCandidate> candidates, String prefix) {
        List<String> names = new ArrayList<>();
        for (SubCommand sc : registeredSubCommands()) {
            if (!sc.name().isEmpty()) {
                names.add(sc.name());
            }
        }
        Collections.sort(names);
        for (String name : names) {
            if (extendsWord(prefix, name)) {
                candidates.add(NArgCompleteCandidate.of(name,
                        name + " — " + subCommand(name).get().description().toString()));
            }
        }
    }

    /**
     * Everything {@code /model use} (and the bare form) can select: an index of
     * the pinned listing, an existing registration id, or a full provider/model
     * key of the merged catalog.
     */
    private static void completeModelReference(List<NArgCompleteCandidate> candidates, NaruSession session, String prefix) {
        List<NaruModelKey> listed = session == null ? null : session.listedModels();
        if (listed != null) {
            for (int i = 0; i < listed.size(); i++) {
                String ix = String.valueOf(i + 1);
                if (extendsWord(prefix, ix)) {
                    candidates.add(NArgCompleteCandidate.of(ix, "index " + ix + " — " + listed.get(i)));
                }
            }
        }
        completeRegistrationId(candidates, session, prefix);
        for (NaruModelKey mk : catalogModelKeys(session)) {
            if (extendsWord(prefix, mk.toString())) {
                candidates.add(NArgCompleteCandidate.of(mk.toString(), mk.toString()));
            }
        }
    }

    private static void completeRegistrationId(List<NArgCompleteCandidate> candidates, NaruSession session, String prefix) {
        Map<String, NaruModelRegistration> regs = session == null ? null : session.registrations();
        if (regs != null) {
            for (Map.Entry<String, NaruModelRegistration> e : regs.entrySet()) {
                if (extendsWord(prefix, e.getKey())) {
                    candidates.add(NArgCompleteCandidate.of(e.getKey(),
                            "registration " + e.getKey() + " (provider " + e.getValue().provider() + ")"));
                }
            }
        }
    }

    /**
     * A {@code --param=…} token of {@code add}/{@code update}: option names while
     * the user is still typing the flag, then candidate values once the text
     * reaches the {@code =} — provider types, wire protocols supported by the
     * provider chosen in the line, the catalog models of that provider, and
     * {@code true}/{@code false} for the boolean flags.
     */
    private static void completeRegistrationOption(List<NArgCompleteCandidate> candidates, NaruSession session, String[] words, int wordIndex) {
        String current = currentWord(words, wordIndex);
        int eq = current.indexOf('=');
        if (eq < 0) {
            for (String name : REGISTRATION_PARAMS) {
                String cand = "--" + name + "=";
                if (extendsWord(current, cand)) {
                    candidates.add(NArgCompleteCandidate.of(cand, "--" + name + "=<value>"));
                }
            }
            return;
        }
        String prefix = current.substring(0, eq);
        String key = normalizeOptionKey(prefix);
        switch (key) {
            case "provider":
                // the internal custom type is never offered: --provider=custom is
                // rejected at parse (as are wire protocol ids with no provider type)
                for (String t : providerTypes(session)) {
                    if ("custom".equalsIgnoreCase(t)) {
                        continue;
                    }
                    addValueCandidate(candidates, prefix, t, t, current);
                }
                break;
            case "protocol":
                for (String p : protocolIds(session, words, wordIndex)) {
                    addValueCandidate(candidates, prefix, p, p, current);
                }
                break;
            case "model":
            case "models":
                completeModelsValue(candidates, session, words, wordIndex, prefix, current);
                break;
            case "tools":
            case "probe":
            case "enabled":
                addValueCandidate(candidates, prefix, "true", "true", current);
                addValueCandidate(candidates, prefix, "false", "false", current);
                break;
            default:
                // numeric or free-text parameters have no fixed vocabulary
                break;
        }
    }

    private static void completeModelsValue(List<NArgCompleteCandidate> candidates, NaruSession session, String[] words, int wordIndex, String prefix, String current) {
        String chosen = providerChosen(words, wordIndex);
        for (NaruModelKey mk : catalogModelKeys(session)) {
            if (chosen != null && !NBlankable.isBlank(chosen) && !chosen.equalsIgnoreCase(mk.provider())) {
                continue;
            }
            addValueCandidate(candidates, prefix, mk.model(), mk.model() + " (" + mk.provider() + ")", current);
        }
    }

    private static void addValueCandidate(List<NArgCompleteCandidate> candidates, String prefix, String value, String desc, String current) {
        String cand = prefix + "=" + value;
        if (extendsWord(current, cand)) {
            candidates.add(NArgCompleteCandidate.of(cand, desc));
        }
    }

    /**
     * {@code /model list} arguments: the {@code --provider=}/{@code -p} filter
     * (with provider values after the {@code =}), {@code --free}/{@code -f}, and
     * — for the free-text keyword — the words a listing can actually be filtered
     * by (provider names and full provider/model keys).
     */
    private static void completeListArgument(List<NArgCompleteCandidate> candidates, NaruSession session, String[] words, int wordIndex) {
        String current = currentWord(words, wordIndex);
        if (current.startsWith("-")) {
            int eq = current.indexOf('=');
            if (eq >= 0) {
                String prefix = current.substring(0, eq);
                String key = normalizeOptionKey(prefix);
                if ("provider".equals(key) || "p".equals(key)) {
                    for (String t : providerTypes(session)) {
                        addValueCandidate(candidates, prefix, t, t, current);
                    }
                }
                return;
            }
            if (extendsWord(current, "--provider=")) {
                candidates.add(NArgCompleteCandidate.of("--provider=", "--provider=<type>"));
            }
            if (extendsWord(current, "--free")) {
                candidates.add(NArgCompleteCandidate.of("--free", "--free"));
            }
            if (extendsWord(current, "-p=")) {
                candidates.add(NArgCompleteCandidate.of("-p=", "-p=<type>"));
            }
            if (extendsWord(current, "-f")) {
                candidates.add(NArgCompleteCandidate.of("-f", "-f"));
            }
            return;
        }
        // a free-text filter: the words a listing can actually be filtered by
        for (String t : providerTypes(session)) {
            if (extendsWord(current, t)) {
                candidates.add(NArgCompleteCandidate.of(t, "provider " + t));
            }
        }
        for (NaruModelKey mk : catalogModelKeys(session)) {
            if (extendsWord(current, mk.toString())) {
                candidates.add(NArgCompleteCandidate.of(mk.toString(), mk.toString()));
            }
        }
    }

    /** The provider {@code --provider=…} chosen earlier in the line, if any. */
    private static String providerChosen(String[] words, int wordIndex) {
        for (int i = 2; i < wordIndex && i < words.length; i++) {
            String w = words[i];
            int e = w.indexOf('=');
            if (e > 0 && normalizeOptionKey(w.substring(0, e)).equals("provider")) {
                return NStringUtils.stripToNull(w.substring(e + 1));
            }
        }
        return null;
    }

    private static String normalizeOptionKey(String key) {
        String k = key.trim();
        while (k.startsWith("-")) {
            k = k.substring(1);
        }
        return k.toLowerCase();
    }

    /** The distinct provider types (never registration ids) currently registered. */
    private static Set<String> providerTypes(NaruSession session) {
        TreeSet<String> out = new TreeSet<>();
        Map<String, NaruModelProvider> all = session == null || session.registry() == null
                ? null : session.registry().modelProviders();
        if (all != null) {
            for (NaruModelProvider p : all.values()) {
                if (p != null && !NBlankable.isBlank(p.type())) {
                    out.add(p.type());
                }
            }
        }
        return out;
    }

    /**
     * The wire shapes the provider chosen earlier in the line can speak
     * (design §8); the union over all providers when the user has not picked one
     * yet, so {@code --protocol=} always offers the vocabulary it can use.
     */
    private static Set<String> protocolIds(NaruSession session, String[] words, int wordIndex) {
        TreeSet<String> out = new TreeSet<>();
        String chosen = providerChosen(words, wordIndex);
        Map<String, NaruModelProvider> all = session == null || session.registry() == null
                ? null : session.registry().modelProviders();
        if (all != null) {
            for (NaruModelProvider p : all.values()) {
                if (p == null || (chosen != null && !p.type().equalsIgnoreCase(chosen))) {
                    continue;
                }
                Set<String> sp = p.supportedProtocols();
                if (sp != null) {
                    out.addAll(sp);
                }
            }
        }
        return out;
    }

    /** The selectable keys of the merged catalog (built-ins + registrations). */
    private static List<NaruModelKey> catalogModelKeys(NaruSession session) {
        if (session == null || session.registry() == null) {
            return List.of();
        }
        List<NaruModelKey> keys = session.registry().modelsKeys(session);
        return keys == null ? List.of() : keys;
    }

    public NaruStmtResult executeList(SubCommandContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        String filter = null;
        String providerFilter = null;
        boolean freeOnly = false;
        int limit = Integer.MAX_VALUE;
        int offset = 0;
        while (cmdLine.hasNext()) {
            NArg a = cmdLine.next().get();
            if (a.isOption()) {
                if (a.key().equals("--provider") || a.key().equals("-p")) {
                    providerFilter = a.getStringValue().orNull();
                } else if (a.key().equals("--free") || a.key().equals("-f")) {
                    freeOnly = true;
                } else if (a.key().equals("--limit")) {
                    Integer value = NLiteral.of(a.getStringValue().orNull()).asInt().orNull();
                    if (value == null || value < 1) {
                        return fail(context, NMsg.ofC("Error: --limit must be a positive integer"));
                    }
                    limit = value;
                } else if (a.key().equals("--offset")) {
                    Integer value = NLiteral.of(a.getStringValue().orNull()).asInt().orNull();
                    if (value == null || value < 0) {
                        return fail(context, NMsg.ofC("Error: --offset must be a non-negative integer"));
                    }
                    offset = value;
                }
            } else {
                if (filter == null) {
                    filter = a.image();
                } else {
                    filter = filter + " " + a.image();
                }
            }
        }

        List<NaruModelInfo> allModels = context.task().session().registry().modelsInfos(task.session());
        if (allModels.isEmpty()) {
            NMsg msg = NMsg.ofC("No available models found. Check if %s is running ('/ollama status') or configure an API key for a cloud provider (e.g. 'openrouter.apiKey', 'gemini.apiKey', 'groq.apiKey').", NMsg.ofStyledPrimary1("ollama")).asError();
            context.errln(msg);
            return NaruStmtResult.ofError();
        }

        List<NaruModelInfo> models = new ArrayList<>();
        NaruModelInfo exactMatch = null;
        for (NaruModelInfo m : allModels) {
            // --provider filters by type, so it matches the base provider and every
            // registration of that type together (design §4), not just by instance id
            if (providerFilter != null
                    && !m.provider().equalsIgnoreCase(providerFilter)
                    && !providerType(task.session(), m.provider()).equalsIgnoreCase(providerFilter)) {
                continue;
            }
            if (freeOnly && !m.model().endsWith(":free") && !m.provider().equals("ollama")) {
                continue;
            }
            if (filter != null) {
                String flc = filter.toLowerCase();
                // a full key such as "ollama/qwen2.5-coder:7b" is what /model prints and
                // what users paste back; substring matching can never match it because the
                // key contains the provider separator, so accept an exact key (or an exact
                // "provider/model") before falling back to fuzzy matching.
                boolean exact = m.key().toString().equalsIgnoreCase(filter)
                        || (m.provider() + "/" + m.model()).equalsIgnoreCase(filter);
                if (exact) {
                    exactMatch = m;
                }
                boolean match = exact
                        || m.model().toLowerCase().contains(flc)
                        || m.provider().toLowerCase().contains(flc);
                if (!match) {
                    continue;
                }
            }
            models.add(m);
        }

        // an exact key is a selection, not a query: /model advertises "or a full
        // provider/model", and a pasted key must switch models rather than print a
        // listing. Fuzzy filters keep listing.
        if (exactMatch != null) {
            NaruModelConfig selected = task.session().findModel(exactMatch.key().toString()).orNull();
            if (selected != null) {
                task.setModel(selected);
                context.outln(NMsg.ofC("Selected model : %s", task.model().toText()));
                return NaruStmtResult.ofSuccess();
            }
        }

        if (models.isEmpty()) {
            String crit = filter != null ? "'" + filter + "'" : (providerFilter != null ? "provider '" + providerFilter + "'" : "criteria");
            NMsg msg = NMsg.ofC("No available models found matching %s (%s total models available).", crit, allModels.size());
            context.outln(msg);
            return NaruStmtResult.ofSuccess();
        }

        String titleSuffix = (filter != null || providerFilter != null || freeOnly) ? " (filtered)" : "";
        int fromIndex = Math.min(offset, models.size());
        int toIndex = (int) Math.min((long) models.size(), (long) fromIndex + limit);
        List<NaruModelInfo> displayedModels = models.subList(fromIndex, toIndex);
        // freeze the displayed order: '/model use <n>' must resolve against these exact
        // rows, not the unfiltered catalog, otherwise filtering renumbers the list and
        // every index the user copies becomes a different model.
        task.session().setListedModels(displayedModels.stream().map(NaruModelInfo::key).collect(Collectors.toList()));
        NMsg msg = NMsg.ofC("%s Available models%s: showing %s-%s%s",
                models.size(), titleSuffix,
                displayedModels.isEmpty() ? 0 : fromIndex + 1,
                toIndex,
                displayedModels.size() == models.size() ? " (all)" : "");
        context.outln(msg);
        int zeros = displayedModels.size() <= 1 ? 1 : (int) Math.ceil(Math.log10(displayedModels.size()));
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", Math.max(1, zeros)));
        NaruModelConfig selectedModel = task.model();
        int index = 1;
        for (NaruModelInfo model : displayedModels) {

            NaruModelKey mkey = model.key();

            NMsg extra2 = null;
            if (selectedModel != null && mkey.equals(selectedModel.key())) {
                extra2 = NMsg.ofC("%s%s%s",
                        NMsg.ofStyledSeparator("("),
                        NMsg.ofStyledSuccess("*"),
                        NMsg.ofStyledSeparator(")")
                );
            } else {
                extra2 = NMsg.ofC("   ");
            }

            NMsg extra3 = null;
            long cl = model.capabilities() == null ? 0 : model.capabilities().contextLength();
            if (cl > 0) {
                extra3 = NMsg.ofC(" %s%s%s",
                        NMsg.ofStyledSeparator("["),
                        NaruUtils.formattedTokensSize(cl),
                        NMsg.ofStyledSeparator("]")
                );
            }
            // a registration instance is annotated with its provider type: the catalog
            // is keyed by instance id, and two ids may share one type — the built-in
            // base and its registrations must read as one family (§4)
            String type = providerType(task.session(), model.provider());
            NMsg typeMsg = type.equalsIgnoreCase(model.provider())
                    ? NMsg.ofP("")
                    : NMsg.ofC(" (%s)", type);
            String details = model.parameterSize() == null ? "" : " " + model.parameterSize();
            if (model.quantization() != null) details += " " + model.quantization();
            if (model.sizeBytes() > 0) details += " " + formatModelBytes(model.sizeBytes());
            NMsg row = NMsg.ofC("  %s[%s] %s%s%s%s",
                    extra2,
                    NMsg.ofStyledNumber(zformat.format(index)),
                    model.toText(),
                    typeMsg,
                    extra3 == null ? "" : extra3,
                    details.isEmpty() ? "" : " {" + details.trim() + "}"
            );
            context.outln(row);
            index++;
        }
        NMsg hint = NMsg.ofC("Use %s or %s with any of these %sindexes%s, or a full %s.",
                NMsg.ofStyledPrimary1("/model use <n>"),
                NMsg.ofStyledPrimary1("/model <n>"),
                NMsg.ofStyledSuccess(""),
                NMsg.ofStyledSuccess(""),
                NMsg.ofStyledPrimary1("provider/model")
        );
        context.outln(hint);
        return NaruStmtResult.ofSuccess(NElement.of(context.out().filteredText()));
    }


    public NaruStmtResult executeSetByNumber(SubCommandContext context, int nbr) {
        NaruTask task = context.task();
        NaruModelConfig k = task.session().findModel(String.valueOf(nbr)).orNull();
        if (k == null) {
            int listed = task.session().listedModels().size();
            NMsg msg = (listed > 0
                    ? NMsg.ofC("Error: no model at index %s in the last listing (%s model(s) shown). Use %s to refresh.",
                    nbr, listed, NMsg.ofStyledPrimary1("/model list"))
                    : NMsg.ofC("Error: no model at index %s. Use %s first.",
                    nbr, NMsg.ofStyledPrimary1("/model list"))).asError();
            context.errln(msg);
            return NaruStmtResult.ofError();
        }
        context.task().setModel(k);
        context.outln(NMsg.ofC("Selected model : %s",
                task.model().toText()));
        return NaruStmtResult.ofSuccess(NElement.of(task.model().toText().filteredText()));
    }

    // ── /model add|update|remove|registered ───────────────────────────────────

    /**
     * Creates or updates a registration (design doc §4/§7/§8). The first non-option
     * is the registration id; every {@code --key=value} becomes one of its
     * parameters — {@code --provider} selects a built-in type,
     * {@code --protocol} the wire shape (together: an override of the type's wire;
     * {@code --protocol} alone: a generic endpoint), the rest are merged into
     * every model selected under the id. An update is a merge: parameters the
     * flags do not mention are kept, an empty value clears one.
     */
    public NaruStmtResult executeRegistration(SubCommandContext context, NCmdLine cmdLine, String operation) {
        NaruTask task = context.task();
        NaruSession session = task.session();
        boolean update = "update".equals(operation);

        // first non-option is the id, every option one parameter of the registration
        NRef<String> idRef = NRef.of();
        List<String> problems = new ArrayList<>();
        LinkedHashMap<String, List<String>> flags = new LinkedHashMap<>();
        cmdLine.matcher()
                .whenNonOption().asArg(a -> {
                    if (idRef.isNull()) {
                        idRef.set(NStringUtils.stripToNull(a.asString().orNull()));
                    } else {
                        problems.add("unexpected argument '" + a.image() + "'");
                    }
                })
                .whenAny().asEntry(a -> {
                    String k = NStringUtils.stripToNull(a.key());
                    if (k == null) {
                        problems.add("unexpected option '" + a.image() + "'");
                        return;
                    }
                    while (k.startsWith("-")) {
                        k = k.substring(1);
                    }
                    String v = a.getStringValue().orNull();
                    if (v == null) {
                        problems.add("missing value for --" + k);
                        return;
                    }
                    flags.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
                })
                .requireAll();
        if (!problems.isEmpty()) {
            return fail(context, NMsg.ofC("Error: %s.", problems.get(0)));
        }
        String typedId = idRef.isNull() ? null : idRef.get();
        // The cross-field rules (identity, provider type, wire shape, generic-endpoint
        // requirements, id collisions) and the normalization they imply live in
        // NaruModelRegistrationRules, so the desktop's registration form and this command line
        // can never disagree about what a valid registration is.
        LinkedHashMap<String, NElement> edits = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> f : flags.entrySet()) {
            edits.put(f.getKey(), flagElement(f.getKey(), f.getValue()));
        }
        // --scope names the store, not the model: it decides which file the entry is written to,
        // so it leaves the parameter map before the registration rules see it
        NaruRegistrationScope scope = NaruRegistrationScope.PROJECT;
        NElement scopeEdit = edits.remove("scope");
        if (scopeEdit != null) {
            String scopeWord = scopeEdit.asStringValue().orNull();
            NOptional<NaruRegistrationScope> parsed = NaruRegistrationScope.parse(scopeWord);
            if (!parsed.isPresent()) {
                return fail(context, NMsg.ofC("Error: unknown scope '%s' — use 'project' or 'user'.",
                        scopeWord));
            }
            scope = parsed.get();
        }
        NaruModelRegistrationRules.Outcome outcome =
                NaruModelRegistrationRules.apply(session, typedId, edits, update, scope);
        if (!outcome.isOk()) {
            return fail(context, NMsg.ofC("Error: %s", outcome.problem()));
        }
        NaruModelRegistration reg = outcome.registration();
        session.putRegistration(reg, scope);
        // a built-in type's own default wire is dropped as a no-op, so the line falls back to
        // whatever the stored registration actually names
        String shownProtocol = outcome.protocol() != null ? outcome.protocol() : reg.protocol().orNull();
        String where = scope == NaruRegistrationScope.PROJECT ? "" : ", in your user config";
        NMsg ok = outcome.created()
                ? outcome.generic()
                ? NMsg.ofC("registration '%s' created (protocol=%s, generic endpoint%s)",
                NMsg.ofStyledPrimary1(reg.id()), shownProtocol, where)
                : NMsg.ofC("registration '%s' created (provider=%s%s%s)",
                NMsg.ofStyledPrimary1(reg.id()), reg.provider(),
                shownProtocol == null ? "" : ", protocol=" + shownProtocol, where)
                : NMsg.ofC("registration '%s' updated%s", NMsg.ofStyledPrimary1(reg.id()), where);
        context.outln(ok);
        return NaruStmtResult.ofSuccess(NElement.of(true),null);
    }

    /**
     * Deletes a registration and its stored parameters from one scope's visibility files —
     * the project's unless {@code --scope=user} says otherwise. The other scope keeps whatever it
     * declares: removing a project override from an id the user config also declares uncovers that
     * registration instead of deleting it, and the reply names the file it survives in.
     */
    public NaruStmtResult executeRemoveRegistration(SubCommandContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NRef<String> idRef = NRef.of();
        NaruRegistrationScope[] scopeRef = {NaruRegistrationScope.PROJECT};
        List<String> problems = new ArrayList<>();
        cmdLine.matcher()
                .whenNonOption().asArg(a -> {
                    if (idRef.isNull()) {
                        idRef.set(NStringUtils.stripToNull(a.asString().orNull()));
                    } else {
                        problems.add("unexpected argument '" + a.image() + "'");
                    }
                })
                .whenAny().asEntry(a -> {
                    String k = NStringUtils.stripToNull(a.key());
                    while (k != null && k.startsWith("-")) {
                        k = k.substring(1);
                    }
                    if (!"scope".equals(k)) {
                        problems.add("unexpected option '" + a.image() + "'");
                        return;
                    }
                    String v = a.getStringValue().orNull();
                    NOptional<NaruRegistrationScope> parsed = NaruRegistrationScope.parse(v);
                    if (!parsed.isPresent()) {
                        problems.add("unknown scope '" + v + "' — use 'project' or 'user'");
                        return;
                    }
                    scopeRef[0] = parsed.get();
                })
                .requireAll();
        if (!problems.isEmpty()) {
            return fail(context, NMsg.ofC("Error: %s.", problems.get(0)));
        }
        String image = idRef.get();
        if (NBlankable.isBlank(image)) {
            return fail(context, NMsg.ofC("Error: missing registration id (see '/model registered')."));
        }
        Map<String, NaruModelRegistration> regs = task.session().registrations();
        String key = findRegistrationKey(regs, image);
        if (key == null) {
            return fail(context, NMsg.ofC("Error: no registration '%s' — see '/model registered'.", image));
        }
        NaruRegistrationScope scope = scopeRef[0];
        boolean removed = task.session().removeRegistration(key, scope);
        NOptional<NaruRegistrationOrigin> origin = task.session().registrationOrigin(key);
        NMsg ok = origin.isPresent()
                ? NMsg.ofC("registration '%s' removed from the %s config — it is still declared in the %s config",
                NMsg.ofStyledPrimary1(key), scope.word(), origin.get().scope().word())
                : removed
                ? NMsg.ofC("registration '%s' removed", NMsg.ofStyledPrimary1(key))
                : NMsg.ofC("registration '%s' was not declared in the %s config",
                NMsg.ofStyledPrimary1(key), scope.word());
        context.outln(ok);
        return NaruStmtResult.ofSuccess(NElement.of(true),null);
    }

    /**
     * Every registration with its stored parameters, secrets masked: exactly what
     * {@code /model add} wrote. A {@code $NAME} value is shown as-is — it names a
     * variable, not a value, and masking a reference would hide what it points to.
     */
    public NaruStmtResult executeRegisteredList(SubCommandContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        Map<String, NaruModelRegistration> regs = task.session().registrations();
        if (regs == null || regs.isEmpty()) {
            NMsg msg = NMsg.ofC("No model registrations. Create one with %s (e.g. %s).",
                    NMsg.ofStyledPrimary1("/model add <id> --provider=<type>"),
                    NMsg.ofStyledPrimary1("/model add personal --provider=gemini"));
            context.errln(msg);
            return NaruStmtResult.ofError();
        }
        TreeMap<String, NaruModelRegistration> sorted = new TreeMap<>(regs);
        int width = 0;
        for (String id : sorted.keySet()) {
            width = Math.max(width, id.length());
        }
        NMsg header = NMsg.ofC("Registrations: %s", sorted.size());
        context.outln(header);
        context.outln(header);
        for (Map.Entry<String, NaruModelRegistration> e : sorted.entrySet()) {
            NaruModelRegistration r = e.getValue().masked();
            List<String> kv = new ArrayList<>();
            // the model pin reads first; models=auto when the registration lets its
            // type enumerate (§9)
            if (r.param("model").isPresent()) {
                kv.add("model=" + displayValue(r.param("model").get()));
            } else if (r.param("models").isPresent()) {
                kv.add("models=" + displayValue(r.param("models").get()));
            } else {
                kv.add("models=auto");
            }
            for (Map.Entry<String, NElement> p : r.params().entrySet()) {
                String k = p.getKey();
                if (k.equals("provider") || k.equals("model") || k.equals("models")) {
                    continue;
                }
                kv.add(k + "=" + displayValue(p.getValue()));
            }
            NMsg row = NMsg.ofC("  %s  %s  %s",
                    String.format("%-" + width + "s", e.getKey()),
                    registrationTypeColumn(r),
                    String.join("  ", kv));
            context.outln(row);
            context.outln(row.toString());
        }
        return NaruStmtResult.ofSuccess(NElement.of(context.out().filteredText()));
    }

    /**
     * One flag's occurrences as the typed element the store keeps (§7): the typed
     * flags stay numeric/boolean in the file instead of degrading to strings,
     * everything else is stored as the text the user typed, a repeated flag
     * becomes the array {@code --stop} expects, and a blank value clears the
     * parameter (signalled by a null return).
     */
    private static NElement flagElement(String key, List<String> values) {
        List<String> kept = new ArrayList<>();
        for (String v : values) {
            if (!NBlankable.isBlank(v)) {
                kept.add(v);
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        String k = key.toLowerCase();
        if (kept.size() == 1) {
            String v = kept.get(0);
            switch (k) {
                case "temperature":
                case "nucleusthreshold": {
                    NOptional<Float> f = NLiteral.of(v).asFloat();
                    return f.isPresent() ? NElement.of(f.get()) : NElement.ofString(v);
                }
                case "contextlength":
                case "maxtokens":
                case "candidatecount":
                case "maxretries": {
                    NOptional<Long> l = NLiteral.of(v).asLong();
                    return l.isPresent() ? NElement.of(l.get()) : NElement.ofString(v);
                }
                case "tools":
                case "probe":
                case "enabled": {
                    NOptional<Boolean> b = NLiteral.of(v).asBoolean();
                    return b.isPresent() ? NElement.of(b.get()) : NElement.ofString(v);
                }
                default:
                    return NElement.ofString(v);
            }
        }
        NElement[] items = new NElement[kept.size()];
        for (int i = 0; i < kept.size(); i++) {
            items[i] = NElement.ofString(kept.get(i));
        }
        return NElement.ofArray(items);
    }

    /**
     * The stored key of a registration: exact id first, then case-insensitive so
     * an id typed with another case still refers to the same entry.
     */
    private static String findRegistrationKey(Map<String, NaruModelRegistration> regs, String id) {
        if (regs == null || id == null) {
            return null;
        }
        if (regs.containsKey(id)) {
            return id;
        }
        for (String k : regs.keySet()) {
            if (k.equalsIgnoreCase(id)) {
                return k;
            }
        }
        return null;
    }

    private static NaruModelProvider findProvider(NaruSession session, String id) {
        if (id == null || session == null || session.registry() == null) {
            return null;
        }
        NOptional<NaruModelProvider> p = session.registry().provider(id);
        return p == null ? null : p.orNull();
    }

    /**
     * The type behind an instance id (the id itself when it is a built-in or
     * unknown): {@code /model list} annotates and filters by type.
     */
    private static String formatModelBytes(long bytes) {
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String providerType(NaruSession session, String id) {
        NaruModelProvider p = findProvider(session, id);
        return p == null || NBlankable.isBlank(p.type()) ? id : p.type();
    }

    /**
     * The type column of {@code /model registered}: a provider-based registration
     * names its type; a generic endpoint (no provider param) names the wire
     * protocol it speaks instead of the internal {@code custom} type.
     */
    private static String registrationTypeColumn(NaruModelRegistration r) {
        if ("custom".equalsIgnoreCase(r.provider())) {
            return r.protocol().orElse("custom");
        }
        return r.provider();
    }

    /**
     * Why a bare id did not resolve when it names a registration (design §3): no
     * available model points at configuration (key, url, probe), several models
     * ask the user to pick one. Null when the id is not a registration at all.
     */
    private static NMsg registrationSelectionError(NaruSession session, String ref) {
        if (session == null || ref == null || ref.indexOf('/') >= 0) {
            return null;
        }
        String key = findRegistrationKey(session.registrations(), ref);
        if (key == null) {
            return null;
        }
        List<String> available = new ArrayList<>();
        List<NaruModelKey> all = session.registry() == null ? null : session.registry().modelsKeys(session);
        if (all != null) {
            for (NaruModelKey k : all) {
                if (k != null && k.provider().equalsIgnoreCase(key)) {
                    available.add(k.model());
                }
            }
        }
        if (available.isEmpty()) {
            return NMsg.ofC(
                    "Error: registration '%s' has no available model — check its api key, url or probe (see '/model registered').",
                    key).asError();
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String m : available) {
            if (shown++ >= 10) {
                sb.append("  ... and ").append(available.size() - 10).append(" more\n");
                break;
            }
            sb.append("  ").append(key).append("/").append(m).append("\n");
        }
        return NMsg.ofC(
                "Error: registration '%s' does not pin a single model — its available models:\n%sUse '/model use <key>' to pick one.",
                key, sb).asError();
    }

    /**
     * A stored parameter as {@code /model registered} prints it: strings verbatim
     * (a masked key or a {@code $NAME} reference is informative as-is), arrays and
     * objects in their element form.
     */
    private static String displayValue(NElement e) {
        if (e == null || e.isNull()) {
            return "";
        }
        if (e.isArray() || e.isAnyObject()) {
            return e.toString();
        }
        NOptional<String> v = e.asStringValue();
        return v != null && v.isPresent() ? v.get() : e.toString();
    }

    private static NaruStmtResult fail(SubCommandContext context, NMsg msg) {
        NMsg e = msg.asError();
        context.errln(e);
        return NaruStmtResult.ofError();
    }

}
