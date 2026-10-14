/* EnterpriseAI read-only SQLite bridge. SQLite itself is public domain.
 * No library extraction, writable VFS operations, extension loading or WAL.
 */
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include "sqlite3.h"

typedef struct GuardFile { sqlite3_file file; sqlite3_file *real; } GuardFile;
static sqlite3_vfs guard_vfs;
static sqlite3_vfs *base_vfs;
#define GF(f) ((GuardFile *)(f))
#define RM(f) (GF(f)->real->pMethods)
static int gClose(sqlite3_file *f){ return RM(f)->xClose(GF(f)->real); }
static int gRead(sqlite3_file *f,void *b,int n,sqlite3_int64 o){return RM(f)->xRead(GF(f)->real,b,n,o);}
static int gWrite(sqlite3_file *f,const void *b,int n,sqlite3_int64 o){return SQLITE_READONLY;}
static int gTruncate(sqlite3_file *f,sqlite3_int64 n){return SQLITE_READONLY;}
static int gSync(sqlite3_file *f,int n){return SQLITE_READONLY;}
static int gSize(sqlite3_file *f,sqlite3_int64 *n){return RM(f)->xFileSize(GF(f)->real,n);}
static int gLock(sqlite3_file *f,int n){return RM(f)->xLock(GF(f)->real,n);}
static int gUnlock(sqlite3_file *f,int n){return RM(f)->xUnlock(GF(f)->real,n);}
static int gReserved(sqlite3_file *f,int *n){return RM(f)->xCheckReservedLock(GF(f)->real,n);}
static int gControl(sqlite3_file *f,int op,void *v){
    /* Header size hints and persist-WAL requests may mutate the file. */
    if(op==SQLITE_FCNTL_SIZE_HINT || op==SQLITE_FCNTL_PERSIST_WAL || op==SQLITE_FCNTL_CHUNK_SIZE)
        return SQLITE_READONLY;
    return RM(f)->xFileControl(GF(f)->real,op,v);
}
static int gSector(sqlite3_file *f){return RM(f)->xSectorSize(GF(f)->real);}
static int gDevice(sqlite3_file *f){return RM(f)->xDeviceCharacteristics(GF(f)->real);}
static int gShmMap(sqlite3_file *f,int p,int s,int w,void volatile **v){*v=0; return SQLITE_READONLY;}
static int gShmLock(sqlite3_file *f,int o,int n,int flags){return SQLITE_READONLY;}
static void gShmBarrier(sqlite3_file *f){}
static int gShmUnmap(sqlite3_file *f,int d){return SQLITE_OK;}
/* Memory mapping is disabled: the bridge never exposes a mapped writable view. */
static int gFetch(sqlite3_file *f,sqlite3_int64 o,int n,void **v){*v=0;return SQLITE_OK;}
static int gUnfetch(sqlite3_file *f,sqlite3_int64 o,void *v){return SQLITE_OK;}
static const sqlite3_io_methods io={3,gClose,gRead,gWrite,gTruncate,gSync,gSize,gLock,gUnlock,
    gReserved,gControl,gSector,gDevice,gShmMap,gShmLock,gShmBarrier,gShmUnmap,gFetch,gUnfetch};
