package net.thevpc.naru.ext.tools.tags;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.util.NStringUtils;

import java.util.*;

public class ToolTagRemoveTool extends DefaultNaruTool {

    public ToolTagRemoveTool() {
        super("tag_remove", new String[]{NaruToolTags.TAGS});
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Removes tools that define the given tag";
    }

    @Override
    public boolean isRelevant(NaruTask task){
        return !task.findToolTags().isEmpty();
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        String description = "Remove one or more tool tags by name (comma separated). "
                + "Use tag_list with enabled=true to see the tags currently enabled on this task.";
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
                context.task().removeToolTag(s);
                added.add(s);
            }
        }
        return "removed " + added.size() + " tags";
    }
}
