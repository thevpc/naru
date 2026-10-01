package net.thevpc.naru.ext.tools.plan;

/**
 * Test probe for the {@link net.thevpc.naru.api.registry.NaruSessionExtension#onModeChanged}
 * contract. Registered through the service loader so the assertions run against the same
 * discovery path a third-party extension would use.
 *
 * <p>State is static because the registry creates one instance per session and the test
 * cannot reach that instance to inspect it.
 */
public class RecordingSessionExtension implements net.thevpc.naru.api.registry.NaruSessionExtension {

    public static final java.util.List<String> CALLS = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public static final String NAME_FOR_LOOKUP = "recording-probe";

    public static void reset() {
        CALLS.clear();
    }

    @Override
    public String name() {
        return "recording-probe";
    }

    @Override
    public int order() {
        // last, so a test asserting on this never races the plan extension
        return 1000;
    }

    @Override
    public void onModeChanged(net.thevpc.naru.api.task.NaruTask task,
                              net.thevpc.naru.api.mode.NaruPromptMode old,
                              net.thevpc.naru.api.mode.NaruPromptMode now) {
        CALLS.add(old.name() + "->" + now.name());
    }
}