package ai.guitarmentor.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryObjectStoreTest {
    @TempDir Path temp;

    private LibraryObjectStore store() {
        return new LibraryObjectStore(temp.resolve("library").toString(),
            temp.resolve("attempts").toString(),temp.resolve("scores").toString());
    }

    @Test void preservesOriginalBytesAndDigest() throws Exception {
        byte[] original=new byte[16384];
        for(int i=0;i<original.length;i++) original[i]=(byte)(i%251);
        var store=store();
        var object=store.put(new ByteArrayInputStream(original),20000);
        assertEquals(original.length,object.length());
        assertEquals(64,object.sha256().length());
        assertArrayEquals(original,Files.readAllBytes(store.file(object.key())));
        store.delete(object.key());
        assertThrows(java.io.IOException.class,()->store.file(object.key()));
    }

    @Test void boundedUploadNeverPublishesPartialObject() throws Exception {
        var store=store();
        assertThrows(LibraryObjectStore.FileTooLarge.class,
            ()->store.put(new ByteArrayInputStream(new byte[8193]),8192));
        assertThrows(LibraryObjectStore.EmptyFile.class,
            ()->store.put(new ByteArrayInputStream(new byte[0]),100));
        try(var listing=Files.list(temp.resolve("library"))) {
            assertEquals(0,listing.count());
        }
    }

    @Test void rejectsNestedDiagnosticStorage() {
        assertThrows(IllegalStateException.class,()->new LibraryObjectStore(
            temp.resolve("attempts/library").toString(),
            temp.resolve("attempts").toString(),temp.resolve("scores").toString()));
    }
    @Test void rejectsSymlinkedParentWhenSupported() throws Exception {
        Path real=Files.createDirectory(temp.resolve("real"));
        Path alias=temp.resolve("alias");
        try {Files.createSymbolicLink(alias,real);}
        catch(UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,"Symlinks unavailable in this CI filesystem");
            return;
        }
        assertThrows(IllegalStateException.class,()->new LibraryObjectStore(
            alias.resolve("library").toString(),
            temp.resolve("attempts").toString(),temp.resolve("scores").toString()));
    }

    @Test void posixObjectsAndDirectoryAreOwnerOnly() throws Exception {
        var store=store();
        Path directory=temp.resolve("library");
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Files.getFileStore(directory).supportsFileAttributeView("posix"));
        var object=store.put(new ByteArrayInputStream(new byte[]{1,2,3}),10);
        assertEquals("rwx------",java.nio.file.attribute.PosixFilePermissions.toString(
            Files.getPosixFilePermissions(directory)));
        assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(
            Files.getPosixFilePermissions(store.file(object.key()))));
    }

}
