package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.agent.NAruVisibility;
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
import net.thevpc.nuts.text.NTextBuilder;
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
                new SubCommandHelp(NText.of("<model>"), NText.ofPlain("model name, 'provider/model', alias, or index of the last listing ('/model list [--free|--provider=x|<filter>]')"))
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
        register(new AbstractSubCommand("install", NText.ofPlain("install a new model (equivalent to ollama pull)"),
                new SubCommandHelp(NText.of("<model>"), NText.ofPlain("model name to install"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NOptional<NArg> n = cmdLine.next();
                if (!n.isPresent()) {
                    NMsg msg = NMsg.ofC("Error: missing model name to install.").asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NaruModelKey key = NaruModelKey.parse(n.get().image()).get();
                if (NBlankable.isBlank(key.provider())) {
                    key = new NaruModelKey("ollama", n.get().image());
                }
                NaruModelProvider naruModelProvider = task.session().registry().provider(key.provider()).orNull();
                if (naruModelProvider == null) {
                    NMsg msg = NMsg.ofC("Error: provider not found :%s", key.provider()).asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                if (naruModelProvider.isSupportedInstallModel()) {
                    naruModelProvider.installModel(key, task.session());
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("model installed/pulled :%s", key.toMsg()).asError());
                } else {
                    NMsg msg = NMsg.ofC("Error: unsupported 'install' :%s", key.toMsg()).asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                return NaruStmtResult.ofSuccess(null);
            }
        });
        register(new AbstractSubCommand("uninstall", NText.ofPlain("uninstall a new model (equivalent to ollama delete)"),
                new SubCommandHelp(NText.of("<model>"), NText.ofPlain("model name to uninstall"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NOptional<NArg> n = cmdLine.next();
                if (!n.isPresent()) {
                    NMsg msg = NMsg.ofC("Error: missing model name to uninstall.").asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NaruModelKey key = NaruModelKey.parse(n.get().image()).get();
                if (NBlankable.isBlank(key.provider())) {
                    key = new NaruModelKey("ollama", n.get().image());
                }
                NaruModelProvider naruModelProvider = task.session().registry().provider(key.provider()).orNull();
                if (naruModelProvider == null) {
                    NMsg msg = NMsg.ofC("Error: provider not found :%s", key.provider()).asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                if (naruModelProvider.isSupportedUninstallModel()) {
                    naruModelProvider.uninstallModel(key, task.session());
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("model uninstalled :%s", key.toMsg()).asError());
                } else {
                    NMsg msg = NMsg.ofC("Error: unsupported 'uninstall' :%s", key.toMsg()).asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                return NaruStmtResult.ofSuccess(null);
            }
        });
        register(new AbstractSubCommand("unload", NText.ofPlain("unload model and free VRAM/RAM"),
                new SubCommandHelp(NText.of("<model>"), NText.ofPlain("model name to unload"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NOptional<NArg> n = cmdLine.next();
                if (!n.isPresent()) {
                    NMsg msg = NMsg.ofC("Error: missing model name to unload.").asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NaruModelKey key = NaruModelKey.parse(n.get().image()).get();
                if (NBlankable.isBlank(key.provider())) {
                    key = new NaruModelKey("ollama", n.get().image());
                }
                NaruModelProvider naruModelProvider = task.session().registry().provider(key.provider()).orNull();
                if (naruModelProvider == null) {
                    NMsg msg = NMsg.ofC("Error: provider not found :%s", key.provider()).asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                if (naruModelProvider.isSupportedUnloadModel()) {
                    naruModelProvider.unloadModel(key, task.session());
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("model unload :%s", key.toMsg()).asError());
                } else {
                    NMsg msg = NMsg.ofC("Error: unsupported 'unload' :%s", key.toMsg()).asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                return NaruStmtResult.ofSuccess(null);
            }
        });
        register(new AbstractSubCommand("ps", NText.ofPlain("list loaded (in VRAM) models")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
//                NOptional<NArg> n = cmdLine.next();
                //should call all providers
                String provider = "ollama";
//                if (n.isPresent()) {
//                    String provider2 = n.get().image();
//                    if(!NBlankable.isBlank(provider2)) {
//                        provider=provider2;
//                    }
//                }
                NaruModelProvider naruModelProvider = task.session().registry().provider(provider).orNull();
                if (naruModelProvider == null) {
                    NMsg msg = NMsg.ofC("Error: provider not found :%s", provider).asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                if (naruModelProvider.isSupportedPsModel()) {
                    List<NaruModelPsResult> elements = naruModelProvider.psModel(task.session());
                    NStringBuilder sb = NStringBuilder.of();
                    for (NaruModelPsResult element : elements) {
                        NMsg row = NMsg.ofC("%s size: %s vram-size: %s (%s on VRAM) %s",
                                element.getModel().toMsg(),
                                NMsg.ofStyledNumber(NMemoryFormat.DEFAULT.format(NMemorySize.ofBytes(element.getSize()).normalize().canonicalize())),
                                NMsg.ofStyledNumber(NMemoryFormat.DEFAULT.format(NMemorySize.ofBytes(element.getSizeVram()).normalize().canonicalize())),
                                NMsg.ofStyledNumber(
                                        (element.getSize() == 0 ? "0.00" :
                                                new DecimalFormat("0.00").format((100.0 * element.getSizeVram() / element.getSize()))
                                        ) + "%"
                                ),
                                element.getExpiresAt()
                        );
                        task.log(NaruLogMode.AGENT_RESPONSE, row);
                        sb.println(row.toString());
                    }
                    return NaruStmtResult.ofSuccess(sb.toString());
                } else {
                    NMsg msg = NMsg.ofC("Error: unsupported 'ps' :%s", provider).asError();
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
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
                context.task().session().setProjectEnv("model", k.toElement(), NAruVisibility.PRIVATE);
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("switch global model : %s",
                        task.model().toText()));
                return NaruStmtResult.ofSuccess(null);
            }
        });

        register(new AbstractSubCommand("alias", NText.ofPlain("manager model aliases")
                , new SubCommandHelp(NText.of(""), NText.ofPlain("list aliases"))
                , new SubCommandHelp(NText.of("<alias>=<name>"), NText.ofPlain("set alias"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                if (cmdLine.isEmpty()) {
                    return executeListAlias(context, cmdLine);
                } else {
                    return executeSetAlias(context, cmdLine);
                }
            }
        });
        register(new AbstractSubCommand("update", NText.ofPlain("update an alias to configure context length etc...")
                , new SubCommandHelp(NText.of("<alias> <options>"), NText.ofPlain("update option of the alias"))
                , new SubCommandHelp(NText.of("<alias> --alias=<value>"), NText.ofPlain("update alias name"))
                , new SubCommandHelp(NText.of("<alias> --alias=<value>"), NText.ofPlain("update alias name"))
                , new SubCommandHelp(NText.of("<alias> --model=<value>"), NText.ofPlain("update model name"))
                , new SubCommandHelp(NText.of("<alias> --contextLength=<value>"), NText.ofPlain("update context length (ex: 15b)"))
                , new SubCommandHelp(NText.of("<alias> --temperature=<value>"), NText.ofPlain("update temperature length (ex: 0.6)"))
                , new SubCommandHelp(NText.of("<alias> --nucleusThreshold=<value>"), NText.ofPlain("update nucleusThreshold (top_p) (ex: 0.6)"))
                , new SubCommandHelp(NText.of("<alias> --candidateCount=<value>"), NText.ofPlain("update candidateCount ('top_k') (ex: 2)"))
                , new SubCommandHelp(NText.of("<alias> --maxTokens=<value>"), NText.ofPlain("update maxTokens ('num_predict') (ex: 2)"))
                , new SubCommandHelp(NText.of("<alias> --stop=<value>"), NText.ofPlain("update/append stop words ('stop') (ex: '<|start>')"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeSetAlias(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("unalias", NText.ofPlain("remove alias by name")
                , new SubCommandHelp(NText.of("<alias>"), NText.ofPlain("remove alias named <alias>"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeUnsetAlias(context, cmdLine);
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
        register(new AbstractSubCommand("endpoint", NText.ofPlain("manage config-driven custom provider endpoints"),
                new SubCommandHelp(NText.of("add <name> --url=<url> [--type=openapi|anthropic] [--apiKey=<key>] [--models=<a,b>] [--chatPath=<path>] [--contextLength=<n>] [--tools=true|false] [--probe=true|false]"), NText.ofPlain("register (or update) a config-driven custom endpoint")),
                new SubCommandHelp(NText.of("remove <name>"), NText.ofPlain("unregister a config-driven custom endpoint")),
                new SubCommandHelp(NText.of("list"), NText.ofPlain("list all config-driven custom endpoints"))
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeEndpoint(context, cmdLine);
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
        for (NaruModelInfo m : allModels) {
            if (providerFilter != null && !m.provider().equalsIgnoreCase(providerFilter)) {
                continue;
            }
            if (freeOnly && !m.model().endsWith(":free") && !m.provider().equals("ollama")) {
                continue;
            }
            if (filter != null) {
                String flc = filter.toLowerCase();
                boolean match = m.model().toLowerCase().contains(flc)
                        || m.provider().toLowerCase().contains(flc)
                        || task.session().modelAliases().values().stream()
                        .filter(x -> x.key().equals(m.key()))
                        .anyMatch(x -> x.name().toLowerCase().contains(flc));
                if (!match) {
                    continue;
                }
            }
            models.add(m);
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

            NTextBuilder extra1 = null;
            List<String> currAliases = task.session().modelAliases().values().stream().filter(x -> x.key().equals(model.key())).map(x -> x.name())
                    .sorted()
                    .collect(Collectors.toList());
            if (!currAliases.isEmpty()) {
                extra1 = NTextBuilder.of();
                extra1.append(NMsg.ofStyledSeparator(" ("));
                for (int i = 0; i < currAliases.size(); i++) {
                    if (i > 0) {
                        extra1.append(NMsg.ofStyledSeparator(", "));
                    }
                    String c = currAliases.get(i);
                    if (selectedModel != null && Objects.equals(c, selectedModel.name())) {
                        extra1.append(NMsg.ofStyledPrimary3(c));
                    } else {
                        extra1.append(NMsg.ofStyledPrimary1(c));
                    }
                }
                extra1.append(NMsg.ofStyledSeparator(")"));
            }

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
            NMsg row = NMsg.ofC("  %s[%s] %s%s%s",
                    extra2,
                    NMsg.ofStyledNumber(zformat.format(index)),
                    model.toText(),
                    extra1 == null ? "" : extra1,
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

    // ── /model endpoint add|remove|list ────────────────────────────────────────

    public NaruStmtResult executeEndpoint(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NOptional<NArg> op = cmdLine.next();
        if (!op.isPresent()) {
            NMsg msg = NMsg.ofC("Error: missing endpoint operation (add|remove|list).").asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        switch (op.get().image()) {
            case "add":
                return executeEndpointAdd(context, cmdLine);
            case "remove":
                return executeEndpointRemove(context, cmdLine);
            case "list":
                return executeEndpointList(context, cmdLine);
            default:
                NMsg msg = NMsg.ofC("Error: invalid endpoint operation '%s' (expected add|remove|list).", op.get().image()).asError();
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                return NaruStmtResult.ofError(msg.toString());
        }
    }

    public NaruStmtResult executeEndpointAdd(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NRef<String> name = NRef.of();
        NRef<String> url = NRef.of();
        NRef<String> type = NRef.of();
        NRef<String> apiKey = NRef.of();
        NRef<String> models = NRef.of();
        NRef<String> chatPath = NRef.of();
        NRef<Long> contextLength = NRef.of();
        NRef<Boolean> tools = NRef.of();
        NRef<Boolean> probe = NRef.of();

        cmdLine.matcher()
                .whenNonOption()
                .asArg(a -> {
                    if (name.isNull()) {
                        name.set(a.asString().orNull());
                    } else {
                        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: unexpected argument %s", a.toString()).asError());
                    }
                })
                .when("--url").asEntry(a -> url.set(a.getStringValue().orNull()))
                .when("--type").asEntry(a -> type.set(a.getStringValue().orNull()))
                .when("--apiKey").asEntry(a -> apiKey.set(a.getStringValue().orNull()))
                .when("--models").asEntry(a -> models.set(a.getStringValue().orNull()))
                .when("--chatPath").asEntry(a -> chatPath.set(a.getStringValue().orNull()))
                .when("--contextLength").asEntry(a -> contextLength.set(a.getLongValue().orNull()))
                .when("--tools").asEntry(a -> tools.set(a.getBooleanValue().orNull()))
                .when("--probe").asEntry(a -> probe.set(a.getBooleanValue().orNull()))
                .requireAll();

        if (NBlankable.isBlank(name.get())) {
            NMsg msg = NMsg.ofC("Error: missing endpoint name.").asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        if (NBlankable.isBlank(url.get())) {
            NMsg msg = NMsg.ofC("Error: missing --url for endpoint '%s'.", name.get()).asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }

        NaruSession session = task.session();
        List<String> eps = readCustomEndpoints(session);
        if (!eps.contains(name.get())) {
            eps.add(name.get());
            session.setProjectEnv("custom.endpoints", NElement.ofString(String.join(",", eps)), NAruVisibility.PUBLIC);
        }
        String prefix = "custom.endpoints." + name.get();
        session.setProjectEnv(prefix + ".url", NElement.ofString(url.get()), NAruVisibility.PUBLIC);
        if (!type.isNull() && !NBlankable.isBlank(type.get())) {
            session.setProjectEnv(prefix + ".type", NElement.ofString(type.get()), NAruVisibility.PUBLIC);
        }
        if (!apiKey.isNull() && !NBlankable.isBlank(apiKey.get())) {
            session.setProjectEnv(prefix + ".apiKey", NElement.ofString(apiKey.get()), NAruVisibility.PRIVATE);
        }
        if (!models.isNull() && !NBlankable.isBlank(models.get())) {
            session.setProjectEnv(prefix + ".models", NElement.ofString(models.get()), NAruVisibility.PUBLIC);
        }
        if (!chatPath.isNull() && !NBlankable.isBlank(chatPath.get())) {
            session.setProjectEnv(prefix + ".chatPath", NElement.ofString(chatPath.get()), NAruVisibility.PUBLIC);
        }
        if (!contextLength.isNull() && contextLength.get() != null) {
            session.setProjectEnv(prefix + ".contextLength", NElement.of(contextLength.get()), NAruVisibility.PUBLIC);
        }
        if (!tools.isNull() && tools.get() != null) {
            session.setProjectEnv(prefix + ".tools", NElement.of(tools.get()), NAruVisibility.PUBLIC);
        }
        if (!probe.isNull() && probe.get() != null) {
            session.setProjectEnv(prefix + ".probe", NElement.of(probe.get()), NAruVisibility.PUBLIC);
        }

        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("endpoint '%s' added (type=%s, url=%s)",
                NMsg.ofStyledPrimary1(name.get()),
                type.isNull() ? "openapi" : type.get(),
                url.get()
        ));
        return NaruStmtResult.ofSuccess(null);
    }

    public NaruStmtResult executeEndpointRemove(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NRef<String> name = NRef.of();
        cmdLine.matcher()
                .whenNonOption()
                .asArg(a -> {
                    if (name.isNull()) {
                        name.set(a.asString().orNull());
                    } else {
                        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: unexpected argument %s", a.toString()).asError());
                    }
                })
                .requireAll();
        if (NBlankable.isBlank(name.get())) {
            NMsg msg = NMsg.ofC("Error: missing endpoint name.").asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        NaruSession session = task.session();
        List<String> eps = readCustomEndpoints(session);
        if (!eps.remove(name.get())) {
            NMsg msg = NMsg.ofC("Error: endpoint '%s' not found.", name.get()).asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        session.setProjectEnv("custom.endpoints", NElement.ofString(String.join(",", eps)), NAruVisibility.PUBLIC);
        String prefix = "custom.endpoints." + name.get();
        session.setProjectEnv(prefix + ".url", null, NAruVisibility.PUBLIC);
        session.setProjectEnv(prefix + ".type", null, NAruVisibility.PUBLIC);
        session.setProjectEnv(prefix + ".apiKey", null, NAruVisibility.PRIVATE);
        session.setProjectEnv(prefix + ".models", null, NAruVisibility.PUBLIC);
        session.setProjectEnv(prefix + ".chatPath", null, NAruVisibility.PUBLIC);
        session.setProjectEnv(prefix + ".contextLength", null, NAruVisibility.PUBLIC);
        session.setProjectEnv(prefix + ".tools", null, NAruVisibility.PUBLIC);
        session.setProjectEnv(prefix + ".probe", null, NAruVisibility.PUBLIC);
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("endpoint '%s' removed.", NMsg.ofStyledPrimary1(name.get())));
        return NaruStmtResult.ofSuccess(null);
    }

    public NaruStmtResult executeEndpointList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NaruSession session = task.session();
        List<String> eps = readCustomEndpoints(session);
        if (eps.isEmpty()) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("No custom endpoint registered. Use '/model endpoint add <name> --url=<url> [--type=openapi|anthropic]'."));
            return NaruStmtResult.ofSuccess(null);
        }
        NStringBuilder sb = NStringBuilder.of();
        NMsg msg = NMsg.ofC("Custom endpoints: %s", eps.size());
        task.log(NaruLogMode.AGENT_RESPONSE, msg);
        sb.println(msg.toString());
        for (String endpoint : eps) {
            String prefix = "custom.endpoints." + endpoint;
            String url = session.agent().env().get(prefix + ".url").flatMap(NElement::asStringValue).orElse("?");
            String type = session.agent().env().get(prefix + ".type").flatMap(NElement::asStringValue).orElse("openapi");
            String models = session.agent().env().get(prefix + ".models").flatMap(NElement::asStringValue).orElse("");
            String probe = session.agent().env().get(prefix + ".probe")
                    .flatMap(NElement::asBooleanValue)
                    .map(String::valueOf)
                    .orElse("true");
            NMsg row = NMsg.ofC("  [%s] %s %s %s%s",
                    NMsg.ofStyledPrimary1(endpoint),
                    type,
                    url,
                    models.isEmpty() ? "" : "models=" + models,
                    " probe=" + probe
            );
            task.log(NaruLogMode.AGENT_RESPONSE, row);
            sb.println(row.toString());
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    private List<String> readCustomEndpoints(NaruSession session) {
        List<String> eps = new ArrayList<>();
        session.agent().env().get("custom.endpoints").flatMap(NElement::asStringValue)
                .ifPresent(v -> {
                    for (String s : v.split("[,\\s]+")) {
                        if (!s.isBlank() && !eps.contains(s.trim())) {
                            eps.add(s.trim());
                        }
                    }
                });
        return eps;
    }

    public NaruStmtResult executeListAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        Map<String, NaruModelConfig> aliases = task.session().modelAliases();
        NStringBuilder sb = NStringBuilder.of();
        NMsg msg = NMsg.ofC("Aliases: %s", aliases.size());
        task.log(NaruLogMode.AGENT_RESPONSE, msg);
        sb.println(msg.toString());
        int index = 1;

        if (aliases.isEmpty()) {
            return NaruStmtResult.ofSuccess(sb.toString());
        }
        int zeros = (int) Math.ceil(Math.log10(aliases.size()));
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", zeros));
        for (Map.Entry<String, NaruModelConfig> e : aliases.entrySet().stream().sorted(Comparator.comparing(x -> x.getKey())).collect(Collectors.toList())) {
            NMsg row = NMsg.ofC("[%s] %s",
                    NMsg.ofStyledNumber(zformat.format(index)),
                    e.getValue().toText());
            task.log(NaruLogMode.AGENT_RESPONSE, row);
            sb.println(row.toString());
            index++;
        }
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    public NaruStmtResult executeSetAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NRef<String> aliasName = NRef.of();
        NRef<String> modelName = NRef.of();
        NRef<Long> contextLength = NRef.of();
        NRef<Float> temperature = NRef.of();
        NRef<Float> nucleusThreshold = NRef.of();
        NRef<Integer> candidateCount = NRef.of();
        NRef<Integer> maxTokens = NRef.of();
        List<String> stop = new ArrayList<>();

        cmdLine.matcher()
                .whenNonOption()
                .asArg(a -> {
                    if (a.getStringValue().isPresent()) {
                        if (aliasName.isNull() && modelName.isNull()) {
                            aliasName.set(a.key());
                            modelName.set(a.value());
                        } else if (modelName.isNull()) {
                            modelName.set(a.asString().orNull());
                        } else {
                            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: invalid argument %s", a.toString()).asError());
                        }
                    } else {
                        if (aliasName.isNull()) {
                            aliasName.set(a.asString().orNull());
                        } else if (modelName.isNull()) {
                            modelName.set(a.asString().orNull());
                        } else {
                            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: invalid argument %s", a.toString()).asError());
                        }
                    }
                })
                .when("--alias").asEntry(a -> aliasName.set(a.asString().orNull()))
                .when("--model").asEntry(a -> modelName.set(a.asString().orNull()))
                .when("--contextLength").asEntry(a -> contextLength.set(NMemorySize.parse(a.value(), NMemoryUnit.BYTE).get().asBytes()))
                .when("--temperature").asEntry(a -> temperature.set(a.asFloat().orNull()))
                .when("--nucleusThreshold").asEntry(a -> nucleusThreshold.set(a.asFloat().orNull()))
                .when("--candidateCount").asEntry(a -> candidateCount.set(a.asInt().orNull()))
                .when("--maxTokens").asEntry(a -> maxTokens.set(a.asInt().orNull()))
                .when("--stop").asEntry(a -> stop.add(a.asString().orNull()))
                .requireAll();
        if (NBlankable.isBlank(aliasName.get())) {
            NMsg msg = NMsg.ofC("Error: missing alias name to set.").asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        if (NBlankable.isBlank(modelName.get())) {
            NMsg msg = NMsg.ofC("Error: missing model name to set.").asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }

        NaruModelConfig k = task.session().findModel(modelName.get()).orNull();
        if (k == null) {
            NMsg msg = NMsg.ofC("Error: model %s not found.",
                    modelName.get()).asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }

        NaruModelConfig oldAliasTarget = context.task().session().findModelAlias(aliasName.get()).orNull();
        if (oldAliasTarget != null) {
            NMsg msg = NMsg.ofC("alias %s already bound to %s", aliasName.get(), oldAliasTarget.toText()).asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        context.task().session().addModelAlias(aliasName.get(), new NaruModelConfig(
                aliasName.get(),
                k.provider(),
                k.model(),
                contextLength.orElse(k.contextLength()),
                temperature.orElse(k.temperature()),
                nucleusThreshold.orElse(k.nucleusThreshold()),
                candidateCount.orElse(k.candidateCount()),
                maxTokens.orElse(k.maxTokens()),
                stop
        ));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("set-alias %s=%s",
                NMsg.ofStyledPrimary1(aliasName.get()), k.toText()
        ));
        return NaruStmtResult.ofSuccess(null);
    }

    public void executeUpdateAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NRef<String> aliasName = NRef.of();
        NRef<String> modelName = NRef.of();
        NRef<Long> contextLength = NRef.of();
        NRef<Float> temperature = NRef.of();
        NRef<Float> nucleusThreshold = NRef.of();
        NRef<Integer> candidateCount = NRef.of();
        NRef<Integer> maxTokens = NRef.of();
        List<String> stop = new ArrayList<>();

        cmdLine.matcher()
                .whenNonOption()
                .asArg(a -> {
                    if (a.getStringValue().isPresent()) {
                        if (aliasName.isNull() && modelName.isNull()) {
                            aliasName.set(a.key());
                            modelName.set(a.value());
                        } else if (modelName.isNull()) {
                            modelName.set(a.asString().orNull());
                        } else {
                            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: invalid argument %s", a.toString()).asError());
                        }
                    } else {
                        if (aliasName.isNull()) {
                            aliasName.set(a.asString().orNull());
                        } else if (modelName.isNull()) {
                            modelName.set(a.asString().orNull());
                        } else {
                            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: invalid argument %s", a.toString()).asError());
                        }
                    }
                })
                .when("--alias").asEntry(a -> aliasName.set(a.asString().orNull()))
                .when("--model").asEntry(a -> modelName.set(a.asString().orNull()))
                .when("--contextLength").asEntry(a -> contextLength.set(a.asLong().orNull()))
                .when("--temperature").asEntry(a -> temperature.set(a.asFloat().orNull()))
                .when("--nucleusThreshold").asEntry(a -> nucleusThreshold.set(a.asFloat().orNull()))
                .when("--candidateCount").asEntry(a -> candidateCount.set(a.asInt().orNull()))
                .when("--maxTokens").asEntry(a -> maxTokens.set(a.asInt().orNull()))
                .when("--stop").asEntry(a -> stop.add(a.asString().orNull()))
                .requireAll();
        if (NBlankable.isBlank(aliasName.get())) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing alias name to set.").asError());
            return;
        }
        if (NBlankable.isBlank(modelName.get())) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
            return;
        }
        NaruModelConfig naruModelConfig = task.session().loadModelConfig(aliasName.get()).orNull();
        if (naruModelConfig == null) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing alias %s", aliasName.get()).asError());
            return;
        }
        if (!modelName.isNull()) {
            NaruModelConfig k = task.session().findModel(modelName.get()).orNull();
            if (k == null) {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
                        modelName.get()).asError());
                return;
            }
            naruModelConfig = naruModelConfig.withModel(k.toString());
        }
        if (contextLength.isSet()) {
            naruModelConfig = naruModelConfig.withContextLength(contextLength.get());
        }
        if (temperature.isSet()) {
            naruModelConfig = naruModelConfig.withTemperature(temperature.get());
        }
        if (nucleusThreshold.isSet()) {
            naruModelConfig = naruModelConfig.withNucleusThreshold(nucleusThreshold.get());
        }
        if (candidateCount.isSet()) {
            naruModelConfig = naruModelConfig.withCandidateCount(candidateCount.get());
        }
        if (maxTokens.isSet()) {
            naruModelConfig = naruModelConfig.withMaxTokens(maxTokens.get());
        }

        if (!stop.isEmpty()) {
            naruModelConfig = naruModelConfig.withStop(stop);
        }

        task.session().saveModelConfig(aliasName.get(), naruModelConfig);

        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("update-alias %s",
                NMsg.ofStyledPrimary1(aliasName.get())
        ));
    }

    public NaruStmtResult executeUnsetAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent()) {
            NMsg msg = NMsg.ofC("Error: missing alias to unset.").asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        NArg a = n.get();
        NaruModelConfig oldAliasTarget = context.task().session().findModelAlias(a.image()).orNull();
        if (oldAliasTarget == null) {
            NMsg msg = NMsg.ofC("Error: alias %s not found", a.image()).asError();
            task.log(NaruLogMode.AGENT_RESPONSE, msg);
            return NaruStmtResult.ofError(msg.toString());
        }
        context.task().session().removeModelAlias(a.key());
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Selected model : %s",
                task.model().toText()));
        return NaruStmtResult.ofSuccess(null);
    }


}