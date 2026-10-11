package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.registry.DefaultNaruToolset;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.registry.NaruToolsetProvider;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

import java.util.List;

/**
 * Registers {@link ContextCompactTool} as the {@code context} toolset.
 *
 * <p>A toolset rather than a bare tool so a session can turn the whole thing off, or take
 * only part of it, with the ordinary toolset mechanism. The tool itself is also gated by
 * {@code naru.compact.modelCanCompact}, because "the toolset is installed" and "the model may
 * use it" are separate decisions: a project may want the tool available to scripts without
 * handing an agent the ability to discard its own history.
 */
public class NaruCompactToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "context";
    }

    @Override
    public List<String> supportedTypes() {
        return List.of("context", "compact");
    }

    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        String type = NNameFormat.LOWER_KEBAB_CASE.format(id);
        switch (type) {
            case "context":
            case "compact":
                return new DefaultNaruToolset(id, List.of(new ContextCompactTool()));
            default:
                throw new NIllegalArgumentException(
                        NMsg.ofC(getClass().getSimpleName() + ": unknown type '%s'", type));
        }
    }

    /** Kept so the tool type is a visible part of this file's contract. */
    static Class<? extends NaruTool> toolType() {
        return ContextCompactTool.class;
    }
}