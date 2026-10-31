import {
  ApiFailure,
  isObject,
  isString,
  isUuid,
} from "../../shared/api-failure";
import type { LibraryMaterial, MaterialType } from "./library.service";
import { validMaterial } from "./library.service";
export type AssetRole =
  | "SCORE_ORIGINAL"
  | "PHOTO_ORIGINAL"
  | "ORIGINAL_RECORDING"
  | "SYNTHESIZED_PREVIEW";
export type RightsBasis =
  | "OWN_WORK"
  | "LICENSE"
  | "PUBLIC_DOMAIN"
  | "PERMISSION";
export const provenance = {
  OWN_WORK: "CREATED",
  LICENSE: "LICENSED",
  PUBLIC_DOMAIN: "PUBLIC_DOMAIN",
  PERMISSION: "PERMISSION",
} as const;
export interface AssetRight {
  assetId: string;
  provenance: string;
  rightsBasis: RightsBasis;
  rightsEvidence: string;
  attribution: string;
}
export interface PublicationAsset {
  id: string;
  originalName: string;
  format: string;
  byteSize: number;
  sha256: string;
  role: AssetRole;
  mediaType: string;
  provenance: string;
  rightsBasis: RightsBasis;
  attribution: string | null;
  downloadUrl: string | null;
  previewUrl: string | null;
}
export interface Publication {
  id: string;
  version: number;
  sourceRevision: number;
  title: string;
  type: MaterialType;
  author: string | null;
  state: "PUBLISHED" | "WITHDRAWN" | "HIDDEN";
  revision: number;
  assets: PublicationAsset[];
  tags: string[];
}
export interface Summary {
  id: string;
  title: string;
  type: MaterialType;
  author: string | null;
  version: number;
  publishedAt: string;
  tags: string[];
  hasAudio: boolean;
  detailUrl: string;
}
export interface SearchPage {
  items: Summary[];
  nextCursor: string | null;
}
export interface Filters {
  q: string;
  type: string;
  tags: string[];
  hasAudio: string;
}
export interface Capabilities {
  enabled: boolean;
  allowedExtensions: Record<string, string[]>;
  roles: AssetRole[];
  maxFilesPerRequest: number;
  maxFilesPerMaterial: number;
  maxPhotosAndScores: number;
  maxAudio: number;
  maxRequestBytes: number;
  maxPhotoBytes: number;
  maxAudioBytes: number;
  maxPhotoPixels: number;
  maxPhotoSide: number;
  maxAudioDurationMillis: number;
  minAudioSampleRate: number;
  maxAudioSampleRate: number;
  audioChannels: number[];
  photoFormats: string[];
  audioFormats: string[];
  wavEncoding: string | null;
}
export class LibraryFailure extends ApiFailure {
  constructor(
    status: number,
    code: string | null,
    public retryAt: number = 0,
  ) {
    super("HTTP", status, code);
  }
}
export const privateBase = "/api/v1/library/materials";
export const commonBase = "/api/v1/community/publications";
export function assetPath(
  kind: "material" | "publication",
  id: string,
  asset: string,
  preview = false,
): string {
  if (!isUuid(id) || !isUuid(asset)) throw new ApiFailure("INVALID_RESPONSE");
  return (
    (kind === "material" ? privateBase : commonBase) +
    "/" +
    id +
    "/files/" +
    asset +
    (preview ? "/preview" : "/content")
  );
}
const stringList = (v: unknown): v is string[] =>
  Array.isArray(v) && v.every(isString);
const positive = (v: unknown) => Number.isSafeInteger(v) && Number(v) > 0;
const nullableString = (v: unknown) => v === null || isString(v);
const materialType = (v: unknown) =>
  ["EXERCISE", "ETUDE", "COMPOSITION", "OTHER"].includes(String(v));
const role = (v: unknown) =>
  [
    "SCORE_ORIGINAL",
    "PHOTO_ORIGINAL",
    "ORIGINAL_RECORDING",
    "SYNTHESIZED_PREVIEW",
  ].includes(String(v));
