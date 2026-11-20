package net.thevpc.naru.ext.skills;

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
 * Registers the {@code skill} tool as the {@code skills} toolset (WP5).
 * <p>
 * A toolset provider is discovered by the registry and its tools become part of the tool
 * list; the {@code skills} tag decides whether any given task can see the {@code skill}
 * tool. The jar is optional, so removing it removes the tool, exactly as it removes the
 * extension and the directive.
 */
public class NaruSkillsToolsetProvider implements NaruToolsetProvider {

    public static final String TOOLSET = "skills";

    @Override
    public String name() {
        return TOOLSET;
    }

    @Override
    public List<String> supportedTypes() {
        return List.of(TOOLSET);
    }

    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        String type = NNameFormat.LOWER_KEBAB_CASE.format(id);
        if (!TOOLSET.equals(type)) {
            throw new NIllegalArgumentException(
                    NMsg.ofC(getClass().getSimpleName() + ": unknown type '%s'", type));
        }
        List<NaruTool> tools = List.of(new NaruSkillTool());
        return new DefaultNaruToolset(id, tools);
    }
}
