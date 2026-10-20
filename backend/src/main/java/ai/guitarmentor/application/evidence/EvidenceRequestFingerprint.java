package ai.guitarmentor.application.evidence;

import ai.guitarmentor.application.attempt.AttemptMediaStore;
import ai.guitarmentor.infrastructure.media.DocumentMediaStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stable SHA-256 of normalized metadata AND both uploaded files.
 * Persisted separately from media so replay can be verified even after TTL deletion.
 * Length prefixes and optional-file markers avoid ambiguous concatenations.
 */
final class EvidenceRequestFingerprint {
    private EvidenceRequestFingerprint() {}

    static String calculate(String title, String composer, String comment,
            MultipartFile audio, MultipartFile score) {
        if (audio == null || audio.isEmpty()) throw bad(HttpStatus.BAD_REQUEST, "MISSING_AUDIO");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            text(digest, "diagnostic-evidence-v1");
            text(digest, title);
            text(digest, composer);
            text(digest, comment);
            file(digest, audio, AttemptMediaStore.MAX_BYTES, "INVALID_AUDIO_SIZE");
            file(digest, score, DocumentMediaStore.MAX_BYTES, "INVALID_SCORE_SIZE");
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        } catch (IOException unreadable) {
            throw bad(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD_READ_FAILED");
        }
    }

    private static ResponseStatusException bad(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }

    private static void text(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) 1);
        length(digest, bytes.length);
        digest.update(bytes);
    }

    private static void length(MessageDigest digest, long bytes) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes).array());
    }

    private static void file(MessageDigest digest, MultipartFile file,
            long maximum, String invalidCode) throws IOException {
        if (file == null) {
            digest.update((byte) 0);
            return;
        }
        long expected = file.getSize();
        if (expected <= 0 || expected > maximum) throw bad(HttpStatus.BAD_REQUEST, invalidCode);
        digest.update((byte) 1);
        text(digest, file.getOriginalFilename());
        length(digest, expected);
        long read = 0;
        try (var stream = file.getInputStream()) {
            byte[] chunk = new byte[8192];
            int amount;
            while ((amount = stream.read(chunk)) != -1) {
                read += amount;
                if (read > maximum) throw bad(HttpStatus.BAD_REQUEST, invalidCode);
                digest.update(chunk, 0, amount);
            }
        }
        if (read != expected) throw bad(HttpStatus.BAD_REQUEST, invalidCode);
    }
}
