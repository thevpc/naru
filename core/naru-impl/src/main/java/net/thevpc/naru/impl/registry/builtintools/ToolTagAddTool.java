package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.util.NStringUtils;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class ToolTagAddTool implements NaruTool {

    @Override
    public String name() {
        return "tooltag_add";
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Add tools that define the given tag";
    }

    // empty set, it is always included
    @Override
    public Set<String> tags() {
        return Set.of();
    }

    @Override
    public boolean isRelevant(NaruTask task) {
        Map<String, NaruToolTag> all = task.session().registry().availableTags();
        for (NaruToolTag toolTag : task.findToolTags()) {
            all.remove(toolTag.name());
        }
        return all.size() > 0;
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        Map<String, NaruToolTag> all = task.session().registry().availableTags();
        for (NaruToolTag toolTag : task.findToolTags()) {
            all.remove(toolTag.name());
        }
        StringBuilder sb = new StringBuilder("add one or more tags by name (comma separated) from the following list :");
        for (NaruToolTag value : all.values()) {
            sb.append("\n").append(value.name() + " : " + value.description());
        }
        return new NaruToolDefinitionFunction(
                name(),
                sb.toString(),
                NaruToolParameter.string("tags", sb.toString(), true).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String t = context.stringArg("tags").orNull();
        Set<String> added = new LinkedHashSet<>();
        if (t != null) {
            for (String s : NStringUtils.split(t, ", ;", true, true)) {
                context.task().addToolTag(s);
                added.add(s);
            }
        }
        return "added " + added.size() + " tags";
    }
}
