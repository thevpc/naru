package net.thevpc.naru.api.registry;

import java.util.List;

public interface NaruDirectiveProvider {
    String name();                      // "mcp-stdio", "mcp-sse", "builtin", ...

    List<NaruDirective> directives();   // static: what types I know about
}
