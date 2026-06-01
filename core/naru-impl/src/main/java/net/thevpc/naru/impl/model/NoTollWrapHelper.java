package net.thevpc.naru.impl.model;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.model.*;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.text.NMsg;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NoTollWrapHelper {
    //    public static final Separators TOOL_CALL = new Separators("<tool_call>", "</tool_call>");
    public static final Separators TOOL_CALL_SEP = new Separators("<|tool_call|>", "<|end_tool_call|>");
    public static final Separators TOOL_RESULT_SEP = new Separators("<|tool_result|>", "<|end_tool_result|>");


    public static NaruModelRequest wrapRequest(NaruModelRequest mrequest, Separators callSep, Separators resultSep) {
        List<NaruMessage> messages = new ArrayList<>(mrequest.messages());
        for (int i = 0; i < messages.size(); i++) {
            NaruMessage message = messages.get(i);
            if(message.getRole()== NaruRole.assistant) {
                message = message.copy();
                if(message.getToolCalls()!=null && !message.getToolCalls().isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    if(message.getContent()!=null){
                        sb.append(message.getContent());
                        sb.append("\n");
                    }
                    for (NaruToolCall toolCall : message.getToolCalls()) {
                        sb.append(callSep.open+"\n");
                        Map<String, Object> t = new HashMap<>();
                        t.put("tool", toolCall.getName());
                        t.put("args", new HashMap<>(toolCall.getArguments()));
                        sb.append(
                                NElementWriter.ofJson().formatPlain(NElements.of().toElement(t))
                        ).append("\n");
                        sb.append(callSep.close+"\n");
                    }
                    message.setContent(sb.toString());
                    message.setToolCalls(null);
                    messages.set(i,message);
                }
            }
            if(message.getRole()== NaruRole.tool) {
                message = message.copy();
                message.setRole(NaruRole.user);
                message.setContent(
                        resultSep.open+"\n"+
                                "tool: " + message.getToolName()+"\n"+
                                message.getContent()+"\n"+
                                resultSep.close+"\n"
                );
                message.setToolCallId(null);
                message.setToolName(null);
                messages.set(i,message);
            }
        }
        if(!mrequest.tools().isEmpty()) {
            StringBuilder toolsPrompt = new StringBuilder();

            for (NaruToolDefinition tool : mrequest.tools()) {
                toolsPrompt.append("You have access to the following tools:\n\n");
                toolsPrompt.append("### ").append(tool.getName()).append("\n");
                toolsPrompt.append(tool.getDescription()).append("\n");
                if (tool instanceof NaruToolDefinitionFunction) {
                    NaruToolDefinitionFunction f = (NaruToolDefinitionFunction) tool;
                    toolsPrompt.append("Parameters:\n");
                    // serialize the parameters schema as JSON so the model understands the shape
                    toolsPrompt.append(NElementWriter.ofJson().formatPlain(f.getParams()));
                    toolsPrompt.append("\n\n");
                }
            }
            toolsPrompt.append("When you need to use a tool, output ONLY a JSON block like this:\n" +
                    "\n" +
                    callSep.open + "\n" +
                    "{\"tool\": \"file_read\", \"args\": {\"path\": \"/some/file.java\"}}\n" +
                    callSep.close + "\n" +
                    "\n" +
                    "Wait. The result will be provided as " + resultSep.open + "..." + resultSep.close + ".\n" +
                    "Then continue your reasoning.");
            messages.add(NaruMessage.system(toolsPrompt.toString()));
        }
        return new NaruModelRequest(messages, Collections.emptyList(),mrequest.env());
    }

    public static class Separators {
        private final String open;
        private final String close;
        private final Pattern pattern;

        public Separators(String open, String close) {
            this.open = open;
            this.close = close;
            this.pattern = Pattern.compile(
                    Pattern.quote(open) + "(.*?)" + Pattern.quote(close),
                    Pattern.DOTALL
            );
        }
    }

    public static NaruResponse unwrapResponse(NaruResponse naruResponse, Separators openClose, NaruTask task) {
        NaruMessage msg = naruResponse.getMessage();
        ParsedEmulatedResponse parsed = parseEmulatedToolCalls(msg.getContent(), openClose, task);
        if (!parsed.calls.isEmpty()) {
            return new NaruResponse(
                    new NaruMessage(msg.getSourceName(),
                            msg.getSource(),
                            msg.getRole(),
                            parsed.cleanContent,
                            msg.getImages(),
                            msg.getToolCallId(),
                            msg.getToolName(),
                            parsed.calls),
                    naruResponse.isDone(),
                    naruResponse.getStopReason(),
                    naruResponse.getTotalTokens(),
                    naruResponse.getPromptTokens(),
                    naruResponse.getEvalTokens()
            );
        }
        return naruResponse;
    }

    private static ParsedEmulatedResponse parseEmulatedToolCalls(
            String content, Separators separators, NaruTask task) {
        List<NaruToolCall> calls = new ArrayList<>();
        if (content == null) return new ParsedEmulatedResponse(null, calls);

        Pattern pattern = separators.pattern;
        Matcher matcher = pattern.matcher(content);
        StringBuffer clean = new StringBuffer();

        while (matcher.find()) {
            String json = matcher.group(1).trim();
            try {
                NElement el = NElementReader.ofJson().read(json);
                NObjectElement obj = el.asObject().get();
                String toolName = obj.getStringValue("name").orElse(
                        obj.getStringValue("tool").orNull()  // fallback for deepseek
                );
                NElement argsEl = obj.get("arguments").orElse(
                        obj.get("args").orNull()              // fallback for deepseek
                );
                NaruToolCall call = new NaruToolCall();
                call.setId("emulated-" + UUID.randomUUID().toString().substring(0, 8));
                call.setName(toolName);
                call.setArguments(argsEl != null ? (Map) NElements.of().fromElement(argsEl,Map.class) : new HashMap<>());
                calls.add(call);
            } catch (Exception e) {
                task.log(NaruLogMode.AGENT_RESPONSE,
                        NMsg.ofC("Failed to parse emulated tool_call: %s", json));
            }
            matcher.appendReplacement(clean, "");
        }
        matcher.appendTail(clean);

        String cleanContent = clean.toString().trim();
        return new ParsedEmulatedResponse(cleanContent.isEmpty() ? null : cleanContent, calls);
    }

    static class ParsedEmulatedResponse {
        final String cleanContent;
        final List<NaruToolCall> calls;

        ParsedEmulatedResponse(String cleanContent, List<NaruToolCall> calls) {
            this.cleanContent = cleanContent;
            this.calls = calls;
        }
    }
}
