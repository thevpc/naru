package net.thevpc.naru.ext.tools.routines;

import net.thevpc.naru.api.routine.NaruRoutine;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.util.NBlankable;

import java.util.*;

public class RoutinesToolHelper {

    public static String routineAddLine(NaruTask task, String scriptName, Number lineNumObj, String command) {
        if (NBlankable.isBlank(scriptName)) {
            scriptName = task.editRoutineName();
        }

        if (lineNumObj == null) {
            return "Error: line_number is required";
        }
        int lineNum = lineNumObj.intValue();

        if (command == null) {
            return "Error: command is required";
        }

        try {
            String target = NBlankable.isNonBlank(scriptName) ? scriptName : "main";
            String oldName = task.editRoutineName();
            task.useRoutine(target).orNull();
            task.setRoutineLine(lineNum, command);
            if (oldName != null) {
                task.useRoutine(oldName).orNull();
            }
            return "Successfully wrote line " + lineNum + " to script '" + target + "'";
        } catch (Exception e) {
            return "Error writing line to script: " + e.getMessage();
        }
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
