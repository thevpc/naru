package net.thevpc.naru.ext.models.stream;

import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.nuts.net.NHttpResponse;

import java.io.IOException;

/**
 * Reads a successful HTTP response as a sequence of model events and returns the
 * assembled result.
 *
 * <p>Separate from the wire format so that the request lifecycle -- retries,
 * audit logging, error classification, cache commit -- is written once and
 * reused by every protocol, whether its stream arrives as SSE frames or as one
 * JSON document per line.
 */
public interface NaruStreamResponseParser {

    /**
     * Consumes the body incrementally and returns the assembled response.
     *
     * <p>Must deliver events as they arrive rather than reading the whole body
     * first: the entire point is that a renderer sees text while the model is
     * still generating it.
     */
    NaruResponse read(NHttpResponse response) throws IOException;

    /**
     * Whether any user-visible output has already been delivered.
     *
     * <p>Consulted by the caller before retrying. A stream that has delivered
     * nothing can be retried invisibly; one that has cannot, because the tokens
     * cannot be recalled.
     */
    boolean hasDeliveredContent();
}
