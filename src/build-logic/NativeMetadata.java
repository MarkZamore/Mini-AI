import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Build-time only, broad registration of JNI-accessed SWT structs/callbacks. */
public class NativeMetadata {
    static String quote(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[1]);Files.createDirectories(out);
        var classes=new TreeSet<String>();
        classes.add("java.lang.Throwable");
        try(var jar=new JarFile(args[0])){jar.stream().filter(e->e.getName().endsWith(".class")&&!e.getName().contains("module-info")&&!e.getName().startsWith("org/eclipse/swt/awt/")&&!e.getName().startsWith("org/eclipse/swt/browser/")&&!e.getName().startsWith("org/eclipse/swt/opengl/")).forEach(e->classes.add(e.getName().replace('/','.').replace(".class","")));}
        StringBuilder config=new StringBuilder("[");boolean first=true;
        for(String name:classes){try{Class<?> type=Class.forName(name,false,NativeMetadata.class.getClassLoader());if(!first)config.append(',');first=false;config.append("{\"name\":").append(quote(name)).append(",\"fields\":[");boolean f=true;for(Field field:type.getDeclaredFields()){if(!f)config.append(',');f=false;config.append("{\"name\":").append(quote(field.getName())).append("}");}config.append("],\"methods\":[");f=true;for(Constructor<?> constructor:type.getDeclaredConstructors()){if(!f)config.append(',');f=false;method(config,"<init>",constructor.getParameterTypes());}for(Method method:type.getDeclaredMethods()){if(!f)config.append(',');f=false;method(config,method.getName(),method.getParameterTypes());}config.append("]}");}catch(LinkageError ignored){}}
        config.append(']');Files.writeString(out.resolve("jni-config.json"),config);Files.writeString(out.resolve("reflect-config.json"),config);
    }
    static void method(StringBuilder b,String name,Class<?>[] parameters){b.append("{\"name\":").append(quote(name)).append(",\"parameterTypes\":[");for(int i=0;i<parameters.length;i++){if(i>0)b.append(',');b.append(quote(parameters[i].getTypeName()));}b.append("]}");}
}
