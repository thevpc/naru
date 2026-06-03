package net.thevpc.naru.api.routine;

public class NaruStmtResult {
    private Object value;
    private NaruStmtResultType type;

    public static NaruStmtResult nonNull(NaruStmtResult other) {
        if(other==null){
            return ofSuccess(null);
        }
        return other;
    }
    public static NaruStmtResult ofSuccess(Object value) {
        return new NaruStmtResult(value, NaruStmtResultType.SUCCESS);
    }

    public static NaruStmtResult ofError(String value) {
        return new NaruStmtResult(value, NaruStmtResultType.ERROR);
    }

    public NaruStmtResult(Object value, NaruStmtResultType type) {
        this.value = value;
        this.type = type;
    }

    public Object successValue() {
        return type == NaruStmtResultType.SUCCESS ? value : null;
    }

    public Object value() {
        return value;
    }

    public NaruStmtResultType type() {
        return type;
    }
}
