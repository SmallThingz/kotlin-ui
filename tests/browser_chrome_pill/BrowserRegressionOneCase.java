import java.util.Set;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.internal.TextListener;
/** Existing JUnit single-method entry pattern; deliberately no whole-class fallback. */
public final class BrowserRegressionOneCase {
    public static void main(String[] args) throws Exception {
        Set<String> allowed=Set.of(
            "BrowserPillRegressionTest.attachedPillTargetsAndTouchVersusKeyboardFocus");
        if(args.length!=1 || !allowed.contains(args[0])) throw new IllegalArgumentException("One frozen exact selector required");
        if(!"true".equals(System.getProperty("control.actualActivity.rootAdmitted"))) throw new IllegalStateException("Root admission required");
        int dot=args[0].lastIndexOf('.');
        JUnitCore core=new JUnitCore();core.addListener(new TextListener(System.out));
        Result result=core.run(Request.method(Class.forName(args[0].substring(0,dot)),args[0].substring(dot+1)));
        System.exit(result.wasSuccessful()?0:1);
    }
}
