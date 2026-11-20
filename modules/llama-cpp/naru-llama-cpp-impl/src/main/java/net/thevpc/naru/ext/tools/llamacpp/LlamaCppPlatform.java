package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.net.NHttpClient;
import net.thevpc.nuts.net.NHttpRequest;
import net.thevpc.nuts.net.NHttpResponse;
import net.thevpc.nuts.platform.NArchFamily;
import net.thevpc.nuts.platform.NEnv;
import net.thevpc.nuts.platform.NOsFamily;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves, for the current operating system and CPU architecture, the llama.cpp
 * GitHub release asset (and, for the CUDA backend, the companion {@code cudart}
 * archive) that matches the configured backend.
 *
 * <p>llama.cpp's stable releases (e.g. {@code v0.6.0}) no longer ship binaries;
 * the prebuilt bundles are attached to the nightly {@code b<NNNNN>} releases.
 * The releases list endpoint returns the newest first, so resolution walks it in
 * order and returns the first release carrying a matching asset. A pinned
 * {@code llamacpp.releaseTag} switches to the single-release endpoint.
 *
 * <p>The version-qualified assets ({@code cuda-13.4}, {@code rocm-10.0}, ...)
 * change over time, so they are matched by shape rather than by a hard-coded
 * version: the name has to carry the {@code <backend>} token and the target
 * architecture, and the CUDA runtime companion is found from the CUDA version
 * parsed out of the main asset name.
 */
public final class LlamaCppPlatform {

    public static final String GITHUB_REPO = "ggml-org/llama.cpp";
    public static final String RELEASES_URL = "https://api.github.com/repos/" + GITHUB_REPO + "/releases";

    private LlamaCppPlatform() {
    }

    /** {@code ubuntu}, {@code macos} or {@code win} — the token used in asset names. */
    public static String osKey() {
        NOsFamily os = NEnv.of().osFamily();
        if (os.isWindow()) {
            return "win";
        }
        if (os.isMacOs()) {
            return "macos";
        }
        return "ubuntu";
    }

    /** {@code x64} or {@code arm64} — the token used in asset names. */
    public static String archKey() {
        NArchFamily arch = NEnv.of().archFamily();
        return arch.isArm() ? "arm64" : "x64";
    }

    public static boolean isZip() {
        return NEnv.of().osFamily().isWindow();
    }

    static String assetSuffix() {
        return isZip() ? ".zip" : ".tar.gz";
    }

