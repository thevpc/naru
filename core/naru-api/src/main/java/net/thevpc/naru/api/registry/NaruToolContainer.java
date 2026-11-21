package net.thevpc.naru.api.registry;

import net.thevpc.nuts.spi.NComponent;

/**
 * A class whose {@link net.thevpc.naru.api.annotations.NaruToolSpec} methods become tools.
 *
 * <p>Implementing it is only needed for discovery: listed in
 * {@code META-INF/services/net.thevpc.nuts.spi.NComponent} like any other extension, the class
 * is found on the classpath and its tools are registered in every session, each session with its
 * own instance. A class registered explicitly with
 * {@link NaruRegistry#registerToolsetProviderType(Class)} does not need it.
 */
public interface NaruToolContainer extends NComponent {
}
