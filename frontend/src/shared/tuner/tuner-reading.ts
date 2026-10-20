import { cents, noteName } from './pitch.ts';

export type TuningStatus = 'inTune' | 'flat' | 'sharp' | 'wrongString';

export interface TunerReading {
  note: string;
  cents: number;
  status: TuningStatus;
  /** The accessible and visual meter is bounded to [-50, 50] cents. */
  meterValue: number;
}

/** A valid detected pitch is independent of the selected target string. */
export function tunerReading(frequency: number | null, targetHz: number): TunerReading | null {
  if (frequency === null || !Number.isFinite(frequency) || frequency <= 0 ||
      !Number.isFinite(targetHz) || targetHz <= 0) return null;

  const deviation = cents(frequency, targetHz);
  const status: TuningStatus = Math.abs(deviation) > 200 ? 'wrongString' :
    Math.abs(deviation) <= 5 ? 'inTune' : deviation < 0 ? 'flat' : 'sharp';
  return {
    note: noteName(frequency),
    cents: deviation,
    status,
    meterValue: Math.round(Math.max(-50, Math.min(50, deviation))),
  };
}
