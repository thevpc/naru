package net.thevpc.naru.ext.tools.index.spi;

public class CodeSymbol {
    private String file;
    private int line;
    private int endLine;
    private SymbolKind kind;
    private String name;
    private String signature;
    private String parent;
    private String packageName;

    public CodeSymbol() {}

    public CodeSymbol(String file, int line, int endLine, SymbolKind kind, String name, String signature, String parent, String packageName) {
        this.file = file;
        this.line = line;
        this.endLine = endLine;
        this.kind = kind;
        this.name = name;
        this.signature = signature;
        this.parent = parent;
        this.packageName = packageName;
    }

    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }

    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    public int getEndLine() { return endLine; }
    public void setEndLine(int endLine) { this.endLine = endLine; }

    public SymbolKind getKind() { return kind; }
    public void setKind(SymbolKind kind) { this.kind = kind; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public String getParent() { return parent; }
    public void setParent(String parent) { this.parent = parent; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    @Override
    public String toString() {
        return "CodeSymbol{" +
                "file='" + file + '\'' +
                ", line=" + line +
                ", endLine=" + endLine +
                ", kind=" + kind +
                ", name='" + name + '\'' +
                ", signature='" + signature + '\'' +
                ", parent='" + parent + '\'' +
                ", packageName='" + packageName + '\'' +
                '}';
    }
}
