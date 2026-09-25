package net.thevpc.naru.api.task;

import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class NaruTaskSpec {
    private long parentId=-1;
    private String name;
    private NPath workingDirectory;
    private List<String> statements = new ArrayList<>();
    private NaruPromptMode promptMode;
    private final Set<String> toolTags = new LinkedHashSet<>();

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
