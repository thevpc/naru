package net.thevpc.naru.ext.tools.index.tools;

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
import net.thevpc.naru.ext.tools.index.spi.SymbolKind;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import net.thevpc.nuts.text.NText;

public class FindSymbolTool extends DefaultNaruTool {
    public FindSymbolTool() {
        super("find_symbol", new String[]{NaruToolTags.DEV, NaruToolTags.INDEX});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Finds symbols by name.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task),
                NaruToolParameter.string("name", "symbol name to search", true).build(),
                NaruToolParameter.string("kind", "SymbolKind filter", false).build(),
                NaruToolParameter.string("match", "exact/prefix/contains", false).build(),
                NaruToolParameter.integer("max_results", "Max results", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String name = context.stringArg("name").orNull();
        if (name == null) name = "";
        String kindStr = context.stringArg("kind").orNull();
        String match = context.stringArg("match").orNull();
        if (match == null) match = "contains";
        Integer max = context.intArg("max_results").orNull();
        if (max == null) max = 20;

        SymbolKind kind = null;
        if (kindStr != null) {
            try { kind = SymbolKind.valueOf(kindStr.toUpperCase()); } catch (Exception e) {}
        }

        Path projPath = Paths.get(context.task().projectDir().toString());
        CodeIndex index = ProjectScanner.getCachedOrScan(projPath);
        
        List<CodeSymbol> results = index.findByName(name, kind, match);
        if (results.size() > max) {
            results = results.subList(0, max);
        }

        StringBuilder sb = new StringBuilder();
        for (CodeSymbol sym : results) {
            sb.append(sym.getKind().name()).append(" ")
              .append(sym.getName()).append(" (")
              .append(sym.getSignature()).append(") at ")
              .append(sym.getFile()).append(":")
              .append(sym.getLine()).append("\n");
        }
        
        if (sb.length() == 0) return "No matching symbols found.";
        return sb.toString();
    }
}
