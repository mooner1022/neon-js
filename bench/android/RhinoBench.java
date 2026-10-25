import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

/**
 * Runs a script with Rhino on Android, as an Android app embeds it: interpreted, or compiled with rhino-android's
 * AndroidContextFactory (each generated class translated to dex by dx at run time).
 *
 * usage: RhinoBench interpreted|compiled FILE
 */
public class RhinoBench {
    public static void main(String[] args) throws Exception {
        boolean compiled = args[0].equals("compiled");
        ContextFactory factory = compiled
            ? new com.faendir.rhino_android.AndroidContextFactory(new java.io.File("/data/local/tmp/rhino-cache"))
            : new ContextFactory();
        Context cx = factory.enterContext();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            // (setOptimizationLevel rather than setInterpretedMode: Rhino 1.7 has only the former)
            cx.setOptimizationLevel(compiled ? 9 : -1);
            Scriptable scope = cx.initStandardObjects();
            ScriptableObject.putProperty(scope, "print", new BaseFunction() {
                @Override
                public Object call(Context c, Scriptable s, Scriptable thisObj, Object[] a) {
                    System.out.println(a.length > 0 ? Context.toString(a[0]) : "");
                    return Undefined.instance;
                }
            });
            String source = new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8);
            cx.evaluateString(scope, source, args[1], 1, null);
        } finally {
            Context.exit();
        }
    }
}
