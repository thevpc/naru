package net.thevpc.naru.api.routine;

public class IndexedLine {
    private int index;
    private String command;

    public IndexedLine(int index, String command) {
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
