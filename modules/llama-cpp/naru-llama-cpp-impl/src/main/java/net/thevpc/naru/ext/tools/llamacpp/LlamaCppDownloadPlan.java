package net.thevpc.naru.ext.tools.llamacpp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The set of files that make up one model to provision: a single GGUF, or every
 * shard of a split GGUF. {@link #getMainFileName()} is the file to hand to
 * {@code llama-server -m} (the first shard for a split model).
 */
public class LlamaCppDownloadPlan {

    public static class FileEntry {
        private final String fileName;
        private final String url;

        public FileEntry(String fileName, String url) {
            this.fileName = fileName;
            this.url = url;
        }

        public String getFileName() {
            return fileName;
        }

        public String getUrl() {
            return url;
        }
    }

    private final String mainFileName;
    private final List<FileEntry> files;

    public LlamaCppDownloadPlan(String mainFileName, List<FileEntry> files) {
        this.mainFileName = mainFileName;
        this.files = Collections.unmodifiableList(new ArrayList<>(files));
    }

    public String getMainFileName() {
        return mainFileName;
    }

    public List<FileEntry> getFiles() {
        return files;
    }
}
