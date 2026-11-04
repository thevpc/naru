package net.thevpc.naru.ext.tools.ollama;

import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.platform.NArchFamily;
import net.thevpc.nuts.platform.NEnv;
import net.thevpc.nuts.platform.NOsFamily;
import net.thevpc.nuts.text.NMsg;

import java.io.File;
import java.util.function.Consumer;

/**
 * Cross-platform installer and uninstaller for Ollama based on NEnv platform detection.
 */
public class OllamaInstaller {

    public static boolean install(Consumer<NMsg> logger) {
        NEnv env = NEnv.of();
        NOsFamily os = env.osFamily();
        NArchFamily arch = env.archFamily();

        if (logger != null) {
            logger.accept(NMsg.ofC("Detecting platform: OS=%s, Arch=%s, Dist=%s",
                    NMsg.ofStyledPrimary1(os.id()),
                    NMsg.ofStyledPrimary2(arch.id()),
                    NMsg.ofStyledPrimary3(env.osDist() != null ? env.osDist().toString() : "unknown")
            ));
        }

        if (os.isWindow()) {
            return installWindows(logger);
        } else if (os.isMacOs()) {
            return installMac(logger);
        } else {
            // Linux / Unix
            return installLinux(arch, logger);
        }
    }

    private static boolean installLinux(NArchFamily arch, Consumer<NMsg> logger) {
        String archStr = arch.isArm() ? "arm64" : "amd64";
        if (logger != null) {
            logger.accept(NMsg.ofC("Installing Ollama on Linux (%s)...", archStr));
        }

        // Method 1: Try official install script
        try {
            if (logger != null) {
                logger.accept(NMsg.ofC("Running official installation script: curl -fsSL https://ollama.com/install.sh | sh ..."));
            }
            NExec e = NExec.ofSystem("sh", "-c", "curl -fsSL https://ollama.com/install.sh | sh")
                    .failFast(false);
            String out = e.grabbedAll();
            if (e.exitCode() == 0) {
                if (logger != null) {
                    logger.accept(NMsg.ofC("Official installation script completed successfully.\n%s", out));
                }
                return true;
            } else {
                if (logger != null) {
                    logger.accept(NMsg.ofC("Script returned code %s. Attempting fallback standalone download...\n%s", e.exitCode(), out));
                }
            }
        } catch (Exception ex) {
            if (logger != null) {
                logger.accept(NMsg.ofC("Install script execution failed: %s. Attempting fallback...", ex.getMessage()));
            }
        }

        // Method 2: Download standalone binary bundle
        try {
            String downloadUrl = "https://ollama.com/download/ollama-linux-" + archStr + ".tgz";
            NPath tempTar = NPath.ofUserHome().resolve(".naru/tmp/ollama-linux-" + archStr + ".tgz");
            tempTar.parent().mkdirs();

            if (logger != null) {
                logger.accept(NMsg.ofC("Downloading %s ...", NMsg.ofStyledPrimary1(downloadUrl)));
            }

            NPath.of(downloadUrl).copyTo(tempTar);

            NPath targetBinDir = NPath.ofUserHome().resolve(".local/bin");
            targetBinDir.mkdirs();

            if (logger != null) {
                logger.accept(NMsg.ofC("Extracting binary to %s ...", targetBinDir));
            }

            NExec extract = NExec.ofSystem("tar", "-xzf", tempTar.toString(), "-C", targetBinDir.parent().toString())
                    .failFast(false);
            extract.run();

            // Check if ollama binary exists
            NPath localBin = targetBinDir.resolve("ollama");
            if (localBin.exists()) {
                NExec.ofSystem("chmod", "+x", localBin.toString()).failFast(false).run();
                if (logger != null) {
                    logger.accept(NMsg.ofC("Ollama binary installed to %s", NMsg.ofStyledPrimary1(localBin.toString())));
                }
                return true;
            }

            // Also check extracted bin/ollama inside target parent
            NPath altBin = targetBinDir.parent().resolve("bin/ollama");
            if (altBin.exists()) {
                NExec.ofSystem("chmod", "+x", altBin.toString()).failFast(false).run();
                if (logger != null) {
                    logger.accept(NMsg.ofC("Ollama binary installed to %s", NMsg.ofStyledPrimary1(altBin.toString())));
                }
                return true;
            }

            return false;
        } catch (Exception ex) {
            if (logger != null) {
                logger.accept(NMsg.ofC("Fallback installation failed: %s", ex.getMessage()));
            }
            return false;
        }
    }

