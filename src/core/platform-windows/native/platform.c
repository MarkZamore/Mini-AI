#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#include <windows.h>
#include <shlobj.h>
#include <shellapi.h>
#include <intrin.h>
#include <jni.h>
#include <stdint.h>
#include <wchar.h>
#include <stdlib.h>
#include <imm.h>

static void fail(JNIEnv *e,const char *text){jclass c=(*e)->FindClass(e,"java/lang/IllegalStateException");if(c)(*e)->ThrowNew(e,c,text);}
static WCHAR* wide(JNIEnv *e,jstring value){jsize n=(*e)->GetStringLength(e,value);WCHAR* out=calloc((size_t)n+1,sizeof(WCHAR));if(!out){fail(e,"Allocation failed");return NULL;}(*e)->GetStringRegion(e,value,0,n,(jchar*)out);return out;}
static WCHAR* widePath(JNIEnv *e,jstring value){WCHAR*raw=wide(e,value);if(!raw)return NULL;size_t n=wcslen(raw);if(n<248||!wcsncmp(raw,L"\\\\?\\",4))return raw;WCHAR*result=calloc(n+10,sizeof(WCHAR));if(!result){free(raw);fail(e,"Allocation failed");return NULL;}if(raw[0]==L'\\'&&raw[1]==L'\\')swprintf_s(result,n+10,L"\\\\?\\UNC\\%s",raw+2);else if(raw[1]==L':'&&raw[2]==L'\\')swprintf_s(result,n+10,L"\\\\?\\%s",raw);else{free(result);return raw;}free(raw);return result;}
static HANDLE handle(jlong n){return (HANDLE)(intptr_t)n;}
/* Per-current-thread only. Windows 11 TextInputFramework rejects long process images
 * during focus registration; never alter registry/system settings or other threads. */
