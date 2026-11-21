package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.naru.api.model.NaruModelContext;
import net.thevpc.naru.api.model.NaruModelRegistration;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.platform.NEnv;
import net.thevpc.nuts.util.NBlankable;

import java.net.URI;

/**
 * Resolution of the llama.cpp configuration keys and of the directories the
 * extension manages.
 *
 * <p>Every key is read through the layered environment (session env, then agent
 * env, then the system environment) with the same precedence the model
 * registration path uses, so a key exported in any layer is honoured without
 * re-registration:
 *
 * <pre>
 * llamacpp.url        server root (default http://127.0.0.1:8080)
 * llamacpp.host       bind host (default parsed from url, else 127.0.0.1)
 * llamacpp.port       bind port (default parsed from url, else 8080)
 * llamacpp.home       install home (default ~/.naru/llama.cpp)
 * llamacpp.modelsDir  GGUF directory (default &lt;home&gt;/models)
 * llamacpp.backend    cpu|cuda|vulkan|rocm|sycl|openvino (default cpu)
 * llamacpp.releaseTag pinned release tag, e.g. b11429 (default: latest with binaries)
 * llamacpp.model      default GGUF to load when starting the server
 * </pre>
 */
public final class LlamaCppPaths {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8080;

    private LlamaCppPaths() {
    }

    /** Layered lookup of a raw configuration key, or null when unset. */
    public static String config(NaruModelContext context, String key) {
        return (context == null ? NaruModelContext.standalone() : context).envValue(key);
    }

    public static String url(NaruModelContext context) {
        String u = config(context, "llamacpp.url");
        if (NBlankable.isBlank(u)) {
            u = "http://" + host(context) + ":" + port(context);
        }
        return u.replaceAll("/+$", "");
    }

    public static String host(NaruModelContext context) {
        String h = config(context, "llamacpp.host");
        if (!NBlankable.isBlank(h)) {
            return h.trim();
        }
        String u = config(context, "llamacpp.url");
        if (!NBlankable.isBlank(u)) {
            try {
                String host = URI.create(u.trim()).getHost();
                if (!NBlankable.isBlank(host)) {
                    return host;
                }
            } catch (Exception ignored) {
            }
        }
        return DEFAULT_HOST;
    }

    public static int port(NaruModelContext context) {
        String p = config(context, "llamacpp.port");
        if (!NBlankable.isBlank(p)) {
            try {
                return Integer.parseInt(p.trim());
            } catch (Exception ignored) {
            }
        }
        String u = config(context, "llamacpp.url");
        if (!NBlankable.isBlank(u)) {
            try {
                int pp = URI.create(u.trim()).getPort();
                if (pp > 0) {
                    return pp;
                }
            } catch (Exception ignored) {
            }
        }
        return DEFAULT_PORT;
    }

    public static NPath home(NaruModelContext context) {
        String h = config(context, "llamacpp.home");
        if (NBlankable.isBlank(h)) {
            return NPath.ofUserHome().resolve(".naru").resolve("llama.cpp");
        }
        return NPath.of(h.trim());
    }

    public static NPath binDir(NaruModelContext context) {
        return home(context).resolve("bin");
    }

    public static NPath modelsDir(NaruModelContext context) {
        String m = config(context, "llamacpp.modelsDir");
        if (NBlankable.isBlank(m)) {
            return home(context).resolve("models");
        }
        return NPath.of(m.trim());
    }

    public static String serverExecutableName() {
        return NEnv.of().osFamily().isWindow() ? "llama-server.exe" : "llama-server";
    }

    public static NPath serverExecutable(NaruModelContext context) {
        return binDir(context).resolve(serverExecutableName());
    }

    public static String backend(NaruModelContext context) {
        String b = config(context, "llamacpp.backend");
        return NBlankable.isBlank(b) ? "cpu" : b.trim().toLowerCase();
    }

    public static String releaseTag(NaruModelContext context) {
        String t = config(context, "llamacpp.releaseTag");
        if (NBlankable.isBlank(t)) {
            t = config(context, "llamacpp.tag");
        }
        return NBlankable.isBlank(t) ? null : t.trim();
    }

    public static String defaultModel(NaruModelContext context) {
        String m = config(context, "llamacpp.model");
        return NBlankable.isBlank(m) ? null : m.trim();
    }
}
