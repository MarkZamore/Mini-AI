#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>

/* Test executable only: all communication is anonymous stdin/stdout pipes.
   It never opens files, contacts a network, or modifies the registry. */
static int contains(const char *line, const char *word) { return strstr(line, word) != NULL; }
static void emit(const char *line) { puts(line); fflush(stdout); }

int wmain(int argc, wchar_t **argv) {
    SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX);
    if (argc > 1 && wcscmp(argv[1], L"--descendant") == 0) { Sleep(INFINITE); return 0; }
    if (argc < 2 || wcscmp(argv[1], L"--stdio") != 0) return 91;
    char line[65536];
    int pending_cancel = 0;
    while (fgets(line, sizeof(line), stdin)) {
        if (contains(line, "\"type\":\"hello\"")) {
            DWORD cwd_length = GetCurrentDirectoryW(0, NULL);
            wchar_t cwd[32768], system[32768];
            DWORD cwd_result = GetCurrentDirectoryW(32768, cwd), system_result = GetSystemDirectoryW(system, 32768);
            int system_cwd = cwd_result && system_result && _wcsicmp(cwd, system) == 0;
            printf("{\"type\":\"ready\",\"pid\":%lu,\"console\":%s,\"cwdCharacters\":%lu,\"cwdIsSystemDirectory\":%s}\n", GetCurrentProcessId(), GetConsoleWindow() ? "true" : "false", cwd_length ? cwd_length - 1 : 0, system_cwd ? "true" : "false"); fflush(stdout);
        } else if (contains(line, "\"type\":\"quit\"")) {
            emit("{\"type\":\"bye\"}"); return 0;
        } else if (contains(line, "\"type\":\"cancel\"")) {
            emit(pending_cancel ? "{\"type\":\"cancelled\"}" : "{\"type\":\"error\",\"message\":\"no pending request\"}"); pending_cancel = 0;
        } else if (contains(line, "\"type\":\"generate\"")) {
            if (contains(line, "\"mode\":\"hang\"")) { Sleep(INFINITE); }
            else if (contains(line, "\"mode\":\"cancel\"")) { pending_cancel = 1; }
            else if (contains(line, "\"mode\":\"crash\"")) { ExitProcess(42); }
            else if (contains(line, "\"mode\":\"memory\"")) {
                void *allocation = VirtualAlloc(NULL, (SIZE_T)128 * 1024 * 1024, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
                printf("{\"type\":\"memory\",\"denied\":%s,\"error\":%lu}\n", allocation ? "false" : "true", allocation ? 0UL : GetLastError()); fflush(stdout);
                if (allocation) VirtualFree(allocation, 0, MEM_RELEASE);
                emit("{\"type\":\"done\"}");
            } else if (contains(line, "\"mode\":\"descendant\"")) {
                wchar_t application[32768], command[32768]; STARTUPINFOW startup = {sizeof(startup)}; PROCESS_INFORMATION process = {0};
                DWORD length = GetModuleFileNameW(NULL, application, 32768);
                if (!length || length >= 32000) { emit("{\"type\":\"error\",\"message\":\"module path unavailable\"}"); continue; }
                swprintf_s(command, 32768, L"\"%s\" --descendant", application);
                if (!CreateProcessW(application, command, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, NULL, &startup, &process)) {
                    emit("{\"type\":\"error\",\"message\":\"descendant start failed\"}"); continue;
                }
                printf("{\"type\":\"descendant\",\"pid\":%lu}\n", process.dwProcessId); fflush(stdout);
                CloseHandle(process.hThread); CloseHandle(process.hProcess);
            } else {
                emit("{\"type\":\"token\",\"text\":\"Тест ә ғ ң 😀\"}");
                emit("{\"type\":\"done\"}");
            }
        }
    }
    return 0;
}
