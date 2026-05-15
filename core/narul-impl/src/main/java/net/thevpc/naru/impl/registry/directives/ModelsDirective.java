package net.thevpc.naru.impl.registry.directives;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.text.NMsg;

import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

public class ModelsDirective extends AbstractDirective {
    public ModelsDirective() {
        super("models", "show models");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruAgent r = context.session().runner();
        r.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Available models:"));
        for (Map.Entry<String, NaruModelProvider> e : context.session().runner().registry().modelProviders().entrySet().stream().sorted(Comparator.comparing(a -> a.getKey())).collect(Collectors.toList())) {
            for (String s : e.getValue().listModels().stream().sorted().collect(Collectors.toList())) {
                NMsg extra = null;
                if (s.equals(r.model())) {
                    extra = NMsg.ofC(" %s%s%s",
                            NMsg.ofStyledSeparator("("),
                            NMsg.ofStyledSuccess("*"),
                            NMsg.ofStyledSeparator(")")
                    );
                }
                r.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s (%s)%s",
                        NMsg.ofStyledPrimary1(s),
                         NMsg.ofStyledPrimary2(e.getValue().getName()),
                        extra == null ? "" : extra));
            }
        }
    }
}
