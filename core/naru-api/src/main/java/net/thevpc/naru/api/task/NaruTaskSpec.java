package net.thevpc.naru.api.task;

import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NaruTaskSpec {
    private long parentId=-1;
    private String name;
    private NPath workingDirectory;
    private List<String> statements = new ArrayList<>();

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
