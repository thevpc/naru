package net.thevpc.naru.ext.tools.semantic.store;

import net.thevpc.naru.ext.tools.semantic.spi.ChunkMatch;
import net.thevpc.naru.ext.tools.semantic.spi.CodeChunk;

import java.util.*;
import java.util.stream.Collectors;

public class InMemoryVectorStore {

    private static class Entry {
        CodeChunk chunk;
        float[] vector;

        Entry(CodeChunk chunk, float[] vector) {
            this.chunk = chunk;
            this.vector = vector;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public synchronized void add(CodeChunk chunk, float[] vector) {
        entries.add(new Entry(chunk, vector));
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized List<ChunkMatch> search(float[] queryVector, int topK) {
        if (entries.isEmpty() || queryVector == null) {
            return Collections.emptyList();
        }

        List<ChunkMatch> matches = new ArrayList<>();
        for (Entry e : entries) {
            double sim = cosineSimilarity(queryVector, e.vector);
            if (sim > 0) {
                matches.add(new ChunkMatch(e.chunk, sim));
            }
        }

        matches.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        if (matches.size() > topK) {
            return matches.subList(0, topK);
        }
        return matches;
    }

    private double cosineSimilarity(float[] v1, float[] v2) {
        int len = Math.min(v1.length, v2.length);
        double dot = 0;
        double norm1 = 0;
        double norm2 = 0;

        for (int i = 0; i < len; i++) {
            dot += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }

        if (norm1 == 0 || norm2 == 0) return 0;
        return dot / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
