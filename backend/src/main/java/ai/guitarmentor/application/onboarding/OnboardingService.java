package ai.guitarmentor.application.onboarding;

import ai.guitarmentor.domain.learner.InstrumentProfile;
import ai.guitarmentor.domain.learner.Learner;
import ai.guitarmentor.domain.onboarding.KnownPiece;
import ai.guitarmentor.domain.onboarding.LearningBackground;
import ai.guitarmentor.infrastructure.persistence.LearnerRepository;
import ai.guitarmentor.infrastructure.persistence.LearningBackgroundRepository;
import ai.guitarmentor.infrastructure.persistence.SkillMapRepository;
import ai.guitarmentor.domain.onboarding.SkillTag;
import org.springframework.beans.factory.annotation.Autowired;
import ai.guitarmentor.interfaces.rest.onboarding.OnboardingDtos.BackgroundRequest;
import ai.guitarmentor.interfaces.rest.onboarding.OnboardingDtos.BackgroundView;
import ai.guitarmentor.interfaces.rest.onboarding.OnboardingDtos.KnownPieceInput;
import jakarta.persistence.EntityNotFoundException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OnboardingService {

    private final LearnerRepository learners;
    private final LearningBackgroundRepository backgrounds;
    private final SkillMapRepository skillSignals;

    @Autowired
    public OnboardingService(LearnerRepository learners, LearningBackgroundRepository backgrounds,
            SkillMapRepository skillSignals) {
        this.learners = learners;
        this.backgrounds = backgrounds;
        this.skillSignals = skillSignals;
    }

    /** Existing isolated unit tests use the two-argument constructor. */
    public OnboardingService(LearnerRepository learners, LearningBackgroundRepository backgrounds) {
        this(learners, backgrounds, null);
    }

    @Transactional(readOnly = true)
    public Optional<BackgroundView> get(UUID learnerId, String instrumentCode) {
        var learner = learners.findById(learnerId)
                .orElseThrow(() -> new EntityNotFoundException("Learner not found"));
        // Signup deliberately creates a bare learner. Reading that learner's
        // supported initial questionnaire is empty, and must not write any rows.
        return instrument(learner, instrumentCode)
                .flatMap(profile -> backgrounds.findByInstrumentProfile_Id(profile.getId()))
                .map(OnboardingService::toView);
    }

    @Transactional
    public BackgroundView upsert(UUID learnerId, String instrumentCode, BackgroundRequest request) {
        // Lock the learner row before reading/updating claims: concurrent PUTs
        // must serialize so duplicate claims and lost retractions cannot occur.
        var learner = learners.findLockedById(learnerId)
                .orElseThrow(() -> new EntityNotFoundException("Learner not found"));
        validate(request);
        var instrument = instrument(learner, instrumentCode).orElseGet(() -> {
            // Same row lock as PUT preferences: either first save may provision
            // the initial instrument, without racing or creating another learner.
            var initial = new InstrumentProfile("ACOUSTIC_GUITAR", "FINGERSTYLE");
            learner.addInstrumentProfile(initial);
            learners.saveAndFlush(learner);
            return initial;
        });
        var background = backgrounds.findByInstrumentProfile_Id(instrument.getId())
                .orElseGet(() -> new LearningBackground(instrument));
        boolean wasFamiliar = background.getSkills().contains(SkillTag.FINGERSTYLE_MELODY);
        boolean nowFamiliar = request.skills().contains(SkillTag.FINGERSTYLE_MELODY);
        background.update(
                request.experienceLevel(),
                request.yearsPlaying(),
                request.skills(),
                request.knownPieces().stream()
                        .map(piece -> new KnownPiece(piece.title().trim(), piece.artist().trim()))
                        .toList(),
                request.goals(),
                request.practiceMinutes(),
                request.sessionsPerWeek(),
                request.notes().trim());
        var saved = backgrounds.saveAndFlush(background);
        if (skillSignals != null && wasFamiliar != nowFamiliar) {
            // An onboarding statement does not constitute an assessed skill.
            skillSignals.appendOnboardingClaim(instrument.getId(), "MELODY", nowFamiliar);
        }
        return toView(saved);
    }

    private Optional<InstrumentProfile> instrument(Learner learner, String instrumentCode) {
        var existing = learner.getInstrumentProfiles().stream()
                .filter(profile -> profile.getInstrumentCode().equals(instrumentCode))
                .findFirst();
        if (existing.isPresent()) return existing;
        if (learner.getInstrumentProfiles().isEmpty() && "ACOUSTIC_GUITAR".equals(instrumentCode)) {
            return Optional.empty();
        }
        throw new EntityNotFoundException("Instrument profile not found");
    }

    private static void validate(BackgroundRequest request) {
        Set<String> pieceNames = new HashSet<>();
        for (var piece : request.knownPieces()) {
            var normalized = piece.title().trim().toLowerCase(java.util.Locale.ROOT)
                    + "|" + piece.artist().trim().toLowerCase(java.util.Locale.ROOT);
            if (!pieceNames.add(normalized)) {
                throw new IllegalArgumentException("Duplicate known composition");
            }
        }
    }

    private static BackgroundView toView(LearningBackground background) {
        var pieces = background.getKnownPieces().stream()
                .map(piece -> new KnownPieceInput(piece.getTitle(), piece.getArtist()))
                .sorted(Comparator.comparing(KnownPieceInput::title))
                .toList();
        return new BackgroundView(
                background.getExperienceLevel(),
                background.getYearsPlaying(),
                background.getSkills(),
                pieces,
                background.getGoals(),
                background.getPracticeMinutes(),
                background.getSessionsPerWeek(),
                background.getNotes());
    }
}
