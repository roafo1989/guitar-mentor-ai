import { ApiFailure, isArrayOf, isObject, isString, isUuid, type ApiFailureKind } from "../../shared/api-failure";
import { validMaterial, type LibraryMaterial } from "./library.service";

export interface ConversionTrack {
  trackIndex: number;
  name: string;
  guitar: boolean;
  instrument: string | null;
}
export interface ConversionJob {
  id: string;
  materialId: string;
  sourceAssetId: string;
  sourceFormat: string;
  state: "QUEUED" | "RUNNING" | "AWAITING_TRACK_SELECTION" | "CANCEL_REQUESTED"
    | "SUCCEEDED" | "UNSUPPORTED" | "FAILED" | "TIMED_OUT" | "CANCELLED";
  stage: string | null;
  progress: number;
  tracks: ConversionTrack[];
  selectedTrackIndex: number | null;
  cancelRequested: boolean;
  attemptCount: number;
  retryable: boolean;
  errorCode: string | null;
  pdfAssetId: string | null;
  audioAssetId: string | null;
  revision: number;
  createdAt: string;
  updatedAt: string;
}
export class ConversionFailure extends ApiFailure {
  constructor(status: number | null, code: string | null, kind: ApiFailureKind = "HTTP") {
    super(kind, status, code);
    this.name = "ConversionFailure";
  }
}
const base = "/api/v1/library/materials";
const uuid = (v: unknown): v is string => isUuid(v);
const nullableUuid = (v: unknown): v is string | null => v === null || uuid(v);
function validTrack(v: unknown): v is ConversionTrack {
  return isObject(v) && Number.isSafeInteger(v.trackIndex) && Number(v.trackIndex) >= 0
    && isString(v.name) && typeof v.guitar === "boolean"
    && (v.instrument === null || v.instrument === undefined || isString(v.instrument));
}
export function validConversionJob(v: unknown): v is ConversionJob {
  return isObject(v) && uuid(v.id) && uuid(v.materialId) && uuid(v.sourceAssetId)
    && isString(v.sourceFormat)
    && ["QUEUED","RUNNING","AWAITING_TRACK_SELECTION","CANCEL_REQUESTED","SUCCEEDED",
      "UNSUPPORTED","FAILED","TIMED_OUT","CANCELLED"].includes(String(v.state))
    && (v.stage === null || v.stage === undefined || isString(v.stage))
    && Number.isSafeInteger(v.progress) && Number(v.progress) >= 0 && Number(v.progress) <= 100
    && isArrayOf(v.tracks, validTrack)
    && (v.selectedTrackIndex === null || v.selectedTrackIndex === undefined || (Number.isSafeInteger(v.selectedTrackIndex) && Number(v.selectedTrackIndex) >= 0))
    && typeof v.cancelRequested === "boolean"
    && Number.isSafeInteger(v.attemptCount) && Number(v.attemptCount) >= 0
    && typeof v.retryable === "boolean"
    && (v.errorCode === null || v.errorCode === undefined || isString(v.errorCode))
    && (nullableUuid(v.pdfAssetId) || v.pdfAssetId === undefined)
    && (nullableUuid(v.audioAssetId) || v.audioAssetId === undefined)
    && Number.isSafeInteger(v.revision) && Number(v.revision) >= 1
    && isString(v.createdAt) && isString(v.updatedAt);
}
async function request(url: string, init: RequestInit = {}): Promise<ConversionJob> {
  let response: Response;
  try {
    response = await fetch(url, {
      ...init, credentials: "same-origin", cache: "no-store",
      headers: { "Content-Type": "application/json", ...init.headers },
    });
  } catch (e) {
    if ((e instanceof Error && e.name === "AbortError") || init.signal?.aborted) throw e;
    throw new ConversionFailure(null, null, "NETWORK");
  }
  if (!response.ok) {
    let code: string | null = null;
    try {
      const body: unknown = await response.json();
      if (isObject(body) && isString(body.code) && /^[A-Z0-9_]{1,64}$/.test(body.code)
          && (body.code.startsWith("CONVERSION_") || body.code.startsWith("LIBRARY_")
            || body.code === "IDEMPOTENCY_KEY_REUSED"))
        code = body.code;
    } catch { /* Keep only status and allow-listed machine code. */ }
    throw new ConversionFailure(response.status, code);
  }
  let body: unknown;
  try { body = await response.json(); }
  catch { throw new ConversionFailure(response.status, null, "INVALID_RESPONSE"); }
  if (!validConversionJob(body)) throw new ConversionFailure(response.status, null, "INVALID_RESPONSE");
  return { ...body, stage: body.stage ?? null, selectedTrackIndex: body.selectedTrackIndex ?? null,
    errorCode: body.errorCode ?? null, pdfAssetId: body.pdfAssetId ?? null,
    audioAssetId: body.audioAssetId ?? null,
    tracks: body.tracks.map(track => ({ ...track, instrument: track.instrument ?? null })) };
}
function jobUrl(materialId: string, jobId?: string): string {
  return base + "/" + encodeURIComponent(materialId) + "/conversion-jobs" + (jobId ? "/" + encodeURIComponent(jobId) : "");
}
function revisionHeader(revision: number): Record<string,string> {
  return { "If-Match": '"' + revision + '"' };
}
export const conversionService = {
  create(material: LibraryMaterial, sourceAssetId: string, key: string, signal?: AbortSignal) {
    return request(jobUrl(material.id), {
      method: "POST", signal,
      headers: { ...revisionHeader(material.revision), "Idempotency-Key": key },
      body: JSON.stringify({ sourceAssetId }),
    });
  },
  get(materialId: string, jobId: string, signal?: AbortSignal) {
    return request(jobUrl(materialId, jobId), { signal });
  },
  selectTrack(job: ConversionJob, trackIndex: number, signal?: AbortSignal) {
    return request(jobUrl(job.materialId, job.id) + "/track-selection", {
      method: "POST", signal, headers: revisionHeader(job.revision),
      body: JSON.stringify({ trackIndex }),
    });
  },
  cancel(job: ConversionJob, signal?: AbortSignal) {
    return request(jobUrl(job.materialId, job.id) + "/cancel", {
      method: "POST", signal, headers: revisionHeader(job.revision), body: "{}",
    });
  },
  retry(job: ConversionJob, signal?: AbortSignal) {
    return request(jobUrl(job.materialId, job.id) + "/retry", {
      method: "POST", signal, headers: revisionHeader(job.revision), body: "{}",
    });
  },
  async refreshMaterial(materialId: string, signal?: AbortSignal): Promise<LibraryMaterial> {
    let response: Response;
    try {
      response = await fetch(base + "/" + encodeURIComponent(materialId), {
        credentials: "same-origin", cache: "no-store", signal,
      });
    } catch (e) {
      if ((e instanceof Error && e.name === "AbortError") || signal?.aborted) throw e;
      throw new ConversionFailure(null, null, "NETWORK");
    }
    if (!response.ok) throw new ConversionFailure(response.status, null);
    let body: unknown;
    try { body = await response.json(); } catch { throw new ConversionFailure(response.status, null, "INVALID_RESPONSE"); }
    if (!validMaterial(body)) throw new ConversionFailure(response.status, null, "INVALID_RESPONSE");
    return body;
  },
};
