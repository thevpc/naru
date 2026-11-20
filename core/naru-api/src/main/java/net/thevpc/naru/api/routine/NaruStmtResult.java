package net.thevpc.naru.api.routine;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.text.NMsg;

public record NaruStmtResult(NElement value, NaruStmtResultType type, int exitCode) {
    public static NaruStmtResult nonNull(NaruStmtResult other) {
        if (other == null) {
            return ofSuccess(NElement.ofNull(), null);
        }
        return other;
    }

    public static NaruStmtResult ofSuccess(NElement value) {
        return new NaruStmtResult(value, NaruStmtResultType.SUCCESS, 0);
    }
    public static NaruStmtResult ofSuccess(NElement value, NMsg message) {
        return new NaruStmtResult(value, NaruStmtResultType.SUCCESS, 0);
    }

    public static NaruStmtResult ofSuccess() {
        return new NaruStmtResult(NElement.ofNull(), NaruStmtResultType.SUCCESS, 0);
    }

//    public static NaruStmtResult ofSuccess(NMsg message) {
//        return new NaruStmtResult(message == null ? NElement.ofNull() : NElement.ofString(message.toString()), NaruStmtResultType.SUCCESS, message, 0);
//    }

//    public static NaruStmtResult ofSuccess(String value, NMsg message) {
//        return new NaruStmtResult(NElement.ofString(value), NaruStmtResultType.SUCCESS, message, 0);
//    }

    public static NaruStmtResult of(NElement value, NMsg message, int exitCode) {
        return new NaruStmtResult(value, exitCode == 0 ? NaruStmtResultType.SUCCESS : NaruStmtResultType.ERROR, exitCode);
    }
    public static NaruStmtResult of(NElement value, int exitCode) {
        return new NaruStmtResult(value, exitCode == 0 ? NaruStmtResultType.SUCCESS : NaruStmtResultType.ERROR, exitCode);
    }

//    public static NaruStmtResult ofError(String value, NMsg message) {
//        return new NaruStmtResult(NElement.ofString(value), NaruStmtResultType.ERROR, message, 1);
//    }

    public static NaruStmtResult ofError(NElement value) {
        return of(value, null, 1);
    }

    public static NaruStmtResult ofError(NMsg message, int exitCode) {
        return new NaruStmtResult(NElement.ofNull(), NaruStmtResultType.ERROR, exitCode);
    }

//    public static NaruStmtResult ofError(NElement value, NMsg message, int exitCode) {
//        return new NaruStmtResult(value == null ? NElement.ofNull() : value, NaruStmtResultType.ERROR, message, exitCode);
//    }
    public static NaruStmtResult ofError(NElement value, int exitCode) {
        return new NaruStmtResult(value == null ? NElement.ofNull() : value, NaruStmtResultType.ERROR, exitCode);
    }
    public static NaruStmtResult ofError(int exitCode) {
        return new NaruStmtResult(NElement.ofNull(), NaruStmtResultType.ERROR, exitCode);
    }

    public static NaruStmtResult ofError() {
        return new NaruStmtResult(
                NElement.ofNull(), NaruStmtResultType.ERROR, 1);
    }

    public static NaruStmtResult ofError(NMsg message) {
        return new NaruStmtResult(
                message == null ? NElement.ofNull() : NElement.ofString(message.toString())
                , NaruStmtResultType.ERROR, 1);
    }

    public boolean isSuccess() {
        return type == NaruStmtResultType.SUCCESS;
    }

    public boolean isError() {
        return type == NaruStmtResultType.ERROR;
    }

    public NElement successValue() {
        return type == NaruStmtResultType.SUCCESS ? value : null;
    }

    public NElement errorValue() {
        return type == NaruStmtResultType.ERROR ? value : null;
    }
}
