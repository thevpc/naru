package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelInfo;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NTextBuilder;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NStringUtils;

import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class ModelDirective extends AbstractDirective {
    public ModelDirective() {
        super("model", "manage models");
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
                case "unalias": {
                    executeUnsetAlias(context, cmdLine);
                    break;
                }
                case "list": {
                    executeList(context, cmdLine);
                    break;
                }
//                case "drop": {
//                    executeDrop(context, cmdLine);
//                    break;
//                }
//                case "clear": {
//                    executeClear(context, cmdLine);
//                    break;
//                }
//                case "load": {
//                    executeLoad(context, cmdLine);
//                    break;
//                }
//                case "unload": {
//                    executeUnload(context, cmdLine);
//                    break;
//                }
//                case "run": {
//                    executeRun(context, cmdLine);
//                    break;
//                }
                case "--help":
                case "help": {
                    executeHelp(context, cmdLine);
                    break;
                }
                default: {
                    sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command %s", a.image()));
                }
            }
        }
    }

    public void executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Available models:"));
        int index = 1;
        List<NaruModelInfo> models = context.session().registry().modelsInfos();
        if (models.isEmpty()) {
            return;
        }
        int zeros = (int) Math.ceil(Math.log10(models.size()));
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", zeros));
        Map<NaruModelKey, List<String>> naruModelKeyListMap = sessionContext.reversedModelAliases();
        for (NaruModelInfo model : models) {

            NaruModelKey mkey = model.key();

            NTextBuilder extra1 = null;
            List<String> currAliases = naruModelKeyListMap.get(mkey);
            if (currAliases != null && !currAliases.isEmpty()) {
                extra1 = NTextBuilder.of();
                extra1.append(NMsg.ofStyledSeparator(" ("));
                extra1.appendAll(currAliases);
                extra1.append(NMsg.ofStyledSeparator(")"));
            }

            NMsg extra2 = null;
            if (mkey.equals(sessionContext.model())) {
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
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s[%s] %s%s%s",
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
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", sessionContext.model().toMsg()));
    }

    public void executeSet(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent()) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
            return;
        }
        NArg a = n.get();
        NaruModelKey k = sessionContext.findModel(a.image()).orNull();
        if (k == null) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
                    a.image()).asError());
        }
        context.session().setModel(k);
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("switch to model : %s",
                sessionContext.model().toMsg()));
    }

    public void executeSetGlobal(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent()) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
            return;
        }
        NArg a = n.get();
        NaruModelKey k = sessionContext.findModel(a.image()).orNull();
        if (k == null) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
                    a.image()).asError());
        }
        context.session().setModel(k);
        assert k != null;
        context.session().setProjectEnv("model", k.toString());
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("switch global model : %s",
                sessionContext.model().toMsg()));
    }

    public void executeListAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        Map<String, NaruModelKey> aliases = sessionContext.modelAliases();
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Aliases: %s", aliases.size()));
        int index = 1;

        if (aliases.isEmpty()) {
            return;
        }
        int zeros = (int) Math.ceil(Math.log10(aliases.size()));
        DecimalFormat zformat = new DecimalFormat(NStringUtils.repeat("0", zeros));
        for (Map.Entry<String, NaruModelKey> e : aliases.entrySet().stream().sorted(Comparator.comparing(x -> x.getKey())).collect(Collectors.toList())) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s=%s",
                    NMsg.ofStyledNumber(zformat.format(index)),
                    NMsg.ofStyledPrimary1(e.getKey()),
                    e.getValue().toMsg()));
            index++;
        }
    }

    public void executeSetAlias(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NOptional<NArg> n = cmdLine.next();
        if (!n.isPresent()) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
            return;
        }
        NArg a = n.get();
        if (a.getValue().asString().orNull() == null) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: missing model name to set.").asError());
        }
        NaruModelKey k = sessionContext.findModel(a.getValue().asString().orNull()).orNull();
        if (k == null) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: model %s not found.",
                    a.getValue().asString().orNull()).asError());
            return;
        }
        NaruModelKey oldAliasTarget = context.session().findModelAlias(a.key()).orNull();
        if (oldAliasTarget != null) {
            if (Objects.equals(oldAliasTarget, k)) {
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("alias %s already bound to %s", a.key(), oldAliasTarget.toMsg()).asError());
            } else {
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: alias %s already bound to %s", a.key(), oldAliasTarget.toMsg()).asError());
            }
            return;
        }
        context.session().addModelAlias(a.key(), k);
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("set-alias %s=%s",
                NMsg.ofStyledPrimary1(a.key()), k.toMsg()
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
        NaruModelKey oldAliasTarget = context.session().findModelAlias(a.image()).orNull();
        if (oldAliasTarget == null) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Error: alias %s not found", a.image()).asError());
        }
        context.session().removeModelAlias(a.key());
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("switch to model : %s",
                sessionContext.model().toMsg()));
    }


}
