package net.thevpc.naru.impl.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * Utility helpers for image handling.
 */
public class ImageUtil {

    private ImageUtil() {}

    /**
     * Reads an image file from disk and returns it as a Base64-encoded string
     * (no data-URL prefix — just the raw base64 data that Ollama expects).
     */
    public static String toBase64(String imagePath) throws IOException {
        Path p = Paths.get(imagePath);
        if (!Files.exists(p)) {
            throw new IOException("Image not found: " + imagePath);
        }
        byte[] bytes = Files.readAllBytes(p);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Returns the MIME type based on file extension (best-effort).
     */
    public static String mimeType(String imagePath) {
        String lower = imagePath.toLowerCase();
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png"; // default
    }
}
