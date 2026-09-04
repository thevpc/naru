package net.thevpc.naru.ext.models.util;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.core.NStoreKey;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NPairElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathOption;
import net.thevpc.nuts.mon.NChronometer;
import net.thevpc.nuts.net.NHttpRequest;
import net.thevpc.nuts.net.NHttpResponse;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.platform.NStoreScope;
import net.thevpc.nuts.platform.NStoreType;
import net.thevpc.nuts.text.*;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class NaruModelUtils {

    public static NMsg formatDirective(String name) {
        return NMsg.ofC("%s%s",NMsg.ofStyledSeparator("/"),NMsg.ofStyledPrimary1(name));
    }

    public static void checkValidRoutineName(String text) {
        if (!isValidRoutineName(text)) {
            throw new NIllegalArgumentException(NMsg.ofC("invalid routine name %s", text));
        }
    }

    public static boolean isValidRoutineName(String text) {
        if (text == null || text.isEmpty()) return false;

        char first = text.charAt(0);
        if (!Character.isLetter(first) && first != '_') return false;
        if (text.length() == 1) return Character.isLetter(first);
        boolean prevWasDash = false;
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '-') {
                if (prevWasDash) return false;
                prevWasDash = true;
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                prevWasDash = false;
            } else {
                return false;
            }
        }
        // last char was '-' → prevWasDash is true
        return !prevWasDash;
    }


    public static Map<String, NElement> parseEnv(String msg) {
        if (NBlankable.isBlank(msg)) {
            return Collections.emptyMap();
        }
        NElement aa = NElementReader.ofTson().read(msg);
        return parseEnv(aa);
    }

    public static Map<String, NElement> parseEnv(NElement aa) {
        if (aa == null) {
            return new LinkedHashMap<>();
        }
        Map<String, NElement> env = new HashMap<>();
        if (aa.isNamedPair()) {
            NPairElement p = aa.asPair().get();
            env.put(p.key().asStringValue().orNull(), p.value());
        } else if (aa.isListContainer()) {
            for (NPairElement p : aa.asListContainer().get().namedPairs()) {
                env.put(p.key().asStringValue().orNull(), p.value());
            }
        } else if (aa.isFragment()) {
            for (NPairElement p : aa.asFragment().get().namedPairs()) {
                env.put(p.key().asStringValue().orNull(), p.value());
            }
        }
        return env;
    }

    private static void log(NMsg msg) {
        NPath path = NPath.of(NStoreKey.of(
                NStoreScope.WORKSPACE,
                NStoreType.LOG,
                NId.of("net.thevpc.naru:naru").sharedId(),
                "naru.log"
        ));
        path.mkParentDirs().writeString(msg.toFullString() + "\n", NPathOption.APPEND, NPathOption.CREATE);
    }

    public static String abbreviate(String s, int max) {
        if (s == null) return "(null)";
        s = s.replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public static void logWebRequest(NHttpRequest request, NMsg text, Object input) {
        NTextBuilder sb = NTextBuilder.of();
        sb.append(NMsg.ofStyledPath(request.effectiveUri()));
        sb.append(" : ");
        sb.append(text);
        sb.append(" : ");
        if (input != null) {
            sb.append("\n REQUEST : ");
            sb.append("\n").append(NElementWriter.ofTson().formatPlain(input));
        }
        log(NMsg.ofC("%s", sb.build()));
    }

    public static void logWebResponse(NHttpRequest request, NMsg text, Object input, Object output, NChronometer chronometer) {
        NTextBuilder sb = NTextBuilder.of();
        sb.append(NMsg.ofStyledPath(request.effectiveUri()));
        sb.append(" : ");
        sb.append(text);
        sb.append(" : ");
        if (input != null) {
            sb.append("\n REQUEST : ");
            sb.append("\n").append(NElementWriter.ofTson().formatPlain(input));
        }
        if (output != null) {
            sb.append("\n RESPONSE : ");
            sb.append("\n").append(NElementWriter.ofTson().formatPlain(output));
        }
        log(NMsg.ofC("%s", sb.build()).withDuration(chronometer.duration()));
    }

    public static NDuration parseRetryAfter(NHttpResponse response) {
        if (response == null) return null;
        String header = response.header("retry-after").orNull();
        if (NBlankable.isBlank(header)) {
            header = response.header("Retry-After").orNull();
        }
        if (NBlankable.isBlank(header)) {
            header = response.header("retry-after-ms").orNull();
            if (NBlankable.isNonBlank(header)) {
                try {
                    long ms = Long.parseLong(header.trim());
                    return NDuration.ofMillis(Math.max(100, ms));
                } catch (Exception ignored) {
                }
            }
            return null;
        }
        header = header.trim();
        // 1. Try integer seconds
        try {
            long seconds = Long.parseLong(header);
            return NDuration.ofSeconds(Math.max(1, seconds));
        } catch (NumberFormatException ignored) {
        }
        // 2. Try decimal seconds (e.g. 1.5)
        try {
            double seconds = Double.parseDouble(header);
            return NDuration.ofMillis((long) (Math.max(0.1, seconds) * 1000));
        } catch (NumberFormatException ignored) {
        }
        // 3. Try RFC 1123 HTTP Date
        try {
            java.time.ZonedDateTime serverTime = java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            Instant target = serverTime.toInstant();
            Instant now = Instant.now();
            if (target.isAfter(now)) {
                return NDuration.ofMillis(Duration.between(now, target).toMillis());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static List<NPath> resolveTaskAuditPaths(NaruTask task, net.thevpc.naru.api.agent.NaruSession session) {
        List<NPath> paths = new ArrayList<>();
        final net.thevpc.naru.api.agent.NaruSession finalSession = (session == null && task != null) ? task.session() : session;
        long taskId = task != null ? task.id() : 0;
        String sessionUuid = finalSession != null ? finalSession.uuid() : "default";

        // Check if custom audit dir configured in env
        if (finalSession != null) {
            String customDir = finalSession.agent().env().get("audit.dir")
                    .flatMap(NElement::asStringValue)
                    .orElseGetOptionalFrom(() -> finalSession.agent().env().get("llm.audit.dir").flatMap(NElement::asStringValue))
                    .orNull();
            if (NBlankable.isNonBlank(customDir)) {
                paths.add(NPath.of(customDir).resolve("task-" + taskId + ".tson"));
                return paths;
            }
        }

        // 1. Session audit folder in projectDir
        if (finalSession != null && finalSession.projectDir() != null) {
            paths.add(finalSession.projectDir().resolve(".naru/local/sessions").resolve(sessionUuid).resolve("audit").resolve("task-" + taskId + ".tson"));
            paths.add(finalSession.projectDir().resolve(".naru/local/logs").resolve("task-" + taskId + "-llm-audit.tson"));
        } else {
            // 2. Workspace log fallback
            paths.add(NPath.of(NStoreKey.of(
                    NStoreScope.WORKSPACE,
                    NStoreType.LOG,
                    NId.of("net.thevpc.naru:naru").sharedId(),
                    "audit/task-" + taskId + ".tson"
            )));
        }
        return paths;
    }

    public static String maskHeaderValue(String name, String value) {
        if (value == null) return null;
        if ("authorization".equalsIgnoreCase(name) || "x-api-key".equalsIgnoreCase(name) || "api-key".equalsIgnoreCase(name)) {
            if (value.regionMatches(true, 0, "bearer ", 0, 7)) {
                String token = value.substring(7).trim();
                if (token.length() > 8) {
                    return "Bearer " + token.substring(0, 4) + "..." + token.substring(token.length() - 3);
                }
                return "Bearer ***";
            }
            if (value.length() > 8) {
                return value.substring(0, 4) + "..." + value.substring(value.length() - 3);
            }
            return "***";
        }
        return value;
    }

    public static void logAudit(NaruTask task,
                                net.thevpc.naru.api.agent.NaruSession session,
                                String provider,
                                String model,
                                NHttpRequest request,
                                Object requestBody,
                                NHttpResponse response,
                                Object responseBody,
                                Throwable error,
                                int attempt,
                                NDuration duration,
                                Instant requestTime) {
        try {
            if (session == null && task != null) {
                session = task.session();
            }
            Map<String, Object> auditMap = new LinkedHashMap<>();
            auditMap.put("id", UUID.randomUUID().toString());
            auditMap.put("timestamp", requestTime != null ? requestTime.toString() : Instant.now().toString());
            if (task != null) {
                auditMap.put("taskId", task.id());
                if (task.name() != null) {
                    auditMap.put("taskName", task.name());
                }
            }
            if (session != null) {
                auditMap.put("sessionUuid", session.uuid());
            }
            if (provider != null) {
                auditMap.put("provider", provider);
            }
            if (model != null) {
                auditMap.put("model", model);
            }
            if (request != null) {
                auditMap.put("url", request.effectiveUri());
                auditMap.put("method", request.method() != null ? request.method().toString() : "POST");

                Map<String, Object> reqHeaders = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
                    String k = entry.getKey();
                    List<String> values = entry.getValue();
                    if (values != null && !values.isEmpty()) {
                        reqHeaders.put(k, maskHeaderValue(k, values.get(0)));
                    }
                }
                auditMap.put("requestHeaders", reqHeaders);
            }
            if (requestBody != null) {
                auditMap.put("requestBody", requestBody);
            }

            auditMap.put("attempt", attempt);
            if (duration != null) {
                auditMap.put("durationMs", duration.toMillis());
            }

            if (response != null) {
                auditMap.put("statusCode", response.intStatusCode());
                auditMap.put("statusMessage", response.statusMessage() != null ? response.statusMessage().toString() : "");

                Map<String, Object> respHeaders = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> entry : response.headers().entrySet()) {
                    String k = entry.getKey();
                    List<String> values = entry.getValue();
                    if (values != null && !values.isEmpty()) {
                        respHeaders.put(k, values.size() == 1 ? values.get(0) : values);
                    }
                }
                auditMap.put("responseHeaders", respHeaders);
            }

            if (responseBody != null) {
                auditMap.put("responseBody", responseBody);
            }

            if (error != null) {
                Map<String, Object> errorMap = new LinkedHashMap<>();
                errorMap.put("type", error.getClass().getName());
                errorMap.put("message", error.getMessage());
                auditMap.put("error", errorMap);
            }

            String tsonRecord = NElementWriter.ofTson().formatPlain(auditMap);
            List<NPath> auditPaths = resolveTaskAuditPaths(task, session);
            for (NPath p : auditPaths) {
                p.mkParentDirs().writeString(tsonRecord + "\n\n", NPathOption.APPEND, NPathOption.CREATE);
            }
        } catch (Exception ex) {
            // Guard against failure in audit logging to never crash the main LLM flow
            log(NMsg.ofC("Failed to write audit log: %s", ex.getMessage()));
        }
    }



    public static boolean isPath(String command) {
        String a = command.trim();
        if (a.indexOf('/') >= 0) {
            for (int i = 0; i < a.length(); i++) {
                char c = a.charAt(i);
                switch (c) {
                    case ' ':
                    case ':':
                    case '\t':
                    case '(':
                    case ')':
                    case '[':
                    case ']':
                    case '$':
                    case ',':
                    case ';':
                    case '=':
                    case '#': {
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }


    public static class LineRange {
        private final int from;
        private final int to;

        public LineRange(int from, int to) {
            this.from = from;
            this.to = to;
        }

        public LineRange(int from) {
            this.from = from;
            this.to = from;
        }
    }

    public static Set<Integer> resolveIndexes(LineRange[] ranges, int count) {
        Set<Integer> toShow = new HashSet<>();
        for (LineRange r : ranges) {
            int from = r.from;
            int to = r.to;
            if (from < 0) {
                from = count + from;
            }
            if (to < 0) {
                to = count + to;
            }
            for (int i = from; i <= to; i++) {
                toShow.add(i);
            }
        }
        return toShow;
    }

    public static List<LineRange> parseRanges(NCmdLine cmdLine) {
        List<LineRange> toShow = new ArrayList<>();
        while (!cmdLine.isEmpty()) {
            String a = cmdLine.next().get().image();
            for (String range : a.split(",;")) {
                range = range.trim();
                if (!range.isEmpty()) {
                    if (range.matches("[0-9]+")) {
                        toShow.add(new LineRange(Integer.parseInt(range) - 1));
                    } else if (range.matches("[0-9]+[-][0-9]+")) {
                        String[] ss = range.split("-");
                        int x = Integer.parseInt(ss[0]);
                        int y = Integer.parseInt(ss[1]);
                        toShow.add(new LineRange(x, y));
                    } else if (range.matches("[-]?[0-9]+[.][.][-]?[0-9]+")) {
                        String[] ss = range.split("[.][.]");
                        int x = Integer.parseInt(ss[0]);
                        int y = Integer.parseInt(ss[1]);
                        toShow.add(new LineRange(x, y));
                    } else if (range.matches("[-]?[0-9]+[.][.][.][-]?[0-9]+")) {
                        String[] ss = range.split("[.][.][.]");
                        int x = Integer.parseInt(ss[0]);
                        int y = Integer.parseInt(ss[1]);
                        toShow.add(new LineRange(x, y));
                    } else if (range.matches("[-]?[0-9]+[.][.][.]")) {
                        int x = Integer.parseInt(range.substring(0, range.length() - 3));
                        toShow.add(new LineRange(x, Integer.MAX_VALUE));
                    } else if (range.matches("[.][.][.][-]?[0-9]+")) {
                        int x = Integer.parseInt(range.substring(3));
                        toShow.add(new LineRange(1, x));
                    } else {
                        throw new IllegalArgumentException("invalid position to drop");
                    }
                }
            }
        }
        return toShow;
    }




}
