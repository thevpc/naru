package net.thevpc.naru.ext.tools.fs;

import net.thevpc.naru.api.registry.DefaultNaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.registry.NaruToolTagProvider;
import net.thevpc.naru.api.registry.NaruToolTags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NaruFilesystemToolTagProvider implements NaruToolTagProvider {
    private final List<NaruToolTag> all = new ArrayList<>();

    public NaruFilesystemToolTagProvider() {
        all.add(new DefaultNaruToolTag(NaruToolTags.FILE_SYSTEM, "file system operations including add,edit,search files"));
    }

    @Override
    public String name() {
        return "fs";
    }

    @Override
    public List<NaruToolTag> tags() {
        return Collections.unmodifiableList(all);
    }
}