static __declspec(thread) int desktopThreadPrepared;
static int prepareDesktopThread(void){WCHAR path[32768];DWORD count=GetModuleFileNameW(NULL,path,32768);if(!count||count>=32768)return -1;size_t length=count;if(!wcsncmp(path,L"\\\\?\\UNC\\",8))length-=6;else if(!wcsncmp(path,L"\\\\?\\",4))length-=4;if(length<256)return 0;if(desktopThreadPrepared)return 1;if(!ImmDisableIME(0))return -1;desktopThreadPrepared=1;return 1;}
JNIEXPORT jint JNICALL Java_ai_enterprise_platform_WindowsPlatform_prepareDesktopNative(JNIEnv*e,jclass c){(void)e;(void)c;return prepareDesktopThread();}
JNIEXPORT void JNICALL Java_ai_enterprise_platform_WindowsPlatform_configureNative(JNIEnv*e,jclass c){(void)e;(void)c;SetErrorMode(SEM_FAILCRITICALERRORS|SEM_NOGPFAULTERRORBOX|SEM_NOOPENFILEERRORBOX);SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_SYSTEM32|LOAD_LIBRARY_SEARCH_USER_DIRS);}
JNIEXPORT jstring JNICALL Java_ai_enterprise_platform_WindowsPlatform_executableNative(JNIEnv*e,jclass c){(void)c;WCHAR path[32768];DWORD n=GetModuleFileNameW(NULL,path,32768);if(!n||n>=32768){fail(e,"GetModuleFileNameW failed");return NULL;}return (*e)->NewString(e,(jchar*)path,(jsize)n);}
JNIEXPORT jobjectArray JNICALL Java_ai_enterprise_platform_WindowsPlatform_argumentsNative(JNIEnv*e,jclass c){(void)c;int count=0;LPWSTR*parts=CommandLineToArgvW(GetCommandLineW(),&count);if(!parts||count<1){if(parts)LocalFree(parts);fail(e,"Cannot parse original Unicode command line");return NULL;}jclass strings=(*e)->FindClass(e,"java/lang/String");jobjectArray result=(*e)->NewObjectArray(e,count-1,strings,NULL);if(result){for(int i=1;i<count;i++){jstring part=(*e)->NewString(e,(jchar*)parts[i],(jsize)wcslen(parts[i]));if(!part)break;(*e)->SetObjectArrayElement(e,result,i-1,part);(*e)->DeleteLocalRef(e,part);}}LocalFree(parts);return result;}
JNIEXPORT jlong JNICALL Java_ai_enterprise_platform_WindowsPlatform_memoryNative(JNIEnv*e,jclass c){(void)e;(void)c;MEMORYSTATUSEX m={sizeof(m)};if(!GlobalMemoryStatusEx(&m))return 0;return (jlong)m.ullAvailPhys;}
JNIEXPORT jboolean JNICALL Java_ai_enterprise_platform_WindowsPlatform_cpuNative(JNIEnv*e,jclass c,jstring isa){(void)c;const char*s=(*e)->GetStringUTFChars(e,isa,NULL);int ok=0,r[4];if(!_stricmp(s,"baseline")||!_stricmp(s,"x86-64"))ok=1;else if(!_stricmp(s,"avx2")){__cpuid(r,1);if((r[2]&(1<<27))&&(r[2]&(1<<28))&&(_xgetbv(0)&6)==6){__cpuidex(r,7,0);ok=(r[1]&(1<<5))!=0;}}else if(!_stricmp(s,"avx512")){__cpuid(r,1);if((r[2]&(1<<27))&&(_xgetbv(0)&0xE6)==0xE6){__cpuidex(r,7,0);ok=(r[1]&(1<<16))!=0;}}(*e)->ReleaseStringUTFChars(e,isa,s);return ok?JNI_TRUE:JNI_FALSE;}
JNIEXPORT jint JNICALL Java_ai_enterprise_platform_WindowsPlatform_openWithNative(JNIEnv*e,jclass c,jstring path){(void)c;if(prepareDesktopThread()<0)return (jint)E_FAIL;WCHAR*w=wide(e,path);if(!w)return (jint)E_OUTOFMEMORY;HRESULT init=CoInitializeEx(NULL,COINIT_APARTMENTTHREADED);OPENASINFO info={w,NULL,OAIF_EXEC};HRESULT result=SHOpenWithDialog(NULL,&info);if(SUCCEEDED(init))CoUninitialize();free(w);return (jint)result;}
JNIEXPORT jlongArray JNICALL Java_ai_enterprise_platform_WindowsPlatform_startNative(JNIEnv*e,jclass c,jstring exe,jstring cwd,jlong memory){(void)c;WCHAR*application=widePath(e,exe),*directory=widePath(e,cwd);if(!application||!directory){free(application);free(directory);return NULL;}size_t n=wcslen(application);WCHAR*cmd=calloc(n+16,sizeof(WCHAR));if(!cmd){free(application);free(directory);fail(e,"Allocation failed");return NULL;}swprintf_s(cmd,n+16,L"\"%s\" --stdio",application);
 /* Windows cannot create a process with a current directory above MAX_PATH.
  * The worker receives absolute model paths and anchors resources to its own EXE.
  * A long model directory uses the OS-derived System32 directory for child cwd. */
 if(wcslen(directory)>=248){WCHAR shortDirectory[MAX_PATH];UINT count=GetSystemDirectoryW(shortDirectory,MAX_PATH);if(!count||count>=MAX_PATH){free(application);free(directory);free(cmd);fail(e,"Cannot resolve safe short worker working directory");return NULL;}free(directory);directory=calloc((size_t)count+1,sizeof(WCHAR));if(!directory){free(application);free(cmd);fail(e,"Allocation failed");return NULL;}wcscpy_s(directory,(size_t)count+1,shortDirectory);}
 SECURITY_ATTRIBUTES sa={sizeof(sa),NULL,TRUE};HANDLE inR=NULL,inW=NULL,outR=NULL,outW=NULL,errR=NULL,errW=NULL,job=NULL;PROCESS_INFORMATION pi={0};STARTUPINFOEXW si={0};si.StartupInfo.cb=sizeof(si);int attributeReady=0;
 if(!CreatePipe(&inR,&inW,&sa,0)||!CreatePipe(&outR,&outW,&sa,0)||!CreatePipe(&errR,&errW,&sa,0))goto failed;
 SetHandleInformation(inW,HANDLE_FLAG_INHERIT,0);SetHandleInformation(outR,HANDLE_FLAG_INHERIT,0);SetHandleInformation(errR,HANDLE_FLAG_INHERIT,0);
 job=CreateJobObjectW(NULL,NULL);if(!job)goto failed;JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits={0};limits.BasicLimitInformation.LimitFlags=JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
 if(memory>0){limits.BasicLimitInformation.LimitFlags|=JOB_OBJECT_LIMIT_PROCESS_MEMORY;limits.ProcessMemoryLimit=(SIZE_T)memory;}
 if(!SetInformationJobObject(job,JobObjectExtendedLimitInformation,&limits,sizeof(limits)))goto failed;
 SIZE_T attributeSize=0;InitializeProcThreadAttributeList(NULL,1,0,&attributeSize);si.lpAttributeList=malloc(attributeSize);if(!si.lpAttributeList)goto failed;
 if(!InitializeProcThreadAttributeList(si.lpAttributeList,1,0,&attributeSize))goto failed;attributeReady=1;
 HANDLE inherited[3]={inR,outW,errW};if(!UpdateProcThreadAttribute(si.lpAttributeList,0,PROC_THREAD_ATTRIBUTE_HANDLE_LIST,inherited,sizeof(inherited),NULL,NULL))goto failed;
 si.StartupInfo.dwFlags=STARTF_USESTDHANDLES;si.StartupInfo.hStdInput=inR;si.StartupInfo.hStdOutput=outW;si.StartupInfo.hStdError=errW;
 if(!CreateProcessW(application,cmd,NULL,NULL,TRUE,CREATE_SUSPENDED|CREATE_NO_WINDOW|EXTENDED_STARTUPINFO_PRESENT,NULL,directory,&si.StartupInfo,&pi))goto failed;
 if(!AssignProcessToJobObject(job,pi.hProcess))goto failed;
 if(ResumeThread(pi.hThread)==(DWORD)-1)goto failed;
 DeleteProcThreadAttributeList(si.lpAttributeList);free(si.lpAttributeList);CloseHandle(pi.hThread);CloseHandle(inR);CloseHandle(outW);CloseHandle(errW);free(application);free(directory);free(cmd);
 jlong h[5]={(jlong)(intptr_t)pi.hProcess,(jlong)(intptr_t)job,(jlong)(intptr_t)inW,(jlong)(intptr_t)outR,(jlong)(intptr_t)errR};jlongArray result=(*e)->NewLongArray(e,5);if(result)(*e)->SetLongArrayRegion(e,result,0,5,h);else{TerminateProcess(pi.hProcess,1);for(int i=0;i<5;i++)CloseHandle(handle(h[i]));}return result;
failed:
 if(attributeReady)DeleteProcThreadAttributeList(si.lpAttributeList);free(si.lpAttributeList);if(pi.hProcess){TerminateProcess(pi.hProcess,1);CloseHandle(pi.hProcess);}if(pi.hThread)CloseHandle(pi.hThread);if(inR)CloseHandle(inR);if(inW)CloseHandle(inW);if(outR)CloseHandle(outR);if(outW)CloseHandle(outW);if(errR)CloseHandle(errR);if(errW)CloseHandle(errW);if(job)CloseHandle(job);free(application);free(directory);free(cmd);fail(e,"Windows could not create suspended, job-contained worker");return NULL;}