static int gOpen(sqlite3_vfs *v,const char *name,sqlite3_file *f,int flags,int *out){
    GuardFile *g=GF(f); memset(g,0,sizeof(*g));
    if(!name || !(flags&SQLITE_OPEN_READONLY) || !(flags&SQLITE_OPEN_MAIN_DB) ||
       (flags&(SQLITE_OPEN_READWRITE|SQLITE_OPEN_CREATE|SQLITE_OPEN_DELETEONCLOSE))) return SQLITE_READONLY;
    g->real=(sqlite3_file *)(g+1); memset(g->real,0,base_vfs->szOsFile);
    int rc=base_vfs->xOpen(base_vfs,name,g->real,flags,out);
    if(rc==SQLITE_OK)g->file.pMethods=&io; return rc;
}
static int gDelete(sqlite3_vfs *v,const char *name,int sync){return SQLITE_READONLY;}
static int gAccess(sqlite3_vfs *v,const char *name,int flags,int *r){return base_vfs->xAccess(base_vfs,name,flags,r);}
static int gFull(sqlite3_vfs *v,const char *name,int n,char *r){return base_vfs->xFullPathname(base_vfs,name,n,r);}
static void *gDlOpen(sqlite3_vfs *v,const char *name){return NULL;}
static void gDlError(sqlite3_vfs *v,int n,char *r){if(n>0){strncpy(r,"Extensions disabled",n-1);r[n-1]=0;}}
static void (*gDlSym(sqlite3_vfs *v,void *p,const char *n))(void){return NULL;}
static void gDlClose(sqlite3_vfs *v,void *p){}
static int gRandom(sqlite3_vfs *v,int n,char *r){return base_vfs->xRandomness(base_vfs,n,r);}
static int gSleep(sqlite3_vfs *v,int n){return base_vfs->xSleep(base_vfs,n);}
static int gTime(sqlite3_vfs *v,double *r){return base_vfs->xCurrentTime(base_vfs,r);}
static int gError(sqlite3_vfs *v,int n,char *r){return base_vfs->xGetLastError?base_vfs->xGetLastError(base_vfs,n,r):0;}
static int gTime64(sqlite3_vfs *v,sqlite3_int64 *r){return base_vfs->xCurrentTimeInt64(base_vfs,r);}

