package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.nuts.elem.NArrayElement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.net.NHttpClient;
import net.thevpc.nuts.net.NHttpRequest;
import net.thevpc.nuts.net.NHttpResponse;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a model specification into the concrete GGUF files to download.
 *
 * <p>Accepted forms:
 * <pre>
 * hf.co/&lt;owner&gt;/&lt;repo&gt;[:quant]        a HuggingFace repository, quant optional
 * &lt;owner&gt;/&lt;repo&gt;[:quant]              the same repository without the hf.co prefix
 * hf.co/&lt;owner&gt;/&lt;repo&gt;/&lt;file&gt;.gguf    one explicit file of a repository
 * https://.../&lt;file&gt;.gguf             a direct download URL
 * </pre>
 *
 * <p>When no quant is named, a widely used quantisation is picked in preference
 * order ({@code Q4_K_M} first); when one is named, files whose name carries it
 * are selected. A split model ({@code ...-00001-of-0000N.gguf}) is expanded to
 * every shard, and the first shard is the file {@code llama-server} loads.
 */
public final class LlamaCppModelResolver {

    private static final List<String> PREFERRED_QUANTS = List.of(
            "Q4_K_M", "Q4_K_S", "Q5_K_M", "Q5_K_S", "Q6_K", "Q8_0",
            "Q4_0", "Q3_K_M", "Q3_K_S", "F16", "FP16", "BF16"
    );

    private static final Pattern SHARD = Pattern.compile("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$", Pattern.CASE_INSENSITIVE);

    private LlamaCppModelResolver() {
    }

