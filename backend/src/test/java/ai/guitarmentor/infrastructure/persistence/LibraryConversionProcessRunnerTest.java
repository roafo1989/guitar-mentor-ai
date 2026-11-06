package ai.guitarmentor.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Protocol/fault tests use a standalone process; real Docker queue tests live in WorkerPostgresTest. */
@EnabledOnOs({OS.LINUX, OS.MAC})
class LibraryConversionProcessRunnerTest {
    @TempDir Path temp;
    private Path source() throws Exception { return Files.writeString(temp.resolve("source.gp"), "immutable synthetic source"); }
    private String sha(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }
    private LibraryConversionProcessRunner runner(Path docker) {
        return new LibraryConversionProcessRunner(docker.toString(), "gm-conversion-worker:170e", temp.resolve("work").toString(), Duration.ofSeconds(2));
    }
    private void clean() throws Exception {
        if (Files.exists(temp.resolve("work"))) try (var entries=Files.list(temp.resolve("work"))) { assertEquals(0,entries.count()); }
    }
    private Path docker(String mode, String hash) throws Exception {
        Path p=temp.resolve("docker-stub");
        String script="""
            #!/usr/bin/env python3
            import sys,json,struct,time
            from pathlib import Path
            root=Path(__file__).parent
            if sys.argv[1]=='rm':
                (root/'removed').write_text(sys.argv[-1]); sys.exit(0)
            (root/'args').write_text('\n'.join(sys.argv[1:]))
            mode='MODE'; sha='HASH'
            if mode=='hang': time.sleep(10)
            if mode=='unavailable': sys.exit(125)
            if mode=='bad-protocol': sys.stdout.buffer.write(b'BAD'); sys.exit(0)
            pdf=b'%PDF-1.7'+b'x'*1400+b'%%EOF'
            wav=b'RIFF'+b'0000'+b'WAVE'+b'x'*1400
            r=dict(status='RENDERED',format='GP5',sourceSha256=sha,scoreRevision=sha,selectedTrackIndex=0,
                selectedTrackName='Lead',pdfBytes=len(pdf),wavBytes=len(wav),wavDurationSeconds=1)
            if mode=='selection': r=dict(status='ERROR',code='TRACK_SELECTION_REQUIRED',tracks=[dict(trackIndex=i,name='Guitar',guitar=True) for i in range(2)]); pdf=wav=b''
            if mode=='mismatch': r['selectedTrackIndex']=1
            if mode=='invalid': pdf=b'INVALID'+pdf[7:]
            if mode=='partial': wav=b''
            out=sys.stdout.buffer; out.write(b'GM170E01')
            if mode=='oversized': out.write(struct.pack('>I',65537)); sys.exit(0)
            for data in [json.dumps(r).encode(),pdf,wav]: out.write(struct.pack('>I',len(data))); out.write(data)
            """.replace("'\n'.join", "'\\n'.join").replace("MODE",mode).replace("HASH",hash);
        Files.writeString(p,script);
        Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rwx------"));
        return p;
    }
    @Test void rendersBoundedPairAndUsesAllIsolationFlags() throws Exception {
        Path source=source(); var runner=runner(docker("ok",sha(source)));
        var result=runner.execute(source,"GP5",sha(source),0,()->false);
        assertTrue(result.rendered(),result.errorCode());
        String args=Files.readString(temp.resolve("args"));
        for(String flag:new String[]{"--network\nnone","--memory\n512m","--memory-swap\n512m","--cpus\n1",
                "--pids-limit\n64","--read-only","--cap-drop\nALL","no-new-privileges:true","size=100m",
                "--user\n10001:10001","--pull=never","target=/input,readonly","GM_CONVERSION_STREAM=1"})
            assertTrue(args.contains(flag),flag);
        assertFalse(args.contains("target=/out"));
        assertTrue(Files.readString(temp.resolve("removed")).startsWith("gm-conversion-"));
        runner.deleteWorkspace(result.workspace()); clean();
    }
    @Test void selectionDoesNotLeakWorkspace() throws Exception {
        Path source=source(); var r=runner(docker("selection",sha(source))).execute(source,"GP5",sha(source),null,()->false);
        assertTrue(r.trackSelectionRequired()); assertEquals(2,r.tracks().size()); clean();
    }
    @Test void checksInputBeforeLaunchingProcess() throws Exception {
        Path source=source(); var runner=runner(docker("ok",sha(source)));
        assertEquals("UNSUPPORTED_FORMAT",runner.execute(source,"GPX",sha(source),null,()->false).errorCode());
        assertEquals("SOURCE_HASH_MISMATCH",runner.execute(source,"GP5","0".repeat(64),null,()->false).errorCode());
        try(var out=new java.io.RandomAccessFile(source.toFile(),"rw")) {out.setLength(20L*1024*1024+1);}
        assertEquals("SOURCE_TOO_LARGE",runner.execute(source,"GP5","x",null,()->false).errorCode());
        assertFalse(Files.exists(temp.resolve("args")));
    }
    @Test void cancelsAndKillsContainerByPreassignedName() throws Exception {
        Path source=source(); long start=System.nanoTime();
        var r=runner(docker("hang",sha(source))).execute(source,"GP5",sha(source),0,()->System.nanoTime()-start>100_000_000);
        assertTrue(r.cancelled()); assertTrue(Files.exists(temp.resolve("removed"))); clean();
    }
    @Test void watchdogTerminatesHungProcess() throws Exception {
        Path source=source(); var r=runner(docker("hang",sha(source))).execute(source,"GP5",sha(source),0,()->false);
        assertEquals("TIMED_OUT",r.errorCode()); clean();
    }
    @Test void rejectsMalformedOrOversizedProtocolBeforeWritingLargeFiles() throws Exception {
        Path source=source();
        for(String mode:new String[]{"bad-protocol","oversized"}) {
            assertEquals("WORKER_PROTOCOL_ERROR",runner(docker(mode,sha(source))).execute(source,"GP5",sha(source),0,()->false).errorCode()); clean();
        }
    }
    @Test void rejectsInvalidPartialAndMismatchingOutputs() throws Exception {
        Path source=source();
        for(String mode:new String[]{"invalid","partial"}) {
            assertThrows(java.io.IOException.class,()->runner(docker(mode,sha(source))).execute(source,"GP5",sha(source),0,()->false)); clean();
        }
        assertEquals("WORKER_RESULT_MISMATCH",runner(docker("mismatch",sha(source))).execute(source,"GP5",sha(source),0,()->false).errorCode()); clean();
    }
    @Test void missingExecutableOrImageCleansWorkspace() throws Exception {
        Path source=source();
        assertEquals("WORKER_UNAVAILABLE",runner(temp.resolve("missing")).execute(source,"GP5",sha(source),0,()->false).errorCode()); clean();
        assertEquals("WORKER_UNAVAILABLE",runner(docker("unavailable",sha(source))).execute(source,"GP5",sha(source),0,()->false).errorCode()); clean();
    }
    @Test void cleanupCannotDeleteRootOrFollowSymlinksAndRecoversOldWorkspaces() throws Exception {
        var runner=runner(temp.resolve("missing"));
        Path root=Files.createDirectory(temp.resolve("work")), job=Files.createDirectory(root.resolve("job-old"));
        Path outside=Files.writeString(temp.resolve("outside"),"keep");
        Files.createSymbolicLink(job.resolve("link"),outside);
        assertThrows(java.io.IOException.class,()->runner.deleteWorkspace(root));
        assertThrows(java.io.IOException.class,()->runner.deleteWorkspace(temp));
        Files.setLastModifiedTime(job,java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(700)));
        runner.cleanupAbandonedWorkspaces(); assertTrue(Files.exists(outside)); clean();
    }
}
