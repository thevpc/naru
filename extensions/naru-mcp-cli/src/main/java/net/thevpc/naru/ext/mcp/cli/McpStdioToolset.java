package net.thevpc.naru.ext.mcp.cli;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NObjectElement;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class McpStdioToolset implements NaruToolset {

    private final String id;
    private final NObjectElement config;
    private McpSyncClient client;
    private List<NaruTool> tools;

    public McpStdioToolset(String id,NObjectElement config) {
        this.id = id;
        this.config = config;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void open(NaruSession session) {
        // resolve command via Nuts runtime (not raw npx)
        String command = config.getStringValue("command").get();
        List<String> args = (List<String>) config.getArray("args").orElse(NArrayElement.ofEmpty())
                .stream().map(x->x.asStringValue().orNull()).collect(Collectors.toList());
        Map<String, String> env = config.getObject("env")
                .map(o -> o.namedPairs().stream()
                        .collect(Collectors.toMap(
                                x->x.key().asStringValue().orNull(),
                                e -> e.value().asStringValue().orElse(""))))
                .orElse(Collections.emptyMap());

        ServerParameters params = ServerParameters.builder(command)
                .env(env)
                .args(args.toArray(new String[0]))
                .build();
        client = McpClient.sync(new StdioClientTransport(params)).build();
        client.initialize();
        tools = client.listTools().tools().stream()
                .map(t -> new McpBackedTool(t, client))
                .collect(Collectors.toList());
    }

    @Override
    public List<NaruTool> tools() { return tools; }

    @Override
    public void close() { client.close(); }
}
