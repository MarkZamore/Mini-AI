package ai.enterprise.build;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.windows.headers.WinBase.HMODULE;
import com.oracle.svm.core.windows.headers.WindowsDirectives;
import com.oracle.svm.core.windows.headers.WindowsLibC.WCharPointer;
import org.graalvm.nativeimage.PinnedObject;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;

/** Wide executable path only: no replacement loader or application DLL fallback. */
@TargetClass(className="com.oracle.svm.core.windows.WindowsProcessPropertiesSupport")
final class Target_StaticWindowsProcessProperties {
    @Substitute private static String getModulePath(HMODULE module) {
        for(int size=512;size<=32768;size*=2) {
            char[] buffer=new char[size];
            try(var pinned=PinnedObject.create(buffer)) {
                int length=StaticWindowsPath.GetModuleFileNameW(module,pinned.addressOfArrayElement(0),size);
                if(length==0)return null;
                if(length<size)return new String(buffer,0,length);
            }
        }
        return null;
    }
}
@CContext(WindowsDirectives.class)
final class StaticWindowsPath {
    @CFunction static native int GetModuleFileNameW(HMODULE module,WCharPointer path,int size);
}
