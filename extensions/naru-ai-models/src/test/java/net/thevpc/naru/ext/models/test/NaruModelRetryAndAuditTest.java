package net.thevpc.naru.ext.models.test;

import net.thevpc.naru.api.model.*;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.ext.models.NaruModelProtocolBase;
import net.thevpc.naru.ext.models.openapi.NaruOpenApiRequestSerializer;
import net.thevpc.naru.ext.models.openapi.NaruOpenApiResponseParser;
import net.thevpc.naru.ext.models.util.NaruModelUtils;
import net.thevpc.nuts.concurrent.NRetryCall;
import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.io.NInputSource;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.net.NHttpCode;
import net.thevpc.nuts.net.NHttpCookie;
import net.thevpc.nuts.net.NHttpResponse;
import net.thevpc.nuts.net.NHttpResponseException;
import net.thevpc.nuts.text.NContentType;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NMsgCode;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class NaruModelRetryAndAuditTest {

    @BeforeAll
    public static void setUp() {
        try {
            net.thevpc.nuts.core.NWorkspace ws = net.thevpc.nuts.Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                net.thevpc.nuts.core.NWorkspace ws = net.thevpc.nuts.Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void testParseRetryAfterHeaders() {
        // 1. Integer seconds
        NHttpResponse respSec = new MockHttpResponse(429, Map.of("retry-after", List.of("60")));
        NDuration d1 = NaruModelUtils.parseRetryAfter(respSec);
        Assertions.assertNotNull(d1);
        Assertions.assertEquals(60, d1.toSeconds());

        // 2. Decimal seconds
        NHttpResponse respDec = new MockHttpResponse(429, Map.of("Retry-After", List.of("2.5")));
        NDuration d2 = NaruModelUtils.parseRetryAfter(respDec);
        Assertions.assertNotNull(d2);
        Assertions.assertEquals(2500, d2.toMillis());

        // 3. Milliseconds header
        NHttpResponse respMs = new MockHttpResponse(429, Map.of("retry-after-ms", List.of("1500")));
        NDuration d3 = NaruModelUtils.parseRetryAfter(respMs);
        Assertions.assertNotNull(d3);
        Assertions.assertEquals(1500, d3.toMillis());

        // 4. RFC 1123 HTTP Date
        String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now().plusSeconds(10));
        NHttpResponse respDate = new MockHttpResponse(429, Map.of("retry-after", List.of(httpDate)));
        NDuration d4 = NaruModelUtils.parseRetryAfter(respDate);
        Assertions.assertNotNull(d4);
        Assertions.assertTrue(d4.toSeconds() >= 8 && d4.toSeconds() <= 12);
    }

    @Test
    public void testHeaderMasking() {
        Assertions.assertEquals("Bearer sk-1...xyz", NaruModelUtils.maskHeaderValue("Authorization", "Bearer sk-1234567890xyz"));
        Assertions.assertEquals("Bearer ***", NaruModelUtils.maskHeaderValue("authorization", "Bearer short"));
        Assertions.assertEquals("secr...789", NaruModelUtils.maskHeaderValue("x-api-key", "secrettoken123456789"));
        Assertions.assertEquals("application/json", NaruModelUtils.maskHeaderValue("Content-Type", "application/json"));
    }

    @Test
    public void testTsonAuditLogging() {
        NPath tempDir = NPath.ofTempFolder("naru-audit-test");
        NPath auditFile = tempDir.resolve("audit/task-42.tson");

        Map<String, Object> reqBody = Map.of("model", "llama-3.3-70b", "messages", List.of(Map.of("role", "user", "content", "Hello!")));
        Map<String, Object> respBody = Map.of("id", "chatcmpl-123", "choices", List.of(Map.of("message", Map.of("role", "assistant", "content", "Hi there!"))));

        Map<String, Object> auditRecord = new LinkedHashMap<>();
        auditRecord.put("id", UUID.randomUUID().toString());
        auditRecord.put("timestamp", Instant.now().toString());
        auditRecord.put("taskId", 42L);
        auditRecord.put("provider", "groq");
        auditRecord.put("model", "llama-3.3-70b");
        auditRecord.put("url", "https://api.groq.com/openai/v1/chat/completions");
        auditRecord.put("method", "POST");
        auditRecord.put("attempt", 1);
        auditRecord.put("statusCode", 200);
        auditRecord.put("durationMs", 230L);
        auditRecord.put("requestBody", reqBody);
        auditRecord.put("responseBody", respBody);

        String tsonText = NElementWriter.ofTson().formatPlain(auditRecord);
        auditFile.mkParentDirs().writeString(tsonText + "\n\n", net.thevpc.nuts.io.NPathOption.CREATE);

        Assertions.assertTrue(auditFile.exists());
        String content = auditFile.readString();
        Assertions.assertFalse(content.isEmpty());

        // Parse back with TSON reader
        NElement parsed = NElementReader.ofTson().read(content);
        Assertions.assertTrue(parsed.isAnyObject());
        NObjectElement obj = parsed.asObject().get();
        Assertions.assertEquals(42L, obj.getLongValue("taskId").orElse(0L));
        Assertions.assertEquals("groq", obj.getStringValue("provider").orElse(""));
        Assertions.assertEquals(200, obj.getIntValue("statusCode").orElse(0));
    }

    @Test
    public void testToolParameterSchemaGeneration() {
        NaruToolParameter paramString = NaruToolParameter.string("query", "Search term", true).defaultValue("test").build();
        NElement schemaString = NaruOpenApiRequestSerializer.paramToSchema(paramString);
        Assertions.assertEquals("string", schemaString.asObject().get().getStringValue("type").orElse(""));
        Assertions.assertEquals("Search term", schemaString.asObject().get().getStringValue("description").orElse(""));
        Assertions.assertEquals("test", schemaString.asObject().get().getStringValue("default").orElse(""));

        NaruToolParameter paramArray = NaruToolParameter.array("tags", "List of tags", true, NaruToolParameter.string("tag", "single tag", true).build()).build();
        NElement schemaArray = NaruOpenApiRequestSerializer.paramToSchema(paramArray);
        Assertions.assertEquals("array", schemaArray.asObject().get().getStringValue("type").orElse(""));
        Assertions.assertTrue(schemaArray.asObject().get().getObject("items").isPresent());
        Assertions.assertEquals("string", schemaArray.asObject().get().getObject("items").get().getStringValue("type").orElse(""));

        NaruToolParameter paramEnum = NaruToolParameter.string("mode", "Operation mode", false).enumValues("fast", "precise").build();
        NElement schemaEnum = NaruOpenApiRequestSerializer.paramToSchema(paramEnum);
        Assertions.assertTrue(schemaEnum.asObject().get().getArray("enum").isPresent());
        Assertions.assertEquals(2, schemaEnum.asObject().get().getArray("enum").get().size());
    }

    @Test
    public void testToolArgumentsParsingWithMarkdownAndWhitespace() {
        // Plain JSON
        NElement el1 = NElement.ofString("{\"path\":\"/home/user/file.txt\",\"line\":10}");
        Map<String, Object> args1 = NaruOpenApiResponseParser.parseArguments(el1);
        Assertions.assertEquals("/home/user/file.txt", args1.get("path"));
        Assertions.assertEquals(10, ((Number) args1.get("line")).intValue());

        // Markdown-wrapped JSON
        NElement el2 = NElement.ofString("```json\n{\"command\":\"ls -la\",\"timeout\":30}\n```");
        Map<String, Object> args2 = NaruOpenApiResponseParser.parseArguments(el2);
        Assertions.assertEquals("ls -la", args2.get("command"));
        Assertions.assertEquals(30, ((Number) args2.get("timeout")).intValue());

        // Empty / whitespace
        NElement el3 = NElement.ofString("  ");
        Map<String, Object> args3 = NaruOpenApiResponseParser.parseArguments(el3);
        Assertions.assertTrue(args3.isEmpty());
    }

    @Test
    public void testEmbeddedToolCallsParsing() {
        // XML-like format
        String text1 = "I will read the file.\n<function=file_read><parameter=path>src/Main.java</parameter></function>";
        List<NaruToolCall> calls1 = NaruOpenApiResponseParser.parseEmbeddedToolCalls(text1);
        Assertions.assertEquals(1, calls1.size());
        Assertions.assertEquals("file_read", calls1.get(0).getName());
        Assertions.assertEquals("src/Main.java", calls1.get(0).getArguments().get("path"));

        // <|tool_call|> format
        String text2 = "<|tool_call|>\n{\"tool\": \"calculator\", \"args\": {\"expression\": \"2 + 2\"}}\n<|end_tool_call|>";
        List<NaruToolCall> calls2 = NaruOpenApiResponseParser.parseEmbeddedToolCalls(text2);
        Assertions.assertEquals(1, calls2.size());
        Assertions.assertEquals("calculator", calls2.get(0).getName());
        Assertions.assertEquals("2 + 2", calls2.get(0).getArguments().get("expression"));
    }

    @Test
    public void testNRetryCallWith429AndNonRetryableErrors() {
        // Test 1: Retry on 429 succeeds on attempt 3
        AtomicInteger attempts = new AtomicInteger(0);
        AtomicReference<NDuration> dynamicRetryAfter = new AtomicReference<>();

        NRetryCall<String> retryCall = NRetryCall.of("test-429", () -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                dynamicRetryAfter.set(NDuration.ofMillis(10));
                throw new NHttpResponseException(NMsg.ofC("Rate limit"), null, NHttpCode.of(429));
            }
            return "SUCCESS";
        });

        retryCall.maxRetries(5)
                .retryPeriod(attempt -> {
                    NDuration d = dynamicRetryAfter.getAndSet(null);
                    return d != null ? d : NDuration.ofMillis(10);
                });

        String result = retryCall.call();
        Assertions.assertEquals("SUCCESS", result);
        Assertions.assertEquals(3, attempts.get());

        // Test 2: NonRetryableWebException (Error) terminates immediately
        AtomicInteger nonRetryAttempts = new AtomicInteger(0);
        NRetryCall<String> nonRetryCall = NRetryCall.of("test-401", () -> {
            nonRetryAttempts.incrementAndGet();
            throw new NaruModelProtocolBase.NonRetryableWebException(
                    new NHttpResponseException(NMsg.ofC("Unauthorized"), null, NHttpCode.of(401)),
                    "{\"error\":\"invalid_api_key\"}"
            );
        });

        nonRetryCall.maxRetries(5).retryPeriod(a -> NDuration.ofMillis(10));

        Assertions.assertThrows(NaruModelProtocolBase.NonRetryableWebException.class, nonRetryCall::call);
        Assertions.assertEquals(1, nonRetryAttempts.get());
    }

    // Helper Mock class
    private static class MockHttpResponse implements NHttpResponse {
        private final int statusCode;
        private final Map<String, List<String>> headers;

        public MockHttpResponse(int statusCode, Map<String, List<String>> headers) {
            this.statusCode = statusCode;
            this.headers = headers;
        }

        @Override
        public NHttpCode statusCode() {
            return NHttpCode.of(statusCode);
        }

        @Override
        public int intStatusCode() {
            return statusCode;
        }

        @Override
        public NMsg statusMessage() {
            return NMsg.ofC(statusCode == 429 ? "Too Many Requests" : "OK");
        }

        @Override
        public Map<String, List<String>> headers() {
            return headers;
        }

        @Override
        public NOptional<String> header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return NOptional.of(e.getValue().get(0));
                }
            }
            return NOptional.ofEmpty();
        }

        @Override
        public NInputSource content() {
            return null;
        }

        @Override
        public <K, V> Map<K, V> contentMapAsJson() {
            return Collections.emptyMap();
        }

        @Override
        public <K> List<K> contentListAsJson() {
            return Collections.emptyList();
        }

        @Override
        public <T> List<T> contentArrayAsJson() {
            return Collections.emptyList();
        }

        @Override
        public <T> T contentAsJson(Class<T> clz) {
            return null;
        }

        @Override
        public <T> T contentAs(Class<T> clz, NContentType type) {
            return null;
        }

        @Override
        public Map<?, ?> contentAsJsonMap() {
            return Collections.emptyMap();
        }

        @Override
        public List<?> contentAsJsonList() {
            return Collections.emptyList();
        }

        @Override
        public String contentAsString() {
            return "{}";
        }

        @Override
        public byte[] contentAsBytes() {
            return new byte[0];
        }

        @Override
        public List<NHttpCookie> cookies() {
            return Collections.emptyList();
        }

        @Override
        public NElement contentAsJson() {
            return NElement.ofObjectBuilder().build();
        }

        @Override
        public NHttpResponse ifErrorThrow() {
            if (isError()) throw new NHttpResponseException(statusMessage(), null, statusCode());
            return this;
        }

        @Override
        public boolean isError() {
            return statusCode >= 400;
        }

        @Override
        public boolean isOk() {
            return statusCode >= 200 && statusCode < 300;
        }

        @Override
        public boolean isClientError() {
            return statusCode >= 400 && statusCode < 500 && statusCode != 429;
        }

        @Override
        public boolean isServerError() {
            return statusCode >= 500;
        }

        @Override
        public boolean isRedirect() {
            return statusCode >= 300 && statusCode < 400;
        }

        @Override
        public Map<String, String> firstHeaders() {
            Map<String, String> m = new HashMap<>();
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (!e.getValue().isEmpty()) {
                    m.put(e.getKey(), e.getValue().get(0));
                }
            }
            return m;
        }

        @Override
        public List<String> headers(String name) {
            return headers.getOrDefault(name, Collections.emptyList());
        }

        @Override
        public String contentType() {
            return "application/json";
        }

        @Override
        public NMsgCode userMessage() {
            return null;
        }

        @Override
        public NHttpResponse userMessage(NMsgCode msgCode) {
            return this;
        }
    }
}
