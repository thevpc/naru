package net.thevpc.naru.api.registry;

import net.thevpc.nuts.util.NAssert;
import net.thevpc.nuts.util.NNameFormat;
import net.thevpc.nuts.util.NStringUtils;

import java.util.Objects;

public class DefaultNaruToolTag implements NaruToolTag{
    private String id;
    private String description;

    public DefaultNaruToolTag(String id, String description) {
        NAssert.requireNamedNonBlank(id,"tag id");
        NAssert.requireNamedNonBlank(description,"tag description");
        this.id = NNameFormat.LOWER_KEBAB_CASE.format(NStringUtils.trim(id));
        this.description = NStringUtils.trim(description);
    }

    @Override
    public String name() {
        return id;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return String.valueOf(id);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        DefaultNaruToolTag that = (DefaultNaruToolTag) o;
        return Objects.equals(id, that.id) && Objects.equals(description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, description);
    }
}