    public static LlamaCppDownloadPlan resolve(String spec) {
        if (NBlankable.isBlank(spec)) {
            throw new NIllegalArgumentException(NMsg.ofC("missing model specification"));
        }
        String s = spec.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return resolveUrl(s);
        }
        String rest = s;
        if (rest.startsWith("hf.co/")) {
            rest = rest.substring("hf.co/".length());
        }
        String quant = null;
        int colon = rest.lastIndexOf(':');
        if (colon >= 0) {
            String after = rest.substring(colon + 1);
            if (!after.isEmpty() && !after.contains("/") && !after.contains("\\")) {
                quant = after;
                rest = rest.substring(0, colon);
            }
        }
        rest = rest.replaceAll("^/+", "").replaceAll("/+$", "");
        String repo;
        String file = null;
        if (rest.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            int slash = rest.lastIndexOf('/');
            if (slash < 0) {
                throw new NIllegalArgumentException(NMsg.ofC("invalid model specification '%s'", spec));
            }
            file = rest.substring(slash + 1);
            repo = rest.substring(0, slash);
        } else {
            repo = rest;
        }
        if (repo.isEmpty() || !repo.contains("/")) {
            throw new NIllegalArgumentException(NMsg.ofC(
                    "invalid model specification '%s': expected <owner>/<repo>[:quant], hf.co/<repo>, or a .gguf URL", spec));
        }
        return resolveHf(repo, quant, file);
    }

    // ── HuggingFace ────────────────────────────────────────────────────────────

    private static LlamaCppDownloadPlan resolveHf(String repo, String quant, String file) {
        NElement root;
        try {
            root = fetchHfModel(repo);
        } catch (Exception e) {
            throw new NIllegalArgumentException(NMsg.ofC(
                    "cannot read HuggingFace repository '%s': %s", repo, e.getMessage()));
        }
        List<String> siblings = new ArrayList<>();
        if (root != null && root.isAnyObject()) {
            NArrayElement arr = root.asObject().get().getArray("siblings").orNull();
            if (arr != null) {
                for (NElement el : arr) {
                    el.asObject().ifPresent(o -> o.getStringValue("rfilename").ifPresent(siblings::add));
                }
            }
        }
        if (siblings.isEmpty()) {
            throw new NIllegalArgumentException(NMsg.ofC(
                    "HuggingFace repository '%s' exposes no files (is it gated or mistyped?)", repo));
        }

        List<String> ggufs = new ArrayList<>();
        for (String name : siblings) {
            if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
                ggufs.add(name);
            }
        }
        if (ggufs.isEmpty()) {
            throw new NIllegalArgumentException(NMsg.ofC(
                    "HuggingFace repository '%s' contains no .gguf file", repo));
        }

        String main;
        if (!NBlankable.isBlank(file)) {
            main = null;
            for (String g : ggufs) {
                if (lastSegment(g).equalsIgnoreCase(file) || g.equalsIgnoreCase(file)) {
                    main = g;
                    break;
                }
            }
            if (main == null) {
                // trust the explicit request even if it is not listed verbatim
                main = file;
            }
        } else {
            main = chooseMain(ggufs, quant, repo);
        }

        List<String> parts = expandShards(ggufs, main);
        String mainName = lastSegment(main);
        List<LlamaCppDownloadPlan.FileEntry> files = new ArrayList<>();
        if (parts.size() > 1) {
            for (String p : parts) {
                files.add(new LlamaCppDownloadPlan.FileEntry(lastSegment(p), hfUrl(repo, p)));
            }
            mainName = lastSegment(parts.get(0));
        } else {
            files.add(new LlamaCppDownloadPlan.FileEntry(mainName, hfUrl(repo, main)));
        }
        return new LlamaCppDownloadPlan(mainName, files);
    }

    private static String chooseMain(List<String> ggufs, String quant, String repo) {
        List<String> base = new ArrayList<>();
        for (String g : ggufs) {
            String n = g.toLowerCase(Locale.ROOT);
            if (!n.contains("mmproj")) {
                base.add(g);
            }
        }
        if (base.isEmpty()) {
            base = ggufs;
        }
        if (!NBlankable.isBlank(quant)) {
            String q = quant.toUpperCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String g : base) {
                if (lastSegment(g).toUpperCase(Locale.ROOT).contains(q)) {
                    matches.add(g);
                }
            }
            if (matches.isEmpty()) {
                throw new NIllegalArgumentException(NMsg.ofC(
                        "no '%s' quantisation in HuggingFace repository '%s' (available: %s)",
                        quant, repo, String.join(", ", availableQuants(base))));
            }
            return matches.get(0);
        }
        for (String preferred : PREFERRED_QUANTS) {
            for (String g : base) {
                if (lastSegment(g).toUpperCase(Locale.ROOT).contains(preferred)) {
                    return g;
                }
            }
        }
        return base.get(0);
    }

    private static List<String> availableQuants(List<String> names) {
        LinkedHashSet<String> qs = new LinkedHashSet<>();
        for (String n : names) {
            String upper = lastSegment(n).toUpperCase(Locale.ROOT);
            for (String q : PREFERRED_QUANTS) {
                if (upper.contains(q)) {
                    qs.add(q);
                }
            }
        }
        if (qs.isEmpty()) {
            return names;
        }
        return new ArrayList<>(qs);
    }

    /** All shards of {@code main} (itself when it is a single file). */
    static List<String> expandShards(List<String> available, String main) {
        List<String> one = new ArrayList<>();
        String seg = lastSegment(main);
        Matcher m = SHARD.matcher(seg);
        if (!m.matches()) {
            one.add(main);
            return one;
        }
        String base = m.group(1);
        String total = m.group(3);
        // find every part by matching <dir><base>-<NNNNN>-of-<total>.gguf
        String dir = main.substring(0, main.length() - seg.length());
        List<String> parts = new ArrayList<>();
        for (int i = 1; i <= Integer.parseInt(total); i++) {
            String candidateSeg = base + "-" + String.format("%05d", i) + "-of-" + total + ".gguf";
            String candidate = dir + candidateSeg;
            boolean found = false;
            for (String a : available) {
                if (a.equalsIgnoreCase(candidate) || lastSegment(a).equalsIgnoreCase(candidateSeg)) {
                    parts.add(a);
                    found = true;
                    break;
                }
            }
            if (!found) {
                // the URL form can always be reconstructed even when not listed
                parts.add(candidate);
            }
        }
        return parts;
    }

    private static String hfUrl(String repo, String rfilename) {
        return "https://huggingface.co/" + repo + "/resolve/main/" + rfilename + "?download=true";
    }

    private static NElement fetchHfModel(String repo) {
        String url = "https://huggingface.co/api/models/" + repo;
        NHttpClient http = NHttpClient.of()
                .connectTimeout(NDuration.ofSeconds(30))
                .baseUri(url);
        NHttpRequest request = http.GET("")
                .timeout(NDuration.ofSeconds(60))
                .header("User-Agent", "naru-llamacpp");
        NHttpResponse response = request.run().failFast();
        return NElementReader.ofJson().read(response.contentAsString());
    }

    // ── Direct URL ─────────────────────────────────────────────────────────────

    private static LlamaCppDownloadPlan resolveUrl(String url) {
        String noQuery = url;
        int q = noQuery.indexOf('?');
        if (q >= 0) {
            noQuery = noQuery.substring(0, q);
        }
        int slash = noQuery.lastIndexOf('/');
        String fileName = slash >= 0 ? noQuery.substring(slash + 1) : noQuery;
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            throw new NIllegalArgumentException(NMsg.ofC(
                    "unsupported model URL '%s': expected a .gguf file", url));
        }
        List<LlamaCppDownloadPlan.FileEntry> files = new ArrayList<>();
        Matcher m = SHARD.matcher(fileName);
        if (m.matches()) {
            String base = m.group(1);
            String total = m.group(3);
            String prefix = url.substring(0, url.length() - fileName.length());
            for (int i = 1; i <= Integer.parseInt(total); i++) {
                String seg = base + "-" + String.format("%05d", i) + "-of-" + total + ".gguf";
                files.add(new LlamaCppDownloadPlan.FileEntry(seg, prefix + seg + suffixOf(url)));
            }
        } else {
            files.add(new LlamaCppDownloadPlan.FileEntry(fileName, url));
        }
        return new LlamaCppDownloadPlan(files.get(0).getFileName(), files);
    }

    private static String suffixOf(String url) {
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(q) : "";
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
