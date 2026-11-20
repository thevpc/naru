package net.thevpc.naru.ext.tools.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolsetProviderTest {
    @Test
    void doesNotAcceptSynthesizedEmptyTransportConfigs() {
        assertFalse(McpToolsetProvider.accepts("", false, false));
        assertFalse(McpToolsetProvider.accepts("mcp-stdio", false, false));
        assertFalse(McpToolsetProvider.accepts("mcp-sse", false, false));
    }

    @Test
    void acceptsConfiguredTransports() {
        assertTrue(McpToolsetProvider.accepts("", true, false));
        assertTrue(McpToolsetProvider.accepts("mcp-stdio", true, false));
        assertTrue(McpToolsetProvider.accepts("mcp-sse", false, true));
    }
}
