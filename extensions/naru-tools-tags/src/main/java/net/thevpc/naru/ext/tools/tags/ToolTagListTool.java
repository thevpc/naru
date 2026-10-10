package net.thevpc.naru.ext.tools.tags;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code tag_list} — discover the tool tags and their status on this task.
 * <p>
 * The listing companion {@code tag_add}/{@code tag_remove} deliberately are not:
 * their schemas used to inline the tag catalog, which conflated "change a tag"
 * with "browse tags" and bloated every system prompt. The listing lives here
 * instead, with status filters ({@code enabled} = granted on this task,
 * {@code disabled} = not granted yet, {@code all} = both, the default) and a
 * keyword filter.
 */
public class ToolTagListTool extends DefaultNaruTool {

    public ToolTagListTool() {
        super("tag_list", new String[]{NaruToolTags.TAGS});
    }

    @Override
    public String name() {
        return "tag_list";
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Lists the tool tags known to this session and shows which are enabled on this task. "
                + "Filter by enabled, disabled or all (default). Use it to discover tags before tag_add/tag_remove.");
    }

    @Override
    public boolean isRelevant(NaruTask task) {
        Map<String, NaruToolTag> available = task.session().registry().availableTags();
        return (available != null && !available.isEmpty()) || !task.findToolTagNames().isEmpty();
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.bool("enabled", "Include tags currently enabled on this task.", false).build(),
                NaruToolParameter.bool("disabled", "Include tags not yet enabled on this task.", false).build(),
                NaruToolParameter.bool("all", "Include every known tag regardless of status (default).", false).build(),
                NaruToolParameter.string("query", "Optional keyword matched against the tag name and description.", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return listTags(context.task(),
                context.booleanArg("enabled").orNull(),
                context.booleanArg("disabled").orNull(),
                context.booleanArg("all").orNull(),
                context.stringArg("query").orNull());
    }

    /**
     * The listing itself, static so tests can drive it without the tool plumbing.
     *
     * @param enabled true to include tags enabled on the task, false/null to skip
     * @param disabled true to include tags not enabled on the task, false/null to skip
     * @param all true to include both statuses (also the default when neither filter is true)
     * @param query optional keyword matched on tag name and description
     */
    public static String listTags(NaruTask task, Boolean enabled, Boolean disabled, Boolean all, String query) {
        Map<String, NaruToolTag> available = task.session().registry().availableTags();
        if (available == null) {
            available = new LinkedHashMap<>();
        }
        Set<String> enabledNames = new LinkedHashSet<>();
        for (String n : task.findToolTagNames()) {
            if (!NBlankable.isBlank(n)) {
                enabledNames.add(n);
            }
        }

        // the universe: every registered tag, plus any granted name whose provider is not
        // installed (it is still enabled and must not disappear from the listing)
        Map<String, String> descriptions = new TreeMap<>();
        for (Map.Entry<String, NaruToolTag> e : available.entrySet()) {
            if (!NBlankable.isBlank(e.getKey())) {
                descriptions.put(e.getKey(), e.getValue() == null ? "" : e.getValue().description());
            }
        }
        for (NaruToolTag t : task.findToolTags()) {
            if (t != null && !NBlankable.isBlank(t.name())) {
                descriptions.putIfAbsent(t.name(), t.description());
            }
        }
        for (String n : enabledNames) {
            descriptions.putIfAbsent(n, "");
        }

        int enabledCount = 0;
        for (String n : descriptions.keySet()) {
            if (enabledNames.contains(n)) {
                enabledCount++;
            }
        }
        int disabledCount = descriptions.size() - enabledCount;

        boolean wantEnabled = Boolean.TRUE.equals(enabled);
        boolean wantDisabled = Boolean.TRUE.equals(disabled);
        if (Boolean.TRUE.equals(all) || (!wantEnabled && !wantDisabled)) {
            wantEnabled = true;
            wantDisabled = true;
        }

        String q = NBlankable.isBlank(query) ? null : query.toLowerCase(Locale.ROOT);
        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : descriptions.entrySet()) {
            String n = e.getKey();
            boolean isEnabled = enabledNames.contains(n);
            if (isEnabled && !wantEnabled) {
                continue;
            }
            if (!isEnabled && !wantDisabled) {
                continue;
            }
            String desc = e.getValue();
            if (q != null
                    && !n.toLowerCase(Locale.ROOT).contains(q)
                    && (desc == null || !desc.toLowerCase(Locale.ROOT).contains(q))) {
                continue;
            }
            rows.add("  [" + (isEnabled ? "enabled" : "disabled") + "] " + n
                    + (NBlankable.isBlank(desc) ? "" : " - " + desc));
        }

        if (descriptions.isEmpty()) {
            return "No tool tags are registered in this session.";
        }
        if (rows.isEmpty()) {
            return q != null
                    ? "No tag matches '" + query + "'."
                    : "No tags match the requested status.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(rows.size()).append(" tag").append(rows.size() == 1 ? "" : "s")
                .append(" (").append(enabledCount).append(" enabled, ")
                .append(disabledCount).append(" disabled):\n");
        for (String r : rows) {
            sb.append(r).append('\n');
        }
        return sb.toString();
    }
}
