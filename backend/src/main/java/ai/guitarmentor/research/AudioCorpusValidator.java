package ai.guitarmentor.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Standalone offline corpus validator. Never touches application media storage or database.
 * Exit codes: 0 READY_FOR_POC, 2 INCOMPLETE/INVALID, 3 CLI/environment failure.
 */
public final class AudioCorpusValidator {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final long MAX_MS = 120_000;
    private static final long MAX_JSON_BYTES = 1024 * 1024;
    private static final Set<String> KINDS = Set.of("REAL", "SYNTHETIC", "QUALITY");
    private static final Set<String> GROUPS = Set.of("REAL-BASS", "REAL-TIMING", "REAL-POLY",
            "SYNTHETIC-CONTROL", "QUALITY-CONTROL");
    private static final Set<String> SPLITS = Set.of("CALIBRATION", "HOLDOUT", "DIAGNOSTIC_ONLY");
    private static final Set<String> AVAILABILITY = Set.of("AVAILABLE", "MISSING", "WITHDRAWN");
    private static final Set<String> LABELS = Set.of("MATCH", "OMISSION", "EXTRA",
            "WRONG_BASS", "PAUSE", "TEMPO_CHANGE", "AMBIGUOUS");
    private static final ObjectMapper JSON = new ObjectMapper();

    private AudioCorpusValidator() {}

    public record Result(String status, List<String> errors, List<String> gaps, int realCount) {
        public int exitCode() { return "READY_FOR_POC".equals(status) ? 0 : 2; }
    }

