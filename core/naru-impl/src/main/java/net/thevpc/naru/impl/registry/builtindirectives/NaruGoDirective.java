package net.thevpc.naru.impl.registry.builtindirectives;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.impl.engine.stmt.shared.NaruStatementHelper;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.List;

public class NaruGoDirective extends NaruDirectiveBase {

    /**
     * What {@code /go} sends when the history holds nothing but the system prompt.
     *
     * <p>A request made of nothing but a system message has no turn for the model to
     * answer, and providers reject it -- so the first {@code /go} of a session used to
     * fail with an empty-prompt error from the far side. One word is enough to be a
     * turn; what the model does with it is its business.
     */
    static final String KICKOFF = "Begin.";

    public NaruGoDirective() {
        super("go", "general", "call model without additional prompt");
        register(new AbstractSubCommand() {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String prompt = task.inputBuffer();
                task.inputBuffer("");
                if (NBlankable.isBlank(prompt)) {
                    // The same context the model call will send, not a bare one: the
                    // conversation history is only included when NaruSource.USER is asked
                    // for, so judging a context() built from no sources at all meant
                    // judging a request that is never made -- a history ending in an
                    // unanswered tool call looked here like a system-only session.
                    NaruModelRequest cc = task.context(NaruSource.values());
                    // Summaries are replayed context rather than turns, so they are not
                    // part of what the model is being asked now. Everything else is: an
                    // assistant turn in particular is what decides whether there is
                    // anything left to do, so it cannot be filtered away here.
                    List<NaruMessage> messages = cc.messages()
                            .stream().filter(x -> x.getRole() != NaruRole.summary).toList();
                    if (messages.isEmpty()) {
                        task.log(NaruLogMode.TRACE, NMsg.ofC("nothing to do..."));
                        return NaruStmtResult.ofError("nothing to do...");
                    }
                    NaruMessage last = messages.get(messages.size() - 1);
                    if (last.getRole() == NaruRole.assistant) {
                        // The last thing that happened is an answer. Asking again would
                        // send a request whose tail is an assistant message, which is not
                        // a request.
                        task.log(NaruLogMode.TRACE, NMsg.ofC("nothing to do..."));
                        return NaruStmtResult.ofError("nothing to do...");
                    }
                    if (isSystemOnly(messages)) {
                        // The system prompt is instructions, not a turn. With nothing
                        // after it there is no question to answer, and the request that
                        // used to go out here carried an empty user message -- which is
                        // what providers answer with "you must provide a prompt".
                        prompt = KICKOFF;
                    } else {
                        // Asked before the call is made rather than discovered as a 400
                        // from the provider: the reason this tail cannot be continued is
                        // known here, and a user pressing /go deserves to be told it
                        // instead of watching a request fail.
                        NOptional<String> problem = validateTail(task, cc);
                        if (problem.isPresent()) {
                            task.log(NaruLogMode.DEBUG, NMsg.ofC("cannot go: %s", problem.get()));
                            return NaruStmtResult.ofError("cannot go: " + problem.get());
                        }
                    }
                }
                task.prependStatement(NaruStatementHelper.ofModelCall(prompt));
                return NaruStmtResult.ofSuccess(null);
            }
        });
    }

    private static NOptional<String> validateTail(NaruTask task, NaruModelRequest request) {
        try {
            NaruModelProtocol protocol = task.session().registry().protocol(task.model(), task.session()).orNull();
            if (protocol == null) {
                // no protocol resolved: whatever is wrong with that is reported by the
                // call itself, and guessing a reason here would only be wrong
                return NOptional.ofNamedEmpty("protocol");
            }
            return protocol.validateTail(request.messages());
        } catch (Exception e) {
            return NOptional.ofNamedEmpty("protocol");
        }
    }

    /**
     * Whether the caller has nothing but instructions to send.
     *
     * <p>Takes the summaries already filtered out, since a summary is replayed context
     * rather than a turn. What is left is the set of things the model has been told and
     * the things it has been asked, and when all of it is the former there is no turn for
     * it to take -- which is the state a session is in before its first {@code /go}, and
     * the one users reach it in by accident.
     */
    private static boolean isSystemOnly(List<NaruMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (NaruMessage m : messages) {
            if (m.getRole() != NaruRole.system) {
                return false;
            }
        }
        return true;
    }

}