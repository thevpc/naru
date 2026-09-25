package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.registry.DefaultNaruToolset;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.registry.NaruToolsetProvider;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

import java.util.Arrays;
import java.util.List;

public class NaruPlanToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "plan";
    }

    @Override
    public List<String> supportedTypes() {
        return Arrays.asList("plan");
    }

    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        String type = NNameFormat.LOWER_KEBAB_CASE.format(id);
        switch (type) {
            case "plan":
                return new DefaultNaruToolset(id, planTools());
            default:
                throw new NIllegalArgumentException(
                        NMsg.ofC(getClass().getSimpleName() + ": unknown type '%s'", type)
                );
        }
    }

    private static List<NaruTool> planTools() {
        // the structural tools are tagged PLAN, so a task only sees them once it has been
        // granted the plan tag; think is a no-op scratchpad and stays untagged
        return Arrays.asList(
                new PlanCreateTool(), new PlanUpdateTool(), new PlanGetTool(), new ThinkTool()
        );
    }
}
