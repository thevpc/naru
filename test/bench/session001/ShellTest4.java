package bench;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.command.NExec;
public class ShellTest4 {
    public static void main(String[] args) {
        NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
        if (ws != null) ws.share();
        
        for (int n : new int[]{50, 100, 200, 500, 1000}) {
            String cmd = "seq 1 " + n;
            NExec nExec = NExec.ofSystem("/bin/sh", "-c", cmd)
                    .env(System.getenv())
                    .failFast(true);
            String grabbed = nExec.grabbedAll();
            System.out.println("seq 1 " + n + " => len=" + grabbed.length() + ", exitCode=" + nExec.exitCode());
        }
    }
}
