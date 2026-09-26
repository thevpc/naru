package net.thevpc.naru.api.task;

import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NaruTaskSpec {
    private long parentId=-1;
    private String name;
    private NPath workingDirectory;
    private List<String> statements = new ArrayList<>();
    private NaruPromptMode promptMode;
    private final Set<String> toolTags = new LinkedHashSet<>();
    private final Map<String, Object> vars = new LinkedHashMap<>();

    public static NaruTaskSpec of() {
        return new NaruTaskSpec();
    }

    public NaruTaskSpec() {

    }

    public long parentId() {
        return parentId;
    }

    public NaruTaskSpec parentId(long parentId) {
        this.parentId = parentId;
        return this;
    }

    public NPath workingDirectory() {
        return workingDirectory;
    }

    public NaruTaskSpec workingDirectory(NPath workingDirectory) {
        this.workingDirectory = workingDirectory;
        return this;
    }

    public List<String> statements() {
        return statements;
    }

    public NaruTaskSpec statements(String... commands) {
        this.statements = commands == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(commands));
        return this;
    }

    public NaruTaskSpec statements(List<String> commands) {
        this.statements = commands == null ? new ArrayList<>() : new ArrayList<>(commands);
        return this;
    }

    public String name() {
        return name;
    }

    public NaruTaskSpec name(String name) {
        this.name = name;
        return this;
    }

    /**
     * Prompt mode to run the task in. When {@code null} the task inherits its
     * parent's mode, falling back to {@code PLANNING} for root tasks.
     * <p>
     * Declaring it here rather than calling {@code NaruTask.promptMode(..)} after
     * {@code newTask(..)} matters: a freshly created task is already visible to
     * scheduler workers, so a mode flipped after the fact leaves a window in
     * which the task runs under the inherited mode.
     */
    public NaruPromptMode promptMode() {
        return promptMode;
    }

    public NaruTaskSpec promptMode(NaruPromptMode promptMode) {
        this.promptMode = promptMode;
        return this;
    }

    /**
     * Tool tags granted to the task. A task can only see a tagged tool if it
     * holds at least one of that tool's tags, so these must be granted
     * explicitly; tags are never inherited from the parent.
     */
    public Set<String> toolTags() {
        return toolTags;
    }

    public NaruTaskSpec toolTags(String... tags) {
        return toolTags(tags == null ? new ArrayList<>() : Arrays.asList(tags));
    }

    public NaruTaskSpec toolTags(List<String> tags) {
        toolTags.clear();
        if (tags != null) {
            for (String tag : tags) {
                if (!NBlankable.isBlank(tag)) {
                    toolTags.add(tag.trim());
                }
            }
        }
        return this;
    }

    /**
     * Replace the initial task variables, replacing anything set earlier.
     * <p>
     * This is how a host passes real inputs in. The alternative -- a leading
     * {@code /set --task name = value} statement -- forces every value through the script
     * parser, which means stringifying anything that is not a literal and quoting anything
     * containing spaces.
     *
     * @param vars the variables, may be null to clear; null keys are ignored
     */
    public NaruTaskSpec vars(Map<String, Object> vars) {
        this.vars.clear();
        if (vars != null) {
            for (Map.Entry<String, Object> entry : vars.entrySet()) {
                if (entry.getKey() != null) {
                    this.vars.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return this;
    }

    /**
     * Add or replace a single initial task variable, keeping the others.
     */
    public NaruTaskSpec var(String name, Object value) {
        if (name != null) {
            vars.put(name, value);
        }
        return this;
    }

    /**
     * The initial task variables, unmodifiable. Empty rather than null when none were set.
     */
    public Map<String, Object> vars() {
        return Collections.unmodifiableMap(vars);
    }

    public NaruTaskSpec resolveName() {
        return resolveNameOr(NBlankable.isBlank(name) ? "task" : name);
    }

    public NaruTaskSpec resolveNameOr(String name) {
        if (statements.size() == 1) {
            String a = statements.get(0);
            if(a.startsWith("/call ")){
                a=a.substring(6).trim();
            }else if(a.startsWith("/source ")){
                a=a.substring(8).trim();
            }else if(a.startsWith("/start ")){
                a=a.substring(7).trim();
            }
            String name2 = NPath.of(a).nameParts().baseName();
            if(NBlankable.isBlank(name2)){
                this.name=name;
            }else{
                this.name=name2;
            }
        }else{
            this.name=name;
        }
        return this;
    }
}
