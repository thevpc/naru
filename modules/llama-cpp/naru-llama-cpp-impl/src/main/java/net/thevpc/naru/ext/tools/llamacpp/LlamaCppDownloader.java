package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.net.NHttpClient;
import net.thevpc.nuts.net.NHttpRequest;
import net.thevpc.nuts.net.NHttpResponse;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.time.NDuration;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * A single-file HTTP(S) download with byte progress, used for GitHub release
 * assets and HuggingFace model files.
 *
 * <p>The response body is streamed straight to the destination: a GGUF can be
 * several gigabytes, so it is never materialised in memory. Content-Length is
 * reported when the server sends one; otherwise the transfer is still complete,
 * just without a percentage.
 */
public final class LlamaCppDownloader {

    private static final int BUFFER = 1 << 16;

    private LlamaCppDownloader() {
    }

    public static void download(String url, NPath target, Consumer<NMsg> logger) {
        target.parent().mkdirs();
        if (logger != null) {
            logger.accept(NMsg.ofC("Downloading %s", NMsg.ofStyledPrimary1(url)));
        }
        NHttpClient http = NHttpClient.of()
                .connectTimeout(NDuration.ofSeconds(60))
                .baseUri(url);
        NHttpRequest request = http.GET("")
                .timeout(NDuration.ofMinutes(120))
                .header("User-Agent", "naru-llamacpp");
        NHttpResponse response = request.run().failFast();
        long total = -1;
        try {
            total = response.content().contentLength();
        } catch (Exception ignored) {
        }

        long done = 0;
        int lastPercent = -1;
        byte[] buf = new byte[BUFFER];
        try (InputStream in = response.content().inputStream();
             OutputStream out = target.getOutputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (logger != null && total > 0) {
                    int percent = (int) ((done * 100) / total);
                    if (percent >= lastPercent + 5) {
                        lastPercent = percent;
                        logger.accept(NMsg.ofC("  %s%% of %s",
                                NMsg.ofStyledNumber(String.valueOf(percent)),
                                NMsg.ofStyledPale(formatBytes(total))));
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException(NMsg.ofC("Download failed for %s: %s", url, e.getMessage()).toString(), e);
        }
        if (total > 0 && done != total) {
            throw new RuntimeException(NMsg.ofC(
                    "Download incomplete for %s: received %s of %s bytes",
                    url, done, total).toString());
        }
        if (logger != null) {
            logger.accept(NMsg.ofC("  saved %s to %s",
                    NMsg.ofStyledNumber(formatBytes(done)),
                    NMsg.ofStyledPale(target.toString())));
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format("%.1f %s", v, units[u]);
    }
}
