package ai.guitarmentor.application.learner;

import ai.guitarmentor.domain.learner.InstrumentProfile;
import ai.guitarmentor.domain.learner.Learner;
import ai.guitarmentor.domain.learner.MusicPreference;
import ai.guitarmentor.infrastructure.persistence.LearnerRepository;
import ai.guitarmentor.interfaces.rest.learner.LearnerDtos.CreateLearnerRequest;
import ai.guitarmentor.interfaces.rest.learner.LearnerDtos.InstrumentView;
import ai.guitarmentor.interfaces.rest.learner.LearnerDtos.LearnerView;
import ai.guitarmentor.interfaces.rest.learner.LearnerDtos.PreferenceInput;
import ai.guitarmentor.interfaces.rest.learner.LearnerDtos.PreferenceView;
import ai.guitarmentor.interfaces.rest.learner.LearnerDtos.UpdatePreferencesRequest;
import jakarta.persistence.EntityNotFoundException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearnerService {

    private final LearnerRepository learnerRepository;

    public LearnerService(LearnerRepository learnerRepository) {
        this.learnerRepository = learnerRepository;
    }

    @Transactional
    public LearnerView create(CreateLearnerRequest request) {
        var learner = new Learner(request.displayName().trim());
        var instrument = new InstrumentProfile(request.instrumentCode(), request.techniqueCode());
        instrument.replacePreferences(toPreferences(request.preferences()));
        learner.addInstrumentProfile(instrument);
        return toView(learnerRepository.saveAndFlush(learner));
    }

    @Transactional(readOnly = true)
    public LearnerView get(UUID learnerId) {
        return toView(find(learnerId));
    }

    @Transactional
    public LearnerView replacePreferences(UUID learnerId, UpdatePreferencesRequest request) {
        // Public signup provisions a bare learner. First profile save must add the
        // supported instrument in that same account, not create a second learner.
        // Lock serializes concurrent first saves to avoid duplicate instruments.
        var learner = learnerRepository.findLockedById(learnerId)
                .orElseThrow(() -> new EntityNotFoundException("Learner not found"));
        var instrument = learner.getInstrumentProfiles().stream()
                .filter(p -> p.getInstrumentCode().equals(request.instrumentCode()))
                .findFirst().orElse(null);
        if (instrument == null) {
            if (!learner.getInstrumentProfiles().isEmpty()
                    || !"ACOUSTIC_GUITAR".equals(request.instrumentCode())) {
                throw new EntityNotFoundException("Instrument profile not found");
            }
            instrument = new InstrumentProfile("ACOUSTIC_GUITAR", "FINGERSTYLE");
            learner.addInstrumentProfile(instrument);
        }
        instrument.replacePreferences(toPreferences(request.preferences()));
        return toView(learnerRepository.saveAndFlush(learner));
    }

    private Learner find(UUID learnerId) {
        return learnerRepository.findById(learnerId)
                .orElseThrow(() -> new EntityNotFoundException("Learner not found"));
    }

    private static List<MusicPreference> toPreferences(List<PreferenceInput> inputs) {
        Set<String> codes = new HashSet<>();
        return inputs.stream().map(input -> {
            if (!codes.add(input.styleCode())) {
                throw new IllegalArgumentException("Duplicate musical style: " + input.styleCode());
            }
            return new MusicPreference(input.styleCode(), input.rating());
        }).toList();
    }

    private static LearnerView toView(Learner learner) {
        var instruments = learner.getInstrumentProfiles().stream()
                .map(profile -> new InstrumentView(
                        profile.getId(),
                        profile.getInstrumentCode(),
                        profile.getTechniqueCode(),
                        profile.getPreferences().stream()
                                .map(preference -> new PreferenceView(
                                        preference.getStyleCode(), preference.getRating()))
                                .sorted((left, right) -> left.styleCode().compareTo(right.styleCode()))
                                .toList()))
                .toList();
        return new LearnerView(learner.getId(), learner.getDisplayName(), instruments);
    }
}
