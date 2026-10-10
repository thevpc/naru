package net.thevpc.naru.ext.tools.tags;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.util.NStringUtils;

import java.util.*;

public class ToolTagAddTool extends DefaultNaruTool {

    public ToolTagAddTool() {
        super("tag_add", new String[]{NaruToolTags.TAGS});
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Add tools that define the given tag";
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
        String description = "Add one or more tool tags by name (comma separated). "
                + "Use tag_list to see the known tags and which are already enabled.";
        return new NaruToolDefinitionFunction(
                name(),
                description,
                NaruToolParameter.string("tags", description, true).build()
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
