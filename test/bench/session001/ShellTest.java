package bench;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.command.NExec;
public class ShellTest {
    public static void main(String[] args) {
        Nuts.require();
        String cmd = "python3 -c 'print(\"A\" * 20000)'";
        NExec exec = NExec.ofSystem("/bin/sh", "-c", cmd).failFast(true);
        String out = exec.grabbedAll();
        System.out.println("Output len: " + out.length() + ", text: [" + out + "]");
    }
}
