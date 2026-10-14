/*
 * EnterpriseAI adaptation of GraalVM CE 25.0.2 WindowsNativeLibrarySupport.
 * Original copyright (c) 2018, 2019 Oracle and/or its affiliates. All rights reserved.
 * GPL version 2 with Classpath Exception; corresponding licence/notice in distribution.
 * Preserve GraalVM's library ownership/initialization and replace its narrow LoadLibraryA.
 */
package ai.enterprise.build;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.windows.headers.WinBase.HANDLE;
import com.oracle.svm.core.windows.headers.WinBase.HMODULE;
import com.oracle.svm.core.windows.headers.WindowsDirectives;
import com.oracle.svm.core.windows.headers.WindowsLibC.WCharPointer;
import org.graalvm.nativeimage.PinnedObject;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.word.WordFactory;

@Platforms(Platform.WINDOWS.class)
@TargetClass(className="com.oracle.svm.core.windows.WindowsNativeLibrarySupport$WindowsNativeLibrary")
final class Target_WindowsNativeLibrary {
    @Alias private String canonicalIdentifier;
    @Alias private boolean builtin;
    @Alias private HMODULE dlhandle;
    @Substitute private boolean doLoad(){
        Target_Jvm.initialize();
        if(builtin)return true;
        String path=canonicalIdentifier;
        if(!path.startsWith("\\\\?\\")){
            if(path.startsWith("\\\\"))path="\\\\?\\UNC\\"+path.substring(2);
            else if(path.length()>2&&path.charAt(1)==':'&&path.charAt(2)=='\\')path="\\\\?\\"+path;
        }
        char[] utf16=(path+'\0').toCharArray();
        try(var pinned=PinnedObject.create(utf16)){
            WCharPointer pointer=pinned.addressOfArrayElement(0);
            // Limit dependent-library search to this DLL's directory and Windows system DLLs.
            dlhandle=WindowsWideLoader.LoadLibraryExW(pointer,WordFactory.nullPointer(),0x100|0x800);
        }
        return dlhandle.isNonNull();
    }
}

@Platforms(Platform.WINDOWS.class)
@CContext(WindowsDirectives.class)
final class WindowsWideLoader {
    @CFunction static native HMODULE LoadLibraryExW(WCharPointer path,HANDLE unused,int flags);
    @CFunction static native int GetModuleFileNameW(HMODULE module,WCharPointer path,int size);
}

@Platforms(Platform.WINDOWS.class)
@TargetClass(className="com.oracle.svm.core.jdk.Jvm")
final class Target_Jvm {
    @Alias static native void initialize();
}

/** Native Image 25.0.2 otherwise truncates the executable/module path at MAX_PATH. */
@Platforms(Platform.WINDOWS.class)
@TargetClass(className="com.oracle.svm.core.windows.WindowsProcessPropertiesSupport")
final class Target_WindowsProcessProperties {
    @Substitute private static String getModulePath(HMODULE module){
        for(int size=512;size<=32768;size*=2){
            char[] buffer=new char[size];
            try(var pinned=PinnedObject.create(buffer)){
                WCharPointer pointer=pinned.addressOfArrayElement(0);
                int length=WindowsWideLoader.GetModuleFileNameW(module,pointer,size);
                if(length==0)return null;
                if(length<size)return new String(buffer,0,length);
            }
        }
        return null;
    }
}
