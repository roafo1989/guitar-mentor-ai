export type PreferenceRating = 'LIKE' | 'NEUTRAL' | 'EXCLUDE';

export interface MusicPreference {
  styleCode: string;
  rating: PreferenceRating;
}

export interface InstrumentProfile {
  id: string;
  instrumentCode: string;
  techniqueCode: string;
  preferences: MusicPreference[];
}

export interface Learner {
  id: string;
  displayName: string;
  instruments: InstrumentProfile[];
}

export interface CreateLearnerRequest {
  displayName: string;
  instrumentCode: string;
  techniqueCode: string;
  preferences: MusicPreference[];
}

export interface UpdatePreferencesRequest {
  instrumentCode: string;
  preferences: MusicPreference[];
}
