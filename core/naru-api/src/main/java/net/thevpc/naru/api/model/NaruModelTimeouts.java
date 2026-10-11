package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruTaskConfig;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.platform.NEnv;
import net.thevpc.nuts.platform.NGpuDevice;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.List;

/**
 * Hardware- and locality-aware default timeouts for model calls.
 *
 * <p>A fixed 120-second read timeout is wrong for a local model: the first token
 * of a multi-turn context (4k+ tokens) on a CPU-only box routinely takes longer
 * than that, and the call is aborted while it was still making progress. A
 * remote provider, by contrast, has no reason to wait ten minutes.
 *
 * <p>Locality is a decision of the {@link NaruModelProvider} that owns the
 * endpoint (it knows its configured {@code --url}), and the hardware is a
 * property of the machine. Both are resolved here so providers, protocols and
 * tests agree on one rule:
 *
 * <ul>
 *   <li>remote: {@value #REMOTE_READ_TIMEOUT_SECONDS}s;</li>
 *   <li>local with a dedicated GPU: {@value #LOCAL_GPU_READ_TIMEOUT_SECONDS}s;</li>
 *   <li>local without one (CPU/RAM offload): {@value #LOCAL_CPU_READ_TIMEOUT_SECONDS}s.</li>
 * </ul>
 *
 * <p>An explicitly configured {@code readTimeout}/{@code timeout} always wins;
 * the values above are only the fallback. They are configurable at the task,
 * session, agent and settings scopes, either globally ({@code model.readTimeout})
 * or indexed on the model family ({@code model.qwen.readTimeout} for
 * {@code qwen3:32b}).
 */
public final class NaruModelTimeouts {

    /** Read timeout for a remote endpoint. */
    public static final long REMOTE_READ_TIMEOUT_SECONDS = 120;
    /** Read timeout for a local endpoint backed by a dedicated GPU. */
    public static final long LOCAL_GPU_READ_TIMEOUT_SECONDS = 300;
    /** Read timeout for a local endpoint running on CPU / offloaded memory. */
    public static final long LOCAL_CPU_READ_TIMEOUT_SECONDS = 600;

    public static final NDuration REMOTE_READ_TIMEOUT = NDuration.ofSeconds(REMOTE_READ_TIMEOUT_SECONDS);
    public static final NDuration LOCAL_GPU_READ_TIMEOUT = NDuration.ofSeconds(LOCAL_GPU_READ_TIMEOUT_SECONDS);
    public static final NDuration LOCAL_CPU_READ_TIMEOUT = NDuration.ofSeconds(LOCAL_CPU_READ_TIMEOUT_SECONDS);

    /** Global scoped key for the read timeout ({@code model.readTimeout}). */
    public static final String KEY_READ_TIMEOUT = "model.readTimeout";
    /** Global scoped key for the connect timeout ({@code model.connectTimeout}). */
    public static final String KEY_CONNECT_TIMEOUT = "model.connectTimeout";
    /** Global scoped key for both timeouts ({@code model.timeout}). */
    public static final String KEY_TIMEOUT = "model.timeout";

    private NaruModelTimeouts() {
    }

    /**
     * The default read timeout for an endpoint with the given locality and
     * hardware: remote, or local with/without a dedicated GPU.
     */
    public static NDuration defaultReadTimeout(boolean local, boolean dedicatedGpu) {
        if (!local) {
            return REMOTE_READ_TIMEOUT;
        }
        return dedicatedGpu ? LOCAL_GPU_READ_TIMEOUT : LOCAL_CPU_READ_TIMEOUT;
    }

    /**
     * Whether the current machine exposes at least one GPU device. Detection is
     * best-effort: a platform probe that fails is reported as "no GPU", which
     * errs on the side of the longer timeout rather than aborting a call.
     */
    public static boolean hasDedicatedGpu() {
        try {
            List<NGpuDevice> gpus = NEnv.of().gpus();
            return gpus != null && !gpus.isEmpty();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Whether an endpoint url points at the local machine:
     * {@code localhost}, {@code 127.0.0.1}, {@code [::1]} or the wildcard
     * {@code 0.0.0.0} (optionally with a scheme, a port, credentials or a path).
     */
    public static boolean isLocalUrl(String url) {
        if (NBlankable.isBlank(url)) {
            return false;
        }
        try {
            String rest = url.trim();
            int scheme = rest.indexOf("://");
            if (scheme >= 0) {
                rest = rest.substring(scheme + 3);
            }
            // drop any userinfo ("user:pass@host")
            int slash = rest.indexOf('/');
            int at = rest.lastIndexOf('@');
            if (at >= 0 && (slash < 0 || at < slash)) {
                rest = rest.substring(at + 1);
            }
            String host;
            if (rest.startsWith("[")) {
                // bracketed IPv6 literal: host is what sits between the brackets
                int close = rest.indexOf(']');
                host = close > 1 ? rest.substring(1, close) : rest;
            } else {
                int end = rest.length();
                for (int i = 0; i < rest.length(); i++) {
                    char c = rest.charAt(i);
                    if (c == '/' || c == ':' || c == '?' || c == '#') {
                        end = i;
                        break;
                    }
                }
                host = rest.substring(0, end);
            }
            host = host.trim().toLowerCase();
            return "localhost".equals(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host)
                    || "0.0.0.0".equals(host);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The family a model id belongs to: the leading run of letters, lower-cased.
     * {@code qwen3:32b} → {@code qwen}, {@code gpt-4o} → {@code gpt},
     * {@code gemini-2.5-pro} → {@code gemini}. Null when the id starts with no
     * letter (a numeric or symbolic id has no family to index on).
     */
    public static String modelFamily(String model) {
        if (NBlankable.isBlank(model)) {
            return null;
        }
        String m = model.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < m.length(); i++) {
            char c = m.charAt(i);
            if (Character.isLetter(c)) {
                sb.append(Character.toLowerCase(c));
            } else {
                break;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Resolves a scoped timeout key through the task configuration chain,
     * preferring the model-family-indexed key over the global one:
     * {@code model.<family>.<key>} then {@code model.<key>}.
     *
     * <p>The chain is {@link NaruTaskConfig}'s: task env, session env, agent env,
     * project config (private then public), then the JVM system property. A
     * family-indexed value set in any of those beats a global one set in a
     * narrower scope, because the family is the more specific instruction.
     *
     * @param task  the task whose scopes are consulted, may be null
     * @param model the model being called, used to derive the family, may be null
     * @param key   the bare timeout key ({@code readTimeout}, {@code timeout}, ...)
     */
    public static NOptional<NDuration> findScoped(NaruTask task, NaruModelConfig model, String key) {
        if (task == null || key == null) {
            return NOptional.ofEmpty();
        }
        String family = model == null ? null : modelFamily(model.model());
        if (family != null) {
            NOptional<NDuration> indexed = findDuration(task, "model." + family + "." + key);
            if (indexed.isPresent()) {
                return indexed;
            }
        }
        return findDuration(task, "model." + key);
    }

    private static NOptional<NDuration> findDuration(NaruTask task, String key) {
        return NaruTaskConfig.find(task, key)
                .flatMap(x -> NDuration.of(NaruTaskConfig.toText(x)));
    }
}
