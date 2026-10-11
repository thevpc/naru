package net.thevpc.naru.ext.tools.index.spi;

import net.thevpc.nuts.spi.NComponent;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public interface LanguageIndexer extends NComponent {
    String language();
    Set<String> extensions();
    List<CodeSymbol> index(Path file);
}
