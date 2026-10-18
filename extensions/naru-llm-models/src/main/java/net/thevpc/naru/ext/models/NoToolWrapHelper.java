package net.thevpc.naru.ext.models;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruToolSchema;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NoToolWrapHelper {
    //    public static final Separators TOOL_CALL = new Separators("<tool_call>", "</tool_call>");
    public static final Separators TOOL_CALL_SEP = new Separators("<|tool_call|>", "<|end_tool_call|>");
    public static final Separators TOOL_RESULT_SEP = new Separators("<|tool_result|>", "<|end_tool_result|>");

    /**
     * Request/agent env keys a per-model {@code .naru/models/*.md} front matter
     * may use to spell the tags a model actually emits. They were documented but
     * previously ignored: the emulator always used its own constants, so a model
     * with a different tag could not be matched even with the override file.
     */
    public static final String ENV_TOOL_CALL_START = "tool_call_start";
    public static final String ENV_TOOL_CALL_END = "tool_call_end";
    public static final String ENV_TOOL_RESULT_START = "tool_result_start";
    public static final String ENV_TOOL_RESULT_END = "tool_result_end";

    /**
     * The call-tag pair this request emulates: the {@code tool_call_start/end}
     * overrides when both are set, otherwise {@link #TOOL_CALL_SEP}.
     */
    public static Separators forToolCalls(Map<String, NElement> env) {
        return resolve(env, ENV_TOOL_CALL_START, ENV_TOOL_CALL_END, TOOL_CALL_SEP);
    }

    /**
     * The result-tag pair this request emulates: the {@code tool_result_start/end}
     * overrides when both are set, otherwise {@link #TOOL_RESULT_SEP}.
     */
    public static Separators forToolResults(Map<String, NElement> env) {
        return resolve(env, ENV_TOOL_RESULT_START, ENV_TOOL_RESULT_END, TOOL_RESULT_SEP);
    }

    /**
     * A tag override must name both ends to be usable; a half-configured pair
     * (one end missing) falls back to the default rather than producing a
     * separator that can never close.
     */
    private static Separators resolve(Map<String, NElement> env, String startKey, String endKey, Separators defaults) {
        if (env == null) {
            return defaults;
        }
        String start = stringValue(env.get(startKey));
        String end = stringValue(env.get(endKey));
        if (!NBlankable.isBlank(start) && !NBlankable.isBlank(end)) {
            return new Separators(start.trim(), end.trim());
        }
        return defaults;
    }

    private static String stringValue(NElement e) {
        return e == null || e.isNull() ? null : e.asStringValue().orNull();
    }


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
                                NElementWriter.ofJson().formatPlain(NElement.of(t))
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
                    toolsPrompt.append("Parameters (JSON Schema):\n");
                    // The same schema the tool-capable protocols send on the wire.
                    // Serialising the parameter objects directly used to dump their
                    // Java getters into the prompt -- "type":"STRING", "itemType":{...},
                    // requiredness carried per field instead of in a schema-level
                    // list -- which is a description of the class, not of the shape
                    // the model has to produce.
                    toolsPrompt.append(NElementWriter.ofJson().formatPlain(
                            NaruToolSchema.functionSchema(f.getParams())));
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

        public String open() {
            return open;
        }

        public String close() {
            return close;
        }
    }

    /**
     * Recover a tool call that a model wrote as plain text instead of through the
     * wire's native tool-call field. Uses the default separators and is safe to
     * call on any answer: a response that contains no emulated block and no
     * bare tool-call object is returned unchanged.
     *
     * <p>This is what a provider-native parser calls when it assembled the
     * response as text -- a model that ignores both the native tool API and the
     * emulation tags. It must not depend on {@code emulate_tool_calls} being set
     * in the request env, because that comes from a per-model front matter file
     * that may not have been resolved; the response itself is the evidence.
     */
    public static NaruResponse recoverPlainTextToolCalls(NaruResponse naruResponse) {
        return unwrapResponse(naruResponse, TOOL_CALL_SEP, null);
    }

    public static NaruResponse unwrapResponse(NaruResponse naruResponse, Separators openClose, NaruTask task) {
        NaruMessage msg = naruResponse.getMessage();
        ParsedEmulatedResponse parsed = parseEmulatedToolCalls(msg.getContent(), openClose, task);
        if (!parsed.calls.isEmpty()) {
            // withContent keeps the provenance and, importantly, the reasoning
            // channel the parser may have filled (thinking / thinkingSegments) --
            // the bare constructor used before dropped it, so an emulated call
            // from a reasoning model lost its own account of the call.
            NaruMessage rebuiltMessage = msg.withContent(parsed.cleanContent);
            rebuiltMessage.setToolCalls(parsed.calls);
            NaruResponse rebuilt = new NaruResponse(
                    rebuiltMessage,
                    naruResponse.isDone(),
                    naruResponse.getStopReason(),
                    naruResponse.getTotalTokens(),
                    naruResponse.getPromptTokens(),
                    naruResponse.getEvalTokens()
            );
            // The delegating constructor has no cache parameters, so carry the
            // provider's cache breakdown across explicitly. Dropping it here made
            // every emulated-tool-call turn look uncached and mispriced.
            rebuilt.setCacheReadTokens(naruResponse.getCacheReadTokens());
            rebuilt.setCacheWriteTokens(naruResponse.getCacheWriteTokens());
            return rebuilt;
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
                call.setArguments(argsEl != null ? (Map) argsEl.convertTo(Map.class) : new HashMap<>());
                calls.add(call);
            } catch (Exception e) {
                if (task != null) {
                    task.log(NaruLogMode.AGENT_RESPONSE,
                            NMsg.ofC("Failed to parse emulated tool_call: %s", json));
                }
            }
            matcher.appendReplacement(clean, "");
        }
        matcher.appendTail(clean);

        String cleanContent = clean.toString().trim();
        // The sentinels are a request, not a guarantee: small instruct models
        // ignore them and return the very objects the prompt asked for as bare
        // JSON (qwen2.5-coder), often several of them back to back. Recover
        // every such object the tag scan did not already cover -- without this
        // the user sees raw tool-call objects as the assistant's answer and the
        // turn stalls. Objects that are not tool calls are left where they are.
        ParsedEmulatedResponse bare = parseBareJsonToolCalls(cleanContent);
        if (bare != null) {
            calls.addAll(bare.calls);
            cleanContent = bare.cleanContent == null ? "" : bare.cleanContent;
        }
        return new ParsedEmulatedResponse(cleanContent.isEmpty() ? null : cleanContent, calls);
    }

    /**
     * Recovers the tool calls a model wrote as bare JSON objects -- in either
     * the emulated spelling ({@code {"name":..., "arguments":...}}) or the one
     * {@link #wrapRequest} also accepts ({@code {"tool":..., "args":...}}) --
     * and returns them together with whatever text surrounded them.
     *
     * <p>Scans for balanced {@code {...}} spans (string-aware, so a brace inside
     * an argument is not mistaken for the end) rather than requiring the whole
     * answer to be one object: a model that emits two calls in a row produced
     * {@code {"name":"pwd",...}\n{"name":"folder_find",...}}, which is not valid
     * JSON as a whole. Only objects that name a tool <em>and</em> carry an
     * argument field are taken, so an ordinary JSON answer is left as text.
     *
     * @return the recovered calls and the leftover text, or {@code null} when the
     *         content holds no bare tool-call object
     */
    private static ParsedEmulatedResponse parseBareJsonToolCalls(String content) {
        if (content == null || content.indexOf('{') < 0) {
            return null;
        }
        List<NaruToolCall> calls = new ArrayList<>();
        StringBuilder remainder = new StringBuilder();
        int lastCopied = 0;
        int i = 0;
        int n = content.length();
        while (i < n) {
            if (content.charAt(i) != '{') {
                i++;
                continue;
            }
            int end = matchingBrace(content, i);
            if (end < 0) {
                break;
            }
            NaruToolCall call = parseBareJsonToolCall(content.substring(i, end + 1));
            if (call != null) {
                remainder.append(content, lastCopied, i);
                calls.add(call);
                lastCopied = end + 1;
                i = end + 1;
            } else {
                i++;
            }
        }
        if (calls.isEmpty()) {
            return null;
        }
        remainder.append(content, lastCopied, n);
        String left = remainder.toString().trim();
        return new ParsedEmulatedResponse(left.isEmpty() ? null : left, calls);
    }

    /**
     * The index of the {@code }} closing the {@code {} at {@code open}, honouring
     * string literals and escapes; {@code -1} when the object never closes.
     */
    private static int matchingBrace(String s, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Parses one complete JSON object as a tool call, or {@code null} when it is
     * not one. Only an object that names a tool <em>and</em> carries an argument
     * field qualifies, so an ordinary JSON answer is left as assistant text.
     */
    private static NaruToolCall parseBareJsonToolCall(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null;
        }
        try {
            NElement el = NElementReader.ofJson().read(trimmed);
            if (!el.isAnyObject()) {
                return null;
            }
            NObjectElement obj = el.asObject().get();
            String name = obj.getStringValue("name").orElse(
                    obj.getStringValue("tool").orNull());
            NElement argsEl = obj.get("arguments").orElse(obj.get("args").orNull());
            if (NBlankable.isBlank(name) || argsEl == null) {
                return null;
            }
            NaruToolCall call = new NaruToolCall();
            call.setId("emulated-" + UUID.randomUUID().toString().substring(0, 8));
            call.setName(name);
            call.setArguments((Map) argsEl.convertTo(Map.class));
            return call;
        } catch (Exception e) {
            return null;
        }
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
