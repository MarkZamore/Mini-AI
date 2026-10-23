#pragma once
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <bcrypt.h>
#include <nlohmann/json.hpp>
#include <algorithm>
#include <atomic>
#include <array>
#include <cstdint>
#include <cmath>
#include <memory>
#include <map>
#include <mutex>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

namespace worker {
using Json = nlohmann::json;
constexpr size_t max_frame = 8 * 1024 * 1024;
constexpr const char * protocol = "enterpriseai.inference.v1";
struct Failure : std::runtime_error {
    std::string code;
    Failure(std::string code, const std::string & message) : std::runtime_error(message), code(std::move(code)) {}
};
[[noreturn]] inline void invalid(const char * message) { throw Failure("INVALID_REQUEST",message); }
struct Handle {
    HANDLE value = INVALID_HANDLE_VALUE;
    Handle() = default;
    explicit Handle(HANDLE h) : value(h) {}
    Handle(const Handle &) = delete;
    Handle & operator=(const Handle &) = delete;
    Handle(Handle && other) noexcept : value(other.value) { other.value = INVALID_HANDLE_VALUE; }
    Handle & operator=(Handle && other) noexcept {
        if (this != &other) { if (value != INVALID_HANDLE_VALUE && value) CloseHandle(value); value=other.value; other.value=INVALID_HANDLE_VALUE; }
        return *this;
    }
    ~Handle() { if (value != INVALID_HANDLE_VALUE && value) CloseHandle(value); }
};
inline std::wstring wide(const std::string & text) {
    if (text.empty()) return {};
    int n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,text.data(),int(text.size()),nullptr,0);
    if (!n) invalid("Invalid UTF-8");
    std::wstring result(n,L'\0');
    MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,text.data(),int(text.size()),result.data(),n);
    return result;
}
inline std::string utf8(const std::wstring & text) {
    if (text.empty()) return {};
    int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,text.data(),int(text.size()),nullptr,0,nullptr,nullptr);
    if (!n) throw Failure("INCOMPATIBLE_MODEL","Invalid Windows path");
    std::string result(n,'\0');
    WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,text.data(),int(text.size()),result.data(),n,nullptr,nullptr);
    return result;
}
inline std::wstring extended(std::wstring path) {
    if (path.rfind(L"\\\\?\\",0)==0) return path;
    if (path.rfind(L"\\\\",0)==0) return L"\\\\?\\UNC\\"+path.substr(2);
    if (path.size()<3 || path[1]!=L':' || path[2]!=L'\\') throw Failure("INCOMPATIBLE_MODEL","Expected absolute Windows path");
    return L"\\\\?\\"+path;
}
inline std::wstring executable_path() {
    std::vector<wchar_t> buffer(512);
    for (;;) {
        DWORD n=GetModuleFileNameW(nullptr,buffer.data(),DWORD(buffer.size()));
        if (!n) throw Failure("INTERNAL_ERROR","Cannot locate worker executable");
        if (n<buffer.size()-1) return extended(std::wstring(buffer.data(),n));
        if (buffer.size()>=32768) throw Failure("INCOMPATIBLE_MODEL","Executable path is too long");
        buffer.resize(std::min(size_t(32768),buffer.size()*2));
    }
}
inline std::wstring final_path(HANDLE handle) {
    DWORD n=GetFinalPathNameByHandleW(handle,nullptr,0,FILE_NAME_NORMALIZED|VOLUME_NAME_DOS);
    if (!n || n>32768) throw Failure("INCOMPATIBLE_MODEL","Cannot resolve package file");
    std::wstring result(n,L'\0');
    DWORD actual=GetFinalPathNameByHandleW(handle,result.data(),n,FILE_NAME_NORMALIZED|VOLUME_NAME_DOS);
    if (!actual || actual>=n) throw Failure("INCOMPATIBLE_MODEL","Cannot resolve package file");
    result.resize(actual);
    return result;
}
inline bool equal_path(const std::wstring & a,const std::wstring & b) {
    return CompareStringOrdinal(a.data(),int(a.size()),b.data(),int(b.size()),TRUE)==CSTR_EQUAL;
}
inline bool contained(const std::wstring & root,const std::wstring & path) {
    return path.size()>root.size()+1 && path[root.size()]==L'\\' &&
        CompareStringOrdinal(root.data(),int(root.size()),path.data(),int(root.size()),TRUE)==CSTR_EQUAL;
}
inline std::string read_small(HANDLE handle,size_t limit) {
    LARGE_INTEGER size;
    if (!GetFileSizeEx(handle,&size) || size.QuadPart<0 || uint64_t(size.QuadPart)>limit)
        throw Failure("INCOMPATIBLE_MODEL","Package metadata exceeds size limit");
    LARGE_INTEGER zero{};
    if (!SetFilePointerEx(handle,zero,nullptr,FILE_BEGIN)) throw Failure("INCOMPATIBLE_MODEL","Cannot seek metadata");
    std::string result(size_t(size.QuadPart),'\0');
    size_t offset=0;
    while (offset<result.size()) {
        DWORD n=0;
        if (!ReadFile(handle,result.data()+offset,DWORD(result.size()-offset),&n,nullptr) || !n)
            throw Failure("INCOMPATIBLE_MODEL","Cannot read package metadata");
        offset+=n;
    }
    return result;
}
inline Json parse(const std::string & text) {
    if (text.size()>max_frame || text.compare(0,3,"\xef\xbb\xbf")==0) invalid("Invalid JSONL frame size or BOM");
    bool quoted=false,escape=false;
    size_t depth=0;
    for (unsigned char c:text) {
        if (quoted) { if (escape) escape=false; else if (c=='\\') escape=true; else if (c=='\"') quoted=false; }
        else if (c=='\"') quoted=true;
        else if (c=='{' || c=='[') { if (++depth>32) invalid("JSON nesting limit exceeded"); }
        else if (c=='}' || c==']') { if (!depth) invalid("Malformed JSON"); --depth; }
    }
    std::vector<std::set<std::string>> keys;
    auto callback=[&](int,Json::parse_event_t event,Json & value) {
        if (event==Json::parse_event_t::object_start) keys.emplace_back();
        else if (event==Json::parse_event_t::object_end) keys.pop_back();
        else if (event==Json::parse_event_t::key && !keys.back().insert(value.get<std::string>()).second) invalid("Duplicate JSON key");
        return true;
    };
    try {
        auto result=Json::parse(text,callback);
        if (!result.is_object()) invalid("JSONL frame must be an object");
        return result;
    } catch (const Failure &) { throw; }
    catch (const Json::exception &) { invalid("Malformed JSON or UTF-8"); }
}
inline void keys(const Json & value,std::initializer_list<const char *> wanted) {
    if (!value.is_object() || value.size()!=wanted.size()) invalid("Unexpected object fields");
    for (auto key:wanted) if (!value.contains(key)) invalid("Missing object field");
}
inline std::string string(const Json & value,const char * key,size_t maximum,bool empty=false) {
    if (!value.contains(key) || !value.at(key).is_string()) invalid("Expected string field");
    auto result=value.at(key).get<std::string>();
    size_t chars=0;
    for (unsigned char c:result) if ((c&0xc0)!=0x80) ++chars;
    if ((!empty && !chars) || chars>maximum) invalid("String field exceeds limits");
    return result;
}
inline uint64_t integer(const Json & value,const char * key,uint64_t minimum,uint64_t maximum) {
    if (!value.contains(key) || !value.at(key).is_number()) invalid("Expected integer field");
    const auto & number=value.at(key);
    if (number.is_number_float()) {
        double decimal=number.get<double>();
        if (!std::isfinite(decimal) || std::floor(decimal)!=decimal || decimal<double(minimum) || decimal>double(maximum)) invalid("Integer field exceeds limits");
        return uint64_t(decimal);
    }
    if (number.is_number_integer() && !number.is_number_unsigned() && number.get<int64_t>()<0) invalid("Negative integer field");
    auto result=number.get<uint64_t>();
    if (result<minimum || result>maximum) invalid("Integer field exceeds limits");
    return result;
}
inline std::string sha256(HANDLE file,const std::atomic<bool> & stop) {
    BCRYPT_ALG_HANDLE algorithm=nullptr;
    BCRYPT_HASH_HANDLE hash=nullptr;
    struct Cleanup { BCRYPT_ALG_HANDLE & a; BCRYPT_HASH_HANDLE & h; ~Cleanup() { if(h) BCryptDestroyHash(h); if(a) BCryptCloseAlgorithmProvider(a,0); } } cleanup{algorithm,hash};
    if (BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,0)<0 ||
        BCryptCreateHash(algorithm,&hash,nullptr,0,nullptr,0,0)<0) throw Failure("INTERNAL_ERROR","Cannot initialize SHA-256");
    LARGE_INTEGER zero{};
    if (!SetFilePointerEx(file,zero,nullptr,FILE_BEGIN)) throw Failure("INCOMPATIBLE_MODEL","Cannot read package file");
    std::array<unsigned char,256*1024> buffer;
    DWORD n=0;
    for (;;) {
        if (stop.load()) throw Failure("CANCELLED","Worker is shutting down");
        if (!ReadFile(file,buffer.data(),DWORD(buffer.size()),&n,nullptr)) throw Failure("INCOMPATIBLE_MODEL","Cannot hash package file");
        if (!n) break;
        if (BCryptHashData(hash,buffer.data(),n,0)<0) throw Failure("INTERNAL_ERROR","SHA-256 failed");
    }
    std::array<unsigned char,32> bytes;
    if (BCryptFinishHash(hash,bytes.data(),DWORD(bytes.size()),0)<0) throw Failure("INTERNAL_ERROR","SHA-256 failed");
    std::string result;
    for (auto b:bytes) { result.push_back("0123456789abcdef"[b>>4]); result.push_back("0123456789abcdef"[b&15]); }
    return result;
}
inline uint64_t available_memory() {
    MEMORYSTATUSEX status{sizeof(MEMORYSTATUSEX)};
    if (!GlobalMemoryStatusEx(&status)) throw Failure("INTERNAL_ERROR","Cannot query physical memory");
    return status.ullAvailPhys;
}
struct Package {
    std::wstring root,weights;
    std::vector<Handle> open_files;
    uint32_t context=0,threads=0;
    uint64_t minimum_memory=0,maximum_memory=0;
    std::string precision;
    unsigned schema_version=1, device=0;
    std::string backend="cpu";
    uint64_t gpu_budget=0,gpu_reserve=0;
    std::vector<std::wstring> dependencies;
    std::map<std::string,std::string> verified_assets;
    bool inventory_verified=false;
    bool cpu_avx2=false;
    void load_runtime() const;
    explicit Package(const std::atomic<bool> & stop);
    Handle open(const std::string & relative) const;
};
}
