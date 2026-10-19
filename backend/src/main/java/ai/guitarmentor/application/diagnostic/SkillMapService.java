package ai.guitarmentor.application.diagnostic;

import ai.guitarmentor.domain.learner.InstrumentProfile;
import ai.guitarmentor.infrastructure.persistence.LearnerRepository;
import ai.guitarmentor.infrastructure.persistence.SkillMapRepository;
import ai.guitarmentor.infrastructure.persistence.SkillMapRepository.SignalRow;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillMapService {
    private final LearnerRepository learners;
    private final SkillMapRepository skills;

    public SkillMapService(LearnerRepository learners, SkillMapRepository skills) {
        this.learners = learners;
        this.skills = skills;
    }

    public record SkillView(String skillCode, String title, String description,
            List<String> prerequisites, boolean selfReported, int assessedLevel,
            String evidenceStatus, List<String> unresolvedGaps) {}
    public record SignalView(String skillCode, String sourceType, String claimState,
            Integer level, Instant recordedAt, String noteCode) {}

    @Transactional(readOnly = true)
    public List<SkillView> map(UUID learnerId, String instrumentCode) {
        var profile = ownedProfile(learnerId, instrumentCode);
        var catalog = skills.skills();
        var graph = skills.prerequisites();
        validateAcyclic(graph, catalog.stream().map(SkillMapRepository.SkillRow::code)
                .collect(java.util.stream.Collectors.toSet()));
        Map<String, Boolean> claims = new HashMap<>();
        Map<String, SignalRow> assessments = new HashMap<>();
        for (var signal : skills.signals(profile.getId())) {
            if ("SELF_REPORTED".equals(signal.sourceType())) {
                claims.putIfAbsent(signal.skillCode(), "CLAIMED".equals(signal.claimState()));
            } else if (signal.level() != null) {
                assessments.putIfAbsent(signal.skillCode(), signal);
            }
        }
        return catalog.stream().map(skill -> {
            var assessed = assessments.get(skill.code());
            return new SkillView(skill.code(), skill.title(), skill.description(),
                    List.copyOf(graph.getOrDefault(skill.code(), List.of())),
                    claims.getOrDefault(skill.code(), false),
                    assessed == null ? 0 : assessed.level(),
                    assessed == null ? "NOT_ASSESSED" : assessed.sourceType(),
                    List.of());
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<SignalView> history(UUID learnerId, String instrumentCode,
            String skillCode, int limit) {
        var profile = ownedProfile(learnerId, instrumentCode);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("INVALID_LIMIT");
        }
        if (skills.skills().stream().noneMatch(s -> s.code().equals(skillCode))) {
            throw new IllegalArgumentException("INVALID_SKILL_CODE");
        }
        return skills.history(profile.getId(), skillCode, limit).stream()
                .map(s -> new SignalView(s.skillCode(), s.sourceType(), s.claimState(),
                        s.level(), s.recordedAt(), s.noteCode())).toList();
    }

    private InstrumentProfile ownedProfile(UUID learnerId, String instrumentCode) {
        var learner = learners.findById(learnerId)
                .orElseThrow(() -> new EntityNotFoundException("Instrument profile not found"));
        return learner.getInstrumentProfiles().stream()
                .filter(p -> p.getInstrumentCode().equals(instrumentCode)).findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Instrument profile not found"));
    }

    /** Graph validation also rejects references not present in the catalog. */
    public static void validateAcyclic(Map<String, List<String>> graph, Set<String> known) {
        var visited = new HashSet<String>();
        var path = new HashSet<String>();
        for (var entry : graph.entrySet()) {
            if (!known.contains(entry.getKey())) throw new IllegalStateException("Unknown skill");
            for (String dependency : entry.getValue()) {
                if (!known.contains(dependency)) throw new IllegalStateException("Unknown prerequisite");
            }
        }
        for (String skill : known) visit(skill, graph, visited, path);
    }

    private static void visit(String skill, Map<String, List<String>> graph,
            Set<String> visited, Set<String> path) {
        if (path.contains(skill)) throw new IllegalStateException("Skill prerequisite cycle");
        if (!visited.add(skill)) return;
        path.add(skill);
        for (var next : graph.getOrDefault(skill, List.of())) {
            visit(next, graph, visited, path);
        }
        path.remove(skill);
    }
}