JNIEXPORT jint JNICALL Java_ai_enterprise_platform_WindowsPlatform_readNative(JNIEnv*e,jclass c,jlong h,jbyteArray b,jint off,jint len){(void)c;jbyte*data=malloc((size_t)len);if(!data){fail(e,"Allocation failed");return -1;}DWORD n=0;BOOL ok=ReadFile(handle(h),data,(DWORD)len,&n,NULL);if(!ok){DWORD code=GetLastError();free(data);if(code==ERROR_BROKEN_PIPE||code==ERROR_INVALID_HANDLE)return -1;fail(e,"ReadFile failed");return -1;}if(n)(*e)->SetByteArrayRegion(e,b,off,(jsize)n,data);free(data);return n?(jint)n:-1;}
JNIEXPORT void JNICALL Java_ai_enterprise_platform_WindowsPlatform_writeNative(JNIEnv*e,jclass c,jlong h,jbyteArray b,jint off,jint len){(void)c;jbyte*data=malloc((size_t)len);if(!data){fail(e,"Allocation failed");return;}(*e)->GetByteArrayRegion(e,b,off,len,data);DWORD n=0,total=0;while(total<(DWORD)len){if(!WriteFile(handle(h),data+total,(DWORD)len-total,&n,NULL)||!n){fail(e,"WriteFile failed");break;}total+=n;}free(data);}
JNIEXPORT jint JNICALL Java_ai_enterprise_platform_WindowsPlatform_waitNative(JNIEnv*e,jclass c,jlong h,jlong millis){(void)e;(void)c;DWORD result=WaitForSingleObject(handle(h),(DWORD)millis);if(result!=WAIT_OBJECT_0)return -1;DWORD code=1;GetExitCodeProcess(handle(h),&code);return (jint)code;}
JNIEXPORT jboolean JNICALL Java_ai_enterprise_platform_WindowsPlatform_aliveNative(JNIEnv*e,jclass c,jlong h){(void)e;(void)c;DWORD code=0;return GetExitCodeProcess(handle(h),&code)&&code==STILL_ACTIVE?JNI_TRUE:JNI_FALSE;}
JNIEXPORT void JNICALL Java_ai_enterprise_platform_WindowsPlatform_terminateNative(JNIEnv*e,jclass c,jlong h){(void)e;(void)c;TerminateProcess(handle(h),1);}
JNIEXPORT void JNICALL Java_ai_enterprise_platform_WindowsPlatform_closeNative(JNIEnv*e,jclass c,jlong h){(void)e;(void)c;if(h)CloseHandle(handle(h));}