    public static void main(String[] args) {
        try {
            if (args.length != 7 || !"--root".equals(args[0])
                    || !"--manifest".equals(args[2]) || !"--report".equals(args[4])
                    || !"--offline".equals(args[6])) {
                System.err.println("USAGE: --root <dir> --manifest <relative.json> --report <new-external.json> --offline");
                System.exit(3);
            }
            Path root = Path.of(args[1]).toAbsolutePath().normalize();
            Path report = Path.of(args[5]).toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)
                    || report.startsWith(root) || Files.exists(report)
                    || !Files.isDirectory(report.getParent())) {
                throw new IllegalArgumentException("Invalid root or report location");
            }
            Result result = validate(root, args[3]);
            // CREATE_NEW guarantees a previous result is never overwritten.
            try (var output = Files.newOutputStream(report, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                JSON.writerWithDefaultPrettyPrinter().writeValue(output, result);
            }
            System.out.println(result.status());
            System.exit(result.exitCode());
        } catch (Exception e) {
            // Do not include private filenames or paths in public console output.
            System.err.println("ENVIRONMENT_OR_INPUT_ERROR");
            System.exit(3);
        }
    }

    public static Result validate(Path root, String manifestRef) {
        List<String> errors = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        int realCount = 0;
        try {
            Path manifest = safePath(root, manifestRef);
            JsonNode dataset = readBoundedJson(manifest);
            if (dataset == null || dataset.path("schemaVersion").asInt(-1) != 1
                    || !nonblank(dataset, "datasetId") || !nonblank(dataset, "datasetVersion")
                    || !nonblank(dataset, "createdAt") || !nonblank(dataset, "sourceCommit")
                    || !dataset.path("recordings").isArray()) {
                return new Result("INVALID", List.of("MANIFEST_SCHEMA"), List.of(), 0);
            }
            if (dataset.path("recordings").size() > 1000) {
                return new Result("INVALID", List.of("TOO_MANY_RECORDINGS"), List.of(), 0);
            }
            Set<String> ids = new HashSet<>();
            Map<String, String> groupSplits = new HashMap<>();
            Map<String, String> groupKinds = new HashMap<>();

            Map<String, Set<String>> coverage = new HashMap<>();
            Set<String> sourceHashes = new HashSet<>();
            for (JsonNode rec : dataset.path("recordings")) {
                String id = value(rec, "recordingId");
                if (!validId(id) || !ids.add(id)) {
                    errors.add("RECORDING_ID");
                    continue;
                }
                String kind = value(rec, "kind"), group = value(rec, "group");
                String split = value(rec, "split"), available = value(rec, "availability");
                if (!KINDS.contains(kind) || !GROUPS.contains(group)
                        || !SPLITS.contains(split) || !AVAILABILITY.contains(available)
                        || ("REAL".equals(kind) != group.startsWith("REAL-"))
                        || ("SYNTHETIC".equals(kind) != "SYNTHETIC-CONTROL".equals(group))
                        || ("QUALITY".equals(kind) != "QUALITY-CONTROL".equals(group))) {
                    errors.add("RECORDING_ENUM:" + id);
                    continue;
                }
                if ("REAL".equals(kind)) {
                    realCount++;
                    String recordingGroup = value(rec, "recordingGroupId");
                    if (!validId(recordingGroup) || !validId(value(rec, "performerId"))
                            || !validId(value(rec, "sessionId")) || !validId(value(rec, "deviceId"))) {
                        errors.add("REAL_METADATA:" + id);
                    }
                    String sourceKey = value(rec, "sourceSha256");
                    if (validSha(sourceKey) && !sourceHashes.add(sourceKey)) {
                        errors.add("DUPLICATE_REAL_SOURCE:" + id);
                    }
                    String oldKind = groupKinds.putIfAbsent(recordingGroup, group);
                    if (oldKind != null && !oldKind.equals(group)) errors.add("GROUP_REUSED_ACROSS_TYPES:" + id);
                    String oldSplit = groupSplits.putIfAbsent(recordingGroup, split);
                    if (oldSplit != null && !oldSplit.equals(split)) errors.add("SPLIT_LEAK:" + id);
                    // Do not count withdrawn, absent or unreviewed samples as usable holdout.
                    if ("AVAILABLE".equals(available) && rec.path("rightsConfirmed").asBoolean(false)
                            && rec.path("manualReviewConfirmed").asBoolean(false)
                            && nonblank(rec, "annotationRef") && validSha(value(rec, "annotationSha256"))
                            && nonblank(rec, "referenceRef") && validSha(value(rec, "referenceSha256"))
                            && ("CALIBRATION".equals(split) || "HOLDOUT".equals(split))
                            && (!"HOLDOUT".equals(split) || rec.path("holdoutIndependentConfirmed").asBoolean(false))) {
                        coverage.computeIfAbsent(group, ignored -> new HashSet<>()).add(recordingGroup + ":" + split);
                    }
                    if (!Set.of("OWNED", "LICENSED", "EXPLICIT_PERMISSION").contains(value(rec, "rightsBasis"))
                            || !validId(value(rec, "consentRef"))
                            || !Set.of("LOCAL_RESEARCH", "EXTERNAL_APPROVED").contains(value(rec, "useScope"))
                            || !rec.path("rightsConfirmed").asBoolean(false)) gaps.add("RIGHTS_UNCONFIRMED:" + id);
                    if (!rec.path("manualReviewConfirmed").asBoolean(false)) gaps.add("REVIEW_MISSING:" + id);
                    if ("HOLDOUT".equals(split) && !rec.path("holdoutIndependentConfirmed").asBoolean(false)) {
                        gaps.add("HOLDOUT_INDEPENDENCE_UNCONFIRMED:" + id);
                    }
                }
                if (!"AVAILABLE".equals(available)) {
                    gaps.add("SOURCE_UNAVAILABLE:" + id);
                    continue;
                }
                try {
                    Path source = safePath(root, value(rec, "fileRef"));
                    long bytes = Files.size(source);
                    if (bytes == 0 || bytes > MAX_BYTES || bytes != rec.path("bytes").asLong(-1)
                            || !hash(source).equals(value(rec, "sourceSha256"))) {
                        errors.add("SOURCE_INTEGRITY:" + id);
                    } else {
                        try {
                            if (!AudioMediaProbe.matches(source, rec.path("durationMs").asLong(-1),
                                    rec.path("sampleRateHz").asInt(-1), rec.path("channels").asInt(-1))) {
                                errors.add("MEDIA_PROBE_MISMATCH:" + id);
                            }
                        } catch (IOException probeFailure) {
                            gaps.add("MEDIA_PROBE_UNAVAILABLE:" + id);
                        }
                    }
                } catch (Exception e) { errors.add("SOURCE_REFERENCE:" + id); }
                long duration = rec.path("durationMs").asLong(-1);
                if (duration < 1 || duration > MAX_MS || rec.path("sampleRateHz").asInt(-1) < 1
                        || rec.path("channels").asInt(-1) < 1 || rec.path("channels").asInt() > 2) {
                    errors.add("MEDIA_METADATA:" + id);
                }
                if ("REAL".equals(kind) || "SYNTHETIC".equals(kind)) {
                    if (!validId(value(rec, "referenceId")) || !nonblank(rec, "referenceVersion")
                            || !validSha(value(rec, "referenceSha256"))
                            || rec.path("barFrom").asInt(0) < 1
                            || rec.path("barTo").asInt(0) < rec.path("barFrom").asInt(0)) {
                        errors.add("REFERENCE_SCHEMA:" + id);
                    }
                }
                if ("REAL".equals(kind) && !"AVAILABLE".equals(available)) {
                    gaps.add("REAL_NOT_USABLE:" + id);
                }
                if ("SYNTHETIC".equals(kind) && !rec.path("transform").isObject()) {
                    errors.add("TRANSFORM_MISSING:" + id);
                }
                if ("REAL".equals(kind)) {
                    if (!nonblank(rec, "referenceRef")) {
                        gaps.add("REFERENCE_FILE_MISSING:" + id);
                    } else {
                        validateReference(root, rec, id, errors);
                    }
                    if (!nonblank(rec, "annotationRef") || !validSha(value(rec, "annotationSha256"))
                            || !nonblank(rec, "annotationVersion")) {
                        gaps.add("ANNOTATION_MISSING:" + id);
                    } else {
                        validateAnnotation(root, rec, id, errors);
                    }
                }
            }
            for (String group : List.of("REAL-BASS", "REAL-TIMING", "REAL-POLY")) {
                Set<String> samples = coverage.getOrDefault(group, Set.of());
                boolean cal = samples.stream().anyMatch(s -> s.endsWith(":CALIBRATION"));
                boolean hold = samples.stream().anyMatch(s -> s.endsWith(":HOLDOUT"));
                if (samples.size() < 2 || !cal || !hold) gaps.add("NO_INDEPENDENT_HOLDOUT:" + group);
            }
        } catch (Exception e) {
            errors.add("MANIFEST_UNREADABLE_OR_UNSAFE");
        }
        String status = !errors.isEmpty() ? "INVALID" : !gaps.isEmpty() ? "INCOMPLETE" : "READY_FOR_POC";
        return new Result(status, List.copyOf(errors), List.copyOf(gaps), realCount);
    }

    private static void validateReference(Path root, JsonNode rec, String id, List<String> errors) {
        try {
            Path path = safePath(root, value(rec, "referenceRef"));
            if (!hash(path).equals(value(rec, "referenceSha256"))) {
                errors.add("REFERENCE_INTEGRITY:" + id);
                return;
            }
            JsonNode reference = readBoundedJson(path);
            if (!value(rec, "referenceId").equals(value(reference, "referenceId"))
                    || !value(rec, "referenceVersion").equals(value(reference, "referenceVersion"))
                    || !reference.path("events").isArray()
                    || reference.path("barCount").asInt(-1) < rec.path("barTo").asInt(0)
                    || reference.path("bpm").asDouble(-1) <= 0
                    || !Double.isFinite(reference.path("bpm").asDouble(-1))
                    || reference.path("meterNumerator").asInt(4) <= 0
                    || reference.path("events").isEmpty()) {
                errors.add("REFERENCE_CONTENT:" + id);
                return;
            }
            if (reference.path("events").size() > 10000) {
                errors.add("REFERENCE_TOO_LARGE:" + id);
                return;
            }
            Set<String> eventIds = new HashSet<>();
            for (JsonNode e : reference.path("events")) {
                String eventId = value(e, "eventId");
                if (!validId(eventId) || !eventIds.add(eventId)
                        || e.path("bar").asInt(0) < 1
                        || e.path("bar").asInt() > reference.path("barCount").asInt()
                        || e.path("beat").asDouble(-1) < 0
                        || !Double.isFinite(e.path("beat").asDouble(-1))
                        || e.path("durationBeats").asDouble(-1) <= 0
                        || !Double.isFinite(e.path("durationBeats").asDouble(-1))
                        || !e.path("notes").isArray() || e.path("notes").isEmpty()) {
                    errors.add("REFERENCE_EVENT:" + id);
                    return;
                }
            }
            if (!nonblank(rec, "annotationRef") || !validSha(value(rec, "annotationSha256"))) return;
            Path annotationPath = safePath(root, value(rec, "annotationRef"));
            if (!hash(annotationPath).equals(value(rec, "annotationSha256"))) return;
            JsonNode annotation = readBoundedJson(annotationPath);
            for (JsonNode obs : annotation.path("observations")) {
                if (!"EXTRA".equals(value(obs, "label"))
                        && !eventIds.contains(value(obs, "eventId"))) {
                    errors.add("UNKNOWN_EVENT_ID:" + id);
                    return;
                }
            }
        } catch (Exception e) {
            errors.add("REFERENCE_FILE:" + id);
        }
    }

    private static void validateAnnotation(Path root, JsonNode rec, String id, List<String> errors) {
        try {
            Path path = safePath(root, value(rec, "annotationRef"));
            if (!hash(path).equals(value(rec, "annotationSha256"))) {
                errors.add("ANNOTATION_INTEGRITY:" + id);
                return;
            }
            JsonNode a = readBoundedJson(path);
            if (!id.equals(value(a, "recordingId"))
                    || !value(rec, "sourceSha256").equals(value(a, "sourceSha256"))
                    || !a.path("observations").isArray()
                    || !nonblank(a, "annotationId") || !nonblank(a, "annotationVersion")
                    || !value(rec, "annotationVersion").equals(value(a, "annotationVersion"))
                    || !validId(value(a, "annotatorId"))
                    || !a.path("manualConfirmed").asBoolean(false)) {
                errors.add("ANNOTATION_SCHEMA:" + id);
                return;
            }
            if (a.path("observations").size() > 10000) {
                errors.add("TOO_MANY_OBSERVATIONS:" + id);
                return;
            }
            if (a.path("observations").isEmpty()) {
                errors.add("ANNOTATION_EMPTY:" + id);
                return;
            }
            long duration = rec.path("durationMs").asLong();
            for (JsonNode observation : a.path("observations")) {
                String label = value(observation, "label");
                long from = observation.path("fromMs").asLong(-1);
                long onset = observation.path("onsetMs").asLong(-1);
                long to = observation.path("toMs").asLong(-1);
                if (!LABELS.contains(label) || from < 0 || onset < from || to < onset
                        || to > duration || ("EXTRA".equals(label) && !observation.path("eventId").isNull())
                        || (!"EXTRA".equals(label) && !validId(value(observation, "eventId")))) {
                    errors.add("ANNOTATION_EVENT:" + id);
                    break;
                }
            }
        } catch (Exception e) { errors.add("ANNOTATION_REFERENCE:" + id); }
    }

    private static JsonNode readBoundedJson(Path file) throws IOException {
        if (Files.size(file) > MAX_JSON_BYTES) throw new IOException("json too large");
        try (var input = Files.newInputStream(file)) {
            return JSON.readTree(input);
        }
    }

    public static Path safePath(Path root, String ref) throws IOException {
        if (ref == null || ref.isBlank() || ref.indexOf(':') >= 0 || ref.indexOf('\\') >= 0
                || ref.startsWith("/") || ref.startsWith("~")) throw new IOException("unsafe");
        Path relative = Path.of(ref);
        if (relative.isAbsolute()) throw new IOException("unsafe");
        for (Path segment : relative) {
            if (segment.toString().equals("..") || segment.toString().equals(".")) throw new IOException("unsafe");
        }
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) throw new IOException("unsafe");
        Path cursor = base;
        for (Path part : relative) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("unsafe");
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("missing");
        return target;
    }

    private static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = in.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String value(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isTextual() ? v.asText() : "";
    }
    private static boolean nonblank(JsonNode n, String key) { return !value(n, key).isBlank(); }
    private static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{1,80}"); }
    private static boolean validSha(String sha) { return sha != null && sha.matches("[0-9a-f]{64}"); }
}
