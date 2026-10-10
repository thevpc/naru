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
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

public class CodeSymbolsTool extends DefaultNaruTool {
    public CodeSymbolsTool() {
        super("code_symbols", new String[]{NaruToolTags.DEV, NaruToolTags.INDEX});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Returns indexed symbols as a compact list.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task),
                NaruToolParameter.string("path", "file or directory", false).build(),
                NaruToolParameter.string("kind", "filter by SymbolKind name", false).build(),
                NaruToolParameter.bool("recursive", "recursive", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        NaruTask task = context.task();
        Path projPath = Paths.get(task.projectDir().toString());
        CodeIndex index = ProjectScanner.getCachedOrScan(projPath);

        String pathArg = context.stringArg("path").orNull();
        String kind = context.stringArg("kind").orNull();
        
        List<CodeSymbol> symbols;
        if (NBlankable.isNonBlank(pathArg)) {
            Path argPath = Paths.get(task.resolve(pathArg).toString());
            String relPath = projPath.relativize(argPath).toString().replace('\\', '/');
            symbols = index.findByFile(relPath);
            if (symbols == null || symbols.isEmpty()) {
                symbols = index.allSymbols().stream().filter(s -> s.getFile().startsWith(relPath)).collect(Collectors.toList());
            }
        } else {
            symbols = index.allSymbols();
        }

        StringBuilder sb = new StringBuilder();
        for (CodeSymbol sym : symbols) {
            if (kind == null || kind.equalsIgnoreCase(sym.getKind().name())) {
                sb.append(sym.getKind().name()).append(" ")
                  .append(sym.getName()).append(" (line ")
                  .append(sym.getLine()).append("-").append(sym.getEndLine())
                  .append(") in ").append(sym.getFile()).append("\n");
            }
        }
        if (sb.length() == 0) return "No symbols found.";
        return sb.toString();
    }
}
