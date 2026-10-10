package net.thevpc.naru.api.model;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextStyle;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The description of a {@link NaruToolDefinition} is held as an {@link NText}: tools and
 * directives may style it for the terminal help while the model must always receive plain,
 * style-free text. {@link NaruToolDefinition#getDescription()} is the single place where
 * that filtering happens, so these tests pin both sides of the contract.
 */
public class NaruToolDefinitionTest {

    @BeforeAll
    public static void setUpWorkspace() {
        // NText.of/ofPlain go through the Nuts workspace, which is not implicit in a unit test.
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            Nuts.require();
        }
        Nuts.require();
    }

    @Test
    public void plainStringDescriptionIsHandedBackUnchanged() {
        NaruToolDefinition def = new NaruToolDefinition("file_read", "Read a file from disk.");
        Assertions.assertEquals("Read a file from disk.", def.getDescription());
        Assertions.assertEquals("Read a file from disk.", def.getDescriptionText().filteredText());
    }

    @Test
    public void richDescriptionIsFilteredForTheModelButKeptForHelp() {
        NText rich = NText.ofStyled("Read a file", NTextStyle.primary1());
        NaruToolDefinition def = new NaruToolDefinition("file_read", rich);

        // the model sees plain text only ...
        Assertions.assertEquals("Read a file", def.getDescription());
        // ... while help keeps the styling
        Assertions.assertSame(rich, def.getDescriptionText());
        Assertions.assertNotEquals(def.getDescription(), def.getDescriptionText().toString(),
                "the styled text must carry its formatting for the terminal");
    }

    @Test
    public void stringConstructorTreatsMarkupLiterally() {
        // The String constructor is the plain path: it must not reinterpret NTF-like
        // characters, so a description containing '#' or '//' survives untouched.
        // A tool that wants NTF parsing passes an NText (see NaruTool.getDescription).
        NaruToolDefinition def = new NaruToolDefinition("t", "##not-a-style## and // not a comment");
        Assertions.assertEquals("##not-a-style## and // not a comment", def.getDescription());
    }

    @Test
    public void nullDescriptionStaysNullForTheModelAndBlankForHelp() {
        NaruToolDefinition def = new NaruToolDefinition("bare", (String) null);
        Assertions.assertNull(def.getDescription());
        Assertions.assertEquals("", def.getDescriptionText().filteredText());
    }

    @Test
    public void functionOverloadsAcceptNText() {
        NaruToolDefinitionFunction def = new NaruToolDefinitionFunction("t",
                NText.ofStyled("does t", NTextStyle.primary1()));
        Assertions.assertEquals("does t", def.getDescription());
        Assertions.assertEquals("does t", def.getDescriptionText().filteredText());
        Assertions.assertTrue(def.getParams().isEmpty());
    }
}
