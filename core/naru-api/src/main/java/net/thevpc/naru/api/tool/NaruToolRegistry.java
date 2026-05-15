package net.thevpc.naru.api.tool;

import net.thevpc.naru.api.agent.NaruSessionContext;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.model.NaruToolDefinition;

import java.util.*;

public interface NaruToolRegistry {
    /**
     * Build a {@link NaruToolDefinition} from a list of {@link NaruToolParameter}s.
     * Intended to be used inside each tool's constructor.
     */
    static NaruToolDefinition buildDefinition(String name, String description,
                                              NaruToolParameter... params) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (NaruToolParameter p : params) {
            properties.put(p.getName(), p.getSchema());
            if (p.isRequired()) required.add(p.getName());
        }
        return new NaruToolDefinition(name, description, properties, required);
    }

    Map<String, NaruTool> tools();
    Map<String, NaruDirective> directives();
    NaruToolRegistry registerTool(NaruTool tool);

    NaruToolRegistry registerDirective(NaruDirective tool);

    NaruToolRegistry registerModelProvider(NaruModelProvider tool);

    String dispatch(String name, Map<String, Object> arguments, NaruSessionContext context);

    String dispatch(NaruToolCall toolCall, NaruSessionContext context);

    void dispatchSlash(String name, String argument, NaruSessionContext context);

    List<NaruToolDefinition> getDefinitions();

    boolean isEmpty();

    Set<String> names();
    Map<String, NaruModelProvider> modelProviders();
}
