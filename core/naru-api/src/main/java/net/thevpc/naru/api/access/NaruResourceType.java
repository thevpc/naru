package net.thevpc.naru.api.access;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.util.Objects;

/**
 * The kind of thing an access rule talks about: a file, a url, a tool, a
 * directive, a shell command, and so on.
 *
 * <p>This is an <b>open</b> value type, keyed by id, not an enum. An extension
 * that introduces a new kind of resource — an MCP tool bridge, a model call —
 * can name it without a change here, and a rule that stores the type keeps
 * working. The well-known constants are a convenience vocabulary, not a closed
 * set; enforcement never requires that a type be registered.
 *
 * <p>{@link #ANY} is the wildcard: a rule whose type is {@code ANY} matches a
 * resource of any type, which is how a cross-cutting rule ("agents may never
 * read anything under {@code ~/.ssh}") is written once.
 */
public final class NaruResourceType implements NToElement {

    public static final NaruResourceType FILE = of("file");
    public static final NaruResourceType DIR = of("dir");
    public static final NaruResourceType URL = of("url");
    public static final NaruResourceType HOST = of("host");
    public static final NaruResourceType TOOL = of("tool");
    public static final NaruResourceType DIRECTIVE = of("directive");
    public static final NaruResourceType MODEL = of("model");
    public static final NaruResourceType MCP_TOOL = of("mcp-tool");
    public static final NaruResourceType SHELL = of("shell");
    public static final NaruResourceType PROCESS = of("process");
    public static final NaruResourceType ENV = of("env");
    public static final NaruResourceType TASK = of("task");
    public static final NaruResourceType SESSION = of("session");
    public static final NaruResourceType SKILL = of("skill");
    public static final NaruResourceType ANY = of("*");

    private final String id;

    private NaruResourceType(String id) {
        this.id = id;
    }

    public static NaruResourceType of(String id) {
        if (NBlankable.isBlank(id)) {
            throw new NIllegalArgumentException(NMsg.ofC("resource type id must not be blank"));
        }
        return new NaruResourceType(id.trim());
    }

    public String id() {
        return id;
    }

    public boolean isAny() {
        return "*".equals(id);
    }

    @Override
    public NElement toElement() {
        return NElement.ofString(id);
    }

    public static NaruResourceType of(NElement element) {
        if (element == null || element.isNull()) {
            return null;
        }
        String s = element.asStringValue().orNull();
        return s == null ? null : of(s);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NaruResourceType)) {
            return false;
        }
        return id.equals(((NaruResourceType) o).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
