package ai.guitarmentor.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryConversionProcessRunnerTest {
    @TempDir Path temp;

    @Test
    void launchesUnprivilegedNoNetworkContainerAndValidatesBothOutputs() throws Exception {
        Path source = Files.writeString(temp.resolve("source.gp"), "synthetic GP5 source");
        Path docker = stubDocker();
        Path args = temp.resolve("docker-args.txt");
        Path log = temp.resolve("docker-stub.log");
        ProcessRunnerEnv.set(docker, args, log, "RENDERED");
        try {
            LibraryConversionProcessRunner runner = runner(docker);
            var result = runner.execute(source, "GP5", sha256(source), 1, () -> false);

            assertTrue(result.rendered());
            assertEquals(1, result.selectedTrackIndex());
            assertEquals("Lead Guitar", result.selectedTrackName());
            assertTrue(Files.size(result.pdfPath()) > 1000);
            assertTrue(Files.size(result.wavPath()) > 1000);
            String command = Files.readString(args);
            assertTrue(command.contains("--network"));
            assertTrue(command.contains("none"));
            assertTrue(command.contains("--memory"));
            assertTrue(command.contains("512m"));
            assertTrue(command.contains("--cpus"));
            assertTrue(command.contains("1"));
            assertTrue(command.contains("--read-only"));
            assertTrue(command.contains("--cap-drop"));
            assertTrue(command.contains("ALL"));
            assertTrue(command.contains("--pull=never"));
            runner.deleteWorkspace(result.workspace());
            assertFalse(Files.exists(result.workspace()));
        } finally {
            ProcessRunnerEnv.clear();
        }
    }

    @Test
    void returnsTrackSelectionWithoutKeepingScratchFiles() throws Exception {
        Path source = Files.writeString(temp.resolve("source.gp"), "synthetic GP5 source");
        Path docker = stubDocker();
        ProcessRunnerEnv.set(docker, temp.resolve("args.txt"), temp.resolve("log.txt"), "TRACK_SELECTION_REQUIRED");
        try {
            var result = runner(docker).execute(source, "GP5", sha256(source), null, () -> false);
            assertTrue(result.trackSelectionRequired());
            assertEquals(2, result.tracks().size());
            assertEquals(1, result.tracks().get(1).trackIndex());
            assertNull(result.workspace());
        } finally {
            ProcessRunnerEnv.clear();
        }
    }

    @Test
    void rejectsUnsupportedFormatAndSourceHashMismatchBeforeStartingDocker() throws Exception {
        Path source = Files.writeString(temp.resolve("source.gp"), "synthetic GP5 source");
        Path docker = stubDocker();
        ProcessRunnerEnv.set(docker, temp.resolve("args.txt"), temp.resolve("log.txt"), "RENDERED");
        try {
            var unsupported = runner(docker).execute(source, "GPX", sha256(source), null, () -> false);
            assertEquals("UNSUPPORTED_FORMAT", unsupported.errorCode());
            var mismatch = runner(docker).execute(source, "GP5", "0".repeat(64), null, () -> false);
            assertEquals("SOURCE_HASH_MISMATCH", mismatch.errorCode());
            assertFalse(Files.exists(temp.resolve("args.txt")));
        } finally {
            ProcessRunnerEnv.clear();
        }
    }

    @Test
    void rejectsWorkspaceOutsideConfiguredRoot() throws Exception {
        LibraryConversionProcessRunner runner = runner(stubDocker());
        assertThrows(java.io.IOException.class,
                () -> runner.deleteWorkspace(temp.resolve("outside")));
    }

    private LibraryConversionProcessRunner runner(Path docker) {
        String executable = System.getProperty("gm.test.docker", docker.toString());
        return new LibraryConversionProcessRunner(executable, "gm-conversion-worker:170b",
                temp.resolve("work").toString(), new ObjectMapper());
    }

    private Path stubDocker() throws Exception {
        Path script = temp.resolve("docker-stub.sh");
        String body = """
                #!/bin/sh
                printf '%s\\n' "$@" > "$GM_DOCKER_ARGS"
                out=""
                while [ "$#" -gt 0 ]; do
                  if [ "$1" = "--mount" ]; then
                    shift
                    case "$1" in
                      *target=/out*) out="${1#source=}"; out="${out%,target=/out*}" ;;
                    esac
                  fi
                  shift
                done
                mkdir -p "$out"
                if [ "$GM_STUB_MODE" = "TRACK_SELECTION_REQUIRED" ]; then
                  printf '%s' '{"status":"ERROR","code":"TRACK_SELECTION_REQUIRED","tracks":[{"trackIndex":0,"name":"Rhythm","guitar":true,"instrument":"Guitar"},{"trackIndex":1,"name":"Lead","guitar":true,"instrument":"Guitar"}]}' > "$out/result.json"
                  exit 10
                fi
                printf '%s' '{"status":"RENDERED","selectedTrackIndex":1,"selectedTrackName":"Lead Guitar","wavDurationSeconds":2.0}' > "$out/result.json"
                printf '%%PDF' > "$out/score.pdf"
                dd if=/dev/zero bs=1 count=1200 >> "$out/score.pdf" 2>/dev/null
                printf 'RIFF\\000\\004\\000\\000WAVE' > "$out/preview.wav"
                dd if=/dev/zero bs=1 count=1200 >> "$out/preview.wav" 2>/dev/null
                exit 0
                """;
        Files.writeString(script, body);
        Files.setPosixFilePermissions(script, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /** Keep environment mutation isolated and restore it after every test. */
    private static final class ProcessRunnerEnv {
        static void set(Path docker, Path args, Path log, String mode) {
            System.setProperty("gm.test.docker", docker.toString());
            // The stub reads these inherited values; ProcessBuilder inherits the test process environment.
            // Values are injected through a wrapper script to avoid changing global process state.
            try {
                Path wrapper = docker.resolveSibling("docker-wrapper.sh");
                Files.writeString(wrapper, "#!/bin/sh\nGM_DOCKER_ARGS='" + args + "' GM_STUB_MODE='" + mode
                        + "' exec '" + docker + "' \"$@\"");
                Files.setPosixFilePermissions(wrapper, Set.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
                System.setProperty("gm.test.docker", wrapper.toString());
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
        static void clear() { System.clearProperty("gm.test.docker"); }
    }
}
