package net.thevpc.naru.ext.tools.semantic.tools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.tools.index.spi.CodeIndex;
import net.thevpc.naru.ext.tools.index.spi.CodeSymbol;
import net.thevpc.naru.ext.tools.index.spi.ProjectScanner;
import net.thevpc.naru.ext.tools.semantic.embed.TfIdfEmbeddingProvider;
import net.thevpc.naru.ext.tools.semantic.spi.ChunkMatch;
import net.thevpc.naru.ext.tools.semantic.spi.CodeChunk;
import net.thevpc.naru.ext.tools.semantic.spi.EmbeddingProvider;
import net.thevpc.naru.ext.tools.semantic.store.InMemoryVectorStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class SemanticSearchTool extends DefaultNaruTool {

    private static final Map<String, InMemoryVectorStore> STORES = new HashMap<>();
    private static final EmbeddingProvider EMBEDDER = new TfIdfEmbeddingProvider();

    public SemanticSearchTool() {
        // semantic is additive: every semantic tool also wears it, so the tag
        // describes a capability rather than replacing the broader 'dev' grant.
        // Granting either dev or semantic reveals the tool (OR matching).
        super("semantic_search", new String[]{NaruToolTags.DEV, NaruToolTags.SEMANTIC});
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Natural language code search using semantic/TF-IDF vector matching.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task),
                NaruToolParameter.string("query", "Natural language search query", true).build(),
                NaruToolParameter.integer("top_k", "Number of top results to return (default 5)", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String query = context.stringArg("query").orNull();
        if (query == null || query.isBlank()) {
            return "ERROR: 'query' parameter is required.";
        }
        Integer topK = context.intArg("top_k").orNull();
        if (topK == null || topK <= 0) topK = 5;

        Path projectPath = Paths.get(context.task().projectDir().toString());
        InMemoryVectorStore store = getOrBuildStore(projectPath);

        float[] queryVec = EMBEDDER.embed(query);
        List<ChunkMatch> matches = store.search(queryVec, topK);

        if (matches.isEmpty()) {
            return "No semantic matches found for query: " + query;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Semantic search results for: \"").append(query).append("\"\n\n");

        for (int i = 0; i < matches.size(); i++) {
            ChunkMatch m = matches.get(i);
            CodeChunk chunk = m.getChunk();
            sb.append(String.format("[%d] Score: %.2f | %s (lines %d-%d)\n",
                    i + 1, m.getScore(), chunk.getFile(), chunk.getStartLine(), chunk.getEndLine()));
            if (chunk.getSymbolName() != null) {
                sb.append("    Symbol: ").append(chunk.getSymbolName()).append("\n");
            }
            sb.append("```\n").append(chunk.getContent()).append("\n```\n\n");
        }

        return sb.toString();
    }

    public static synchronized InMemoryVectorStore getOrBuildStore(Path projectPath) {
        String key = projectPath.toString();
        InMemoryVectorStore store = STORES.get(key);
        if (store == null || store.size() == 0) {
            store = buildIndex(projectPath);
            STORES.put(key, store);
        }
        return store;
    }

    public static synchronized InMemoryVectorStore buildIndex(Path projectPath) {
        InMemoryVectorStore store = new InMemoryVectorStore();
        CodeIndex codeIndex = ProjectScanner.getCachedOrScan(projectPath);

        for (CodeSymbol sym : codeIndex.allSymbols()) {
            try {
                Path filePath = projectPath.resolve(sym.getFile());
                if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                    List<String> lines = Files.readAllLines(filePath);
                    int start = Math.max(1, sym.getLine());
                    int end = sym.getEndLine() > 0 ? Math.min(lines.size(), sym.getEndLine()) : start;

                    StringBuilder chunkText = new StringBuilder();
                    for (int l = start - 1; l < end && l < lines.size(); l++) {
                        chunkText.append(lines.get(l)).append("\n");
                    }

                    String content = chunkText.toString();
                    if (!content.isBlank()) {
                        CodeChunk chunk = new CodeChunk(sym.getFile(), start, end, content, sym.getName());
                        float[] vec = EMBEDDER.embed(sym.getName() + " " + sym.getSignature() + "\n" + content);
                        store.add(chunk, vec);
                    }
                }
            } catch (Exception e) {
                // Ignore file read error
            }
        }

        return store;
    }
}
