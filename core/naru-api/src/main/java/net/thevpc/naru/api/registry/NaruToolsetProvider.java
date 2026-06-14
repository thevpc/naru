package net.thevpc.naru.api.registry;

import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.util.NNameFormat;

import java.util.List;

public interface NaruToolsetProvider {
    String name();                      // "mcp-stdio", "mcp-sse", "builtin", ...

    List<String> supportedTypes();   // static: what types I know about

    // dynamic: can I actually create this right now?
    // default checks supportedTypes(), override for runtime conditions
    default boolean accept(String id, NObjectElement config) {
        return supportedTypes().contains(NNameFormat.LOWER_KEBAB_CASE.format(id));
    }

    NaruToolset createToolset(String id, NObjectElement config);
}
