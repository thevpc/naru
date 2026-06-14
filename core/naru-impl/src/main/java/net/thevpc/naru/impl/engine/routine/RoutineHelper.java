package net.thevpc.naru.impl.engine.routine;

import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RoutineHelper {
    private static final Pattern NUMBEDRED_LINE_PATTERN = Pattern.compile("^(\\d+)(?:\\s+(.*))?$");
    private static final Pattern METADATA = Pattern.compile("^([a-z]+)\\s*:(\\s*(.*))?$");

    private static boolean isNumberedLine(String line) {
        return NUMBEDRED_LINE_PATTERN.matcher(line.trim()).matches();
    }

    private static boolean isRuler(String line) {
        char[] cc = line.trim().toCharArray();
        if (cc.length < 3) {
            return false;
        }
        for (char c : cc) {
            if (c != '-') {
                return false;
            }
        }
        return true;
    }

    public static synchronized NOptional<NaruRoutine> loadFileRoutine(NPath path, boolean loadContent) {
        if (!path.exists()) {
            return NOptional.ofNamedEmpty(path.toString());
        }
        boolean numbered=false;
        try {
            NaruRoutineMem rr = new NaruRoutineMem(null, null, NAruVisibility.PUBLIC);
            String name = path.toString();
            String text = path.readString();
            final int EXPECT_START_METADATA = 1;
            final int EXPECT_END_METADATA = 2;
            final int EXPECT_CMD = 3;
            int status = EXPECT_START_METADATA;
            int goodIndex = 10;
            Instant creationInstant = null;
            Instant modificationInstant = null;
            String uuid = null;
            Map<Integer, String> lines = new TreeMap<>();
            for (String line : text.split("\n")) {
                line = line.trim();
                switch (status) {
                    case EXPECT_START_METADATA: {
                        if (line.isEmpty()) {

                        } else if (isRuler(line)) {
                            status = EXPECT_END_METADATA;
                        } else {
                            status = EXPECT_CMD;
                            if (!loadContent) {
                                fill2(rr, path, creationInstant, modificationInstant, uuid, name, NAruVisibility.PUBLIC, lines);
                                return NOptional.of(rr);
                            }
                            if (numbered) {
                                Matcher m = NUMBEDRED_LINE_PATTERN.matcher(line);
                                if (m.matches()) {
                                    int num = Integer.parseInt(m.group(1));
                                    String content = m.group(2) != null ? m.group(2) : "";
                                    lines.put(num, content);
                                } else {
                                    lines.put(goodIndex, line);
                                    goodIndex += 10;
                                }
                            } else {
                                lines.put(goodIndex, line);
                                goodIndex += 10;
                            }
                        }
                        break;
                    }
                    case EXPECT_END_METADATA: {
                        if (line.isEmpty()) {

                        } else if (isRuler(line)) {
                            status = EXPECT_CMD;
                        } else {
                            Matcher m0 = METADATA.matcher(line);
                            if (m0.matches()) {
                                String n = m0.group(1);
                                String content = m0.group(2);
                                switch (n) {
//                                case "name": {
//                                    if (NBlankable.isBlank(content)) {
//                                        this.name = content.trim();
//                                    }
//                                    break;
//                                }
                                    case "uuid": {
                                        if (!NBlankable.isBlank(content)) {
                                            uuid = content.trim();
                                        }
                                        break;
                                    }
                                    case "creationInstant": {
                                        if (!NBlankable.isBlank(content)) {
                                            try {
                                                creationInstant = Instant.parse(content.trim());
                                            } catch (Exception ex) {
                                                creationInstant = path.creationInstant();
                                            }
                                        }
                                        break;
                                    }
                                    case "modificationInstant": {
                                        if (!NBlankable.isBlank(content)) {
                                            try {
                                                creationInstant = Instant.parse(content.trim());
                                            } catch (Exception ex) {
                                                modificationInstant = path.lastModifiedInstant();
                                            }
                                        }
                                        break;
                                    }
                                }
                            }
                            break;
                        }
                        break;
                    }
                    default: {
                        if (!loadContent) {
                            fill2(rr, path, creationInstant, modificationInstant, uuid, name, NAruVisibility.PUBLIC, lines);
                            return NOptional.of(rr);
                        }
                        if (line.isEmpty()) {
                            if (!numbered) {
                                goodIndex += 10;
                            }
                        } else {
                            if (numbered) {
                                Matcher m = NUMBEDRED_LINE_PATTERN.matcher(line);
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
            }
            fill2(rr, path, creationInstant, modificationInstant, uuid, name, NAruVisibility.PUBLIC, lines);
            return NOptional.of(rr);
        } catch (Exception e) {
            return NOptional.ofNamedError(e.getMessage());
        }
    }

    private static void fill2(NaruRoutineMem rr, NPath path, Instant creationInstant, Instant modificationInstant, String uuid, String name, NAruVisibility visibility, Map<Integer, String> lines) {
        if (creationInstant == null) {
            creationInstant = path.creationInstant();
        }
        if (modificationInstant == null) {
            modificationInstant = creationInstant;
        }
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        rr.setCreationInstant(creationInstant);
        rr.setModificationInstant(modificationInstant);
        rr.setUuid(uuid);
        rr.setLines(lines);
        rr.setName(name);
        rr.setVisibility(visibility);

    }

    public static void renum(int start, int increment, NaruRoutine routine) {
        if (increment <= 0) {
            increment = 10;
        }
        if (start <= 0) {
            start = increment;
        }

        TreeMap<Integer, String> oldSet = routine.getLinesSet();
        List<String> nn = new ArrayList<>(oldSet.values());
        Map<Integer, String> newLines = new HashMap<>(oldSet);
        int index = start;
        Set<Integer> keysToRemove = new HashSet<>(oldSet.keySet());
        for (String s : nn) {
            newLines.put(index, s);
            keysToRemove.remove(index);
            index = index + increment;
        }
        for (Integer i : keysToRemove) {
            routine.removeLine(i);
        }
        for (Map.Entry<Integer, String> e : newLines.entrySet()) {
            Integer ii = e.getKey();
            String oldValue = oldSet.get(ii);
            String newValue = e.getValue();
            if (!Objects.equals(oldValue, newValue)) {
                routine.putLine(ii, newValue);
            }
        }
    }

}
