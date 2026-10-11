package bench;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.io.NPath;
public class ShellTest3 {
    public static void main(String[] args) {
        Nuts.require();
        
        test("echo hello");
        test("seq 1 10");
        test("python3 -c 'print(123)'");
    }
    static void test(String cmd) {
        try {
            NExec nExec = NExec.ofSystem("/bin/sh", "-c", cmd)
                    .env(System.getenv())
                    .failFast(true);
            String grabbed = nExec.grabbedAll();
            int code = nExec.exitCode();
            System.out.println("CMD: " + cmd + " => code=" + code + ", grabbed=[" + grabbed + "]");
        } catch (Exception e) {
            System.out.println("CMD: " + cmd + " => EXCEPTION: " + e.getMessage());
        }
    }
}
