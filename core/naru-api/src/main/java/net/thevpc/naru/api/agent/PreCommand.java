package net.thevpc.naru.api.agent;

public class PreCommand {
    private boolean file;
    private String command;

    public PreCommand(boolean file, String command) {
        this.file = file;
        this.command = command;
    }

    public boolean isFile() {
        return file;
    }

    public String command() {
        return command;
    }
}
