package net.thevpc.naru.api.routine;

public class NaruIndexedLine {
    private int index;
    private String command;

    public NaruIndexedLine(int index, String command) {
        this.index = index;
        this.command = command;
    }

    public int index() {
        return index;
    }

    public String command() {
        return command;
    }
}
