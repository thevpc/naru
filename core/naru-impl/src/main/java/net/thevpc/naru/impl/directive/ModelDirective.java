package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelInfo;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.agent.NaruAgentImpl;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
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
        NaruSession sessionContext = context.session();
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
                    sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
                }
            }
        }
    }

    public void executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Available models:"));
        int index = 1;
        List<NaruModelInfo> models = context.session().registry().modelsInfos(session);
        if (models.isEmpty()) {
            return;
        }
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
                    if(Objects.equals(c,selectedModel.name())) {
                        extra1.append(NMsg.ofStyledPrimary3(c));
                    }else{
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
        NaruSession sessionContext = context.session();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1("script"));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, kk);
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s list", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           list models"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s get", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show current name"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s set <name>", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           switch current model"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s set-global <name>", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           switch current model and save it as global model"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s alias <name>=<value>", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           add an alias to a model"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s update <alias-name>", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           update an alias"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s unalias <name>", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           remove an alias from a model"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));

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
        NaruSession sessionContext = context.session();
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", sessionContext.model().toText()));
    }

    public void executeSet(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent()) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
            return;
        }
        NArg a = n.get();
        NaruModelConfig k = sessionContext.findModel(a.image()).orNull();
        if (k == null) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
                    a.image()).asError());
        }
        context.session().setModel(k);
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Selected model : %s",
                sessionContext.model().toText()));
    }

    public void executeSetByNumber(NaruDirectiveCallContext context, int nbr) {
        NaruSession sessionContext = context.session();
        NaruModelConfig k = sessionContext.findModel(String.valueOf(nbr)).orNull();
        if (k == null) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
                    nbr).asError());
        }
        context.session().setModel(k);
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Selected model : %s",
                sessionContext.model().toText()));
    }

    public void executeSetGlobal(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent()) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
            return;
        }
        NArg a = n.get();
        NaruModelConfig k = sessionContext.findModel(a.image()).orNull();
        if (k == null) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
                    a.image()).asError());
        }
        context.session().setModel(k);
        NAssert.requireNamedNonNull(k, "key");
        context.session().setProjectEnv("model", k.toElement(), NAruVisibility.PRIVATE);
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("switch global model : %s",
                sessionContext.model().toText()));
    }

    public void executeListAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        Map<String, NaruModelConfig> aliases = sessionContext.modelAliases();
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Aliases: %s", aliases.size()));
        int index = 1;

        if (aliases.isEmpty()) {
            return;
        }
        int zeros = (int) Math.ceil(Math.log10(aliases.size()));
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", zeros));
        for (Map.Entry<String, NaruModelConfig> e : aliases.entrySet().stream().sorted(Comparator.comparing(x -> x.getKey())).collect(Collectors.toList())) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s=%s",
                    NMsg.ofStyledNumber(zformat.format(index)),
                    NMsg.ofStyledPrimary1(e.getKey()),
                    e.getValue().toText()));
            index++;
        }
    }

    public void executeSetAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
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
                            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: invalid argument %s", a.toString()).asError());
                        }
                    } else {
                        if (aliasName.isNull()) {
                            aliasName.set(a.asString().orNull());
                        } else if (modelName.isNull()) {
                            modelName.set(a.asString().orNull());
                        } else {
                            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: invalid argument %s", a.toString()).asError());
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
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing alias name to set.").asError());
            return;
        }
        if (NBlankable.isBlank(modelName.get())) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
            return;
        }

        NaruModelConfig k = sessionContext.findModel(modelName.get()).orNull();
        if (k == null) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
                    modelName.get()).asError());
            return;
        }

        NaruModelConfig oldAliasTarget = context.session().findModelAlias(aliasName.get()).orNull();
        if (oldAliasTarget != null) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("alias %s already bound to %s", aliasName.get(), oldAliasTarget.toText()).asError());
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
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("set-alias %s=%s",
                NMsg.ofStyledPrimary1(aliasName.get()), k.toText()
        ));
    }

    public void executeUpdateAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
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
                            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: invalid argument %s", a.toString()).asError());
                        }
                    } else {
                        if (aliasName.isNull()) {
                            aliasName.set(a.asString().orNull());
                        } else if (modelName.isNull()) {
                            modelName.set(a.asString().orNull());
                        } else {
                            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: invalid argument %s", a.toString()).asError());
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
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing alias name to set.").asError());
            return;
        }
        if (NBlankable.isBlank(modelName.get())) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
            return;
        }
        NaruModelConfig naruModelConfig = ((NaruAgentImpl) sessionContext.agent()).getModelAliases().get(aliasName.get()).orNull();
        if (naruModelConfig == null) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing alias %s", aliasName.get()).asError());
            return;
        }
        if (!modelName.isNull()) {
            NaruModelConfig k = sessionContext.findModel(modelName.get()).orNull();
            if (k == null) {
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
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
        ((NaruAgentImpl) sessionContext.agent()).getModelAliases().put(aliasName.get(), naruModelConfig);

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("update-alias %s",
                NMsg.ofStyledPrimary1(aliasName.get())
        ));
    }

    public void executeUnsetAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent()) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing alias to unset.").asError());
            return;
        }
        NArg a = n.get();
        NaruModelConfig oldAliasTarget = context.session().findModelAlias(a.image()).orNull();
        if (oldAliasTarget == null) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: alias %s not found", a.image()).asError());
        }
        context.session().removeModelAlias(a.key());
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Selected model : %s",
                sessionContext.model().toText()));
    }


}
