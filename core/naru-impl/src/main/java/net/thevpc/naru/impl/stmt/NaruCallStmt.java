//package net.thevpc.naru.impl.stmt;
//
//import net.thevpc.naru.api.agent.NaruTask;
//import net.thevpc.naru.api.routine.NaruRoutine;
//import net.thevpc.naru.api.stmt.NaruStatement;
//import net.thevpc.naru.impl.util.NaruArgsParser;
//import net.thevpc.nuts.elem.NElement;
//import net.thevpc.nuts.elem.NListContainerElement;
//import net.thevpc.nuts.elem.NObjectElementBuilder;
//import net.thevpc.nuts.text.NMsg;
//
//import java.util.ArrayList;
//import java.util.List;
//
//public class NaruCallStmt extends NaruStatement implements Cloneable {
//    public String command;
//
//    public NaruCallStmt(String command) {
//        super(Type.CALL);
//        this.command = command;
//    }
//
//    public NaruCallStmt(NElement element) {
//        super(Type.CALL);
//        NListContainerElement lc = element.asListContainer().get();
//        this.command = lc.getStringValue("command").orNull();
//    }
//
//    @Override
//    public NElement toElement() {
//        NObjectElementBuilder a = (NObjectElementBuilder) super.toElement().builder();
//        if (command != null) {
//            a.set("command", command);
//        }
//        return a.build();
//    }
//
//    @Override
//    protected NaruCallStmt clone() {
//        NaruCallStmt o = (NaruCallStmt) super.clone();
//        return o;
//    }
//
//    @Override
//    public void exec(NaruTask task) {
//        NaruArgsParser cmd = new NaruArgsParser(command);
//        if (!cmd.args().isEmpty()) {
//            List<String> li = new ArrayList<>();
//            for (NElement arg : cmd.args()) {
//                if (arg.isAnyStringOrName() && arg.asStringValue().orNull() != null) {
//                    String s = arg.asStringValue().get();
//                    NaruRoutine rtn;
//                    rtn = task.session().routineManager().routineOrUnnumberedRoutine(s, task).orNull();
//                    if (rtn == null) {
//                        task.throwError(NMsg.ofC("Error statement: routine not found %s", arg));
//                        return;
//                    }
//                    li.add(rtn.getName());
//                } else {
//                    task.throwError(NMsg.ofC("Error statement: invalid arg %s", arg));
//                    return;
//                }
//            }
//            for (int i = li.size() - 1; i >= 0; i--) {
//                task.pushFrame(0,null,li.get(i));
//            }
//        } else {
//            task.throwError(NMsg.ofC("Error statement: missing file"));
//            return;
//        }
//        task.defaultAdvance(this);
//    }
//}
