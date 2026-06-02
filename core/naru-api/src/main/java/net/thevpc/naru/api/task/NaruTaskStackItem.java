package net.thevpc.naru.api.task;

public class NaruTaskStackItem {
    private String name;
    private int index;
    private String instruction;

    public NaruTaskStackItem(String name, int index,String instruction) {
        this.name = name;
        this.index = index;
        this.instruction = instruction;
    }

    public String name() {
        return name;
    }

    public int index() {
        return index;
    }

    public String instruction() {
        return instruction;
    }
}