    private static boolean installMac(Consumer<NMsg> logger) {
        if (logger != null) {
            logger.accept(NMsg.ofC("Installing Ollama on macOS..."));
        }

        // Method 1: Check for Homebrew
        try {
            NExec checkBrew = NExec.ofSystem("which", "brew").failFast(false);
            if (checkBrew.run().exitCode() == 0) {
                if (logger != null) {
                    logger.accept(NMsg.ofC("Homebrew detected. Running: brew install ollama ..."));
                }
                NExec brewInstall = NExec.ofSystem("brew", "install", "ollama").failFast(false);
                String out = brewInstall.grabbedAll();
                if (brewInstall.exitCode() == 0) {
                    if (logger != null) {
                        logger.accept(NMsg.ofC("Homebrew installation completed.\n%s", out));
                    }
                    return true;
                }
            }
        } catch (Exception ignored) {
        }

        // Method 2: Download Ollama-darwin.zip
        try {
            String downloadUrl = "https://ollama.com/download/Ollama-darwin.zip";
            NPath tempZip = NPath.ofUserHome().resolve(".naru/tmp/Ollama-darwin.zip");
            tempZip.parent().mkdirs();

            if (logger != null) {
                logger.accept(NMsg.ofC("Downloading %s ...", NMsg.ofStyledPrimary1(downloadUrl)));
            }

            NPath.of(downloadUrl).copyTo(tempZip);

            File sysAppDir = new File("/Applications");
            NPath appDir = sysAppDir.canWrite() ? NPath.of("/Applications") : NPath.ofUserHome().resolve("Applications");
            appDir.mkdirs();

            if (logger != null) {
                logger.accept(NMsg.ofC("Unzipping to %s ...", appDir));
            }

            NExec unzip = NExec.ofSystem("unzip", "-q", "-o", tempZip.toString(), "-d", appDir.toString())
                    .failFast(false);
            unzip.run();

            if (logger != null) {
                logger.accept(NMsg.ofC("Ollama installed to %s/Ollama.app", appDir));
            }
            return true;
        } catch (Exception ex) {
            if (logger != null) {
                logger.accept(NMsg.ofC("macOS installation failed: %s", ex.getMessage()));
            }
            return false;
        }
    }

    private static boolean installWindows(Consumer<NMsg> logger) {
        if (logger != null) {
            logger.accept(NMsg.ofC("Installing Ollama on Windows..."));
        }

        // Method 1: Check winget
        try {
            NExec winget = NExec.ofSystem("winget", "install", "Ollama.Ollama", "--accept-source-agreements", "--accept-package-agreements")
                    .failFast(false);
            String out = winget.grabbedAll();
            if (winget.exitCode() == 0) {
                if (logger != null) {
                    logger.accept(NMsg.ofC("Winget installation succeeded.\n%s", out));
                }
                return true;
            }
        } catch (Exception ignored) {
        }

        // Method 2: Download OllamaSetup.exe
        try {
            String downloadUrl = "https://ollama.com/download/OllamaSetup.exe";
            NPath tempExe = NPath.ofUserHome().resolve(".naru/tmp/OllamaSetup.exe");
            tempExe.parent().mkdirs();

            if (logger != null) {
                logger.accept(NMsg.ofC("Downloading %s ...", NMsg.ofStyledPrimary1(downloadUrl)));
            }

            NPath.of(downloadUrl).copyTo(tempExe);

            if (logger != null) {
                logger.accept(NMsg.ofC("Executing Ollama installer silently..."));
            }

            NExec setup = NExec.ofSystem(tempExe.toString(), "/silent")
                    .failFast(false);
            setup.run();

            if (logger != null) {
                logger.accept(NMsg.ofC("Ollama installer completed."));
            }
            return true;
        } catch (Exception ex) {
            if (logger != null) {
                logger.accept(NMsg.ofC("Windows installation failed: %s", ex.getMessage()));
            }
            return false;
        }
    }

