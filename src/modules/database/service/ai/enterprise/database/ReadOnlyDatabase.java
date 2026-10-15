package ai.enterprise.database;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.graalvm.nativeimage.ImageInfo;

/** Single serialized read-only connection. Cancellation interrupts only its active statement. */
public final class ReadOnlyDatabase implements Api.DatabaseServiceV1 {
    private static final Set<String> JSON_COLUMNS = Set.of("source_refs","quality","span","warnings","sections","candidates","payload");
    private static final Set<String> INTEGER_COLUMNS = Set.of("id","rowid","position","user_version");
    private final Object interruptGuard = new Object();
    private final AtomicBoolean closing = new AtomicBoolean();
    private long handle;
    private Api.ServiceException unavailable;
    private String version = "unavailable";

    public ReadOnlyDatabase(Api.Context context) {
        var environment=context.environment();
        try {
            Path path=environment.db().resolve("index.sqlite");
            if(!Files.isRegularFile(path)) throw new Api.ServiceException("DB_MISSING","Не найден индекс базы:\n"+path+"\nВерните существующий файл index.sqlite в папку db рядом с приложением.");
            Path realRoot=environment.db().toRealPath();
            path=path.toRealPath();
            if(!path.startsWith(realRoot)) throw new Api.ServiceException("DB_PATH","Индекс расположен вне папки db");
            rejectSidecars(path);
            Path dll=environment.nativeDir().resolve("enterpriseai-sqlite.dll");
            if(!ImageInfo.inImageRuntimeCode() && !Files.isRegularFile(dll)) throw new Api.ServiceException("SQLITE_DLL_MISSING","Не найдена библиотека SQLite для Java-встраивания");
            SQLiteNative.load(dll);
            version=SQLiteNative.version();
            handle=SQLiteNative.open(windowsNativePath(path));
            var metadata=query("PRAGMA user_version",List.of(),new Api.CancellationToken());
            if(metadata.isEmpty() || Json.integer(metadata.getFirst().get("user_version"))!=1)
                throw new Api.ServiceException("DB_SCHEMA","Несовместимая версия SQLite-индекса: требуется user_version=1");
            var journal=query("PRAGMA journal_mode",List.of(),new Api.CancellationToken());
            if(journal.isEmpty() || !"delete".equals(journal.getFirst().get("journal_mode")))
                throw new Api.ServiceException("DB_STATE","База должна быть закрыта после подготовки в режиме DELETE; восстановление и WAL не поддерживаются");
            // Prepare all source/search columns to catch damaged or incompatible schemas early.
            query("SELECT document_id,title,source_sha256,source_path,json_path,status,sections,warnings FROM documents LIMIT 0",List.of(),new Api.CancellationToken());
            query("SELECT fragment_id,document_id,block_id,title,codes,text,stemmed,source_sha256,source_refs,quality,span,warnings FROM chunks LIMIT 0",List.of(),new Api.CancellationToken());
            query("SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH ? LIMIT 0",List.of("enterpriseai"),new Api.CancellationToken());
            query("SELECT id,source_id,target_id,relation,block_id,evidence,source_refs,candidates FROM edges LIMIT 0",List.of(),new Api.CancellationToken());
        } catch(Throwable failure) {
            synchronized(interruptGuard) { if(handle!=0) { SQLiteNative.close(handle); handle=0; } }
            unavailable=failure instanceof Api.ServiceException s?s:new Api.ServiceException("DB_INVALID","Не удалось открыть SQLite-индекс только для чтения: "+failure.getMessage(),failure);
            context.diagnostic(unavailable.getMessage());
        }
    }
    private static void rejectSidecars(Path database) {
        for(String suffix:List.of("-journal","-wal","-shm")) {
            if(Files.exists(Path.of(database.toString()+suffix)))
                throw new Api.ServiceException("DB_STATE","Рядом с индексом обнаружен "+suffix+"; закройте подготовку базы и перенесите согласованный индекс. Приложение не выполняет восстановление.");
        }
    }
    private static String windowsNativePath(Path path) {
        String text=path.toString();
        if(text.startsWith("\\\\?\\"))return text;
        if(text.startsWith("\\\\"))return "\\\\?\\UNC\\"+text.substring(2);
        return "\\\\?\\"+text;
    }
    public String sqliteVersion() { return version; }
    public synchronized List<Map<String,Object>> query(String sql,List<?> parameters,Api.CancellationToken token) {
        token.check();
        if(unavailable!=null) throw unavailable;
        if(handle==0 || closing.get()) throw new Api.ServiceException("DB_CLOSED","База закрыта");
        var active=new AtomicBoolean(true);
        token.onCancel(()-> { synchronized(interruptGuard) { if(active.get() && handle!=0) SQLiteNative.interrupt(handle); } });
        String[][] rows;
        try {
            token.check();
            String[] values=parameters.stream().map(v->v==null?null:v.toString()).toArray(String[]::new);
            rows=SQLiteNative.query(handle,sql,values);
            token.check();
        } catch(IllegalStateException e) {
            token.check();
            throw new Api.ServiceException("DB_QUERY","Ошибка чтения SQLite: "+e.getMessage(),e);
        } finally { active.set(false); }
        var result=new ArrayList<Map<String,Object>>(Math.max(0,rows.length-1));
        for(int r=1;r<rows.length;r++) {
            var row=new LinkedHashMap<String,Object>();
            for(int c=0;c<rows[0].length;c++) {
                String key=rows[0][c],value=rows[r][c];Object decoded=value;
                if(value!=null && JSON_COLUMNS.contains(key)) decoded=Json.parse(value);
                else if(value!=null && INTEGER_COLUMNS.contains(key)) decoded=Long.valueOf(value);
                else if(value!=null && key.equals("score")) decoded=Double.valueOf(value);
                row.put(key,decoded);
            }
            result.add(Api.freeze(row));
        }
        return List.copyOf(result);
    }
    public void close() {
        if(!closing.compareAndSet(false,true))return;
        synchronized(interruptGuard) { if(handle!=0)SQLiteNative.interrupt(handle); }
        synchronized(this) { synchronized(interruptGuard) { if(handle!=0) { long h=handle; handle=0; SQLiteNative.close(h); } } }
    }
}
