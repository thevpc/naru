package net.thevpc.naru.ext.tools.ollama;

import net.thevpc.naru.api.model.NaruModelPsResult;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.text.NMsg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OllamaStatus {
    private final boolean running;
    private final String url;
    private final String serverVersion;
    private final boolean startedByNaru;
    private final long pid;
    private final long responseTimeMs;
    private final List<String> availableModels;
    private final List<NaruModelPsResult> loadedModels;
    private final OllamaInstallationInfo installation;

    public OllamaStatus(boolean running, String url, String serverVersion, boolean startedByNaru, long pid,
                        long responseTimeMs, List<String> availableModels, List<NaruModelPsResult> loadedModels,
                        OllamaInstallationInfo installation) {
        this.running = running;
        this.url = url;
        this.serverVersion = serverVersion;
        this.startedByNaru = startedByNaru;
        this.pid = pid;
        this.responseTimeMs = responseTimeMs;
        this.availableModels = availableModels == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(availableModels));
        this.loadedModels = loadedModels == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(loadedModels));
        this.installation = installation;
    }

    public boolean isRunning() {
        return running;
    }

    public String getUrl() {
        return url;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public boolean isStartedByNaru() {
        return startedByNaru;
    }

    public long getPid() {
        return pid;
    }

    public long getResponseTimeMs() {
        return responseTimeMs;
    }

    public List<String> getAvailableModels() {
        return availableModels;
    }

    public List<NaruModelPsResult> getLoadedModels() {
        return loadedModels;
    }

    public OllamaInstallationInfo getInstallation() {
        return installation;
    }

    public NElement toElement() {
        NObjectElementBuilder builder = NObjectElementBuilder.of();
        builder.set("running", running);
        builder.set("url", url);
        if (serverVersion != null) {
            builder.set("serverVersion", serverVersion);
        }
        builder.set("startedByNaru", startedByNaru);
        if (pid > 0) {
            builder.set("pid", pid);
        }
        if (responseTimeMs >= 0) {
            builder.set("responseTimeMs", responseTimeMs);
        }
        builder.set("installed", installation != null && installation.isInstalled());
        if (installation != null && installation.getExecutablePath() != null) {
            builder.set("executablePath", installation.getExecutablePath());
        }
        if (installation != null && installation.getVersion() != null) {
            builder.set("clientVersion", installation.getVersion());
        }
        if (installation != null) {
            builder.set("osFamily", installation.getOsFamily());
            builder.set("archFamily", installation.getArchFamily());
            builder.set("osDist", installation.getOsDist());
        }
        NArrayElementBuilder modelsArr = NArrayElementBuilder.of();
        for (String m : availableModels) {
            modelsArr.add(m);
        }
        builder.set("availableModels", modelsArr.build());

        NArrayElementBuilder psArr = NArrayElementBuilder.of();
        for (NaruModelPsResult ps : loadedModels) {
            NObjectElementBuilder psItem = NObjectElementBuilder.of();
            psItem.set("name", ps.getModel().model());
            psItem.set("size", ps.getSize());
            psItem.set("sizeVram", ps.getSizeVram());
            if (ps.getExpiresAt() != null) {
                psItem.set("expiresAt", ps.getExpiresAt().toString());
            }
            psArr.add(psItem.build());
        }
        builder.set("loadedModels", psArr.build());

        return builder.build();
    }
}
