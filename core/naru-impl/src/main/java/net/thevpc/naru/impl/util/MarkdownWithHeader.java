package net.thevpc.naru.impl.util;

import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

public class MarkdownWithHeader {
    private NPath source;
    private NaruSource sourceType;
    private Map<String, NElement> header;
    private String body;

    public static NOptional<MarkdownWithHeader> of(NPath source, NaruSource sourceType) {
        try {
            if (source.exists()) {
                String str = source.readString(StandardCharsets.UTF_8);
                if (!NBlankable.isBlank(str)) {
                    return of(source, sourceType, str);
                }
            }
        } catch (Exception e) {
            // just ignore
        }
        return NOptional.ofNamedEmpty(NMsg.ofC("path %s", source));
    }

    private static NOptional<MarkdownWithHeader> of(NPath source, NaruSource sourceType, String content) {
        try {
            if (source.exists()) {
                String str = content;
                if (!NBlankable.isBlank(str)) {
                    str = str.trim();
                    String h = null;
                    String p = null;
                    if (str.startsWith("---")) {
                        int x = str.indexOf("---", 3);
                        if (x > 0) {
                            h = str.substring(3, x).trim();
                            p = str.substring(x + 3).trim();
                        } else {
                            h = "";
                            p = str;
                        }
                    } else {
                        h = "";
                        p = str;
                    }
                    if (!NBlankable.isBlank(h) || !NBlankable.isBlank(p)) {
                        Map<String, NElement> e = NaruUtils.parseEnv(NElementReader.ofTson().read(h));
                        if (e.isEmpty() && NBlankable.isBlank(p)) {
                            return NOptional.ofNamedEmpty(NMsg.ofC("path %s", source));
                        }
                        return NOptional.of(new MarkdownWithHeader(source, sourceType, e, p));
                    }
                }
            }
        } catch (Exception e) {
            // just ignore
        }
        return NOptional.ofNamedEmpty(NMsg.ofC("path %s", source));
    }

    public MarkdownWithHeader(NPath source, NaruSource sourceType, Map<String, NElement> header, String body) {
        this.source = source;
        this.sourceType = sourceType;
        this.header = header;
        this.body = body;
    }

    public NPath source() {
        return source;
    }

    public NaruSource sourceType() {
        return sourceType;
    }

    public Map<String, NElement> header() {
        return header;
    }

    public String body() {
        return body;
    }
}
