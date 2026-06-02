package net.thevpc.naru.ext.mcp.cli;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.nuts.elem.NObjectElement;

import java.net.http.HttpClient;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class McpSseToolset implements NaruToolset {

    private final String id;
    private final NObjectElement config;
    private McpSyncClient client;
    private List<NaruTool> tools;

    public McpSseToolset(String id, NObjectElement config) {
        this.id = id;
        this.config = config;
    }

    @Override
    public String id() {
        return id;
    }


    @Override
    public void open(NaruSession session) {
        String url = config.getStringValue("url").get();
        Map<String, String> headers = config.getObject("headers")
                .map(o -> o.namedPairs().stream()
                        .collect(Collectors.toMap(
                                x->x.key().asStringValue().orNull(),
                                e -> e.value().asStringValue().orElse(""))))
                .orElse(Collections.emptyMap());




        HttpClientSseClientTransport transport =
                HttpClientSseClientTransport.builder(url)
                        .customizeClient(builder -> {
                            // inject auth headers if present
                            if (!headers.isEmpty()) {
                                builder.version(HttpClient.Version.HTTP_1_1);
                                // headers injected per-request via custom interceptor
                            }
                        })
                        .build();

        McpSyncClient client = McpClient.sync(transport).build();
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
