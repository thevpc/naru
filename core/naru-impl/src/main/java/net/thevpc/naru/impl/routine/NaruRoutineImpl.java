//package net.thevpc.naru.impl.routine;
//
//import net.thevpc.naru.api.agent.NAruVisibility;
//import net.thevpc.naru.api.task.NaruTask;
//import net.thevpc.naru.api.routine.NaruIndexedLine;
//import net.thevpc.naru.api.routine.SubroutineDef;
//import net.thevpc.naru.api.routine.NaruRoutine;
//import net.thevpc.naru.api.stmt.NaruStatement;
//import net.thevpc.naru.impl.util.NaruUtils;
//import net.thevpc.nuts.io.NPath;
//import net.thevpc.nuts.text.NMsg;
//import net.thevpc.nuts.util.*;
//
//import java.time.Instant;
//import java.util.*;
//import java.util.function.IntPredicate;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//public class NaruRoutineImpl implements NaruRoutine {
//    private String uuid;
//    private String name;
//    private NAruVisibility visibility;
//    private Instant creationInstant;
//    private Instant modificationInstant;
//    private final TreeMap<Integer, String> lines = new TreeMap<>();
//    private final NPath publicDir;
//    private final NPath privateDir;
//    private final NPath preferredPath;
//    private static final Pattern NUMBEDRED_LINE_PATTERN = Pattern.compile("^(\\d+)(?:\\s+(.*))?$");
//    private static final Pattern METADATA = Pattern.compile("^([a-z]+)\\s*:(\\s*(.*))?$");
//
//    public NaruRoutineImpl(String name, NPath publicDir, NPath privateDir, NPath preferredPath, NAruVisibility visibility, boolean loadContent) {
//        this.name = name;
//        this.publicDir = publicDir;
//        this.privateDir = privateDir;
//        this.preferredPath = preferredPath;
//        this.visibility = visibility;
//        this.lines.putAll(lines);
//        if (preferredPath != null) {
//            this.visibility = NAruVisibility.PUBLIC;
//            fill(preferredPath, false, loadContent);
//        } else {
//            name=NStringUtils.trimToNull(name);
//            NaruUtils.checkValidRoutineName(name);
//            String pathName = name + ".naru";
//            if (visibility == NAruVisibility.PUBLIC) {
//                fill(publicDir.resolve(pathName), true, loadContent);
//            } else {
//                fill(privateDir.resolve(pathName), true, loadContent);
//            }
//        }
//    }
//
//    private boolean isNumberedLine(String line) {
//        return NUMBEDRED_LINE_PATTERN.matcher(line.trim()).matches();
//    }
//
//    private boolean isRuler(String line) {
//        char[] cc = line.trim().toCharArray();
//        if (cc.length < 3) {
//            return false;
//        }
//        for (char c : cc) {
//            if (c != '-') {
//                return false;
//            }
//        }
//        return true;
//    }
//
//    private synchronized void fill(NPath path, boolean numbered, boolean loadContent) {
//        if (!path.exists()) {
//            return;
//        }
//        String text = path.readString();
//        final int EXPECT_START_METADATA = 1;
//        final int EXPECT_END_METADATA = 2;
//        final int EXPECT_CMD = 3;
//        int status = EXPECT_START_METADATA;
//        int goodIndex = 10;
//        for (String line : text.split("\n")) {
//            line = line.trim();
//            switch (status) {
//                case EXPECT_START_METADATA: {
//                    if (line.isEmpty()) {
//
//                    } else if (isRuler(line)) {
//                        status = EXPECT_END_METADATA;
//                    } else {
//                        status = EXPECT_CMD;
//                        if (!loadContent) {
//                            if (creationInstant == null) {
//                                creationInstant = path.creationInstant();
//                            }
//                            if (modificationInstant == null) {
//                                modificationInstant = creationInstant;
//                            }
//                            if(uuid==null){
//                                uuid=UUID.randomUUID().toString();
//                            }
//                            return;
//                        }
//                        if (numbered) {
//                            Matcher m = NUMBEDRED_LINE_PATTERN.matcher(line);
//                            if (m.matches()) {
//                                int num = Integer.parseInt(m.group(1));
//                                String content = m.group(2) != null ? m.group(2) : "";
//                                lines.put(num, content);
//                            }
//                        } else {
//                            lines.put(goodIndex, line);
//                            goodIndex += 10;
//                        }
//                    }
//                    break;
//                }
//                case EXPECT_END_METADATA: {
//                    if (line.isEmpty()) {
//
//                    } else if (isRuler(line)) {
//                        status = EXPECT_CMD;
//                    } else {
//                        Matcher m0 = METADATA.matcher(line);
//                        if (m0.matches()) {
//                            String n = m0.group(1);
//                            String content = m0.group(2);
//                            switch (n) {
////                                case "name": {
////                                    if (NBlankable.isBlank(content)) {
////                                        this.name = content.trim();
////                                    }
////                                    break;
////                                }
//                                case "uuid": {
//                                    if (!NBlankable.isBlank(content)) {
//                                        this.uuid = content.trim();
//                                    }
//                                    break;
//                                }
//                                case "creationInstant": {
//                                    if (!NBlankable.isBlank(content)) {
//                                        try {
//                                            this.creationInstant = Instant.parse(content.trim());
//                                        } catch (Exception ex) {
//                                            this.creationInstant = path.creationInstant();
//                                        }
//                                    }
//                                    break;
//                                }
//                                case "modificationInstant": {
//                                    if (!NBlankable.isBlank(content)) {
//                                        try {
//                                            this.creationInstant = Instant.parse(content.trim());
//                                        } catch (Exception ex) {
//                                            this.modificationInstant = path.lastModifiedInstant();
//                                        }
//                                    }
//                                    break;
//                                }
//                            }
//                        }
//                        break;
//                    }
//                    break;
//                }
//                default: {
//                    if (!loadContent) {
//                        if (creationInstant == null) {
//                            creationInstant = path.creationInstant();
//                        }
//                        if (modificationInstant == null) {
//                            modificationInstant = creationInstant;
//                        }
//                        if(uuid==null){
//                            uuid=UUID.randomUUID().toString();
//                        }
//                        return;
//                    }
//                    if (line.isEmpty()) {
//                        if (!numbered) {
//                            goodIndex += 10;
//                        }
//                    } else {
//                        if (numbered) {
//                            Matcher m = NUMBEDRED_LINE_PATTERN.matcher(line);
//                            if (m.matches()) {
//                                int num = Integer.parseInt(m.group(1));
//                                String content = m.group(2) != null ? m.group(2) : "";
//                                lines.put(num, content);
//                            }
//                        } else {
//                            lines.put(goodIndex, line);
//                            goodIndex += 10;
//                        }
//                    }
//                }
//            }
//        }
//        if (creationInstant == null) {
//            creationInstant = path.creationInstant();
//        }
//        if (modificationInstant == null) {
//            modificationInstant = creationInstant;
//        }
//        if(uuid==null){
//            uuid=UUID.randomUUID().toString();
//        }
//    }
//
//    public NAruVisibility visibility() {
//        return visibility;
//    }
//
//    @Override
//    public Instant creationInstant() {
//        return creationInstant;
//    }
//
//    @Override
//    public Instant modificationInstant() {
//        return modificationInstant;
//    }
//
//    public NaruRoutineImpl visibility(NAruVisibility visibility) {
//        this.visibility = visibility;
//        return this;
//    }
//
//
//    public String uuid() {
//        return uuid;
//    }
//
//    public NaruRoutineImpl setUuid(String uuid) {
//        this.uuid = uuid;
//        return this;
//    }
//
//    public NaruRoutineImpl setName(String name) {
//        this.name = name;
//        return this;
//    }
//
//    @Override
//    public String name() {
//        return name;
//    }
//
//    @Override
//    public void putLine(int lineNumber, String text) {
//        if (preferredPath != null) {
//            if(isNumberedLine(text)){
//                System.out.println("Why");
//            }
//        } else {
//            if(isNumberedLine(text)){
//                System.out.println("Why");
//            }
//        }
//        lines.put(lineNumber, text);
//    }
//
//    @Override
//    public boolean removeLine(int lineNumber) {
//        String old = lines.remove(lineNumber);
//        return old != null;
//    }
//
//    @Override
//    public int clear() {
//        int old = lines.size();
//        lines.clear();
//        return old;
//    }
//
//    @Override
//    public TreeMap<Integer, String> getLinesSet() {
//        return lines;
//    }
//
//    @Override
//    public List<NaruIndexedLine> getIndexedLines() {
//        ArrayList<NaruIndexedLine> all = new ArrayList<>();
//        for (Map.Entry<Integer, String> e : lines.entrySet()) {
//            all.add(new NaruIndexedLine(e.getKey(), e.getValue()));
//        }
//        return all;
//    }
//
//    @Override
//    public int firstIndex() {
//        for (Integer i : lines.keySet()) {
//            return i;
//        }
//        return -1;
//    }
//
//    @Override
//    public int nextPc(int currentPc) {
//        List<NaruIndexedLine> lines = getIndexedLines();
//        int currentIt = -1;
//
//        for (int i = 0; i < lines.size(); i++) {
//            if (lines.get(i).index() == currentPc) {
//                currentIt = i;
//                break;
//            }
//        }
//        if (currentIt >= 0 && currentIt + 1 < lines.size()) {
//            return lines.get(currentIt + 1).index();
//        }
//        return -1;
//    }
//
//    @Override
//    public TreeMap<Integer, String> getLinesSet(IntPredicate lineFilter) {
//        TreeMap<Integer, String> newOne = new TreeMap<>();
//        for (Map.Entry<Integer, String> e : lines.entrySet()) {
//            Integer k = e.getKey();
//            if (lineFilter == null || lineFilter.test(k)) {
//                newOne.put(k, e.getValue());
//            }
//        }
//        return newOne;
//    }
//
//    @Override
//    public String getFormattedText() {
//        if (lines.isEmpty()) {
//            return "<empty>";
//        }
//        StringBuilder sb = new StringBuilder();
//        for (Map.Entry<Integer, String> entry : lines.entrySet()) {
//            sb.append(entry.getKey()).append(" ").append(entry.getValue()).append("\n");
//        }
//        return sb.toString();
//    }
//
//    @Override
//    public boolean isEmpty() {
//        return lines.isEmpty();
//    }
//
//    @Override
//    public String lineCommandAt(int n) {
//        return lines.get(n);
//    }
//
//    @Override
//    public NOptional<List<NaruStatement>> parseStatements(NaruTask task) {
//        List<NaruStatement> curr = new ArrayList<>();
//        for (NaruIndexedLine aa : getIndexedLines()) {
//            NOptional<NaruStatement> o = task.parseStatement(aa.command());
//            if (o.isError()) {
//                return NOptional.ofNamedError(NMsg.ofC("Error statement: routine line invalid %s", aa.command()));
//            }
//            if (o.isPresent()) {
//                curr.add(o.get());
//            }
//        }
//        return NOptional.of(curr);
//    }
//
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
//
//    @Override
//    public void flush() {
//        if (NBlankable.isBlank(uuid())) {
//            setUuid(UUID.randomUUID().toString());
//        }
//        if (preferredPath != null) {
//            _write(preferredPath.mkParentDirs());
//        } else {
//            String pathName = NStringUtils.firstNonBlankTrimmed(name(),"noname") + ".naru";
//            NPath pub = publicDir.resolve(pathName);
//            NPath priv = privateDir.resolve(pathName);
//            if (visibility() == NAruVisibility.PUBLIC) {
//                if (priv.isRegularFile()) {
//                    priv.delete();
//                }
//                _write(pub.mkParentDirs());
//            } else {
//                if (pub.isRegularFile()) {
//                    pub.delete();
//                }
//                _write(priv.mkParentDirs());
//            }
//        }
//    }
//
////    private String effectiveName() {
////        if (preferredPath != null) {
////            String n = preferredPath.name();
////            if (n.endsWith(".naru")) {
////                n = n.substring(0, n.length() - 5);
////            }
////            return n;
////        }
////        return name();
////    }
//
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
//}
