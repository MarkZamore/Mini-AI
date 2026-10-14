#include <jni.h>
JNIEXPORT void JNICALL Java_CrashProbe_fatal(JNIEnv*env,jclass klass){(void)klass;(*env)->FatalError(env,"Intentional developer-only JNI FatalError probe");}
