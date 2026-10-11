package net.thevpc.naru.ext.tools.semantic.spi;

public class ChunkMatch {
    private CodeChunk chunk;
    private double score;

    public ChunkMatch(CodeChunk chunk, double score) {
        this.chunk = chunk;
        this.score = score;
    }

    public CodeChunk getChunk() { return chunk; }
    public double getScore() { return score; }
}
