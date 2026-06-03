package net.thevpc.naru.impl.routine;

import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.routine.NaruIndexedLine;
import net.thevpc.naru.api.routine.SubroutineDef;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.*;

import java.time.Instant;
import java.util.*;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NaruRoutineImpl implements NaruRoutine {
    private String uuid;
    private String name;
    private NAruVisibility visibility;
    private Instant creationInstant;
    private Instant modificationInstant;
    private final TreeMap<Integer, String> lines = new TreeMap<>();
    private final NPath publicDir;
    private final NPath privateDir;
    private final NPath preferredPath;
    private static final Pattern LINE_PATTERN = Pattern.compile("^(\\d+)(?:\\s+(.*))?$");
    private static final Pattern METADATA = Pattern.compile("^([a-z]+)\\s*:(\\s*(.*))?$");

    public NaruRoutineImpl(String uuid, String name, NPath publicDir, NPath privateDir, NPath preferredPath, NAruVisibility visibility, boolean loadContent) {
        this.uuid = NAssert.requireNamedNonNull(uuid, "uuid");
        this.name = name;
        this.publicDir = publicDir;
        this.privateDir = privateDir;
        this.preferredPath = preferredPath;
        this.visibility = visibility;
        this.lines.putAll(lines);
        if (preferredPath != null) {
            this.visibility = NAruVisibility.PUBLIC;
            fill(preferredPath, false, loadContent);
        } else {
            String pathName = uuid + ".naru";
            if (visibility == NAruVisibility.PUBLIC) {
                fill(publicDir.resolve(pathName), false, loadContent);
            } else {
                fill(privateDir.resolve(pathName), false, loadContent);
            }
        }
    }

    private synchronized void fill(NPath path, boolean numbered, boolean loadContent) {
        if (!path.exists()) {
            return;
        }
        String text = path.readString();
        boolean metadata = true;
        int goodIndex = 10;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (!line.isEmpty()) {
                if (metadata) {
                    Matcher m0 = METADATA.matcher(line);
                    if (m0.matches()) {
                        String n = m0.group(1);
                        String content = m0.group(2);
                        switch (n) {
                            case "name": {
                                if (NBlankable.isBlank(content)) {
                                    this.name = content.trim();
                                }
                                break;
                            }
                            case "creationInstant": {
                                if (NBlankable.isBlank(content)) {
                                    try {
                                        this.creationInstant = Instant.parse(content.trim());
                                    } catch (Exception ex) {
                                        this.creationInstant = path.creationInstant();
                                    }
                                }
                                break;
                            }
                            case "modificationInstant": {
                                if (NBlankable.isBlank(content)) {
                                    try {
                                        this.creationInstant = Instant.parse(content.trim());
                                    } catch (Exception ex) {
                                        this.modificationInstant = path.lastModifiedInstant();
                                    }
                                }
                                break;
                            }
                        }
                    } else {
                        if (!loadContent) {
                            if(creationInstant==null){
                                creationInstant=path.creationInstant();
                            }
                            if(modificationInstant==null){
                                modificationInstant=creationInstant;
                            }
                            return;
                        }
                        metadata = false;
                        if (numbered) {
                            Matcher m = LINE_PATTERN.matcher(line);
                            if (m.matches()) {
                                int num = Integer.parseInt(m.group(1));
                                String content = m.group(2) != null ? m.group(2) : "";
                                lines.put(num, content);
                            }
                        } else {
                            lines.put(goodIndex, line);
                            goodIndex += 10;
                        }
                    }
                } else {
                    if (!loadContent) {
                        if(creationInstant==null){
                            creationInstant=path.creationInstant();
                        }
                        if(modificationInstant==null){
                            modificationInstant=creationInstant;
                        }
                        return;
                    }
                    Matcher m = LINE_PATTERN.matcher(line);
                    if (numbered) {
                        if (m.matches()) {
                            int num = Integer.parseInt(m.group(1));
                            String content = m.group(2) != null ? m.group(2) : "";
                            lines.put(num, content);
                        }
                    } else {
                        lines.put(goodIndex, line);
                        goodIndex += 10;
                    }
                }
            }
        }
        if(creationInstant==null){
            creationInstant=path.creationInstant();
        }
        if(modificationInstant==null){
            modificationInstant=creationInstant;
        }
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

    public NaruRoutineImpl visibility(NAruVisibility visibility) {
        this.visibility = visibility;
        return this;
    }


    public String uuid() {
        return uuid;
    }

    public NaruRoutineImpl setUuid(String uuid) {
        this.uuid = uuid;
        return this;
    }

    public NaruRoutineImpl setName(String name) {
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

    @Override
    public Map<String, SubroutineDef> getSubroutines() {
        Map<String, SubroutineDef> subs = new HashMap<>();
        NavigableMap<Integer, String> lines = getLinesSet();

        Integer subStart = null;
        String subName = null;
        List<String> subParams = null;

        for (Map.Entry<Integer, String> entry : lines.entrySet()) {
            String raw = entry.getValue().trim();
            int lineNum = entry.getKey();

            if (raw.startsWith("/sub ")) {
                // Parse: /sub name param1 param2
                String[] parts = raw.substring(5).trim().split("\\s+");
                subName = parts[0];
                subParams = Arrays.asList(Arrays.copyOfRange(parts, 1, parts.length));
                subStart = lineNum;
            } else if (raw.equals("/endsub") && subStart != null) {
                subs.put(subName, new SubroutineDefImpl(subStart, lineNum, subParams));
                subStart = null;
                subName = null;
                subParams = null;
            }
        }
        return subs;
    }

    @Override
    public void flush() {
        if (NBlankable.isBlank(uuid())) {
            setUuid(UUID.randomUUID().toString());
        }
        if (preferredPath != null) {
            _write(preferredPath.mkParentDirs());
        } else {
            NAssert.requireNamedNonBlank(name(), "name");
            if (!NStringUtils.isValidVar(name())) {
                throw new IllegalArgumentException("invalid name " + name());
            }
            String pathName = uuid() + ".naru";
            NPath pub = publicDir.resolve(pathName);
            NPath priv = privateDir.resolve(pathName);
            if (visibility() == NAruVisibility.PUBLIC) {
                if (priv.isRegularFile()) {
                    priv.delete();
                }
                _write(pub.mkParentDirs());
            } else {
                if (pub.isRegularFile()) {
                    pub.delete();
                }
                _write(priv.mkParentDirs());
            }
        }
    }

    private String effectiveName() {
        if (preferredPath != null) {
            String n = preferredPath.name();
            if (n.endsWith(".naru")) {
                n = n.substring(0, n.length() - 5);
            }
            return n;
        }
        return name();
    }

    private void _write(NPath pub) {
        StringBuilder sb = new StringBuilder();
        sb.append("name : ").append(NStringUtils.firstNonBlankTrimmed(effectiveName(), "NO_NAME")).append("\n");
        sb.append("creationInstant : ").append(creationInstant==null?Instant.now():creationInstant).append("\n");
        sb.append("modificationInstant : ").append(modificationInstant==null?Instant.now():modificationInstant).append("\n");
        sb.append("\n");
        for (Map.Entry<Integer, String> e : getLinesSet().entrySet()) {
            sb.append(e.getKey()).append(" ").append(e.getValue()).append("\n");
        }
        pub.mkParentDirs().writeString(sb.toString());
    }
}
