#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>
#ifndef VARIANT
#define VARIANT 1
#endif
#if VARIANT == 1
#define WEIGHT L"weights/test.gguf"
#define MAGIC "GGUFfixture"
#elif VARIANT == 2
#define WEIGHT L"weights/test.safetensors"
#define MAGIC "SAFEfixture"
#else
#define WEIGHT L"weights/test.bin"
#define MAGIC "BINfixture"
#endif
/* Test-only worker, not a language model. Never writes files or registry values. */
static void emit(const char *text) { puts(text); fflush(stdout); }
static int weights_valid(void) {
    wchar_t path[32768]; DWORD n = GetModuleFileNameW(NULL, path, 32768);
    if (!n || n >= 32768) return 0;
    wchar_t *slash = wcsrchr(path, L'\\'); if (!slash) return 0;
    slash[1] = 0; if (wcscat_s(path,32768,WEIGHT)) return 0;
    HANDLE file = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) return 0;
    char bytes[32] = {0}; DWORD count = 0;
    int ok = ReadFile(file,bytes,sizeof(bytes),&count,NULL) && count >= strlen(MAGIC) && !memcmp(bytes,MAGIC,strlen(MAGIC));
    CloseHandle(file); return ok;
}
int main(int argc, char **argv) {
    SetErrorMode(SEM_FAILCRITICALERRORS|SEM_NOGPFAULTERRORBOX|SEM_NOOPENFILEERRORBOX);
    if (argc != 2 || strcmp(argv[1],"--stdio")) return 91;
    char line[65536], id[129];
    while (fgets(line,sizeof(line),stdin)) {
        if (!strchr(line,'\n')) return 92;
        if (strstr(line,"\"hello\"")) {
            if (!weights_valid()) { emit("{\"type\":\"error\",\"code\":\"INCOMPATIBLE_MODEL\",\"message\":\"Fixture weight content rejected\"}"); return 0; }
            emit("{\"type\":\"ready\",\"protocol\":\"enterpriseai.inference.v1\"}");
        } else if (strstr(line,"\"generate\"")) {
            char *p = strstr(line,"\"requestId\""); if (!p || !(p = strchr(p,':'))) return 93;
            p++; while (*p==' ') p++; if (*p++!='\"') return 94;
            size_t n=0; while (*p && *p!='\"' && n<128) { if (*p=='\\') return 95; id[n++]=*p++; } id[n]=0;
            if (*p!='\"' || !n) return 96;
            if (strstr(line,"fixture-crash")) ExitProcess(42);
            if (strstr(line,"fixture-error")) {
                printf("{\"type\":\"error\",\"requestId\":\"%s\",\"code\":\"OUT_OF_MEMORY\",\"message\":\"Simulated fixture OOM\"}\n",id); fflush(stdout); continue;
            }
            printf("{\"type\":\"token\",\"requestId\":\"%s\",\"text\":\"Variant%d\"}\n",id,VARIANT); fflush(stdout);
            if (strstr(line,"fixture-hang")) Sleep(INFINITE);
            printf("{\"type\":\"done\",\"requestId\":\"%s\"}\n",id); fflush(stdout);
        } else if (strstr(line,"\"cancel\"")) return 0;
        else return 97;
    }
    return 0;
}