    /**
     * Resolve the release to install, or {@code null} when nothing matches.
     *
     * @param session   configuration source (pins the tag, picks the backend)
     * @param logger    progress sink, may be {@code null}
     */
    public static LlamaCppRelease resolve(NaruSession session, Consumer<NMsg> logger) {
        String os = osKey();
        String arch = archKey();
        String backend = LlamaCppPaths.backend(session);
        String pinned = LlamaCppPaths.releaseTag(session);
        String suffix = assetSuffix();
        String expected = "llama-<tag>-bin-" + osTokenPattern(os) + "-" + arch + suffix;
        if (logger != null) {
            logger.accept(NMsg.ofC("Resolving llama.cpp release for %s/%s (backend: %s, asset: %s)",
                    NMsg.ofStyledPrimary1(os),
                    NMsg.ofStyledPrimary2(arch),
                    NMsg.ofStyledPrimary3(backend),
                    NMsg.ofStyledPale(expected)));
        }

        NElement root;
        try {
            root = fetchReleases(pinned);
        } catch (Exception e) {
            if (logger != null) {
                logger.accept(NMsg.ofC("Unable to reach GitHub releases API: %s", e.getMessage()).asError());
            }
            return null;
        }
        if (root == null) {
            return null;
        }

        List<NObjectElement> releases = new ArrayList<>();
        if (pinned != null) {
            if (root.isAnyObject()) {
                releases.add(root.asObject().get());
            }
        } else if (root.isAnyArray()) {
            NArrayElement arr = root.asArray().get();
            for (NElement el : arr) {
                el.asObject().ifPresent(releases::add);
            }
        }

        for (NObjectElement release : releases) {
            String tagName = release.getStringValue("tag_name").orNull();
            if (NBlankable.isBlank(tagName)) {
                continue;
            }
            if (pinned != null && !pinned.equals(tagName)) {
                continue;
            }
            NArrayElement assetsArr = release.getArray("assets").orNull();
            if (assetsArr == null) {
                continue;
            }
            List<String> names = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            for (NElement assetEl : assetsArr) {
                assetEl.asObject().ifPresent(a -> {
                    names.add(a.getStringValue("name").orNull());
                    urls.add(a.getStringValue("browser_download_url").orNull());
                });
            }
            int idx = -1;
            for (int i = 0; i < names.size(); i++) {
                if (matchesMain(names.get(i), tagName, os, backend, arch)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                continue;
            }
            String mainName = names.get(idx);
            String mainUrl = urls.get(idx);
            String cudartName = null;
            String cudartUrl = null;
            if ("cuda".equals(backend)) {
                int ci = findCudart(names, urls, mainName, arch);
                if (ci >= 0) {
                    cudartName = names.get(ci);
                    cudartUrl = urls.get(ci);
                }
            }
            return new LlamaCppRelease(tagName, mainName, mainUrl, cudartName, cudartUrl);
        }

        if (logger != null) {
            logger.accept(NMsg.ofC("No llama.cpp asset found for %s/%s backend '%s'%s",
                    os, arch, backend,
                    pinned == null ? " in the recent releases" : (" in release " + pinned)).asError());
        }
        return null;
    }

    private static String osTokenPattern(String os) {
        return "ubuntu".equals(os) ? "ubuntu" : os;
    }

    /**
     * Whether {@code name} is the main llama.cpp bundle for the given release,
     * platform and architecture.
     */
    static boolean matchesMain(String name, String tag, String os, String backend, String arch) {
        if (name == null || !name.startsWith("llama-" + tag + "-bin-")) {
            return false;
        }
        if (name.startsWith("cudart-")) {
            return false;
        }
        String suffix = "-" + arch + assetSuffix();
        if (!name.endsWith(suffix)) {
            return false;
        }
        if (!name.contains("-bin-" + os + "-")) {
            return false;
        }
        switch (backend) {
            case "cpu": {
                if ("win".equals(os)) {
                    return name.contains("-bin-win-cpu-");
                }
                // ubuntu/macos cpu bundles carry no backend token at all
                return name.equals("llama-" + tag + "-bin-" + os + "-" + arch + assetSuffix());
            }
            case "cuda":
                return name.contains("-cuda-");
            case "vulkan":
                return name.contains("-vulkan-");
            case "rocm":
                return name.contains("-rocm-");
            case "sycl":
                return name.contains("-sycl-");
            case "openvino":
                return name.contains("-openvino-");
            default:
                return false;
        }
    }

    /**
     * Index of the CUDA runtime companion asset for a main CUDA bundle, or
     * {@code -1}. The companion shares the CUDA version token with the main
     * asset; note the Windows companion omits the release tag from its name.
     */
    static int findCudart(List<String> names, List<String> urls, String mainName, String arch) {
        Matcher m = Pattern.compile("cuda-([0-9][0-9.]*)").matcher(mainName);
        String ver = m.find() ? m.group(1) : null;
        String suffix = "-" + arch + assetSuffix();
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (n == null || !n.startsWith("cudart-")) {
                continue;
            }
            if (ver != null && !n.contains("cuda-" + ver)) {
                continue;
            }
            if (!n.endsWith(suffix)) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static NElement fetchReleases(String pinned) {
        String url = NBlankable.isBlank(pinned)
                ? RELEASES_URL + "?per_page=30"
                : RELEASES_URL + "/tags/" + pinned.trim();
        NHttpClient http = NHttpClient.of()
                .connectTimeout(NDuration.ofSeconds(30))
                .baseUri(url);
        NHttpRequest request = http.GET("")
                .timeout(NDuration.ofSeconds(60))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "naru-llamacpp");
        NHttpResponse response = request.run().failFast();
        return NElementReader.ofJson().read(response.contentAsString());
    }
}
