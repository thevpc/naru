package net.thevpc.naru.ext.tools.semantic.tools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.tools.semantic.store.InMemoryVectorStore;

import java.nio.file.Path;
import java.nio.file.Paths;
import net.thevpc.nuts.text.NText;

public class SemanticIndexTool extends DefaultNaruTool {

    public SemanticIndexTool() {
        // semantic is additive; the index is built in memory, so this stays a
        // read/compute tool and is still visible in plan mode.
        super("semantic_index", new String[]{NaruToolTags.DEV, NaruToolTags.SEMANTIC});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Builds or rebuilds the semantic vector index for the project.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task));
    }

    @Override
    public String execute(NaruToolCallContext context) {
        Path projectPath = Paths.get(context.task().projectDir().toString());
        InMemoryVectorStore store = SemanticSearchTool.buildIndex(projectPath);
        return String.format("SUCCESS: Built semantic index with %d code chunks.", store.size());
    }
}
