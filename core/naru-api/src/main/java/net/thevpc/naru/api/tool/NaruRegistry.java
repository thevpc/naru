package net.thevpc.naru.api.tool;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.*;
import net.thevpc.nuts.util.NOptional;

import java.util.*;

public interface NaruRegistry {
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

    NaruRegistry registerTool(NaruTool tool);

    NaruRegistry registerDirective(NaruDirective tool);

    NaruRegistry registerModelProvider(NaruModelProvider tool);

    String dispatch(String name, Map<String, Object> arguments, NaruSession context);

    String dispatch(NaruToolCall toolCall, NaruSession context);

    NOptional<NaruDirective> findDirective(String name);
    void dispatchSlash(String name, String argument, NaruSession context);

    boolean isEmpty();

    Set<String> names();

    Map<String, NaruModelProvider> modelProviders();

    List<NaruModelInfo> modelsInfos();
    List<NaruModelKey> modelsKeys();

    NOptional<NaruModelKey> findModel(String keyOrName);

    NOptional<NaruModelProvider> provider(String provider);

    NOptional<NaruModelProtocol> protocol(NaruModelKey model);
}
