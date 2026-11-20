package net.thevpc.naru.ext.tools.ollama;

import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

public class OllamaInstallationInfo {
    private final boolean installed;
    private final String executablePath;
    private final String version;
    private final String osFamily;
    private final String archFamily;
    private final String osDist;

    public OllamaInstallationInfo(boolean installed, String executablePath, String version, String osFamily, String archFamily, String osDist) {
        this.installed = installed;
        this.executablePath = executablePath;
        this.version = version;
        this.osFamily = osFamily;
        this.archFamily = archFamily;
        this.osDist = osDist;
    }

    public boolean isInstalled() {
        return installed;
    }

    public String getExecutablePath() {
        return executablePath;
    }

    public String getVersion() {
        return version;
    }

    public String getOsFamily() {
        return osFamily;
    }

    public String getArchFamily() {
        return archFamily;
    }

    public String getOsDist() {
        return osDist;
    }

    public NMsg toMsg() {
        if (installed) {
            String ver = !NBlankable.isBlank(version) ? version : "unknown version";
            String path = !NBlankable.isBlank(executablePath) ? executablePath : "PATH";
            return NMsg.ofC("Installed (%s at %s)", NMsg.ofStyledPrimary1(ver), NMsg.ofStyledPale(path));
        } else {
            return NMsg.ofC("Not installed (OS: %s, Arch: %s)", NMsg.ofStyledPrimary1(osFamily), NMsg.ofStyledPrimary2(archFamily));
        }
    }

    @Override
    public String toString() {
        return toMsg().toString();
    }
}