export function validPublication(v: unknown): v is Publication {
  if (
    !isObject(v) ||
    !isUuid(v.id) ||
    !positive(v.version) ||
    !positive(v.sourceRevision) ||
    !positive(v.revision) ||
    !isString(v.title) ||
    !materialType(v.type) ||
    !nullableString(v.author) ||
    !stringList(v.tags) ||
    !["PUBLISHED", "WITHDRAWN", "HIDDEN"].includes(String(v.state)) ||
    !Array.isArray(v.assets)
  )
    return false;
  return v.assets.every(
    (a) =>
      isObject(a) &&
      isUuid(a.id) &&
      isString(a.originalName) &&
      isString(a.format) &&
      positive(a.byteSize) &&
      isString(a.sha256) &&
      role(a.role) &&
      isString(a.mediaType) &&
      isString(a.provenance) &&
      Object.keys(provenance).includes(String(a.rightsBasis)) &&
      nullableString(a.attribution) &&
      (a.downloadUrl === null ||
        a.downloadUrl === assetPath("publication", String(v.id), a.id)) &&
      (a.previewUrl === null ||
        a.previewUrl === assetPath("publication", String(v.id), a.id, true)),
  );
}
export function validSearch(v: unknown): v is SearchPage {
  return (
    isObject(v) &&
    (v.nextCursor === null || isString(v.nextCursor)) &&
    Array.isArray(v.items) &&
    v.items.every(
      (s) =>
        isObject(s) &&
        isUuid(s.id) &&
        isString(s.title) &&
        materialType(s.type) &&
        nullableString(s.author) &&
        positive(s.version) &&
        isString(s.publishedAt) &&
        stringList(s.tags) &&
        typeof s.hasAudio === "boolean" &&
        s.detailUrl === commonBase + "/" + s.id,
    )
  );
}
export function validCapabilities(v: unknown): v is Capabilities {
  return (
    isObject(v) &&
    typeof v.enabled === "boolean" &&
    isObject(v.allowedExtensions) &&
    Object.values(v.allowedExtensions).every(stringList) &&
    Array.isArray(v.roles) &&
    v.roles.every(role) &&
    [
      "maxFilesPerRequest",
      "maxFilesPerMaterial",
      "maxPhotosAndScores",
      "maxAudio",
      "maxRequestBytes",
      "maxPhotoBytes",
      "maxAudioBytes",
      "maxPhotoPixels",
      "maxPhotoSide",
      "maxAudioDurationMillis",
      "minAudioSampleRate",
      "maxAudioSampleRate",
    ].every((k) => Number.isSafeInteger(v[k]) && Number(v[k]) >= 0) &&
    Array.isArray(v.audioChannels) &&
    v.audioChannels.every(positive) &&
    stringList(v.photoFormats) &&
    stringList(v.audioFormats) &&
    nullableString(v.wavEncoding)
  );
}
export function normalizeTags(raw: string): string[] {
  const values = [
    ...new Set(
      raw
        .split(",")
        .map((s) => s.trim().toLowerCase())
        .filter(Boolean),
    ),
  ];
  if (
    values.length > 10 ||
    values.some((s) => !/^[\p{L}\p{N}][\p{L}\p{N} _-]{0,39}$/u.test(s))
  )
    throw new ApiFailure("HTTP", 400);
  return values;
}
export async function libraryRequest<T>(
  url: string,
  options: RequestInit,
  check: (v: unknown) => v is T,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, {
      credentials: "same-origin",
      cache: "no-store",
      ...options,
    });
  } catch (e) {
    if (
      options.signal?.aborted ||
      (e instanceof Error && e.name === "AbortError")
    )
      throw e;
    throw new ApiFailure("NETWORK");
  }
  if (!response.ok) {
    let code: string | null = null;
    try {
      const e: unknown = await response.json();
      if (
        isObject(e) &&
        isString(e.code) &&
        /^(LIBRARY|CATALOG|PUBLICATION|COMMUNITY)_[A-Z_]+$/.test(e.code)
      )
        code = e.code;
    } catch {
      /* no raw error text */
    }
    const retry = response.headers.get("Retry-After");
    const retryAt = retry
      ? /^\d+$/.test(retry)
        ? Date.now() + Number(retry) * 1000
        : Date.parse(retry)
      : 0;
    throw new LibraryFailure(
      response.status,
      code,
      Number.isFinite(retryAt) ? retryAt : 0,
    );
  }
  if (response.status === 202 || response.status === 204) {
    if (!check(undefined)) throw new ApiFailure("INVALID_RESPONSE");
    return undefined as T;
  }
  const data: unknown = await response.json().catch(() => {
    throw new ApiFailure("INVALID_RESPONSE");
  });
  if (!check(data)) throw new ApiFailure("INVALID_RESPONSE");
  return data;
}
const json = (revision: number, key?: string): HeadersInit => ({
  "Content-Type": "application/json",
  "If-Match": '"' + revision + '"',
  ...(key ? { "Idempotency-Key": key } : {}),
});
export const communityService = {
  search(
    filters: Filters,
    cursor: string | null,
    signal: AbortSignal,
  ): Promise<SearchPage> {
    const p = new URLSearchParams({ limit: "20" });
    if (filters.q) p.set("q", filters.q);
    if (filters.type) p.set("type", filters.type);
    filters.tags.forEach((tag) => p.append("tag", tag));
    if (filters.hasAudio) p.set("hasAudio", filters.hasAudio);
    if (cursor) p.set("cursor", cursor);
    return libraryRequest(commonBase + "?" + p, { signal }, validSearch);
  },
  detail(id: string, signal: AbortSignal): Promise<Publication> {
    return libraryRequest(commonBase + "/" + id, { signal }, validPublication);
  },
  history(id: string, signal: AbortSignal): Promise<Publication[]> {
    return libraryRequest(
      privateBase + "/" + id + "/publications",
      { signal },
      (v): v is Publication[] => Array.isArray(v) && v.every(validPublication),
    );
  },
  publish(
    material: LibraryMaterial,
    assets: AssetRight[],
    tags: string[],
    key: string,
    signal: AbortSignal,
  ): Promise<Publication> {
    return libraryRequest(
      privateBase + "/" + material.id + "/publications",
      {
        method: "POST",
        headers: json(material.revision, key),
        body: JSON.stringify({ assets, tags }),
        signal,
      },
      validPublication,
    );
  },
  withdraw(
    id: string,
    publication: Publication,
    signal: AbortSignal,
  ): Promise<Publication> {
    return libraryRequest(
      privateBase + "/" + id + "/publications/" + publication.id + "/withdraw",
      {
        method: "POST",
        headers: json(publication.revision),
        body: "{}",
        signal,
      },
      validPublication,
    );
  },
  capabilities(signal: AbortSignal): Promise<Capabilities> {
    return libraryRequest(
      "/api/v1/library/capabilities",
      { signal },
      validCapabilities,
    );
  },
  addFiles(
    m: LibraryMaterial,
    role: AssetRole,
    files: File[],
    key: string,
    signal: AbortSignal,
  ): Promise<LibraryMaterial> {
    const form = new FormData();
    form.append(
      "metadata",
      new Blob([JSON.stringify({ role })], { type: "application/json" }),
    );
    files.forEach((f) => form.append("files", f, f.name));
    return libraryRequest(
      privateBase + "/" + m.id + "/files",
      {
        method: "POST",
        headers: { "If-Match": '"' + m.revision + '"', "Idempotency-Key": key },
        body: form,
        signal,
      },
      validMaterial,
    );
  },
  removeFile(
    m: LibraryMaterial,
    asset: string,
    signal: AbortSignal,
  ): Promise<void> {
    return libraryRequest(
      privateBase + "/" + m.id + "/files/" + asset,
      { method: "DELETE", headers: json(m.revision), signal },
      (_): _ is void => true,
    );
  },
};
