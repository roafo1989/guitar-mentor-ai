export type ExperienceLevel = 'NONE' | 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED';
export type SkillTag = 'CHORDS' | 'STRUMMING' | 'ARPEGGIOS' | 'FINGERSTYLE_MELODY';
export type GoalCode =
  | 'FINGERSTYLE' | 'RHYTHM' | 'INDEPENDENT_BASS'
  | 'DIFFERENT_STYLES' | 'COMPOSITIONS' | 'THEORY';

export interface KnownPiece {
  title: string;
  artist: string;
}

export interface LearningBackground {
  experienceLevel: ExperienceLevel;
  yearsPlaying: number;
  skills: SkillTag[];
  knownPieces: KnownPiece[];
  goals: GoalCode[];
  practiceMinutes: number;
  sessionsPerWeek: number;
  notes: string;
}
