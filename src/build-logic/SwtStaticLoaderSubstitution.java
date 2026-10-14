/* EnterpriseAI static-loading adaptation. SWT loader: EPL-2.0. */
package ai.enterprise.build;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(className="org.eclipse.swt.internal.Library")
final class Target_SwtStaticLibrary {
    @Substitute public static void loadLibrary(String name, boolean mapName) {
        if (!name.equals("swt") && !name.equals("swt-gdip") && !name.equals("swt-osversion"))
            throw new UnsatisfiedLinkError("Unsupported built-in SWT library: " + name);
        System.loadLibrary("enterpriseai_swt");
    }
}
