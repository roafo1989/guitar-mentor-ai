import { ApiFailure, isArrayOf, isObject, isString, isUuid } from '../../shared/api-failure';

export type MaterialType = 'EXERCISE' | 'ETUDE' | 'COMPOSITION' | 'OTHER';
export interface LibraryFile {
  id: string; originalName: string; format: string; byteSize: number;
  sha256: string; validationStatus: string; downloadUrl: string;
}
export interface LibraryMaterial {
  id: string; title: string; type: MaterialType; author: string | null;
  state: 'READY'; visibility: 'PRIVATE'; reviewStatus: string; revision: number;
  createdAt: string; updatedAt: string; files: LibraryFile[];
}
export interface LibraryPage { items: LibraryMaterial[]; nextCursor: string | null }
export interface LibraryMetadata { title: string; type: MaterialType; author: string | null }
export const allowedFormats = new Set(['PDF','GTP','GP','GP3','GP4','GP5','GPX']);
export const MAX_TOTAL_UPLOAD = 20 * 1024 * 1024;
export const MAX_FILES = 5;
const base = '/api/v1/library/materials';

function validFile(value: unknown): value is LibraryFile {
  return isObject(value) && isUuid(value.id) && isString(value.originalName)
    && isString(value.format) && typeof value.byteSize === 'number'
    && Number.isSafeInteger(value.byteSize) && value.byteSize > 0
    && isString(value.sha256) && isString(value.validationStatus)
    && isString(value.downloadUrl) && value.downloadUrl.startsWith(base + '/');
}
function validMaterial(value: unknown): value is LibraryMaterial {
  return isObject(value) && isUuid(value.id) && isString(value.title)
    && ['EXERCISE','ETUDE','COMPOSITION','OTHER'].includes(String(value.type))
    && (value.author === null || isString(value.author))
    && value.state === 'READY' && value.visibility === 'PRIVATE'
    && isString(value.reviewStatus) && Number.isSafeInteger(value.revision)
    && Number(value.revision) >= 1 && isString(value.createdAt)
    && isString(value.updatedAt) && isArrayOf(value.files, validFile);
}
function validPage(value: unknown): value is LibraryPage {
  return isObject(value) && isArrayOf(value.items, validMaterial)
    && (value.nextCursor === null || isString(value.nextCursor));
}
async function request<T>(url: string, options: RequestInit, check: (data: unknown) => data is T): Promise<T> {
  let response: Response;
  try { response = await fetch(url, { credentials: 'same-origin', cache: 'no-store', ...options }); }
  catch (cause) {
    if (cause instanceof Error && cause.name === 'AbortError') throw cause;
    throw new ApiFailure('NETWORK');
  }
  if (!response.ok) throw new ApiFailure('HTTP', response.status);
  let data: unknown;
  try { data = await response.json(); }
  catch { throw new ApiFailure('INVALID_RESPONSE'); }
  if (!check(data)) throw new ApiFailure('INVALID_RESPONSE');
  return data;
}
function materialUrl(id: string): string { return base + '/' + encodeURIComponent(id); }
export const libraryService = {
  page(cursor?: string): Promise<LibraryPage> {
    const params = new URLSearchParams({ limit: '20' });
    if (cursor) params.set('cursor', cursor);
    return request(base + '?' + params, {}, validPage);
  },
  detail(id: string): Promise<LibraryMaterial> {
    return request(materialUrl(id), {}, validMaterial);
  },
  upload(metadata: LibraryMetadata, files: File[], key: string): Promise<LibraryMaterial> {
    const form = new FormData();
    form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
    files.forEach(file => form.append('files', file, file.name));
    return request(base, { method: 'POST', headers: { 'Idempotency-Key': key }, body: form }, validMaterial);
  },
  edit(id: string, revision: number, patch: Partial<LibraryMetadata>): Promise<LibraryMaterial> {
    return request(materialUrl(id), {
      method: 'PATCH', headers: { 'If-Match': '"' + revision + '"', 'Content-Type': 'application/json' },
      body: JSON.stringify(patch),
    }, validMaterial);
  },
  async remove(id: string, revision: number): Promise<void> {
    let response: Response;
    try {
      response = await fetch(materialUrl(id), {
        method: 'DELETE', credentials: 'same-origin', cache: 'no-store',
        headers: { 'If-Match': '"' + revision + '"' },
      });
    } catch { throw new ApiFailure('NETWORK'); }
    if (response.status !== 202) throw new ApiFailure('HTTP', response.status);
  },
  async download(id: string, file: LibraryFile, stillAuthorized: () => boolean = () => true): Promise<void> {
    const expected = materialUrl(id) + '/files/' + encodeURIComponent(file.id) + '/content';
    // Use only same-origin routes derived from trusted IDs, never arbitrary server URLs.
    if (file.downloadUrl !== expected) throw new ApiFailure('INVALID_RESPONSE');
    let response: Response;
    try { response = await fetch(expected, { cache: 'no-store', credentials: 'same-origin' }); }
    catch { throw new ApiFailure('NETWORK'); }
    if (!response.ok) throw new ApiFailure('HTTP', response.status);
    const blob = await response.blob();
    // A session can change while downloading the response body.
    if (!stillAuthorized()) return;
    if (blob.size !== file.byteSize) throw new ApiFailure('INVALID_RESPONSE');
    const href = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = href;
    anchor.download = file.originalName.replace(/[\\/\x00-\x1f\x7f]/g, '_');
    anchor.style.display = 'none';
    document.body.appendChild(anchor);
    try { anchor.click(); }
    finally { anchor.remove(); window.setTimeout(() => URL.revokeObjectURL(href), 1000); }
  },
};
export function validateUpload(files: File[]): 'empty' | 'count' | 'format' | 'size' | null {
  if (files.length === 0) return 'empty';
  if (files.length > MAX_FILES) return 'count';
  let total = 0;
  for (const file of files) {
    const ext = file.name.split('.').pop()?.toUpperCase() || '';
    if (!allowedFormats.has(ext)) return 'format';
    if (file.size <= 0) return 'size';
    total += file.size;
    if (total > MAX_TOTAL_UPLOAD) return 'size';
  }
  return null;
}
