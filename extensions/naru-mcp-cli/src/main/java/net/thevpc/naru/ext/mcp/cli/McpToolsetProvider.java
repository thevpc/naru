package net.thevpc.naru.ext.mcp.cli;

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
        switch (NNameFormat.LOWER_KEBAB_CASE.format(type)){
            case "mcp-stdio":{
                return config.getStringValue("command").isPresent();
            }
            case "mcp-sse":{
                return config.getStringValue("url").isPresent();
            }
        }
        return NaruToolsetProvider.super.accept(id, config);
    }

    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        switch (NNameFormat.LOWER_KEBAB_CASE.format(config.getStringValue("type").orElse(""))) {
            case "mcp-stdio":
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
