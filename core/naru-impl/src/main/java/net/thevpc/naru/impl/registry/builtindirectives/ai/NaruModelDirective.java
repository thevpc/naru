package net.thevpc.naru.impl.registry.builtindirectives.ai;

import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.agent.NaruAgentImpl;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextBuilder;
import net.thevpc.nuts.util.*;

import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class NaruModelDirective extends AbstractDirective {

    public NaruModelDirective() {
        super("model", "ai", "manage AI models");
        noCommand("list");
        register(new AbstractSubCommand("current", NText.ofPlain("show current model")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", task.model().toText()));
            }
        });
        register(new AbstractSubCommand("use", NText.ofPlain("select model"),
                new SubCommandHelp(NText.of("<model>"), NText.ofPlain("model name or index (as given by 'list' subcommand) to select"))
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NOptional<NArg> n = cmdLine.next();
                if (!n.isPresent()) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
                    return;
                }
                NArg a = n.get();
                NaruModelConfig k = task.session().findModel(a.image()).orNull();
                if (k == null) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
                            a.image()).asError());
                }
                context.task().setModel(k);
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Selected model : %s",
                        task.model().toText()));
            }
        });
        register(new AbstractSubCommand("install", NText.ofPlain("install a new model (equivalent to ollama pull)"),
                new SubCommandHelp(NText.of("<model>"), NText.ofPlain("model name to install"))
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NOptional<NArg> n = cmdLine.next();
                if (!n.isPresent()) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to install.").asError());
                    return;
                }
                NaruModelKey key = NaruModelKey.parse(n.get().image()).get();
                if (NBlankable.isBlank(key.provider())) {
                    key = new NaruModelKey("ollama", n.get().image());
                }
                NaruModelProvider naruModelProvider = task.session().registry().provider(key.provider()).orNull();
                if (naruModelProvider == null) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: provider not found :%s", key.provider()).asError());
                    return;
                }
                if (naruModelProvider.isSupportedInstallModel()) {
                    naruModelProvider.installModel(key, task.session());
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("model installed/pulled :%s", key.toMsg()).asError());
                } else {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: unsupported 'install' :%s", key.toMsg()).asError());
                }
            }
        });
        register(new AbstractSubCommand("uninstall", NText.ofPlain("uninstall a new model (equivalent to ollama delete)"),
                new SubCommandHelp(NText.of("<model>"), NText.ofPlain("model name to uninstall"))
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NOptional<NArg> n = cmdLine.next();
                if (!n.isPresent()) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to uninstall.").asError());
                    return;
                }
                NaruModelKey key = NaruModelKey.parse(n.get().image()).get();
                if (NBlankable.isBlank(key.provider())) {
                    key = new NaruModelKey("ollama", n.get().image());
                }
                NaruModelProvider naruModelProvider = task.session().registry().provider(key.provider()).orNull();
                if (naruModelProvider == null) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: provider not found :%s", key.provider()).asError());
                    return;
                }
                if (naruModelProvider.isSupportedUninstallModel()) {
                    naruModelProvider.uninstallModel(key, task.session());
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("model uninstalled :%s", key.toMsg()).asError());
                } else {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: unsupported 'uninstall' :%s", key.toMsg()).asError());
                }
            }
        });
        register(new AbstractSubCommand("unload", NText.ofPlain("unload model and free VRAM/RAM"),
                new SubCommandHelp(NText.of("<model>"), NText.ofPlain("model name to unload"))
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NOptional<NArg> n = cmdLine.next();
                if (!n.isPresent()) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to unload.").asError());
                    return;
                }
                NaruModelKey key = NaruModelKey.parse(n.get().image()).get();
                if (NBlankable.isBlank(key.provider())) {
                    key = new NaruModelKey("ollama", n.get().image());
                }
                NaruModelProvider naruModelProvider = task.session().registry().provider(key.provider()).orNull();
                if (naruModelProvider == null) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: provider not found :%s", key.provider()).asError());
                    return;
                }
                if (naruModelProvider.isSupportedUnloadModel()) {
                    naruModelProvider.unloadModel(key, task.session());
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("model unload :%s", key.toMsg()).asError());
                } else {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: unsupported 'unload' :%s", key.toMsg()).asError());
                }
            }
        });
        register(new AbstractSubCommand("ps", NText.ofPlain("list loaded (in VRAM) models")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
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
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: provider not found :%s", provider).asError());
                    return;
                }
                if (naruModelProvider.isSupportedPsModel()) {
                    List<NaruModelPsResult> elements = naruModelProvider.psModel(task.session());
                    for (NaruModelPsResult element : elements) {
                        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s size: %s vram-size: %s (%s on VRAM) %s",
                                element.getModel().toMsg(),
                                NMsg.ofStyledNumber(NMemoryFormat.DEFAULT.format(NMemorySize.ofBytes(element.getSize()).normalize().canonicalize())),
                                NMsg.ofStyledNumber(NMemoryFormat.DEFAULT.format(NMemorySize.ofBytes(element.getSizeVram()).normalize().canonicalize())),
                                NMsg.ofStyledNumber(
                                        (element.getSize() == 0 ? "0.00" :
                                                new DecimalFormat("0.00").format((100.0 * element.getSizeVram() / element.getSize()))
                                        ) + "%"
                                ),
                                element.getExpiresAt()
                        ));
                    }
                } else {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: unsupported 'ps' :%s", provider).asError());
                }
            }
        });
        register(new AbstractSubCommand("set-global", NText.ofPlain("set model as default globally")
                , new SubCommandHelp(NText.of("<model>"), NText.ofPlain("model name to set as default globally"))
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NOptional<NArg> n = cmdLine.next();
                if (!n.isPresent()) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
                    return;
                }
                NArg a = n.get();
                NaruModelConfig k = task.session().findModel(a.image()).orNull();
                if (k == null) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
                            a.image()).asError());
                }
                context.task().setModel(k);
                NAssert.requireNamedNonNull(k, "key");
                context.task().session().setProjectEnv("model", k.toElement(), NAruVisibility.PRIVATE);
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("switch global model : %s",
                        task.model().toText()));
            }
        });

        register(new AbstractSubCommand("alias", NText.ofPlain("manager model aliases")
                , new SubCommandHelp(NText.of(""), NText.ofPlain("list aliases"))
                , new SubCommandHelp(NText.of("<alias>=<name>"), NText.ofPlain("set alias"))
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                if (cmdLine.isEmpty()) {
                    executeListAlias(context, cmdLine);
                } else {
                    executeSetAlias(context, cmdLine);
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
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                executeSetAlias(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("unalias", NText.ofPlain("remove alias by name")
                , new SubCommandHelp(NText.of("<alias>"), NText.ofPlain("remove alias named <alias>"))
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                executeUnsetAlias(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("list", NText.ofPlain("list aliases")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                executeList(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("", NText.ofPlain("special..."),
                new SubCommandHelp("<n>", "set model by index")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                for (NArg a : cmdLine) {
                    NOptional<Integer> b = NLiteral.of(a.image()).asInt();
                    if (b.isPresent()) {
                        executeSetByNumber(context, b.get());
                        return;
                    }else{
                        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
                    }
                }
                context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
            }
        });

    }

    public void executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        int index = 1;
        List<NaruModelInfo> models = context.task().session().registry().modelsInfos(task.session());
        if (models.isEmpty()) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("no model found. is %s live?", NMsg.ofStyledPrimary1("ollama")).asError());
            return;
        }
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s Available models:", models.size()));
        int zeros = (int) Math.ceil(Math.log10(models.size()));
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", zeros));
        NaruModelConfig selectedModel = task.model();
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
                    if (Objects.equals(c, selectedModel.name())) {
                        extra1.append(NMsg.ofStyledPrimary3(c));
                    } else {
                        extra1.append(NMsg.ofStyledPrimary1(c));
                    }
                }
                extra1.append(NMsg.ofStyledSeparator(")"));
            }

            NMsg extra2 = null;
            if (mkey.equals(selectedModel.key())) {
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
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s[%s] %s%s%s",
                    extra2,
                    NMsg.ofStyledNumber(zformat.format(index)),
                    model.toText(),
                    extra1 == null ? "" : extra1,
                    extra3 == null ? "" : extra3
            ));
            index++;
        }
    }


    public void executeSetByNumber(NaruDirectiveCallContext context, int nbr) {
        NaruTask task = context.task();
        NaruModelConfig k = task.session().findModel(String.valueOf(nbr)).orNull();
        if (k == null) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
                    nbr).asError());
        }
        context.task().setModel(k);
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Selected model : %s",
                task.model().toText()));
    }

    public void executeListAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        Map<String, NaruModelConfig> aliases = task.session().modelAliases();
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Aliases: %s", aliases.size()));
        int index = 1;

        if (aliases.isEmpty()) {
            return;
        }
        int zeros = (int) Math.ceil(Math.log10(aliases.size()));
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", zeros));
        for (Map.Entry<String, NaruModelConfig> e : aliases.entrySet().stream().sorted(Comparator.comparing(x -> x.getKey())).collect(Collectors.toList())) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s",
                    NMsg.ofStyledNumber(zformat.format(index)),
                    e.getValue().toText()));
            index++;
        }
    }

    public void executeSetAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
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
                .withNonOption()
                .matchAny(a -> {
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
                .with("--alias").matchEntry(a -> aliasName.set(a.asString().orNull()))
                .with("--model").matchEntry(a -> modelName.set(a.asString().orNull()))
                .with("--contextLength").matchEntry(a -> contextLength.set(NMemorySize.parse(a.value(), NMemoryUnit.BYTE).get().asBytes()))
                .with("--temperature").matchEntry(a -> temperature.set(a.asFloat().orNull()))
                .with("--nucleusThreshold").matchEntry(a -> nucleusThreshold.set(a.asFloat().orNull()))
                .with("--candidateCount").matchEntry(a -> candidateCount.set(a.asInt().orNull()))
                .with("--maxTokens").matchEntry(a -> maxTokens.set(a.asInt().orNull()))
                .with("--stop").matchEntry(a -> stop.add(a.asString().orNull()))
                .requireAll();
        if (NBlankable.isBlank(aliasName.get())) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing alias name to set.").asError());
            return;
        }
        if (NBlankable.isBlank(modelName.get())) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
            return;
        }

        NaruModelConfig k = task.session().findModel(modelName.get()).orNull();
        if (k == null) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
                    modelName.get()).asError());
            return;
        }

        NaruModelConfig oldAliasTarget = context.task().session().findModelAlias(aliasName.get()).orNull();
        if (oldAliasTarget != null) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("alias %s already bound to %s", aliasName.get(), oldAliasTarget.toText()).asError());
            return;
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
                .withNonOption()
                .matchAny(a -> {
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
                .with("--alias").matchEntry(a -> aliasName.set(a.asString().orNull()))
                .with("--model").matchEntry(a -> modelName.set(a.asString().orNull()))
                .with("--contextLength").matchEntry(a -> contextLength.set(a.asLong().orNull()))
                .with("--temperature").matchEntry(a -> temperature.set(a.asFloat().orNull()))
                .with("--nucleusThreshold").matchEntry(a -> nucleusThreshold.set(a.asFloat().orNull()))
                .with("--candidateCount").matchEntry(a -> candidateCount.set(a.asInt().orNull()))
                .with("--maxTokens").matchEntry(a -> maxTokens.set(a.asInt().orNull()))
                .with("--stop").matchEntry(a -> stop.add(a.asString().orNull()))
                .requireAll();
        if (NBlankable.isBlank(aliasName.get())) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing alias name to set.").asError());
            return;
        }
        if (NBlankable.isBlank(modelName.get())) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
            return;
        }
        NaruModelConfig naruModelConfig = ((NaruAgentImpl) task.session().agent()).getModelAliases().get(aliasName.get()).orNull();
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
        ((NaruAgentImpl) task.session().agent()).getModelAliases().put(aliasName.get(), naruModelConfig);

        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("update-alias %s",
                NMsg.ofStyledPrimary1(aliasName.get())
        ));
    }

    public void executeUnsetAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent()) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing alias to unset.").asError());
            return;
        }
        NArg a = n.get();
        NaruModelConfig oldAliasTarget = context.task().session().findModelAlias(a.image()).orNull();
        if (oldAliasTarget == null) {
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: alias %s not found", a.image()).asError());
        }
        context.task().session().removeModelAlias(a.key());
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Selected model : %s",
                task.model().toText()));
    }


}
