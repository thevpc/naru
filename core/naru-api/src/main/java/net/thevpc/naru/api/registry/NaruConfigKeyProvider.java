package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.spi.NComponent;

import java.util.List;

/**
 * Declares the configuration keys one feature reads.
 *
 * <p>This is the extension point behind the settings surface of the interactive
 * shell: {@code /settings} and {@code /set} autocomplete, and {@code /settings}
 * listing, are all driven by the keys the installed providers declare. A
 * provider is discovered the same way as every other NARU SPI -- implement
 * {@link NComponent}, list the class in
 * {@code META-INF/services/net.thevpc.nuts.spi.NComponent} -- and the keys are
 * declared <b>next to the code that consumes them</b>, so an added setting
 * becomes completable from the same change that reads it, and the description
 * cannot drift from the code.
 *
 * <p>The provider receives the session, on purpose: the settings surface is not
 * static. The keys a build understands include ones that only exist because of
 * what is in the session, such as {@code model.qwen.readTimeout} for an
 * installed model family or {@code personal.apiKey} for a registration, and a
 * provider computes those concrete keys from the session rather than listing
 * every possibility. It must be safe to call from completion, so it must not
 * throw and must prefer cheap session reads over network probes.
 */
public interface NaruConfigKeyProvider extends NComponent {

    /**
     * Stable identifier of the provider, for registry bookkeeping and for
     * attributing a key to its module in listings.
     */
    String name();

    /**
     * The keys this feature reads, concrete and exact for this session.
     *
     * <p>Called on demand (each completion, each listing), so it must be cheap
     * and must not throw: a misbehaving provider may not break Tab.
     */
    List<NaruConfigKey> configKeys(NaruSession session);
}