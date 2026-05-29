package net.thevpc.naru.impl.stmt;

import net.thevpc.naru.api.agent.NAruInputMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.io.NTerminal;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NNameFormat;

public class NaruReadlineStmt extends NaruSimpleStatement {

    public static final String DEFAULT_BLOCK_SEPARATOR = "≫";
    public static final String DEFAULT_LINE_SEPARATOR = "›";
    public static final String DEFAULT_PROMPT = "なる";

    public NaruReadlineStmt() {
        super(Type.READLINE);
    }

    public NaruReadlineStmt(NElement element) {
        super(Type.READLINE);
        String name;
        if (element.isName()) {
            name = element.asName().get().stringValue();
        } else if (element.isAnyObject()) {
            NObjectElement o = element.asObject().get();
            name = o.asNamed().get().name().get();
        } else {
            throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
        }
        switch (NNameFormat.CONST_NAME.format(name)) {
            case "READLINE": {
                break;
            }
            default: {
                throw new NIllegalArgumentException(NMsg.ofC("invalid element %s", element));
            }
        }
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder a = NElement.ofObjectBuilder(type.name());
        return a.build();
    }

    @Override
    public void exec(NaruSession session) {
        String line = null;
        line = NTerminal.of().readLine(NMsg.ofC("%s%s ", NMsg.ofStyledPrimary1(DEFAULT_PROMPT),
                session.inputMode()== NAruInputMode.LINE ? NMsg.ofStyledSeparator(DEFAULT_LINE_SEPARATOR): NMsg.ofStyledString(DEFAULT_BLOCK_SEPARATOR)
        ));
        if (line == null) {
            return;
        }
//        if (session.isForever()) {
//            session.pushStatement(NaruStatementHelper.ofReadLine());
//        }
        switch (session.inputMode()) {
            case LINE: {
                StringBuilder sb = new StringBuilder();
                sb.append(session.inputBuffer());
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(line);
                session.inputBuffer("");
                NaruStatement stmt = session.agent().parseStatement(sb.toString()).orNull();
                if (stmt != null) {
                    session.addStatement(stmt);
                }
                break;
            }
            case BLOC: {
                if(line.trim().equals("/buffer")) {
                    session.inputMode(session.inputMode() == NAruInputMode.LINE ? NAruInputMode.BLOC : NAruInputMode.LINE);
                }else if(line.trim().equals("/go")){
                    String b=session.inputBuffer();
                    session.inputBuffer("");
                    NaruStatement stmt = session.agent().parseStatement(b).orNull();
                    if (stmt != null) {
                        session.addStatement(stmt);
                    }
                }else {
                    StringBuilder sb = new StringBuilder();
                    sb.append(session.inputBuffer());
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(line);
                    session.inputBuffer(sb.toString());
                }
                break;
            }
        }
    }
}
