package net.thevpc.naru.api.project;

import net.thevpc.nuts.io.NPath;

/**
 * The edit side of the project store: each field is either {@link NaruSetting#unset()
 * unset} (leave alone) or {@link NaruSetting#set(Object) set} — and for {@code user},
 * {@code set(null)} is the explicit clear.
 *
 * <p>Applying a patch never touches any session: a project's folder and user are
 * creation-time values only. Renaming or moving the project does not follow its sessions,
 * and clearing the user does not clear any session's user.
 */
public final class NaruProjectPatch {

    private NaruSetting<String> name = NaruSetting.unset();
    private NaruSetting<NPath> folder = NaruSetting.unset();
    private NaruSetting<String> user = NaruSetting.unset();

    private NaruProjectPatch() {
    }

    public static NaruProjectPatch of() {
        return new NaruProjectPatch();
    }

    /** An empty patch: no field set, so an edit would be a pure version bump at best. */
    public static NaruProjectPatch unchanged() {
        return new NaruProjectPatch();
    }

    public NaruSetting<String> name() {
        return name;
    }

    public NaruProjectPatch name(String name) {
        this.name = NaruSetting.set(name);
        return this;
    }

    public NaruSetting<NPath> folder() {
        return folder;
    }

    public NaruProjectPatch folder(NPath folder) {
        this.folder = NaruSetting.set(folder);
        return this;
    }

    public NaruSetting<String> user() {
        return user;
    }

    /** Set the user. Passing {@code null} (or {@link #clearUser()}) clears it explicitly. */
    public NaruProjectPatch user(String user) {
        this.user = NaruSetting.set(user);
        return this;
    }

    /** Equivalent to {@code user(null)}: an explicit clear, not an omission. */
    public NaruProjectPatch clearUser() {
        this.user = NaruSetting.set(null);
        return this;
    }

    public boolean isEmpty() {
        return !name.isSet() && !folder.isSet() && !user.isSet();
    }

    @Override
    public String toString() {
        return "NaruProjectPatch{name=" + name + ", folder=" + folder + ", user=" + user + '}';
    }
}
