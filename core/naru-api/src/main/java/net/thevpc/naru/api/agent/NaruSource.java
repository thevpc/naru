package net.thevpc.naru.api.agent;

public enum NaruSource {
    SYSTEM, PROJECT_FOLDER, DOTMD, USER, SKILL;

    public String id() {
        return name().toLowerCase();
    }
}
