package net.thevpc.naru.ext.tools.llamacpp;

import net.thevpc.nuts.io.NPath;

/**
 * A locally available GGUF model.
 */
public class LlamaCppModelInfo {
    private final String id;
    private final String fileName;
    private final NPath path;
    private final long sizeBytes;
    private final boolean sharded;

    public LlamaCppModelInfo(String id, String fileName, NPath path, long sizeBytes, boolean sharded) {
        this.id = id;
        this.fileName = fileName;
        this.path = path;
        this.sizeBytes = sizeBytes;
        this.sharded = sharded;
    }

    public String getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public NPath getPath() {
        return path;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public boolean isSharded() {
        return sharded;
    }

    @Override
    public String toString() {
        return id + (sharded ? " (sharded)" : "");
    }
}
