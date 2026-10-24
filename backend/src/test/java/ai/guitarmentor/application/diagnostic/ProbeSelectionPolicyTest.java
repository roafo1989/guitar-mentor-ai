package ai.guitarmentor.application.diagnostic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProbeSelectionPolicyTest {
    private static final ProbeSelectionPolicy.Candidate TRIPLE =
        new ProbeSelectionPolicy.Candidate("GM-TRIPLE","v1","Pulse 3/8",22,List.of("PULSE"));
    private static final ProbeSelectionPolicy.Candidate LONG =
        new ProbeSelectionPolicy.Candidate("GM-LONG","v1","Too long",74,List.of("PULSE"));
    private static final ProbeSelectionPolicy.Candidate METRONOME =
        new ProbeSelectionPolicy.Candidate("GM-RH","v1","Finger pulse",18,List.of("RH_HAND"));

    @Test void selectsShortestRelevantProbeWithoutDeclaringSkillsFailed() {
        var unassessed=Map.of("PULSE", new RecommendationEngine.SkillState(0,false,List.of()),
              "RH_HAND",new RecommendationEngine.SkillState(0,false,List.of()));
        assertEquals("GM-RH",ProbeSelectionPolicy.next(List.of(TRIPLE,LONG,METRONOME),unassessed)
                .orElseThrow().code());
    }
    @Test void neverOffersProbeForAlreadyDemonstratedSkill() {
        var confirmed=Map.of("PULSE",new RecommendationEngine.SkillState(2,false,List.of()),
                "RH_HAND",new RecommendationEngine.SkillState(2,false,List.of()));
        assertTrue(ProbeSelectionPolicy.next(List.of(TRIPLE,METRONOME),confirmed).isEmpty());
    }
    @Test void neverOffersUnsupportedDurationOrMissingSkillEvidence() {
        var map=Map.of("PULSE",new RecommendationEngine.SkillState(0,true,List.of()));
        assertTrue(ProbeSelectionPolicy.next(List.of(LONG),map).isEmpty());
        assertTrue(ProbeSelectionPolicy.next(List.of(TRIPLE),Map.of()).isEmpty());
    }
}
