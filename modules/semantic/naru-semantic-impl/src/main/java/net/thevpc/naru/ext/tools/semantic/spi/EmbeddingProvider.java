package net.thevpc.naru.ext.tools.semantic.spi;

public interface EmbeddingProvider {
    String name();
    float[] embed(String text);
}
