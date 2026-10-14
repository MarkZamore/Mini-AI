import ai.enterprise.bootstrap.LegalResources;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Stream the large archive with a 16 MiB heap: runtime must not materialize or extract it. */
public final class LegalResourcesTest {
    public static void main(String[] args) throws Exception {
        if(!LegalResources.notices().contains("Classpath")) throw new AssertionError("Missing runtime notice");
        if(!LegalResources.notices().contains("Eclipse Public License")) throw new AssertionError("Missing SWT licence");
        var digest=MessageDigest.getInstance("SHA-256");
        long[] count={0};
        var output=new OutputStream() {
            public void write(int value){digest.update((byte)value);count[0]++;}
            public void write(byte[] bytes,int offset,int length){digest.update(bytes,offset,length);count[0]+=length;}
        };
        if(!LegalResources.dispatch(new String[]{"--third-party-source-stdout"},output)) throw new AssertionError("CLI not dispatched");
        if(count[0]<200_000_000L)throw new AssertionError("Incomplete sources");
        String sha=HexFormat.of().formatHex(digest.digest());
        if(!LegalResources.inventory().contains(sha))throw new AssertionError("Source inventory SHA mismatch");
        if(LegalResources.dispatch(new String[]{"--status"},new ByteArrayOutputStream()))throw new AssertionError("Unrelated CLI intercepted");
        System.out.println("LegalResourcesTest: PASS bytes="+count[0]+" sha256="+sha);
    }
}
