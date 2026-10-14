package ai.enterprise.inference;

import ai.enterprise.contracts.Api;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded, read-only PE32+ import inspection. This cannot attest dynamic LoadLibrary calls. */
public final class WorkerPeAudit {
    private WorkerPeAudit() {}
    private static final Set<String> INBOX = Set.of("kernel32.dll", "kernelbase.dll", "ntdll.dll",
        "advapi32.dll", "bcrypt.dll", "bcryptprimitives.dll", "crypt32.dll", "cryptbase.dll",
        "user32.dll", "gdi32.dll", "gdi32full.dll", "ole32.dll", "oleaut32.dll", "combase.dll",
        "shell32.dll", "shlwapi.dll", "shcore.dll", "comdlg32.dll", "comctl32.dll", "uxtheme.dll",
        "dwmapi.dll", "imm32.dll", "msctf.dll", "version.dll", "setupapi.dll", "cfgmgr32.dll",
        "rpcrt4.dll", "secur32.dll", "sspicli.dll", "normaliz.dll", "psapi.dll", "powrprof.dll",
        "profapi.dll", "userenv.dll", "winmm.dll", "msvcrt.dll", "msvcp_win.dll", "ucrtbase.dll", "gdiplus.dll");
    // Explicit Windows 11 baseline contracts; a DLL merely named api-ms-win-* is not trusted.
    private static final Set<String> API_SETS = Set.of(
        "api-ms-win-core-console-l1-1-0.dll", "api-ms-win-core-errorhandling-l1-1-0.dll",
        "api-ms-win-core-file-l1-1-0.dll", "api-ms-win-core-file-l1-2-0.dll", "api-ms-win-core-file-l2-1-0.dll",
        "api-ms-win-core-handle-l1-1-0.dll", "api-ms-win-core-heap-l1-1-0.dll",
        "api-ms-win-core-interlocked-l1-1-0.dll", "api-ms-win-core-libraryloader-l1-1-0.dll",
        "api-ms-win-core-libraryloader-l1-2-0.dll", "api-ms-win-core-libraryloader-l1-2-1.dll",
        "api-ms-win-core-localization-l1-2-0.dll", "api-ms-win-core-localization-l1-2-1.dll",
        "api-ms-win-core-memory-l1-1-0.dll", "api-ms-win-core-memory-l1-1-1.dll", "api-ms-win-core-memory-l1-1-2.dll",
        "api-ms-win-core-processthreads-l1-1-0.dll", "api-ms-win-core-processthreads-l1-1-1.dll",
        "api-ms-win-core-processthreads-l1-1-2.dll", "api-ms-win-core-profile-l1-1-0.dll",
        "api-ms-win-core-rtlsupport-l1-1-0.dll", "api-ms-win-core-string-l1-1-0.dll", "api-ms-win-core-string-l2-1-0.dll",
        "api-ms-win-core-synch-l1-1-0.dll", "api-ms-win-core-synch-l1-2-0.dll",
        "api-ms-win-core-sysinfo-l1-1-0.dll", "api-ms-win-core-sysinfo-l1-2-0.dll",
        "api-ms-win-core-timezone-l1-1-0.dll", "api-ms-win-core-util-l1-1-0.dll",
        "api-ms-win-crt-conio-l1-1-0.dll", "api-ms-win-crt-convert-l1-1-0.dll",
        "api-ms-win-crt-environment-l1-1-0.dll", "api-ms-win-crt-filesystem-l1-1-0.dll",
        "api-ms-win-crt-heap-l1-1-0.dll", "api-ms-win-crt-locale-l1-1-0.dll",
        "api-ms-win-crt-math-l1-1-0.dll", "api-ms-win-crt-multibyte-l1-1-0.dll",
        "api-ms-win-crt-private-l1-1-0.dll", "api-ms-win-crt-process-l1-1-0.dll",
        "api-ms-win-crt-runtime-l1-1-0.dll", "api-ms-win-crt-stdio-l1-1-0.dll",
        "api-ms-win-crt-string-l1-1-0.dll", "api-ms-win-crt-time-l1-1-0.dll", "api-ms-win-crt-utility-l1-1-0.dll");

