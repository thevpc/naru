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
        Map<String, NaruToolTag> all = task.session().registry().availableTags();
        for (NaruToolTag toolTag : task.findToolTags()) {
            all.remove(toolTag.name());
        }
        StringBuilder sb = new StringBuilder();
        if(all.isEmpty()){
            sb.append("add one or more tags by name (comma separated), however it seems that all tags are already added");
        }else {
            sb.append("add one or more tags by name (comma separated) from the following list :");
            for (NaruToolTag value : all.values()) {
                sb.append("\n        ").append(value.name()).append(" : ").append(value.description());
            }
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
