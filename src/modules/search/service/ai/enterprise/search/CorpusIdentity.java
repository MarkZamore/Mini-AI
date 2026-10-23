package ai.enterprise.search;

import ai.enterprise.contracts.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Lazy, read-only identity with a strong initial index digest and a process-bound file snapshot. */
public final class CorpusIdentity implements CorpusIdentityServiceV1 {
    private final Path directory;
    private final String openedSignature;
    private String digest;
    public CorpusIdentity(Path directory) {
        this.directory=directory.toAbsolutePath().normalize();
        String initial;
        try {initial=signature();} catch(Exception unavailable) {initial="";}
        openedSignature=initial;
    }
    private String signature() throws Exception {
        Path index=directory.resolve("index.sqlite").toRealPath();
        if(!index.startsWith(directory.toRealPath())) throw new Api.ServiceException("CORPUS_INVALID","Индекс выходит за корень базы");
        var attrs=Files.readAttributes(index,BasicFileAttributes.class);
        if(!attrs.isRegularFile())throw new Api.ServiceException("CORPUS_INVALID","Отсутствует обычный файл индекса");
        return index+":"+attrs.fileKey()+":"+attrs.size()+":"+attrs.lastModifiedTime();
    }
    public synchronized Api.Result<Info> current(Api.CancellationToken token) {
        try {
            token.check();String before=signature();
            if(!before.equals(openedSignature))throw new Api.ServiceException("CORPUS_CHANGED","Индекс базы изменился после открытия. Перезапустите приложение для нового сеанса.");
            if(digest==null) {
                var hash=MessageDigest.getInstance("SHA-256");
                try(var input=Files.newInputStream(directory.resolve("index.sqlite"))) {
                    byte[] buffer=new byte[1<<20];int count;
                    while((count=input.read(buffer))!=-1){token.check();hash.update(buffer,0,count);}
                }
                if(!before.equals(signature()))throw new Api.ServiceException("CORPUS_CHANGED","Индекс изменился во время чтения. Перезапустите приложение.");
                digest=HexFormat.of().formatHex(hash.digest());
            }
            String base="local";Path metadata=directory.resolve("base.json");
            if(Files.exists(metadata)) {
                if(!metadata.toRealPath().startsWith(directory.toRealPath()))throw new Api.ServiceException("CORPUS_INVALID","Описание базы выходит за её корень");
                byte[] bytes;
                try(var input=Files.newInputStream(metadata)){bytes=input.readNBytes(16_385);}
                if(bytes.length>16_384)throw new Api.ServiceException("CORPUS_INVALID","Описание базы слишком велико");
                String text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                var value=Json.object(text);
                if(!value.keySet().equals(Set.of("base_id","content_version")) || !(value.get("base_id") instanceof String id)
                    || !id.matches("[A-Za-z0-9._-]{1,64}") || !(value.get("content_version") instanceof String version)
                    || !version.matches("[a-f0-9]{64}"))throw new Api.ServiceException("CORPUS_INVALID","Неверная схема db/base.json: нужны base_id и content_version SHA-256");
                if(!digest.equals(version))throw new Api.ServiceException("CORPUS_MISMATCH","Описание базы относится к другой версии индекса");
                base=id;
            }
            token.check();return Api.Result.ok(new Info(base,digest));
        } catch(Api.ServiceException error){return Api.failure(error);}
        catch(Exception error){return Api.Result.fail("CORPUS_INVALID","Не удалось прочитать согласованное описание базы: "+error.getMessage());}
    }
}
