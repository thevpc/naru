package net.thevpc.naru.api.agent;

public enum NaruSource {
    SYSTEM, PROJECT, FOLDER, USER, CLASSPATH, USER_HOME, WORKSPACE, SKILL;

    public String id() {
        return name().toLowerCase();
    }
}
