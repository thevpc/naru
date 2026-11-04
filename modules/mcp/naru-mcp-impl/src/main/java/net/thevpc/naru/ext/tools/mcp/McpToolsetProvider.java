package net.thevpc.naru.ext.tools.mcp;

import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.registry.NaruToolsetProvider;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

import java.util.Arrays;
import java.util.List;

public class McpToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "mcp";
    }

    @Override
    public List<String> supportedTypes() {
        return Arrays.asList("mcp-stdio", "mcp-sse");
    }

    @Override
    public boolean accept(String id, NObjectElement config) {
        String type = config.getStringValue("type").orElse("");
        return accepts(type,
                config.getStringValue("command").isPresent(),
                config.getStringValue("url").isPresent());
    }

    static boolean accepts(String typeValue, boolean hasCommand, boolean hasUrl) {
        String type = NNameFormat.LOWER_KEBAB_CASE.format(typeValue);
        if (type.isEmpty()) {
            // A configured command is enough to retain the historical stdio shorthand.
            // The empty fallback config synthesized by NaruRegistryImpl must not create
            // an unconfigured toolset that fails when the session starts.
            return hasCommand;
        }
        switch (type) {
            case "mcp-stdio": {
                return hasCommand;
            }
            case "mcp-sse": {
                return hasUrl;
            }
            default:
                return false;
        }
    }

    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        switch (NNameFormat.LOWER_KEBAB_CASE.format(config.getStringValue("type").orElse(""))) {
            case "mcp-stdio":
            case "":
                return new McpStdioToolset(id, config);
            case "mcp-sse":
                return new McpSseToolset(id, config);
            default:
                throw new NIllegalArgumentException(
                        NMsg.ofC("McpToolsetProvider: unsupported transport type '%s'", config.getStringValue("type").orElse("(missing)"))
                );
        }
    }
}
