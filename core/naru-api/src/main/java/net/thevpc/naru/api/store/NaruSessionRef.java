package net.thevpc.naru.api.store;

import net.thevpc.naru.api.agent.NaruResourceInfo;

import java.time.Instant;

/**
 * One entry in a store's catalog: the minimum needed to show it in a listing and to decide
 * whether it is worth opening.
 *
 * <p>Deliberately not {@link NaruSessionData}. Listing a project with fifty sessions must
 * not parse fifty {@code session.tson} files to print fifty lines, so this carries the
 * fields a listing needs and nothing else.
 */
public class NaruSessionRef {

    private final String uuid;
    private final String name;
    private final NaruSessionScope scope;
    private final Instant creationInstant;
    private final Instant modificationInstant;
    private final int taskCount;
    private final int historyCount;

    public NaruSessionRef(String uuid, String name, NaruSessionScope scope,
                          Instant creationInstant, Instant modificationInstant,
                          int taskCount, int historyCount) {
        this.uuid = uuid;
        this.name = name;
        this.scope = scope;
        this.creationInstant = creationInstant;
        this.modificationInstant = modificationInstant;
        this.taskCount = taskCount;
        this.historyCount = historyCount;
    }

    public String uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    /**
     * Where this session's state actually is. Not a remembered preference: it is read off
     * the location, so it cannot say {@code PUBLIC} for a folder under {@code local/}.
     */
    public NaruSessionScope scope() {
        return scope;
    }

    public Instant creationInstant() {
        return creationInstant;
    }

    public Instant modificationInstant() {
        return modificationInstant;
    }

    public int taskCount() {
        return taskCount;
    }

    public int historyCount() {
        return historyCount;
    }

    @Override
    public String toString() {
        return "NaruSessionRef{uuid=" + uuid + ", name=" + name + ", scope=" + scope
                + ", tasks=" + taskCount + ", messages=" + historyCount + '}';
    }
}