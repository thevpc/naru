package net.thevpc.naru.ext.tools.llamacpp;

/**
 * A resolved llama.cpp GitHub release: the nightly tag plus the concrete assets
 * to download for the current platform. {@code cudart} is populated only for a
 * {@code cuda} backend, whose binaries need the companion CUDA runtime archive.
 */
public class LlamaCppRelease {
    private final String tag;
    private final String assetName;
    private final String assetUrl;
    private final String cudartAssetName;
    private final String cudartAssetUrl;

    public LlamaCppRelease(String tag, String assetName, String assetUrl, String cudartAssetName, String cudartAssetUrl) {
        this.tag = tag;
        this.assetName = assetName;
        this.assetUrl = assetUrl;
        this.cudartAssetName = cudartAssetName;
        this.cudartAssetUrl = cudartAssetUrl;
    }

    public String getTag() {
        return tag;
    }

    public String getAssetName() {
        return assetName;
    }

    public String getAssetUrl() {
        return assetUrl;
    }

    public String getCudartAssetName() {
        return cudartAssetName;
    }

    public String getCudartAssetUrl() {
        return cudartAssetUrl;
    }

    @Override
    public String toString() {
        return "LlamaCppRelease{" + tag + " -> " + assetName + (cudartAssetName != null ? " + " + cudartAssetName : "") + "}";
    }
}
