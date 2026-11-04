package net.thevpc.naru.ext.tools.mcp;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.naru.api.registry.NaruConfigKeyProvider;

import java.util.List;

/**
 * The configuration key that materializes MCP toolsets: a
 * {@code toolset.mcp} value in the layered env is read under the generic
 * {@code toolset.<name>} registration pattern (see the registry's
 * {@code registerToolsetProvider}) and turned into one toolset per entry by
 * {@link McpToolsetProvider}. Declared here so the settings surface documents
 * the key that actually brings the MCP tools in.
 */
public class NaruMcpConfigKeysProvider implements NaruConfigKeyProvider {

    @Override
    public String name() {
        return "mcp";
    }

    @Override
    public List<NaruConfigKey> configKeys(NaruSession session) {
        return List.of(
                NaruConfigKey.of("toolset.mcp",
                        "MCP toolsets: a map of <id> to {type=mcp-stdio|mcp-sse, command=..., url=...}, read at registration")
        );
    }
}