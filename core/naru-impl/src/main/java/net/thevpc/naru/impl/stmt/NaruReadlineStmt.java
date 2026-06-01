package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NAruInputMode;
import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.agent.NaruTaskSchedulerView;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.impl.scheduler.NaruInputRequest;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.io.NTerminal;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

public class NaruReadlineStmt extends NaruStatement implements Cloneable{

    public static final String DEFAULT_BLOCK_SEPARATOR = "≫";
    public static final String DEFAULT_LINE_SEPARATOR = "›";
    public static final String DEFAULT_PROMPT = "なる";
    private boolean runtimeWaiting = false; // false=first time, true=resumed
    public NaruReadlineStmt() {
        super(Type.READLINE);
    }

    public NaruReadlineStmt(NElement element) {
        super(Type.READLINE,element);
    }


    @Override
    public void exec(NaruTask task) {

        if (!runtimeWaiting) {
            // Phase A — first time: block and wait for input
            NaruReadlineStmt selfCopy = (NaruReadlineStmt) copy();
            selfCopy.runtimeWaiting = true;

            // prepend self copy — will process input when resumed
            task.prependStatement(selfCopy);
            task.requestInput(NMsg.ofC("%s%s ", NMsg.ofStyledPrimary1(DEFAULT_PROMPT),
                task.inputMode()== NAruInputMode.LINE ? NMsg.ofStyledSeparator(DEFAULT_LINE_SEPARATOR): NMsg.ofStyledString(DEFAULT_BLOCK_SEPARATOR)
            ));
            return;
        }

        String line =task.consumeInput();

        if (line == null) {
            task.defaultAdvance(this);
            return;
        }

        switch (task.inputMode()) {
            case LINE: {
                StringBuilder sb = new StringBuilder();
                sb.append(task.inputBuffer());
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(line);
                task.inputBuffer("");
                NaruStatement stmt = task.parseStatement(sb.toString()).orNull();
                if (stmt != null) {
                    task.addStatement(stmt);
                }
                task.defaultAdvance(this);
                break;
            }
            case BLOC: {
                if(line.trim().equals("/buffer")) {
                    task.inputMode(task.inputMode() == NAruInputMode.LINE ? NAruInputMode.BLOC : NAruInputMode.LINE);
                }else if(line.trim().equals("/go")){
                    String b= task.inputBuffer();
                    task.inputBuffer("");
                    NaruStatement stmt = task.parseStatement(b).orNull();
                    if (stmt != null) {
                        task.addStatement(stmt);
                    }
                }else {
                    StringBuilder sb = new StringBuilder();
                    sb.append(task.inputBuffer());
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(line);
                    task.inputBuffer(sb.toString());
                }
                task.defaultAdvance(this);
                break;
            }
            default:{
                task.defaultAdvance(this);
            }
        }
    }
}
