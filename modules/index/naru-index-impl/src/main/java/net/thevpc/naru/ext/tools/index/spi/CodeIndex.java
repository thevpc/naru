package net.thevpc.naru.ext.tools.index.spi;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class CodeIndex {
    private Map<String, List<CodeSymbol>> byFile = new ConcurrentHashMap<>();
    private Map<String, List<CodeSymbol>> byName = new ConcurrentHashMap<>();
    private Map<SymbolKind, List<CodeSymbol>> byKind = new ConcurrentHashMap<>();
    private Map<String, Long> lastModified = new ConcurrentHashMap<>();

    public void addSymbols(String file, List<CodeSymbol> symbols) {
        byFile.put(file, new ArrayList<>(symbols));
        for (CodeSymbol symbol : symbols) {
            byName.computeIfAbsent(symbol.getName().toLowerCase(), k -> new ArrayList<>()).add(symbol);
            byKind.computeIfAbsent(symbol.getKind(), k -> new ArrayList<>()).add(symbol);
        }
    }

    public List<CodeSymbol> findByName(String name, SymbolKind filter, String matchMode) {
        List<CodeSymbol> results = new ArrayList<>();
        String lowerName = name.toLowerCase();
        
        for (Map.Entry<String, List<CodeSymbol>> entry : byName.entrySet()) {
            boolean matches = false;
            String key = entry.getKey();
            if ("exact".equalsIgnoreCase(matchMode)) {
                matches = key.equals(lowerName);
            } else if ("prefix".equalsIgnoreCase(matchMode)) {
                matches = key.startsWith(lowerName);
            } else {
                matches = key.contains(lowerName);
            }

            if (matches) {
                for (CodeSymbol sym : entry.getValue()) {
                    if (filter == null || sym.getKind() == filter) {
                        results.add(sym);
                    }
                }
            }
        }
        return results;
    }

    public List<CodeSymbol> findByFile(String file) {
        return byFile.getOrDefault(file, Collections.emptyList());
    }

    public List<CodeSymbol> allSymbols() {
        return byFile.values().stream().flatMap(List::stream).collect(Collectors.toList());
    }

    public void clear() {
        byFile.clear();
        byName.clear();
        byKind.clear();
        lastModified.clear();
    }

    public int fileCount() {
        return byFile.size();
    }

    public int symbolCount() {
        return byName.values().stream().mapToInt(List::size).sum();
    }

    public Long getLastModified(String file) {
        return lastModified.get(file);
    }
    
    public void setLastModified(String file, long time) {
        lastModified.put(file, time);
    }

    public void removeFile(String file) {
        List<CodeSymbol> symbols = byFile.remove(file);
        if (symbols != null) {
            for (CodeSymbol sym : symbols) {
                List<CodeSymbol> nameList = byName.get(sym.getName().toLowerCase());
                if (nameList != null) nameList.remove(sym);
                List<CodeSymbol> kindList = byKind.get(sym.getKind());
                if (kindList != null) kindList.remove(sym);
            }
        }
        lastModified.remove(file);
    }
}
