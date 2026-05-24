package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.agent.NaruAgentImpl;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NTextBuilder;
import net.thevpc.nuts.util.*;

import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class ModelDirective extends AbstractDirective {

    public ModelDirective() {
        super("model", "config", "manage models");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession session = context.session();
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        if (cmdLine.isEmpty()) {
            executeList(context, cmdLine);
        } else {
            NArg a = cmdLine.next().get();
            switch (a.image()) {
                case "get": {
                    executeGet(context, cmdLine);
                    break;
                }
                case "set": {
                    executeSet(context, cmdLine);
                    break;
                }
                case "install": {
                    executeInstall(context, cmdLine);
                    break;
                }
                case "uninstall": {
                    executeUninstall(context, cmdLine);
                    break;
                }
                case "unload": {
                    executeUnload(context, cmdLine);
                    break;
                }
                case "ps": {
                    executePs(context, cmdLine);
                    break;
                }
                case "set-global": {
                    executeSetGlobal(context, cmdLine);
                    break;
                }
                case "alias": {
                    if (cmdLine.isEmpty()) {
                        executeListAlias(context, cmdLine);
                    } else {
                        executeSetAlias(context, cmdLine);
                    }
                    break;
                }
                case "update": {
                    executeUpdateAlias(context, cmdLine);
                    break;
                }
                case "unalias": {
                    executeUnsetAlias(context, cmdLine);
                    break;
                }
                case "list": {
                    executeList(context, cmdLine);
                    break;
                }
                case "--help":
                case "help": {
                    executeHelp(context, cmdLine);
                    break;
                }
                default: {
                    NOptional<Integer> b = NLiteral.of(a.image()).asInt();
                    if (b.isPresent()) {
                        executeSetByNumber(context, b.get());
                        return;
                    }
                    session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
                }
            }
        }
    }

    public void executeInstall(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent()) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to install.").asError());
            return;
        }
        NaruModelKey key = NaruModelKey.parse(n.get().image()).get();
        if (NBlankable.isBlank(key.provider())) {
            key = new NaruModelKey("ollama", n.get().image());
        }
        NaruModelProvider naruModelProvider = session.registry().provider(key.provider()).orNull();
        if (naruModelProvider == null) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: provider not found :%s", key.provider()).asError());
            return;
        }
        if (naruModelProvider.isSupportedInstallModel()) {
            naruModelProvider.installModel(key, session);
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("model installed/pulled :%s", key.toMsg()).asError());
        } else {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: unsupported 'install' :%s", key.toMsg()).asError());
        }
    }

    public void executeUninstall(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent()) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to uninstall.").asError());
            return;
        }
        NaruModelKey key = NaruModelKey.parse(n.get().image()).get();
        if (NBlankable.isBlank(key.provider())) {
            key = new NaruModelKey("ollama", n.get().image());
        }
        NaruModelProvider naruModelProvider = session.registry().provider(key.provider()).orNull();
        if (naruModelProvider == null) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: provider not found :%s", key.provider()).asError());
            return;
        }
        if (naruModelProvider.isSupportedUninstallModel()) {
            naruModelProvider.uninstallModel(key, session);
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("model uninstalled :%s", key.toMsg()).asError());
        } else {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: unsupported 'uninstall' :%s", key.toMsg()).asError());
        }
    }

    public void executeUnload(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent()) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to unload.").asError());
            return;
        }
        NaruModelKey key = NaruModelKey.parse(n.get().image()).get();
        if (NBlankable.isBlank(key.provider())) {
            key = new NaruModelKey("ollama", n.get().image());
        }
        NaruModelProvider naruModelProvider = session.registry().provider(key.provider()).orNull();
        if (naruModelProvider == null) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: provider not found :%s", key.provider()).asError());
            return;
        }
        if (naruModelProvider.isSupportedUnloadModel()) {
            naruModelProvider.unloadModel(key, session);
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("model unload :%s", key.toMsg()).asError());
        } else {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: unsupported 'unload' :%s", key.toMsg()).asError());
        }
    }

    public void executePs(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        NOptional<NArg> n = cmdLine.next();
        String provider = "ollama";
        if (n.isPresent()) {
            String provider2 = n.get().image();
            if(!NBlankable.isBlank(provider2)) {
                provider=provider2;
            }
        }
        NaruModelProvider naruModelProvider = session.registry().provider(provider).orNull();
        if (naruModelProvider == null) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: provider not found :%s", provider).asError());
            return;
        }
        if (naruModelProvider.isSupportedPsModel()) {
            List<NaruModelPsResult> elements = naruModelProvider.psModel(session);
            for (NaruModelPsResult element : elements) {
                session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s size: %s vram-size: %s (%s on VRAM) %s",
                        element.getModel().toMsg(),
                        NMsg.ofStyledNumber(NMemoryFormat.DEFAULT.format(NMemorySize.ofBytes(element.getSize()).normalize().canonicalize())),
                        NMsg.ofStyledNumber(NMemoryFormat.DEFAULT.format(NMemorySize.ofBytes(element.getSizeVram()).normalize().canonicalize())),
                        NMsg.ofStyledNumber(
                                (element.getSize() == 0 ? "0.00" :
                                        new DecimalFormat("0.00").format((100.0 * element.getSizeVram() / element.getSize()))
                                )+"%"
                        ),
                        element.getExpiresAt()
                ));
            }
        } else {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: unsupported 'ps' :%s", provider).asError());
        }
    }

    public void executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        int index = 1;
        List<NaruModelInfo> models = context.session().registry().modelsInfos(session);
        if (models.isEmpty()) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("no model found. is %s live?",NMsg.ofStyledPrimary1("ollama")).asError());
            return;
        }
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s Available models:", models.size()));
        int zeros = (int) Math.ceil(Math.log10(models.size()));
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", zeros));
        NaruModelConfig selectedModel = session.model();
        for (NaruModelInfo model : models) {

            NaruModelKey mkey = model.key();

            NTextBuilder extra1 = null;
            List<String> currAliases = session.modelAliases().values().stream().filter(x -> x.key().equals(model.key())).map(x -> x.name())
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
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s[%s] %s%s%s",
                    extra2,
                    NMsg.ofStyledNumber(zformat.format(index)),
                    model.toText(),
                    extra1 == null ? "" : extra1,
                    extra3 == null ? "" : extra3
            ));
            index++;
        }
    }

    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        NMsg kk = NMsg.ofC("%s%s", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary5(name()));
        session.log(NaruLogMode.AGENT_RESPONSE, kk);
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk,NMsg.ofStyledPrimary4("list")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           list models"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk,NMsg.ofStyledPrimary4("get")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show current name"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <name>", kk,NMsg.ofStyledPrimary4("set")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           switch current model"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %sname>", kk,NMsg.ofStyledPrimary4("set-global")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           switch current model and save it as global model"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <name>=<value>", kk,NMsg.ofStyledPrimary4("alias")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           add an alias to a model"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <alias-name>", kk,NMsg.ofStyledPrimary4("update")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           update an alias"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <model>", kk,NMsg.ofStyledPrimary4("install")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           install a new model (equivalent to ollama pull)"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <model>", kk,NMsg.ofStyledPrimary4("uninstall")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           uninstall a model (equivalent to ollama delete)"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <model>", kk,NMsg.ofStyledPrimary4("unload")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           unload a model and free VRAM/RAM"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <name>", kk,NMsg.ofStyledPrimary4("list")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           remove an alias from a model"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));

    }

    @Override
    public List<NArgCandidate> resolveCandidates(
            NCmdLine cmdLine,
            NCmdLineAutoCompleteResolver.Pos pos,
            NaruSession session) {
        List<NArgCandidate> candidates = new java.util.ArrayList<>();
        String[] stringArray = cmdLine.toStringArray();
        int wordIndex = pos.wordIndex();
        String currentArg = wordIndex < stringArray.length ? stringArray[wordIndex] : "";

        if (wordIndex == 1) {
            addCandidates(candidates, currentArg, "name", "list", "help", "alias", "unalias", "set", "get");
        }
        return candidates;
    }

    public void executeGet(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", session.model().toText()));
    }

    public void executeSet(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent()) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
            return;
        }
        NArg a = n.get();
        NaruModelConfig k = session.findModel(a.image()).orNull();
        if (k == null) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
                    a.image()).asError());
        }
        context.session().setModel(k);
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Selected model : %s",
                session.model().toText()));
    }

    public void executeSetByNumber(NaruDirectiveCallContext context, int nbr) {
        NaruSession session = context.session();
        NaruModelConfig k = session.findModel(String.valueOf(nbr)).orNull();
        if (k == null) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
                    nbr).asError());
        }
        context.session().setModel(k);
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Selected model : %s",
                session.model().toText()));
    }

    public void executeSetGlobal(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent()) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
            return;
        }
        NArg a = n.get();
        NaruModelConfig k = session.findModel(a.image()).orNull();
        if (k == null) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
                    a.image()).asError());
        }
        context.session().setModel(k);
        NAssert.requireNamedNonNull(k, "key");
        context.session().setProjectEnv("model", k.toElement(), NAruVisibility.PRIVATE);
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("switch global model : %s",
                session.model().toText()));
    }

    public void executeListAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        Map<String, NaruModelConfig> aliases = session.modelAliases();
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Aliases: %s", aliases.size()));
        int index = 1;

        if (aliases.isEmpty()) {
            return;
        }
        int zeros = (int) Math.ceil(Math.log10(aliases.size()));
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", zeros));
        for (Map.Entry<String, NaruModelConfig> e : aliases.entrySet().stream().sorted(Comparator.comparing(x -> x.getKey())).collect(Collectors.toList())) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s",
                    NMsg.ofStyledNumber(zformat.format(index)),
                    e.getValue().toText()));
            index++;
        }
    }

    public void executeSetAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
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
                            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: invalid argument %s", a.toString()).asError());
                        }
                    } else {
                        if (aliasName.isNull()) {
                            aliasName.set(a.asString().orNull());
                        } else if (modelName.isNull()) {
                            modelName.set(a.asString().orNull());
                        } else {
                            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: invalid argument %s", a.toString()).asError());
                        }
                    }
                })
                .with("--alias").matchEntry(a -> aliasName.set(a.asString().orNull()))
                .with("--model").matchEntry(a -> modelName.set(a.asString().orNull()))
                .with("--contextLength").matchEntry(a -> contextLength.set(NMemorySize.parse(a.value(),NMemoryUnit.BYTE).get().asBytes()))
                .with("--temperature").matchEntry(a -> temperature.set(a.asFloat().orNull()))
                .with("--nucleusThreshold").matchEntry(a -> nucleusThreshold.set(a.asFloat().orNull()))
                .with("--candidateCount").matchEntry(a -> candidateCount.set(a.asInt().orNull()))
                .with("--maxTokens").matchEntry(a -> maxTokens.set(a.asInt().orNull()))
                .with("--stop").matchEntry(a -> stop.add(a.asString().orNull()))
                .requireAll();
        if (NBlankable.isBlank(aliasName.get())) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing alias name to set.").asError());
            return;
        }
        if (NBlankable.isBlank(modelName.get())) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
            return;
        }

        NaruModelConfig k = session.findModel(modelName.get()).orNull();
        if (k == null) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
                    modelName.get()).asError());
            return;
        }

        NaruModelConfig oldAliasTarget = context.session().findModelAlias(aliasName.get()).orNull();
        if (oldAliasTarget != null) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("alias %s already bound to %s", aliasName.get(), oldAliasTarget.toText()).asError());
            return;
        }
        context.session().addModelAlias(aliasName.get(), new NaruModelConfig(
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
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("set-alias %s=%s",
                NMsg.ofStyledPrimary1(aliasName.get()), k.toText()
        ));
    }

    public void executeUpdateAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
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
                            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: invalid argument %s", a.toString()).asError());
                        }
                    } else {
                        if (aliasName.isNull()) {
                            aliasName.set(a.asString().orNull());
                        } else if (modelName.isNull()) {
                            modelName.set(a.asString().orNull());
                        } else {
                            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: invalid argument %s", a.toString()).asError());
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
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing alias name to set.").asError());
            return;
        }
        if (NBlankable.isBlank(modelName.get())) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
            return;
        }
        NaruModelConfig naruModelConfig = ((NaruAgentImpl) session.agent()).getModelAliases().get(aliasName.get()).orNull();
        if (naruModelConfig == null) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing alias %s", aliasName.get()).asError());
            return;
        }
        if (!modelName.isNull()) {
            NaruModelConfig k = session.findModel(modelName.get()).orNull();
            if (k == null) {
                session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
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
        ((NaruAgentImpl) session.agent()).getModelAliases().put(aliasName.get(), naruModelConfig);

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("update-alias %s",
                NMsg.ofStyledPrimary1(aliasName.get())
        ));
    }

    public void executeUnsetAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent()) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing alias to unset.").asError());
            return;
        }
        NArg a = n.get();
        NaruModelConfig oldAliasTarget = context.session().findModelAlias(a.image()).orNull();
        if (oldAliasTarget == null) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: alias %s not found", a.image()).asError());
        }
        context.session().removeModelAlias(a.key());
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Selected model : %s",
                session.model().toText()));
    }


}
