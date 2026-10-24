package ai.guitarmentor.infrastructure.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Separate private root: never share diagnostic media storage or TTL cleanup. */
@Component
public final class LibraryObjectStore {
    public record Stored(String key, long length, String sha256) {}
    private final Path root;
    public LibraryObjectStore(
            @Value("${guitar-mentor.library.storage-path:./.local-media/library}") String storagePath,
            @Value("${guitar-mentor.submissions.storage-path:./.local-media/attempts}") String attempts,
            @Value("${guitar-mentor.submissions.score-storage-path:./.local-media/scores}") String scores) {
        root = Path.of(storagePath).toAbsolutePath().normalize();
        for (String other : new String[]{attempts,scores}) {
            Path excluded = Path.of(other).toAbsolutePath().normalize();
            if (root.startsWith(excluded) || excluded.startsWith(root))
                throw new IllegalStateException("Library root must be separate from diagnostic media");
        }
        try {
            if (Files.exists(root,LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(root))
                throw new IllegalStateException("Library root must not be a symlink");
            Files.createDirectories(root);
            if (!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("Invalid library root");
        } catch (IOException e) { throw new IllegalStateException("Library storage unavailable",e); }
    }
    /** Write bounded bytes to staging without using the user's filename as a path. */
    public Stored put(InputStream input, long maximum) throws IOException {
        String key = UUID.randomUUID().toString();
        Path staging = safe(".staging-" + key);
        Path target = safe(key);
        MessageDigest sha;
        try { sha=MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
        long count=0;
        try {
            try(OutputStream out=Files.newOutputStream(staging,
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE)) {
                byte[] buffer=new byte[8192];
                for(int n;(n=input.read(buffer))!=-1;) {
                    if (n==0) continue;
                    count+=n;
                    if(count>maximum) throw new FileTooLarge();
                    sha.update(buffer,0,n);
                    out.write(buffer,0,n);
                }
            }
            if(count==0) throw new EmptyFile();
            Files.move(staging,target,StandardCopyOption.ATOMIC_MOVE);
            return new Stored(key,count,HexFormat.of().formatHex(sha.digest()));
        } catch(IOException|RuntimeException e) {
            Files.deleteIfExists(staging);
            throw e;
        }
    }
    public Path file(String key) throws IOException {
        if (!key.matches("[a-f0-9-]{36}")) throw new IOException("Invalid object key");
        Path result=safe(key);
        if (!Files.isRegularFile(result,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing object");
        return result;
    }
    public void delete(String key) throws IOException { Files.deleteIfExists(fileOrAbsent(key)); }
    private Path fileOrAbsent(String key) throws IOException {
        if (!key.matches("[a-f0-9-]{36}")) throw new IOException("Invalid object key");
        return safe(key);
    }
    private Path safe(String key) {return root.resolve(key).normalize();}
    public static final class FileTooLarge extends IOException {}
    public static final class EmptyFile extends IOException {}
}
