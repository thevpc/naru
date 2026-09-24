package net.thevpc.naru.api.routine;

public record NaruStmtResult(Object value, NaruStmtResultType type, int exitCode) {
    public static NaruStmtResult nonNull(NaruStmtResult other) {
        if (other == null) {
            return ofSuccess(null);
        }
        return other;
    }

    public static NaruStmtResult ofSuccess(Object value) {
        return new NaruStmtResult(value, NaruStmtResultType.SUCCESS, 0);
    }

    public static NaruStmtResult of(Object value, int exitCode) {
        return new NaruStmtResult(value, exitCode == 0 ? NaruStmtResultType.SUCCESS : NaruStmtResultType.ERROR, exitCode);
    }

    public static NaruStmtResult ofError(String value) {
        return new NaruStmtResult(value, NaruStmtResultType.ERROR, 1);
    }

    public Object successValue() {
        return type == NaruStmtResultType.SUCCESS ? value : null;
    }

    public Object errorValue() {
        return type == NaruStmtResultType.ERROR ? value : null;
    }
}
