package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.registry.DefaultNaruToolset;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.registry.NaruToolsetProvider;
import net.thevpc.nuts.elem.NObjectElement;

import java.util.List;

/**
 * The permission tools, in two groups that mirror their tags: listing and
 * checking are untagged and always offered, while adding and removing carry the
 * {@code perm} tag and appear only once it is granted.
 */
public class NaruPermissionsToolsetProvider implements NaruToolsetProvider {

    @Override
    public String name() {
        return "permissions";
    }

    @Override
    public List<String> supportedTypes() {
        return List.of("permissions");
    }

    @Override
    public NaruToolset createToolset(String id, NObjectElement config) {
        List<NaruTool> tools = List.of(
                new PermissionListTool(),
                new PermissionCheckTool(),
                new PermissionAddTool(),
                new PermissionRemoveTool()
        );
        return new DefaultNaruToolset(id, tools);
    }
}
