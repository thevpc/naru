package net.thevpc.naru.api.agent;

import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.ext.NExtensions;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.spi.NComponent;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Assembles a {@link NaruWorkspace} from an optional set of <b>profiles</b> and
 * <b>extensions</b>.
 *
 * <p>naru's own distribution carries no extension: the app depends only on {@code naru-impl},
 * so what a given run can do is decided by what it loads. Two knobs express that:
 *
 * <ul>
 *   <li>A <b>profile</b> is a Maven artifact whose transitive dependencies are naru
 *       extensions. Naming one bundle is a single decision ("run this as the dev harness",
 *       "run this as a plain coding agent") instead of a list of artifacts.</li>
 *   <li>An <b>extension</b> is a single naru feature, loaded on its own.</li>
 * </ul>
 *
 * <p>Nothing is resolved or loaded until {@link #build()}. When neither a profile nor an
 * extension is declared, the builder falls back to the {@code dev} profile if it is
 * resolvable and otherwise starts with a bare core — so the same call yields a fully
 * loaded harness or a minimal agent depending on what is installed.
 *
 * <p>A builder needs a running nuts workspace (obtained via {@code Nuts.require()} or by
 * running inside a naru app). To create a workspace from the classes already on the
 * classpath with no resolution at all, use {@link NaruWorkspace#of()}.
 *
 * <pre>{@code
 * NaruWorkspace ws = NaruWorkspaceBuilder.of()
 *         .profile("net.thevpc.naru.profiles:naru-profile-dev")
 *         .importExtension("net.thevpc.naru:naru-budget")
 *         .projectDirectory(NPath.of("./my-app"))
 *         .build();
 * }</pre>
 */
public interface NaruWorkspaceBuilder extends NComponent {
    static NaruWorkspaceBuilder of() {
        return NExtensions.of(NaruWorkspaceBuilder.class);
    }

    /**
     * Resolves a profile reference to a Maven id: a coordinate containing a colon is used
     * as-is; a bare name such as {@code dev} is expanded to
     * {@code net.thevpc.naru.profiles:naru-profile-dev}.
     */
    static NId profileId(String nameOrId) {
        return nameOrId.contains(":")
                ? NId.of(nameOrId)
                : NId.of("net.thevpc.naru.profiles", "naru-profile-" + nameOrId);
    }

    /**
     * Declares one extension to load, in addition to any profile (or instead of one).
     * An explicitly named extension is loaded whether or not a declared profile also
     * bundles it.
     */
    NaruWorkspaceBuilder importExtension(NId extension);

    /**
     * Same as {@link #importExtension(NId)}, accepting a Maven coordinate such as
     * {@code group:artifact} or {@code group:artifact#version}.
     */
    default NaruWorkspaceBuilder importExtension(String nameOrId) {
        return importExtension(NId.of(nameOrId));
    }

    <T> NaruWorkspaceBuilder configure(Class<T> api, Supplier<T> supplier);

    <T> NaruWorkspaceBuilder configureSession(Class<T> api, Function<NaruSession, T> supplier);

    NaruWorkspaceBuilder projectDirectory(NPath projectDirectory);

    /**
     * Resolves every declared profile and extension, loads them, and returns the
     * workspace. Profiles are resolved first and expand to their member extensions;
     * explicitly named extensions are then loaded on top.
     */
    NaruWorkspace build();
}
