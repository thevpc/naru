package net.thevpc.naru.impl.routine;

import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.routine.NaruIndexedLine;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.routine.SubroutineDef;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NStringUtils;

import java.time.Instant;
import java.util.*;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NaruRoutineMem implements NaruRoutine {
    private String uuid;
    private String name;
    private NAruVisibility visibility;
    private Instant creationInstant;
    private Instant modificationInstant;
    private TreeMap<Integer, String> lines = new TreeMap<>();

    public NaruRoutineMem(String uuid, String name, NAruVisibility visibility) {
        this.uuid = uuid;
        this.name = name;
        this.visibility = visibility;
        this.creationInstant = Instant.now();
        this.modificationInstant = creationInstant;
    }

    public NaruRoutineMem(NElement element) {
        load(element);
    }

    private void load(NElement element) {
        if (element.isListContainer()) {
            NListContainerElement c = element.asListContainer().get();
            name = c.getStringValue("name").orNull();
            uuid = c.getStringValue("uuid").orNull();
            visibility = NAruVisibility.parse(c.getStringValue("visibility").orNull()).orElse(NAruVisibility.PRIVATE);
            if (visibility == NAruVisibility.MIXED) {
                visibility = NAruVisibility.PRIVATE;
            }
            creationInstant = c.getInstantValue("creationInstant").orNull();
            modificationInstant = c.getInstantValue("modificationInstant").orNull();
            if (creationInstant == null) {
                creationInstant = Instant.now();
            }
            if (modificationInstant == null) {
                modificationInstant = Instant.now();
            }
            NObjectElement lines1 = c.getObject("lines").orNull();
            this.lines.clear();
            if (lines1 != null) {
                for (NElement p : lines1) {
                    if (p.isPair()) {
                        NPairElement pp = p.asPair().get();
                        Integer n = pp.key().asIntValue().orNull();
                        String s = pp.key().asStringValue().orNull();
                        if (n != null && s != null) {
                            lines.put(n, s);
                        }
                    }
                }
            }
        } else {
            throw new NIllegalArgumentException(NMsg.ofC("invalid routine", element));
        }
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder b = NElement.ofObjectBuilder();
        b.add("uuid", name);
        b.add("name", name);
        b.add("visibility", visibility == null ? "private" : visibility.name().toLowerCase());
        b.add("creationInstant", NElement.ofInstant(creationInstant == null ? Instant.now() : creationInstant));
        b.add("modificationInstant", NElement.ofInstant(modificationInstant == null ? Instant.now() : modificationInstant));
        b.add("lines", NElements.of().toElement(lines));
        return b.build();
    }



    public NAruVisibility visibility() {
        return visibility;
    }

    @Override
    public Instant creationInstant() {
        return creationInstant;
    }

    @Override
    public Instant modificationInstant() {
        return modificationInstant;
    }

    public NaruRoutineMem visibility(NAruVisibility visibility) {
        this.visibility = visibility;
        return this;
    }


    public String uuid() {
        return uuid;
    }

    public NaruRoutineMem setUuid(String uuid) {
        this.uuid = uuid;
        return this;
    }

    public NaruRoutineMem setName(String name) {
        this.name = name;
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void putLine(int lineNumber, String text) {
        lines.put(lineNumber, text);
    }

    @Override
    public void appendLine(String text) {
        appendLine(10, text);
    }

    @Override
    public void appendLine(int increment, String text) {
        if (increment <= 0) {
            increment = 10;
        }
        Integer lineNumber = lines.lastKey();
        if (lineNumber == null) {
            lineNumber = 0;
        }
        putLine(lineNumber + increment, text);
    }

    @Override
    public boolean removeLine(int lineNumber) {
        String old = lines.remove(lineNumber);
        return old != null;
    }

    @Override
    public int clear() {
        int old = lines.size();
        lines.clear();
        return old;
    }

    @Override
    public TreeMap<Integer, String> getLinesSet() {
        return lines;
    }

    @Override
    public List<NaruIndexedLine> getIndexedLines() {
        ArrayList<NaruIndexedLine> all = new ArrayList<>();
        for (Map.Entry<Integer, String> e : lines.entrySet()) {
            all.add(new NaruIndexedLine(e.getKey(), e.getValue()));
        }
        return all;
    }

    @Override
    public int firstIndex() {
        for (Integer i : lines.keySet()) {
            return i;
        }
        return -1;
    }

    @Override
    public int nextPc(int currentPc) {
        List<NaruIndexedLine> lines = getIndexedLines();
        int currentIt = -1;

        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).index() == currentPc) {
                currentIt = i;
                break;
            }
        }
        if (currentIt >= 0 && currentIt + 1 < lines.size()) {
            return lines.get(currentIt + 1).index();
        }
        return -1;
    }

    @Override
    public TreeMap<Integer, String> getLinesSet(IntPredicate lineFilter) {
        TreeMap<Integer, String> newOne = new TreeMap<>();
        for (Map.Entry<Integer, String> e : lines.entrySet()) {
            Integer k = e.getKey();
            if (lineFilter == null || lineFilter.test(k)) {
                newOne.put(k, e.getValue());
            }
        }
        return newOne;
    }

    @Override
    public String getFormattedText() {
        if (lines.isEmpty()) {
            return "<empty>";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> entry : lines.entrySet()) {
            sb.append(entry.getKey()).append(" ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    @Override
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    @Override
    public String lineCommandAt(int n) {
        return lines.get(n);
    }

    @Override
    public NOptional<List<NaruStatement>> parseStatements(NaruTask task) {
        List<NaruStatement> curr = new ArrayList<>();
        for (NaruIndexedLine aa : getIndexedLines()) {
            NOptional<NaruStatement> o = task.parseStatement(aa.command());
            if (o.isError()) {
                return NOptional.ofNamedError(NMsg.ofC("Error statement: routine line invalid %s", aa.command()));
            }
            if (o.isPresent()) {
                curr.add(o.get());
            }
        }
        return NOptional.of(curr);
    }

//    @Override
//    public Map<String, SubroutineDef> getSubroutines() {
//        Map<String, SubroutineDef> subs = new HashMap<>();
//        NavigableMap<Integer, String> lines = getLinesSet();
//
//        Integer subStart = null;
//        String subName = null;
//        List<String> subParams = null;
//
//        for (Map.Entry<Integer, String> entry : lines.entrySet()) {
//            String raw = entry.getValue().trim();
//            int lineNum = entry.getKey();
//
//            if (raw.startsWith("/sub ")) {
//                // Parse: /sub name param1 param2
//                String[] parts = raw.substring(5).trim().split("\\s+");
//                subName = parts[0];
//                subParams = Arrays.asList(Arrays.copyOfRange(parts, 1, parts.length));
//                subStart = lineNum;
//            } else if (raw.equals("/endsub") && subStart != null) {
//                subs.put(subName, new SubroutineDefImpl(subStart, lineNum, subParams));
//                subStart = null;
//                subName = null;
//                subParams = null;
//            }
//        }
//        return subs;
//    }

//    public NaruRoutine load(NPath path) {
//        lines.clear();
//        fill(path, true, true);
//        return this;
//    }

//    public NaruRoutine write(NPath path) {
//        _write(path.mkParentDirs());
////        if (NBlankable.isBlank(uuid())) {
////            setUuid(UUID.randomUUID().toString());
////        }
////        if (preferredPath != null) {
////            _write(preferredPath.mkParentDirs());
////        } else {
////            String pathName = NStringUtils.firstNonBlankTrimmed(name(),"noname") + ".naru";
////            NPath pub = publicDir.resolve(pathName);
////            NPath priv = privateDir.resolve(pathName);
////            if (visibility() == NAruVisibility.PUBLIC) {
////                if (priv.isRegularFile()) {
////                    priv.delete();
////                }
////            } else {
////                if (pub.isRegularFile()) {
////                    pub.delete();
////                }
////                _write(priv.mkParentDirs());
////            }
////        }
//        return this;
//    }

//    private String effectiveName() {
//        if (preferredPath != null) {
//            String n = preferredPath.name();
//            if (n.endsWith(".naru")) {
//                n = n.substring(0, n.length() - 5);
//            }
//            return n;
//        }
//        return name();
//    }

//    private void _write(NPath pub) {
//        StringBuilder sb = new StringBuilder();
//        sb.append("------- ").append("\n");
//        sb.append("uuid : ").append(NStringUtils.firstNonBlankTrimmed(uuid(), "NO_UUID")).append("\n");
//        sb.append("creationInstant : ").append(creationInstant == null ? Instant.now() : creationInstant).append("\n");
//        sb.append("modificationInstant : ").append(modificationInstant == null ? Instant.now() : modificationInstant).append("\n");
//        sb.append("------- ").append("\n");
//        for (Map.Entry<Integer, String> e : getLinesSet().entrySet()) {
//            sb.append(e.getKey()).append(" ").append(e.getValue()).append("\n");
//        }
//        pub.mkParentDirs().writeString(sb.toString());
//    }


    public NaruRoutineMem setVisibility(NAruVisibility visibility) {
        this.visibility = visibility;
        return this;
    }

    public NaruRoutineMem setCreationInstant(Instant creationInstant) {
        this.creationInstant = creationInstant;
        return this;
    }

    public NaruRoutineMem setModificationInstant(Instant modificationInstant) {
        this.modificationInstant = modificationInstant;
        return this;
    }

    public NaruRoutineMem setLines(Map<Integer, String> lines) {
        this.lines.putAll(lines);
        return this;
    }
}
