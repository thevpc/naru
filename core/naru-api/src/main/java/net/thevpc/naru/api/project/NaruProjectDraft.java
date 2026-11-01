package net.thevpc.naru.api.project;

import net.thevpc.nuts.io.NPath;

/**
 * The input for {@link NaruProjectStore#create(NaruProjectDraft)}: everything the caller
 * supplies before the store assigns the id, the timestamps and version 1.
 *
 * <p>Fields follow {@link NaruProject}'s rules: {@code name} is required; {@code folder}
 * is required (there is no implicit default — the service reports "folder required" when
 * it is missing); {@code user} is nullable, and an empty string is read as {@code null}.
 */
public final class NaruProjectDraft {

    private String name;
    private NPath folder;
    private String user;

    private NaruProjectDraft() {
    }

    public static NaruProjectDraft of() {
        return new NaruProjectDraft();
    }

    public static NaruProjectDraft of(String name, NPath folder, String user) {
        return new NaruProjectDraft().name(name).folder(folder).user(user);
    }

    public String name() {
        return name;
    }

    public NaruProjectDraft name(String name) {
        this.name = name;
        return this;
    }

    public NPath folder() {
        return folder;
    }

    public NaruProjectDraft folder(NPath folder) {
        this.folder = folder;
        return this;
    }

    public String user() {
        return user;
    }

    public NaruProjectDraft user(String user) {
        this.user = user;
        return this;
    }

    @Override
    public String toString() {
        return "NaruProjectDraft{name=" + name + ", folder=" + folder + ", user=" + user + '}';
    }
}
