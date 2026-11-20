package net.thevpc.naru.ext.tools.semantic.embed;

import net.thevpc.naru.ext.tools.semantic.spi.EmbeddingProvider;

import java.util.*;

public class TfIdfEmbeddingProvider implements EmbeddingProvider {

    private static final int VECTOR_SIZE = 256;

    @Override
    public String name() {
        return "tfidf";
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[VECTOR_SIZE];
        if (text == null || text.isBlank()) {
            return vector;
        }

        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return vector;
        }

        Map<String, Integer> termFreq = new HashMap<>();
        for (String token : tokens) {
            termFreq.put(token, termFreq.getOrDefault(token, 0) + 1);
        }

        for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
            String term = entry.getKey();
            int count = entry.getValue();
            int bucket = Math.abs(term.hashCode()) % VECTOR_SIZE;
            double tf = 1.0 + Math.log(count);
            vector[bucket] += (float) tf;
        }

        // L2 normalize
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < VECTOR_SIZE; i++) {
                vector[i] /= norm;
            }
        }

        return vector;
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        // Split camelCase, snake_case, punctuation
        String[] words = text.split("[^a-zA-Z0-9]+");
        for (String word : words) {
            if (word.isEmpty()) continue;
            // Split camelCase
            String[] camelParts = word.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
            for (String part : camelParts) {
                if (!part.isEmpty()) {
                    tokens.add(part.toLowerCase());
                }
            }
        }
        return tokens;
    }
}
