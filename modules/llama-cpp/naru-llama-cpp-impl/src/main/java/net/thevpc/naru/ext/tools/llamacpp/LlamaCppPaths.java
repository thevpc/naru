package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.naru.api.agent.NaruSession;
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
    public static String config(NaruSession session, String key) {
        return NaruModelRegistration.envValue(session, key);
    }

    public static String url(NaruSession session) {
        String u = config(session, "llamacpp.url");
        if (NBlankable.isBlank(u)) {
            u = "http://" + host(session) + ":" + port(session);
        }
        return u.replaceAll("/+$", "");
    }

    public static String host(NaruSession session) {
        String h = config(session, "llamacpp.host");
        if (!NBlankable.isBlank(h)) {
            return h.trim();
        }
        String u = config(session, "llamacpp.url");
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

    public static int port(NaruSession session) {
        String p = config(session, "llamacpp.port");
        if (!NBlankable.isBlank(p)) {
            try {
                return Integer.parseInt(p.trim());
            } catch (Exception ignored) {
            }
        }
        String u = config(session, "llamacpp.url");
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

    public static NPath home(NaruSession session) {
        String h = config(session, "llamacpp.home");
        if (NBlankable.isBlank(h)) {
            return NPath.ofUserHome().resolve(".naru").resolve("llama.cpp");
        }
        return NPath.of(h.trim());
    }

    public static NPath binDir(NaruSession session) {
        return home(session).resolve("bin");
    }

    public static NPath modelsDir(NaruSession session) {
        String m = config(session, "llamacpp.modelsDir");
        if (NBlankable.isBlank(m)) {
            return home(session).resolve("models");
        }
        return NPath.of(m.trim());
    }

    public static String serverExecutableName() {
        return NEnv.of().osFamily().isWindow() ? "llama-server.exe" : "llama-server";
    }

    public static NPath serverExecutable(NaruSession session) {
        return binDir(session).resolve(serverExecutableName());
    }

    public static String backend(NaruSession session) {
        String b = config(session, "llamacpp.backend");
        return NBlankable.isBlank(b) ? "cpu" : b.trim().toLowerCase();
    }

    public static String releaseTag(NaruSession session) {
        String t = config(session, "llamacpp.releaseTag");
        if (NBlankable.isBlank(t)) {
            t = config(session, "llamacpp.tag");
        }
        return NBlankable.isBlank(t) ? null : t.trim();
    }

    public static String defaultModel(NaruSession session) {
        String m = config(session, "llamacpp.model");
        return NBlankable.isBlank(m) ? null : m.trim();
    }
}