static int readonly_authorizer(void *p,int op,const char *a,const char *b,const char *db,const char *trigger){
    switch(op){
      case SQLITE_SELECT: case SQLITE_READ: case SQLITE_RECURSIVE: return SQLITE_OK;
      case SQLITE_FUNCTION: return b && strcmp(b,"load_extension")==0?SQLITE_DENY:SQLITE_OK;
      case SQLITE_PRAGMA:
        if(!b && a && (!strcmp(a,"user_version") || !strcmp(a,"table_info") ||
            !strcmp(a,"compile_options") || !strcmp(a,"integrity_check") ||
            !strcmp(a,"quick_check") || !strcmp(a,"journal_mode") || !strcmp(a,"data_version")))return SQLITE_OK;
        /* PRAGMA table_info(table) has a read-only argument. */
        if(a && (!strcmp(a,"table_info") || !strcmp(a,"table_xinfo")))return SQLITE_OK;
        return SQLITE_DENY;
      default: return SQLITE_DENY;
    }
}
static void error(JNIEnv *env,const char *m){jclass c=(*env)->FindClass(env,"java/lang/IllegalStateException"); if(c)(*env)->ThrowNew(env,c,m);}
/* JNI modified UTF-8 differs from SQLite UTF-8. Always use Java UTF-16 for values. */
static jstring utf16(JNIEnv *e,const void *p,int bytes){return p?(*e)->NewString(e,(const jchar *)p,bytes/2):NULL;}
static sqlite3 *dbptr(jlong p){return (sqlite3 *)(intptr_t)p;}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm,void *reserved){
    if(sqlite3_initialize()!=SQLITE_OK)return JNI_ERR;
    /* Use SQLite's Unicode long-path Windows VFS rather than MAX_PATH default. */
    base_vfs=sqlite3_vfs_find("win32-longpath"); if(!base_vfs)return JNI_ERR;
    memset(&guard_vfs,0,sizeof(guard_vfs));guard_vfs.iVersion=2;
    guard_vfs.szOsFile=sizeof(GuardFile)+base_vfs->szOsFile;guard_vfs.mxPathname=base_vfs->mxPathname;
    guard_vfs.zName="enterpriseai-readonly";guard_vfs.xOpen=gOpen;guard_vfs.xDelete=gDelete;
    guard_vfs.xAccess=gAccess;guard_vfs.xFullPathname=gFull;guard_vfs.xDlOpen=gDlOpen;
    guard_vfs.xDlError=gDlError;guard_vfs.xDlSym=gDlSym;guard_vfs.xDlClose=gDlClose;
    guard_vfs.xRandomness=gRandom;guard_vfs.xSleep=gSleep;guard_vfs.xCurrentTime=gTime;
    guard_vfs.xGetLastError=gError;guard_vfs.xCurrentTimeInt64=gTime64;
    if(sqlite3_vfs_register(&guard_vfs,0)!=SQLITE_OK)return JNI_ERR; return JNI_VERSION_1_8;
}
JNIEXPORT jlong JNICALL Java_ai_enterprise_database_SQLiteNative_open(JNIEnv *e,jclass c,jstring path){
    sqlite3 *db=NULL;
    /* Encode using Java's real UTF-8 encoder, including supplementary characters. */
    jclass sc=(*e)->FindClass(e,"java/lang/String");
    jmethodID getBytes=(*e)->GetMethodID(e,sc,"getBytes","(Ljava/lang/String;)[B");
    jstring encoding=(*e)->NewStringUTF(e,"UTF-8");
    jbyteArray ba=(jbyteArray)(*e)->CallObjectMethod(e,path,getBytes,encoding);
    if((*e)->ExceptionCheck(e))return 0;
    jsize length=(*e)->GetArrayLength(e,ba);char *name=malloc((size_t)length+1);
    if(!name){error(e,"Not enough memory for database path");return 0;}
    (*e)->GetByteArrayRegion(e,ba,0,length,(jbyte *)name);name[length]=0;
    int rc=sqlite3_open_v2(name,&db,SQLITE_OPEN_READONLY|SQLITE_OPEN_FULLMUTEX,"enterpriseai-readonly");free(name);
    if(rc!=SQLITE_OK){error(e,db?sqlite3_errmsg(db):"Cannot open database read-only");if(db)sqlite3_close(db);return 0;}
#ifndef SQLITE_OMIT_LOAD_EXTENSION
    sqlite3_enable_load_extension(db,0);
#endif
    sqlite3_busy_timeout(db,1000);
    sqlite3_db_config(db,SQLITE_DBCONFIG_DEFENSIVE,1,NULL);
    rc=sqlite3_exec(db,"PRAGMA query_only=ON; PRAGMA temp_store=MEMORY; PRAGMA mmap_size=0;",0,0,0);
    if(rc!=SQLITE_OK){error(e,sqlite3_errmsg(db));sqlite3_close(db);return 0;}
    sqlite3_set_authorizer(db,readonly_authorizer,NULL);
    return (jlong)(intptr_t)db;
}
JNIEXPORT void JNICALL Java_ai_enterprise_database_SQLiteNative_close(JNIEnv *e,jclass c,jlong p){if(p)sqlite3_close(dbptr(p));}
JNIEXPORT void JNICALL Java_ai_enterprise_database_SQLiteNative_interrupt(JNIEnv *e,jclass c,jlong p){if(p)sqlite3_interrupt(dbptr(p));}
JNIEXPORT jstring JNICALL Java_ai_enterprise_database_SQLiteNative_version(JNIEnv *e,jclass c){return (*e)->NewStringUTF(e,sqlite3_libversion());}
JNIEXPORT jobjectArray JNICALL Java_ai_enterprise_database_SQLiteNative_query(JNIEnv *e,jclass c,jlong p,jstring sql,jobjectArray params){
    sqlite3 *db=dbptr(p);sqlite3_stmt *st=NULL;
    const jchar *text=(*e)->GetStringChars(e,sql,0); const void *tail=NULL;
    int len=(*e)->GetStringLength(e,sql)*2;
    int rc=sqlite3_prepare16_v2(db,text,len,&st,&tail);
    /* Multiple statements are never accepted. */
    int leftover=tail?(int)((const jchar *)tail-text):len/2;int valid=1;
    for(int i=leftover;i<len/2;i++){jchar x=text[i];if(x!=' '&&x!='\t'&&x!='\n'&&x!='\r'&&x!=';')valid=0;}
    (*e)->ReleaseStringChars(e,sql,text);
    /* FTS5 declares SELECTs using its virtual table as potentially writable to
     * sqlite3_stmt_readonly(). Authorization + query_only + the guarded VFS
     * are the actual non-writing boundary; denying that flag would reject search. */
    if(rc!=SQLITE_OK || !st || !valid){error(e,rc!=SQLITE_OK?sqlite3_errmsg(db):!st?"Empty statement":"Multiple SQL statements are forbidden");if(st)sqlite3_finalize(st);return NULL;}
    int count=(*e)->GetArrayLength(e,params);
    if(count!=sqlite3_bind_parameter_count(st)){sqlite3_finalize(st);error(e,"Incorrect SQL parameter count");return NULL;}
    for(int i=0;i<count;i++){
      jstring value=(jstring)(*e)->GetObjectArrayElement(e,params,i);
      if(!value)rc=sqlite3_bind_null(st,i+1);
      else {const jchar *v=(*e)->GetStringChars(e,value,0);rc=sqlite3_bind_text16(st,i+1,v,(*e)->GetStringLength(e,value)*2,SQLITE_TRANSIENT);(*e)->ReleaseStringChars(e,value,v);(*e)->DeleteLocalRef(e,value);}
      if(rc!=SQLITE_OK){error(e,sqlite3_errmsg(db));sqlite3_finalize(st);return NULL;}
    }
    jclass stringClass=(*e)->FindClass(e,"java/lang/String");jclass rowClass=(*e)->FindClass(e,"[Ljava/lang/String;");
    jclass listClass=(*e)->FindClass(e,"java/util/ArrayList");
    jmethodID init=(*e)->GetMethodID(e,listClass,"<init>","()V");jmethodID add=(*e)->GetMethodID(e,listClass,"add","(Ljava/lang/Object;)Z");
    jmethodID size=(*e)->GetMethodID(e,listClass,"size","()I");jmethodID get=(*e)->GetMethodID(e,listClass,"get","(I)Ljava/lang/Object;");
    jobject rows=(*e)->NewObject(e,listClass,init);int columns=sqlite3_column_count(st);
    jobjectArray head=(*e)->NewObjectArray(e,columns,stringClass,NULL);
    for(int i=0;i<columns;i++){const jchar *name=(const jchar *)sqlite3_column_name16(st,i);int n=0;while(name[n])n++;jstring s=utf16(e,name,n*2);
      (*e)->SetObjectArrayElement(e,head,i,s);(*e)->DeleteLocalRef(e,s);}
    (*e)->CallBooleanMethod(e,rows,add,head);(*e)->DeleteLocalRef(e,head);
    while((rc=sqlite3_step(st))==SQLITE_ROW){
      jobjectArray row=(*e)->NewObjectArray(e,columns,stringClass,NULL);
      for(int i=0;i<columns;i++){const void *v=sqlite3_column_text16(st,i);jstring s=utf16(e,v,sqlite3_column_bytes16(st,i));
        (*e)->SetObjectArrayElement(e,row,i,s);if(s)(*e)->DeleteLocalRef(e,s);}
      (*e)->CallBooleanMethod(e,rows,add,row);(*e)->DeleteLocalRef(e,row);
      if((*e)->ExceptionCheck(e)){sqlite3_finalize(st);return NULL;}
    }
    if(rc!=SQLITE_DONE){error(e,sqlite3_errmsg(db));sqlite3_finalize(st);return NULL;}
    sqlite3_finalize(st);int n=(*e)->CallIntMethod(e,rows,size);jobjectArray result=(*e)->NewObjectArray(e,n,rowClass,NULL);
    for(int i=0;i<n;i++){jobject row=(*e)->CallObjectMethod(e,rows,get,i);(*e)->SetObjectArrayElement(e,result,i,row);(*e)->DeleteLocalRef(e,row);}
    return result;
}
