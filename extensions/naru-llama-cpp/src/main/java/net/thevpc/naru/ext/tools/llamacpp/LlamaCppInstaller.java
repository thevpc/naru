package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NUncompress;
import net.thevpc.nuts.platform.NEnv;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Downloads and installs the prebuilt llama.cpp bundle for the host platform.
 *
 * <p>Installation is self-contained under {@code llamacpp.home}: the release
 * archive (plus the CUDA runtime companion when the backend is {@code cuda}) is
 * fetched into a staging directory, unpacked there, and every regular file is
 * flattened into {@code <home>/bin}. Flattening is deliberate: Linux and macOS
 * archives nest a single {@code llama-b<NNNNN>/} directory, the Windows zip is
 * flat, and the CUDA runtime is a flat overlay — copying the files rather than
 * the directory structure makes all three layouts land the same way, with
 * {@code llama-server} (or {@code llama-server.exe}) next to its libraries, which
 * is where its {@code $ORIGIN} runtime path expects them.
 */
public final class LlamaCppInstaller {

    private LlamaCppInstaller() {
    }

    public static boolean install(NaruSession session, Consumer<NMsg> logger) {
        LlamaCppRelease release = LlamaCppPlatform.resolve(session, logger);
        if (release == null) {
            return false;
        }
        if (logger != null) {
            logger.accept(NMsg.ofC("Installing llama.cpp %s for %s/%s...",
                    NMsg.ofStyledPrimary1(release.getTag()),
                    NMsg.ofStyledPrimary2(LlamaCppPlatform.osKey()),
                    NMsg.ofStyledPrimary3(LlamaCppPlatform.archKey())));
        }

        NPath home = LlamaCppPaths.home(session);
        NPath staging = home.resolve("tmp").resolve(release.getTag());
        try {
            staging.deleteTree();
        } catch (Exception ignored) {
        }
        staging.mkdirs();

        NPath mainArchive = staging.resolve(release.getAssetName());
        NPath extractDir = staging.resolve("extract");
        extractDir.mkdirs();

        try {
            LlamaCppDownloader.download(release.getAssetUrl(), mainArchive, logger);
            extractArchive(mainArchive, extractDir);

            if (!NBlankable.isBlank(release.getCudartAssetUrl())) {
                NPath cudartArchive = staging.resolve(release.getCudartAssetName());
                LlamaCppDownloader.download(release.getCudartAssetUrl(), cudartArchive, logger);
                extractArchive(cudartArchive, extractDir);
            }

            NPath server = findServer(extractDir);
            if (server == null) {
                if (logger != null) {
                    logger.accept(NMsg.ofC("Archive did not contain a llama-server executable.").asError());
                }
                return false;
            }

            NPath binDir = LlamaCppPaths.binDir(session);
            if (binDir.exists()) {
                binDir.deleteTree();
            }
            binDir.mkdirs();
            copyTreeFlattened(extractDir, binDir);

            NPath installedServer = LlamaCppPaths.serverExecutable(session);
            if (!NEnv.of().osFamily().isWindow()) {
                try {
                    NExec.ofSystem("chmod", "+x", installedServer.toString()).failFast(false).run();
                } catch (Exception ignored) {
                }
            }
            try {
                binDir.resolve("llamacpp-release.txt").writeString(release.getTag());
            } catch (Exception ignored) {
            }

            if (logger != null) {
                logger.accept(NMsg.ofC("llama.cpp %s installed to %s",
                        NMsg.ofStyledPrimary1(release.getTag()),
                        NMsg.ofStyledPale(installedServer.toString())));
            }
            return true;
        } catch (Exception e) {
            if (logger != null) {
                logger.accept(NMsg.ofC("llama.cpp installation failed: %s", e.getMessage()).asError());
            }
            return false;
        } finally {
            try {
                staging.deleteTree();
            } catch (Exception ignored) {
            }
        }
    }

    public static boolean uninstall(NaruSession session, boolean purgeModels, Consumer<NMsg> logger) {
        LlamaCppProcessManager.stopIfStartedByNaru();
        NPath home = LlamaCppPaths.home(session);
        NPath binDir = LlamaCppPaths.binDir(session);
        boolean removed = false;
        try {
            if (binDir.exists()) {
                binDir.deleteTree();
                removed = true;
                if (logger != null) {
                    logger.accept(NMsg.ofC("Removed %s", NMsg.ofStyledPale(binDir.toString())));
                }
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.accept(NMsg.ofC("Could not remove %s: %s", binDir, e.getMessage()));
            }
        }
        if (purgeModels) {
            NPath modelsDir = LlamaCppPaths.modelsDir(session);
            try {
                if (modelsDir.exists()) {
                    modelsDir.deleteTree();
                    removed = true;
                    if (logger != null) {
                        logger.accept(NMsg.ofC("Purged models directory %s", NMsg.ofStyledPale(modelsDir.toString())));
                    }
                }
            } catch (Exception e) {
                if (logger != null) {
                    logger.accept(NMsg.ofC("Could not purge %s: %s", modelsDir, e.getMessage()));
                }
            }
        }
        // remove the home when nothing is left in it
        try {
            if (home.exists()) {
                List<NPath> children = home.list();
                if (children == null || children.isEmpty()) {
                    home.delete();
                }
            }
        } catch (Exception ignored) {
        }
        return removed;
    }

    /**
     * Unpack one downloaded release asset into {@code target}.
     *
     * <p>The format must be selected explicitly: {@link NUncompress} defaults
     * to zip and does not sniff the source. The base runtime only ships zip
     * and gzip support, so the tar-family implementations come from the
     * {@code nuts-compression} extension (a compile dependency of this
     * module). Using the extension rather than a system {@code tar} command
     * keeps installation working on Windows too.
     */
    static void extractArchive(NPath archive, NPath target) {
        NUncompress.of()
                .packaging(packagingOf(archive))
                .source(archive)
                .target(target)
                .run();
    }

    static String packagingOf(NPath archive) {
        String name = archive.name().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            return "zip";
        }
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            return "tar.gz";
        }
        if (name.endsWith(".tar.xz") || name.endsWith(".txz")) {
            return "tar.xz";
        }
        if (name.endsWith(".tar")) {
            return "tar";
        }
        throw new RuntimeException(NMsg.ofC("unsupported archive format: %s", archive.name()).toString());
    }

    static NPath findServer(NPath root) {
        if (root == null || !root.exists()) {
            return null;
        }
        String name = NEnv.of().osFamily().isWindow() ? "llama-server.exe" : "llama-server";
        try {
            for (NPath p : root.walk().toList()) {
                if (p.isRegularFile() && name.equals(p.name())) {
                    return p;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static void copyTreeFlattened(NPath src, NPath dst) {
        for (NPath p : src.walk().toList()) {
            if (!p.isRegularFile()) {
                continue;
            }
            NPath target = dst.resolve(p.name());
            try (java.io.InputStream in = p.getInputStream();
                 java.io.OutputStream out = target.getOutputStream()) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
