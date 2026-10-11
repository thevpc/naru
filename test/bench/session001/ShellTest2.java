package bench;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.command.NExec;
public class ShellTest2 {
    public static void main(String[] args) {
        NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
        if (ws != null) ws.share();
        String cmd = "seq 1 1000";
        NExec exec = NExec.ofSystem("/bin/sh", "-c", cmd).failFast(true);
        String out = exec.grabbedAll();
        System.out.println("Output len: " + out.length() + ", lines: " + out.lines().count());
        System.out.println("Last 50 chars: " + out.substring(Math.max(0, out.length() - 50)));
    }
}