    public static List<String> verify(Path executable, BooleanSupplier cancelled) throws IOException {
        try (var channel = FileChannel.open(executable, StandardOpenOption.READ)) {
            return new Reader(channel, cancelled).verify();
        } catch (InvalidPe e) {
            throw new Api.ServiceException("MODEL_PACKAGE_NOT_SELF_CONTAINED", e.getMessage(), e);
        }
    }
    private static final class InvalidPe extends IllegalArgumentException {
        InvalidPe(String message) { super("Недопустимый автономный worker: " + message); }
    }
    private record Section(long virtualAddress, long virtualSize, long rawOffset, long rawSize) {}
    private static final class Reader {
        final FileChannel file; final long length; final BooleanSupplier cancelled;
        final List<Section> sections = new ArrayList<>(); final LinkedHashSet<String> imports = new LinkedHashSet<>();
        long headers, imageBase;
        Reader(FileChannel file, BooleanSupplier cancelled) throws IOException {
            this.file = file; this.length = file.size(); this.cancelled = cancelled;
        }
        ByteBuffer read(long offset, int count) throws IOException {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            if (offset < 0 || count < 0 || offset > length || count > length - offset) bad("данные вне файла");
            var bytes = ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN);
            while (bytes.hasRemaining()) if (file.read(bytes, offset + bytes.position()) <= 0) bad("усечённый файл");
            return bytes.flip();
        }
        static long u32(ByteBuffer bytes, int offset) { return Integer.toUnsignedLong(bytes.getInt(offset)); }
        List<String> verify() throws IOException {
            ByteBuffer dos = read(0, 64);
            if (dos.getShort(0) != 0x5a4d) bad("нет MZ");
            long pe = u32(dos, 60); if (pe < 64) bad("неверное смещение PE");
            ByteBuffer coff = read(pe, 24);
            if (coff.getInt(0) != 0x00004550 || Short.toUnsignedInt(coff.getShort(4)) != 0x8664) bad("требуется PE Windows x64");
            int count = Short.toUnsignedInt(coff.getShort(6)), optionalSize = Short.toUnsignedInt(coff.getShort(20));
            int characteristics = Short.toUnsignedInt(coff.getShort(22));
            if (count < 1 || count > 96 || optionalSize < 240 || optionalSize > 4096
                || (characteristics & 0x0002) == 0 || (characteristics & 0x2000) != 0) bad("неверный заголовок EXE");
            ByteBuffer optional = read(pe + 24, optionalSize);
            if (Short.toUnsignedInt(optional.getShort(0)) != 0x20b) bad("требуется PE32+");
            int subsystem = Short.toUnsignedInt(optional.getShort(68));
            if (subsystem != 2 && subsystem != 3) bad("неподдерживаемая подсистема");
            imageBase = optional.getLong(24); headers = u32(optional, 60);
            long directories = u32(optional, 108);
            if (headers < pe + 24 + optionalSize + count * 40L || headers > length
                || directories < 14 || directories > 16 || 112 + directories * 8 > optionalSize) bad("неверные каталоги PE");
            if (directories >= 15 && (u32(optional,112+14*8) != 0 || u32(optional,116+14*8) != 0))
                bad("управляемый CLR worker требует дополнительную среду выполнения");
            ByteBuffer table = read(pe + 24 + optionalSize, count * 40);
            for (int i = 0; i < count; i++) {
                int p = i * 40;
                Section s = new Section(u32(table,p+12), u32(table,p+8), u32(table,p+20), u32(table,p+16));
                if (s.rawOffset > length || s.rawSize > length - s.rawOffset
                    || (s.rawSize > 0 && (s.rawOffset < headers || s.virtualAddress < headers))
                    || s.virtualAddress + Math.max(s.virtualSize,s.rawSize) > 0x1_0000_0000L) bad("неверная секция PE");
                for (Section old : sections) if (s.rawSize > 0 && old.rawSize > 0
                    && (overlap(s.virtualAddress, Math.max(s.virtualSize,s.rawSize), old.virtualAddress, Math.max(old.virtualSize,old.rawSize))
                        || overlap(s.rawOffset,s.rawSize,old.rawOffset,old.rawSize))) bad("перекрывающиеся секции");
                sections.add(s);
            }
            inspectDirectory(optional, 1, false);
            inspectDirectory(optional, 13, true);
            return List.copyOf(imports);
        }
        static boolean overlap(long a, long n, long b, long m) { return a < b + m && b < a + n; }
        long physical(long rva, int count) {
            if (rva < 0 || rva > 0xffff_ffffL || count < 0 || rva + count > 0x1_0000_0000L) { bad("неверный RVA"); }
            if (rva < headers && rva + count <= headers) return rva;
            for (Section s : sections) if (rva >= s.virtualAddress && rva - s.virtualAddress <= s.rawSize
                && count <= s.rawSize - (rva - s.virtualAddress)) return s.rawOffset + rva - s.virtualAddress;
            bad("RVA не принадлежит данным файла"); return 0;
        }
        void inspectDirectory(ByteBuffer optional, int index, boolean delayed) throws IOException {
            long rva = u32(optional,112+index*8), size = u32(optional,116+index*8);
            if (rva == 0 && size == 0) return;
            int stride = delayed ? 32 : 20;
            if (rva == 0 || size < stride || size > 16 * 1024 * 1024L) bad("неверный каталог импортов");
            long base = physical(rva, Math.toIntExact(size));
            for (int i = 0; i < 4096 && (i+1L)*stride <= size; i++) {
                ByteBuffer descriptor = read(base + (long)i*stride, stride);
                boolean zero = true;
                for (int j=0;j<stride;j+=4) if (descriptor.getInt(j)!=0) zero=false;
                if (zero) return;
                long name = u32(descriptor,delayed ? 4 : 12);
                if (delayed) {
                    long attributes = u32(descriptor,0);
                    if (attributes != 0 && attributes != 1) bad("неверные атрибуты delay import");
                    if (attributes == 0) {
                        if (imageBase < 0 || imageBase > name) bad("неверный VA delay import");
                        name -= imageBase;
                    }
                }
                if (name == 0) bad("пустое имя импортируемой библиотеки");
                String dll = dllName(name);
                if (!INBOX.contains(dll) && !API_SETS.contains(dll))
                    bad("требуется внешняя библиотека " + dll);
                imports.add(dll);
            }
            bad("каталог импортов без терминатора");
        }
        String dllName(long rva) throws IOException {
            StringBuilder name = new StringBuilder();
            for (int i=0;i<260;i++) {
                int b = Byte.toUnsignedInt(read(physical(rva+i,1),1).get());
                if (b == 0) {
                    String dll = name.toString().toLowerCase(Locale.ROOT);
                    if (!dll.matches("[a-z0-9_.-]+\\.dll")) bad("неверное имя библиотеки");
                    return dll;
                }
                if (b < 33 || b > 126) bad("неверное имя библиотеки");
                name.append((char)b);
            }
            bad("слишком длинное имя библиотеки"); return "";
        }
        static void bad(String message) { throw new InvalidPe(message); }
    }
}