    public static boolean uninstall(boolean purgeModels, Consumer<NMsg> logger) {
        NOsFamily os = NEnv.of().osFamily();

        if (logger != null) {
            logger.accept(NMsg.ofC("Stopping any running Ollama process..."));
        }
        OllamaProcessManager.stopExternalProcess();

        boolean success = false;

        if (os.isWindow()) {
            success = uninstallWindows(logger);
        } else if (os.isMacOs()) {
            success = uninstallMac(logger);
        } else {
            success = uninstallLinux(logger);
        }

        if (purgeModels) {
            try {
                NPath modelsDir = NPath.ofUserHome().resolve(".ollama");
                if (modelsDir.exists()) {
                    if (logger != null) {
                        logger.accept(NMsg.ofC("Purging models directory: %s", modelsDir));
                    }
                    modelsDir.deleteTree();
                }
            } catch (Exception ex) {
                if (logger != null) {
                    logger.accept(NMsg.ofC("Failed to purge models directory: %s", ex.getMessage()));
                }
            }
        }

        return success;
    }

    private static boolean uninstallLinux(Consumer<NMsg> logger) {
        if (logger != null) {
            logger.accept(NMsg.ofC("Uninstalling Ollama on Linux..."));
        }
        try {
            NExec.ofSystem("systemctl", "disable", "--now", "ollama").failFast(false).run();
        } catch (Exception ignored) {
        }

        NPath[] possibleBins = new NPath[]{
                NPath.of("/usr/local/bin/ollama"),
                NPath.of("/usr/bin/ollama"),
                NPath.ofUserHome().resolve(".local/bin/ollama"),
                NPath.ofUserHome().resolve(".ollama/bin/ollama")
        };

        boolean removed = false;
        for (NPath p : possibleBins) {
            try {
                if (p.exists()) {
                    p.delete();
                    removed = true;
                    if (logger != null) {
                        logger.accept(NMsg.ofC("Removed binary: %s", p));
                    }
                }
            } catch (Exception ex) {
                if (logger != null) {
                    logger.accept(NMsg.ofC("Could not delete %s: %s", p, ex.getMessage()));
                }
            }
        }

        return removed;
    }

    private static boolean uninstallMac(Consumer<NMsg> logger) {
        if (logger != null) {
            logger.accept(NMsg.ofC("Uninstalling Ollama on macOS..."));
        }
        try {
            NExec.ofSystem("brew", "uninstall", "ollama").failFast(false).run();
        } catch (Exception ignored) {
        }

        NPath[] paths = new NPath[]{
                NPath.of("/Applications/Ollama.app"),
                NPath.ofUserHome().resolve("Applications/Ollama.app"),
                NPath.of("/usr/local/bin/ollama"),
                NPath.ofUserHome().resolve(".local/bin/ollama")
        };

        boolean removed = false;
        for (NPath p : paths) {
            try {
                if (p.exists()) {
                    if (p.isDirectory()) {
                        p.deleteTree();
                    } else {
                        p.delete();
                    }
                    removed = true;
                    if (logger != null) {
                        logger.accept(NMsg.ofC("Removed: %s", p));
                    }
                }
            } catch (Exception ex) {
                if (logger != null) {
                    logger.accept(NMsg.ofC("Could not remove %s: %s", p, ex.getMessage()));
                }
            }
        }
        return removed;
    }

    private static boolean uninstallWindows(Consumer<NMsg> logger) {
        if (logger != null) {
            logger.accept(NMsg.ofC("Uninstalling Ollama on Windows..."));
        }
        try {
            NExec.ofSystem("winget", "uninstall", "Ollama.Ollama").failFast(false).run();
            return true;
        } catch (Exception ignored) {
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            NPath uninstaller = NPath.of(localAppData).resolve("Programs/Ollama/Uninstall.exe");
            if (uninstaller.exists()) {
                NExec.ofSystem(uninstaller.toString(), "/silent").failFast(false).run();
                return true;
            }
        }
        return false;
    }
}
