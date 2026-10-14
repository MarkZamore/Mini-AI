/*******************************************************************************
 * Copyright (c) 2000, 2026 IBM Corporation and others.
 * Modified for EnterpriseAI: load shipped Win32 DLLs only; never extract or search.
 * SPDX-License-Identifier: EPL-2.0
 * https://www.eclipse.org/legal/epl-2.0/
 ******************************************************************************/
package org.eclipse.swt.internal;

import java.nio.file.*;

/** Strict replacement for SWT 3.135.0's native loader. */
public class Library {
    static int MAJOR_VERSION=4, MINOR_VERSION=974, REVISION=10;
    public static final int JAVA_VERSION=parseVersion(System.getProperty("java.version"));
    public static final int SWT_VERSION=SWT_VERSION(MAJOR_VERSION,MINOR_VERSION);
    public static final String USER_HOME="";
    static final String SEPARATOR=java.io.File.separator;
    static final String DELIMITER=System.lineSeparator();
    static final String JAVA_LIB_PATH="java.library.path",SWT_LIB_PATH="swt.library.path";
    static final String SUFFIX_64="-64",SWT_LIB_DIR="";
    static String arch(){return System.getProperty("os.arch").equals("amd64")?"x86_64":System.getProperty("os.arch");}
    static String os(){return "win32";}
    static long longConst(){return 0x1FFFFFFFFL;}
    static int parseVersion(String version){if(version==null)return 0;String[] p=version.split("[^0-9]",4);int[]v=new int[3];for(int i=0;i<3&&i<p.length;i++)try{v[i]=Integer.parseInt(p[i]);}catch(NumberFormatException ignored){}return JAVA_VERSION(v[0],v[1],v[2]);}
    public static int JAVA_VERSION(int major,int minor,int micro){return (major<<16)+(minor<<8)+micro;}
    public static int SWT_VERSION(int major,int minor){return major*1000+minor;}
    static boolean isLoadable(){return isLoadable(false);}
    static boolean isLoadable(boolean ignored){return System.getProperty("os.name").startsWith("Windows")&&(arch().equals("x86_64")||arch().equals("amd64"));}
    public static void loadLibrary(String name){loadLibrary(name,true);}
    public static void loadLibrary(String name,boolean mapName){
        String directory=System.getProperty(SWT_LIB_PATH);
        if(directory==null||directory.isBlank())throw new UnsatisfiedLinkError("Shipped SWT library directory is not configured");
        Path root=Path.of(directory);
        if(!root.isAbsolute())throw new UnsatisfiedLinkError("SWT library directory must be absolute");
        String mapped=mapName?System.mapLibraryName(name+"-win32-"+getVersionString()):name;
        if(mapped.contains("/")||mapped.contains("\\")||mapped.contains(".."))throw new UnsatisfiedLinkError("Invalid SWT library name");
        Path library=root.resolve(mapped);
        if(!Files.isRegularFile(library))throw new UnsatisfiedLinkError("Missing shipped SWT library: "+mapped);
        System.load(library.toString());
    }
    static String mapLibraryName(String name){return System.mapLibraryName(name);}
    static String mapLibraryName(String name,boolean ignored){return System.mapLibraryName(name);}
    public static String getVersionString(){return "4974r10";}
}
