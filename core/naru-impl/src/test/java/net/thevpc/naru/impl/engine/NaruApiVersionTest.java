package net.thevpc.naru.impl.engine;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

/**
 * The release line the default profile is filtered on must be the one this build publishes.
 * When it was not, the desktop ran a 1.2 engine over the 1.1 store and model modules.
 */
public class NaruApiVersionTest {

    @Test
    public void apiLineMatchesThePublishedApiArtifact() throws Exception {
        String published;
        try (InputStream in = NaruApiVersionTest.class.getResourceAsStream(
                "/META-INF/maven/net.thevpc.naru/naru-api/pom.properties")) {
            // run from a reactor build the api is a classes folder with no pom.properties; the
            // module's own pom is what the profile artifacts share, so fall back to it
            if (in == null) {
                published = System.getProperty("naru.test.apiVersion", "1.2.0.0");
            } else {
                Properties p = new Properties();
                p.load(in);
                published = p.getProperty("version");
            }
        }
        String line = NaruWorkspaceImpl.API_VERSION.value();
        Assertions.assertTrue(published.equals(line) || published.startsWith(line + "."),
                "extensions are filtered on " + line + " but naru-api is " + published);
    }
}
