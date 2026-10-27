package net.thevpc.naru.api.routine;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.nuts.text.NMsg;

public record NaruStmtResult(Object value, NaruStmtResultType type, NaruMessage message, int exitCode) {
    public static NaruStmtResult nonNull(NaruStmtResult other) {
        if (other == null) {
            return ofSuccess(null, null);
        }
        return other;
    }

    public static NaruStmtResult ofSuccess(Object value, NaruMessage message) {
        return new NaruStmtResult(value, NaruStmtResultType.SUCCESS, message, 0);
    }

    public static NaruStmtResult of(Object value, NaruMessage message, int exitCode) {
        return new NaruStmtResult(value, exitCode == 0 ? NaruStmtResultType.SUCCESS : NaruStmtResultType.ERROR, message, exitCode);
    }

    public static NaruStmtResult ofError(String value, NaruMessage message) {
        return new NaruStmtResult(value, NaruStmtResultType.ERROR, message, 1);
    }

    public static NaruStmtResult ofError(NaruMessage message) {
        return new NaruStmtResult(message.getContent(), NaruStmtResultType.ERROR, message, 1);
    }
    public static NaruStmtResult ofError(NMsg message) {
        return new NaruStmtResult(message.toString(), NaruStmtResultType.ERROR, NaruMessage.engine(message), 1);
    }

    public boolean isSuccess(){
        return type == NaruStmtResultType.SUCCESS;
    }
    public boolean isError(){
        return type == NaruStmtResultType.ERROR;
    }
    public Object successValue() {
        return type == NaruStmtResultType.SUCCESS ? value : null;
    }

    public Object errorValue() {
        return type == NaruStmtResultType.ERROR ? value : null;
    }
}
