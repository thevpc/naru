package net.thevpc.naru.ext.tools.semantic.spi;

public class CodeChunk {
    private String file;
    private int startLine;
    private int endLine;
    private String content;
    private String symbolName;

    public CodeChunk() {}

    public CodeChunk(String file, int startLine, int endLine, String content, String symbolName) {
        this.file = file;
        this.startLine = startLine;
        this.endLine = endLine;
        this.content = content;
        this.symbolName = symbolName;
    }

    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }

    public int getStartLine() { return startLine; }
    public void setStartLine(int startLine) { this.startLine = startLine; }

    public int getEndLine() { return endLine; }
    public void setEndLine(int endLine) { this.endLine = endLine; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSymbolName() { return symbolName; }
    public void setSymbolName(String symbolName) { this.symbolName = symbolName; }
}
