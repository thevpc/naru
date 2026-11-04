package net.thevpc.naru.ext.tools.index.java;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import net.thevpc.naru.ext.tools.index.spi.CodeSymbol;
import net.thevpc.naru.ext.tools.index.spi.LanguageIndexer;
import net.thevpc.naru.ext.tools.index.spi.SymbolKind;

import java.nio.file.Path;
import java.util.*;

public class JavaLanguageIndexer implements LanguageIndexer {

    @Override
    public String language() {
        return "java";
    }

    @Override
    public Set<String> extensions() {
        return Set.of(".java");
    }

    @Override
    public List<CodeSymbol> index(Path file) {
        List<CodeSymbol> symbols = new ArrayList<>();
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            String filePath = file.toString();
            String packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");

            if (!packageName.isEmpty()) {
                symbols.add(new CodeSymbol(
                        filePath,
                        cu.getPackageDeclaration().flatMap(p -> p.getBegin()).map(b -> b.line).orElse(1),
                        cu.getPackageDeclaration().flatMap(p -> p.getEnd()).map(e -> e.line).orElse(1),
                        SymbolKind.PACKAGE,
                        packageName,
                        "package " + packageName,
                        null,
                        packageName
                ));
            }

            cu.accept(new SymbolVisitor(filePath, packageName), symbols);
        } catch (Throwable t) {
            // Ignore parse failures
        }
        return symbols;
    }

    private static class SymbolVisitor extends VoidVisitorAdapter<List<CodeSymbol>> {
        private final String filePath;
        private final String packageName;
        private final Deque<String> typeStack = new ArrayDeque<>();

        public SymbolVisitor(String filePath, String packageName) {
            this.filePath = filePath;
            this.packageName = packageName;
        }

        private String currentParent() {
            return typeStack.isEmpty() ? null : typeStack.peek();
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration n, List<CodeSymbol> arg) {
            String name = n.getNameAsString();
            SymbolKind kind = n.isInterface() ? SymbolKind.INTERFACE : SymbolKind.CLASS;
            int start = n.getBegin().map(b -> b.line).orElse(1);
            int end = n.getEnd().map(e -> e.line).orElse(-1);

            String sig = (n.isPublic() ? "public " : "") + (n.isInterface() ? "interface " : "class ") + name;
            arg.add(new CodeSymbol(filePath, start, end, kind, name, sig, currentParent(), packageName));

            typeStack.push(name);
            super.visit(n, arg);
            typeStack.pop();
        }

        @Override
        public void visit(EnumDeclaration n, List<CodeSymbol> arg) {
            String name = n.getNameAsString();
            int start = n.getBegin().map(b -> b.line).orElse(1);
            int end = n.getEnd().map(e -> e.line).orElse(-1);

            arg.add(new CodeSymbol(filePath, start, end, SymbolKind.ENUM, name, "enum " + name, currentParent(), packageName));

            typeStack.push(name);
            super.visit(n, arg);
            typeStack.pop();
        }

        @Override
        public void visit(RecordDeclaration n, List<CodeSymbol> arg) {
            String name = n.getNameAsString();
            int start = n.getBegin().map(b -> b.line).orElse(1);
            int end = n.getEnd().map(e -> e.line).orElse(-1);

            arg.add(new CodeSymbol(filePath, start, end, SymbolKind.RECORD, name, "record " + name, currentParent(), packageName));

            typeStack.push(name);
            super.visit(n, arg);
            typeStack.pop();
        }

        @Override
        public void visit(AnnotationDeclaration n, List<CodeSymbol> arg) {
            String name = n.getNameAsString();
            int start = n.getBegin().map(b -> b.line).orElse(1);
            int end = n.getEnd().map(e -> e.line).orElse(-1);

            arg.add(new CodeSymbol(filePath, start, end, SymbolKind.ANNOTATION, name, "@interface " + name, currentParent(), packageName));

            typeStack.push(name);
            super.visit(n, arg);
            typeStack.pop();
        }

        @Override
        public void visit(MethodDeclaration n, List<CodeSymbol> arg) {
            String name = n.getNameAsString();
            int start = n.getBegin().map(b -> b.line).orElse(1);
            int end = n.getEnd().map(e -> e.line).orElse(-1);

            StringBuilder sig = new StringBuilder();
            if (n.isPublic()) sig.append("public ");
            else if (n.isProtected()) sig.append("protected ");
            else if (n.isPrivate()) sig.append("private ");
            if (n.isStatic()) sig.append("static ");
            if (n.isAbstract()) sig.append("abstract ");
            sig.append(n.getTypeAsString()).append(" ").append(name).append("(");
            
            for (int i = 0; i < n.getParameters().size(); i++) {
                if (i > 0) sig.append(", ");
                Parameter p = n.getParameters().get(i);
                sig.append(p.getTypeAsString()).append(" ").append(p.getNameAsString());
            }
            sig.append(")");

            arg.add(new CodeSymbol(filePath, start, end, SymbolKind.METHOD, name, sig.toString(), currentParent(), packageName));
            super.visit(n, arg);
        }

        @Override
        public void visit(ConstructorDeclaration n, List<CodeSymbol> arg) {
            String name = n.getNameAsString();
            int start = n.getBegin().map(b -> b.line).orElse(1);
            int end = n.getEnd().map(e -> e.line).orElse(-1);

            StringBuilder sig = new StringBuilder();
            if (n.isPublic()) sig.append("public ");
            else if (n.isProtected()) sig.append("protected ");
            else if (n.isPrivate()) sig.append("private ");
            sig.append(name).append("(");

            for (int i = 0; i < n.getParameters().size(); i++) {
                if (i > 0) sig.append(", ");
                Parameter p = n.getParameters().get(i);
                sig.append(p.getTypeAsString()).append(" ").append(p.getNameAsString());
            }
            sig.append(")");

            arg.add(new CodeSymbol(filePath, start, end, SymbolKind.CONSTRUCTOR, name, sig.toString(), currentParent(), packageName));
            super.visit(n, arg);
        }

        @Override
        public void visit(FieldDeclaration n, List<CodeSymbol> arg) {
            int start = n.getBegin().map(b -> b.line).orElse(1);
            int end = n.getEnd().map(e -> e.line).orElse(-1);
            boolean isConstant = n.isStatic() && n.isFinal();
            SymbolKind kind = isConstant ? SymbolKind.CONSTANT : SymbolKind.FIELD;

            for (VariableDeclarator var : n.getVariables()) {
                String name = var.getNameAsString();
                String sig = n.getElementType().asString() + " " + name;
                arg.add(new CodeSymbol(filePath, start, end, kind, name, sig, currentParent(), packageName));
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(EnumConstantDeclaration n, List<CodeSymbol> arg) {
            String name = n.getNameAsString();
            int start = n.getBegin().map(b -> b.line).orElse(1);
            int end = n.getEnd().map(e -> e.line).orElse(-1);

            arg.add(new CodeSymbol(filePath, start, end, SymbolKind.CONSTANT, name, name, currentParent(), packageName));
            super.visit(n, arg);
        }
    }
}
