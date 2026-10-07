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
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NMsg msg = NMsg.ofC("%s", task.model().toText());
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                return NaruStmtResult.ofSuccess(msg.toString());
            }
        });
        register(new AbstractSubCommand("use", NText.ofPlain("select model"),
                new SubCommandHelp(NText.of("<model>"), NText.ofPlain("model name, 'provider/model', registration id, or index of the last listing ('/model list [--free|--provider=x|<filter>]')"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NOptional<NArg> n = cmdLine.next();
                if (!n.isPresent()) {
                    NMsg msg = NMsg.ofC("Error: missing model name to set.").asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
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
                        task.log(NaruLogMode.AGENT_RESPONSE, regErr);
                        return NaruStmtResult.ofError(regErr.toString());
                    }
                    NMsg msg = NMsg.ofC("Error: model %s not found%s.",
                            a.image(),
                            NLiteral.of(a.image()).asInt().isPresent() ? " (see the indexes printed by '/model list')" : ""
                    ).asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                context.task().setModel(k);
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Selected model : %s",
                        task.model().toText()));
                return NaruStmtResult.ofSuccess(null);
            }
        });
        register(new AbstractSubCommand("use-global", NText.ofPlain("use model as default globally")
                , new SubCommandHelp(NText.of("<model>"), NText.ofPlain("model name to set as default globally"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NOptional<NArg> n = cmdLine.next();
                if (!n.isPresent()) {
                    NMsg msg = NMsg.ofC("Error: missing model name to set.").asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NArg a = n.get();
                NaruModelConfig k = task.session().findModel(a.image()).orNull();
                if (k == null) {
                    NMsg msg = NMsg.ofC("Error: model %s not found.",
                            a.image()).asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                context.task().setModel(k);
                NAssert.requireNamedNonNull(k, "key");
                context.task().session().setProjectEnv("model", k.toElement(), NaruVisibility.PRIVATE);
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("switch global model : %s",
                        task.model().toText()));
                return NaruStmtResult.ofSuccess(null);
            }
        });

        register(new AbstractSubCommand("list", NText.ofPlain("list available models"),
                new SubCommandHelp(NText.of("[<filter>] [--provider=<name>] [--free]"), NText.ofPlain("list available models, optionally filtered by keyword, provider or free-only. The printed indexes can be reused with '/model use <n>' until the next listing"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeList(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("", NText.ofPlain("special..."),
                new SubCommandHelp("<n>", "set model by index (as printed by the last '/model' listing)"),
                new SubCommandHelp("<filter>", "list models matching the given filter / keyword")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
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
                new SubCommandHelp(NText.of("<id> --provider=<type> [--protocol=<wire>] [--model=<id>|--models=a,b] [--url=…] [--apiKey=sk-…|$VAR] [--temperature=… --contextLength=…]"), NText.ofPlain("register a provider instance addressed as <id>; with an existing id, merges the given parameters into it"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeRegistration(context, cmdLine, "add");
            }
        });
        register(new AbstractSubCommand("update", NText.ofPlain("change a registration's parameters"),
                new SubCommandHelp(NText.of("<id> [--temperature=… --apiKey=…]"), NText.ofPlain("merge the given parameters into an existing registration; an empty value clears the parameter"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeRegistration(context, cmdLine, "update");
            }
        });
        register(new AbstractSubCommand("remove", NText.ofPlain("delete a registration"),
                new SubCommandHelp(NText.of("<id>"), NText.ofPlain("delete a registration and its stored parameters"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeRemoveRegistration(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("registered", NText.ofPlain("list registrations (api keys masked)")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeRegisteredList(context, cmdLine);
            }
        });

    }

    public NaruStmtResult executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        String filter = null;
        String providerFilter = null;
        boolean freeOnly = false;
        while (cmdLine.hasNext()) {
            NArg a = cmdLine.next().get();
            if (a.isOption()) {
                if (a.key().equals("--provider") || a.key().equals("-p")) {
                    providerFilter = a.getStringValue().orNull();
                } else if (a.key().equals("--free") || a.key().equals("-f")) {
                    freeOnly = true;
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
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
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
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Selected model : %s", task.model().toText()));
                return NaruStmtResult.ofSuccess(null);
            }
        }

        if (models.isEmpty()) {
            String crit = filter != null ? "'" + filter + "'" : (providerFilter != null ? "provider '" + providerFilter + "'" : "criteria");
            NMsg msg = NMsg.ofC("No available models found matching %s (%s total models available).", crit, allModels.size());
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofSuccess(null);
        }

        String titleSuffix = (filter != null || providerFilter != null || freeOnly) ? " (filtered)" : "";
        // freeze the displayed order: '/model use <n>' must resolve against these exact
        // rows, not the unfiltered catalog, otherwise filtering renumbers the list and
        // every index the user copies becomes a different model.
        task.session().setListedModels(models.stream().map(NaruModelInfo::key).collect(Collectors.toList()));
        NStringBuilder sb = NStringBuilder.of();
        NMsg msg = NMsg.ofC("%s Available models%s:", models.size(), titleSuffix);
        task.log(NaruLogMode.AGENT_RESPONSE, msg);
        sb.println(msg.toString());
        int zeros = (int) Math.ceil(Math.log10(models.size()));
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", Math.max(1, zeros)));
        NaruModelConfig selectedModel = task.model();
        int index = 1;
        for (NaruModelInfo model : models) {

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
            long cl = model.capabilities().contextLength();
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
            NMsg row = NMsg.ofC("  %s[%s] %s%s%s",
                    extra2,
                    NMsg.ofStyledNumber(zformat.format(index)),
                    model.toText(),
                    typeMsg,
                    extra3 == null ? "" : extra3
            );
            task.log(NaruLogMode.AGENT_RESPONSE, row);
            sb.println(row.toString());
            index++;
        }
        NMsg hint = NMsg.ofC("Use %s or %s with any of these %sindexes%s, or a full %s.",
                NMsg.ofStyledPrimary1("/model use <n>"),
                NMsg.ofStyledPrimary1("/model <n>"),
                NMsg.ofStyledSuccess(""),
                NMsg.ofStyledSuccess(""),
                NMsg.ofStyledPrimary1("provider/model")
        );
        task.log(NaruLogMode.AGENT_RESPONSE, hint);
        sb.println(hint.toString());
        return NaruStmtResult.ofSuccess(sb.toString());
    }


    public NaruStmtResult executeSetByNumber(NaruDirectiveCallContext context, int nbr) {
        NaruTask task = context.task();
        NaruModelConfig k = task.session().findModel(String.valueOf(nbr)).orNull();
        if (k == null) {
            int listed = task.session().listedModels().size();
            NMsg msg = (listed > 0
                    ? NMsg.ofC("Error: no model at index %s in the last listing (%s model(s) shown). Use %s to refresh.",
                    nbr, listed, NMsg.ofStyledPrimary1("/model list"))
                    : NMsg.ofC("Error: no model at index %s. Use %s first.",
                    nbr, NMsg.ofStyledPrimary1("/model list"))).asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        context.task().setModel(k);
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Selected model : %s",
                task.model().toText()));
        return NaruStmtResult.ofSuccess(null);
    }

    // ── /model add|update|remove|registered ───────────────────────────────────

    /**
     * Creates or updates a registration (design doc §4/§7/§8). The first non-option
     * is the registration id; every {@code --key=value} becomes one of its
     * parameters — {@code --provider} selects the type (required when the id is
     * new), {@code --protocol} the wire shape, the rest are merged into every
     * model selected under the id. An update is a merge: parameters the flags do
     * not mention are kept, an empty value clears one.
     */
    public NaruStmtResult executeRegistration(NaruDirectiveCallContext context, NCmdLine cmdLine, String operation) {
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
        if (NBlankable.isBlank(typedId)) {
            return fail(context, update
                    ? NMsg.ofC("Error: missing registration id (usage: /model update <id> --<param>=<value>)")
                    : NMsg.ofC("Error: missing registration id (usage: /model add <id> --provider=<type> \u2026)"));
        }
        if (typedId.indexOf('/') >= 0) {
            return fail(context, NMsg.ofC(
                    "Error: invalid registration id '%s': '/' separates the instance from the model in a key like '%s'.",
                    typedId, typedId + "/<model>"));
        }

        // the stored registration: exact id first, then case-insensitive so an id
        // written differently never forks the entry
        Map<String, NaruModelRegistration> regs = session.registrations();
        String storedId = typedId;
        NaruModelRegistration existing = regs == null ? null : regs.get(typedId);
        if (existing == null && regs != null) {
            String key = findRegistrationKey(regs, typedId);
            if (key != null) {
                storedId = key;
                existing = regs.get(key);
            }
        }
        if (update && existing == null) {
            return fail(context, NMsg.ofC(
                    "Error: no registration '%s' — see '/model registered' (create it with '/model add').", typedId));
        }

        // assemble the parameter map: stored parameters first (an update is a merge),
        // then the flags — an empty value clears the parameter, and provider is kept
        // first so a hand-edited file reads naturally
        LinkedHashMap<String, NElement> params = new LinkedHashMap<>();
        if (existing != null) {
            params.putAll(existing.params());
        }
        for (Map.Entry<String, List<String>> f : flags.entrySet()) {
            NElement v = flagElement(f.getKey(), f.getValue());
            if (v == null) {
                params.remove(f.getKey());
            } else if (f.getKey().equals("provider")) {
                params.remove("provider");
                LinkedHashMap<String, NElement> ordered = new LinkedHashMap<>();
                ordered.put("provider", v);
                ordered.putAll(params);
                params = ordered;
            } else {
                params.put(f.getKey(), v);
            }
        }
        if (params.get("provider") == null || params.get("provider").isNull()) {
            return fail(context, NMsg.ofC(
                    "Error: missing --provider=<type> (e.g. '/model add %s --provider=gemini'). Known types: %s",
                    typedId, knownProviderTypes(session)));
        }

        NaruModelRegistration reg;
        try {
            reg = NaruModelRegistration.of(storedId, params);
        } catch (RuntimeException e) {
            return fail(context, NMsg.ofC("Error: %s", e.getMessage()));
        }

        // a free id: a built-in (or any other non-registration) instance is never
        // overwritten — the session reload refuses that too
        if (existing == null) {
            NaruModelProvider collides = findProvider(session, storedId);
            if (collides != null) {
                return fail(context, NMsg.ofC(
                        "Error: id '%s' is already used by provider '%s' (type %s) — choose another id.",
                        storedId, collides.name(), collides.type()));
            }
        }

        // --provider names a *type*, never an instance id: the wire shorthands
        // (openapi, anthropic) were already normalized to 'wire' by of()
        NaruModelProvider typeProvider = findProviderType(session, reg.provider());
        if (typeProvider == null) {
            return fail(context, NMsg.ofC(
                    "Error: unknown provider type '%s'. Known types: %s (openapi and anthropic are wire shorthands).",
                    reg.provider(), knownProviderTypes(session)));
        }

        // --protocol must name a wire shape this type speaks (§8): rejected rather
        // than silently ignored — an unknown id is never guessed at
        String protocol = null;
        NOptional<String> proto = reg.protocol();
        if (proto.isPresent()) {
            String wanted = proto.get();
            String match = null;
            Set<String> supported = typeProvider.supportedProtocols();
            if (supported != null) {
                for (String sp : supported) {
                    if (sp.equalsIgnoreCase(wanted)) {
                        match = sp;
                        break;
                    }
                }
            }
            if (match == null) {
                return fail(context, supported == null || supported.isEmpty()
                        ? NMsg.ofC("Error: provider type '%s' does not support --protocol: its wire shape is fixed.", reg.provider())
                        : NMsg.ofC("Error: unknown protocol '%s' for provider type '%s' (supported: %s).",
                        wanted, reg.provider(), String.join(", ", new TreeSet<>(supported))));
            }
            if (!match.equals(wanted)) {
                reg = reg.withParam("protocol", match);   // store the canonical id
            }
            protocol = match;
        }

        boolean created = existing == null;
        session.putRegistration(reg);
        NMsg ok = created
                ? NMsg.ofC("registration '%s' created (provider=%s%s)",
                NMsg.ofStyledPrimary1(reg.id()), reg.provider(),
                protocol == null ? "" : ", protocol=" + protocol)
                : NMsg.ofC("registration '%s' updated", NMsg.ofStyledPrimary1(reg.id()));
        task.log(NaruLogMode.AGENT_RESPONSE, ok);
        if (created && reg.provider().equals("wire")
                && !reg.param("model").isPresent() && !reg.param("models").isPresent()) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC(
                    "note: a wire registration lists only the models it declares — add %s to make them visible.",
                    NMsg.ofStyledPrimary1("--models=a,b")));
        }
        return NaruStmtResult.ofSuccess(ok.toString());
    }

    /**
     * Deletes a registration and its stored parameters from both visibility files.
     */
    public NaruStmtResult executeRemoveRegistration(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent() || NBlankable.isBlank(n.get().image())) {
            return fail(context, NMsg.ofC("Error: missing registration id (see '/model registered')."));
        }
        Map<String, NaruModelRegistration> regs = task.session().registrations();
        String key = findRegistrationKey(regs, n.get().image());
        if (key == null) {
            return fail(context, NMsg.ofC("Error: no registration '%s' — see '/model registered'.", n.get().image()));
        }
        task.session().removeRegistration(key);
        NMsg ok = NMsg.ofC("registration '%s' removed", NMsg.ofStyledPrimary1(key));
        task.log(NaruLogMode.AGENT_RESPONSE, ok);
        return NaruStmtResult.ofSuccess(ok.toString());
    }

    /**
     * Every registration with its stored parameters, secrets masked: exactly what
     * {@code /model add} wrote. A {@code $NAME} value is shown as-is — it names a
     * variable, not a value, and masking a reference would hide what it points to.
     */
    public NaruStmtResult executeRegisteredList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        Map<String, NaruModelRegistration> regs = task.session().registrations();
        if (regs == null || regs.isEmpty()) {
            NMsg msg = NMsg.ofC("No model registrations. Create one with %s (e.g. %s).",
                    NMsg.ofStyledPrimary1("/model add <id> --provider=<type>"),
                    NMsg.ofStyledPrimary1("/model add personal --provider=gemini"));
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofSuccess(msg.toString());
        }
        TreeMap<String, NaruModelRegistration> sorted = new TreeMap<>(regs);
        int width = 0;
        for (String id : sorted.keySet()) {
            width = Math.max(width, id.length());
        }
        NStringBuilder sb = NStringBuilder.of();
        NMsg header = NMsg.ofC("Registrations: %s", sorted.size());
        task.log(NaruLogMode.AGENT_RESPONSE, header);
        sb.println(header.toString());
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
                    r.provider(),
                    String.join("  ", kv));
            task.log(NaruLogMode.AGENT_RESPONSE, row);
            sb.println(row.toString());
        }
        return NaruStmtResult.ofSuccess(sb.toString());
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
     * The provider implementing a <b>type</b> (never an instance id): materializing
     * a registration and validating {@code --provider} both need the type's class,
     * and a built-in always exists under its own type name.
     */
    private static NaruModelProvider findProviderType(NaruSession session, String type) {
        if (NBlankable.isBlank(type)) {
            return null;
        }
        NaruModelProvider p = findProvider(session, type);
        if (p != null && type.equalsIgnoreCase(p.type())) {
            return p;
        }
        Map<String, NaruModelProvider> all = session == null || session.registry() == null
                ? null : session.registry().modelProviders();
        if (all != null) {
            for (NaruModelProvider v : all.values()) {
                if (v != null && type.equalsIgnoreCase(v.type())) {
                    return v;
                }
            }
        }
        return null;
    }

    /**
     * The type behind an instance id (the id itself when it is a built-in or
     * unknown): {@code /model list} annotates and filters by type.
     */
    private static String providerType(NaruSession session, String id) {
        NaruModelProvider p = findProvider(session, id);
        return p == null || NBlankable.isBlank(p.type()) ? id : p.type();
    }

    private static String knownProviderTypes(NaruSession session) {
        Map<String, NaruModelProvider> all = session == null || session.registry() == null
                ? null : session.registry().modelProviders();
        if (all == null || all.isEmpty()) {
            return "";
        }
        TreeSet<String> types = new TreeSet<>();
        for (NaruModelProvider v : all.values()) {
            if (v != null && !NBlankable.isBlank(v.type())) {
                types.add(v.type());
            }
        }
        return String.join(", ", types);
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

    private static NaruStmtResult fail(NaruDirectiveCallContext context, NMsg msg) {
        NMsg e = msg.asError();
        context.task().log(NaruLogMode.AGENT_RESPONSE, e);
        return NaruStmtResult.ofError(e.toString());
    }

}
