package net.thevpc.naru.ext.models.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A failed model call logs its request headers; the provider key must not be in that log. */
public class NaruModelUtilsMaskTest {

    private static final String KEY = "sk-0123456789abcdef0123456789abcdef";

    @Test
    public void credentialHeadersAreMaskedAndOthersKept() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Authorization", List.of("Bearer " + KEY));
        headers.put("x-api-key", List.of(KEY));
        headers.put("x-goog-api-key", List.of(KEY));
        headers.put("Content-Type", List.of("application/json"));

        Map<String, List<String>> masked = NaruModelUtils.maskedHeaders(headers);

        Assertions.assertFalse(masked.toString().contains(KEY), "a key survived masking: " + masked);
        Assertions.assertEquals(List.of("application/json"), masked.get("Content-Type"));
        Assertions.assertTrue(masked.get("Authorization").get(0).startsWith("Bearer "),
                "the scheme stays readable");
        Assertions.assertEquals(List.of("Bearer " + KEY), headers.get("Authorization"),
                "the request's own headers are untouched");
    }
}
