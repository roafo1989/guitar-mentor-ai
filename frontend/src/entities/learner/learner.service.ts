import type {
  CreateLearnerRequest,
  Learner,
  UpdatePreferencesRequest,
} from '../../shared/model/learner.model';

const baseUrl = '/api/v1/learners';

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, {
      ...options,
      headers: { 'Content-Type': 'application/json', ...options?.headers },
    });
  } catch {
    throw new Error('NETWORK_UNAVAILABLE');
  }

  if (!response.ok) {
    const payload: unknown = await response.json().catch(() => null);
    const detail =
      typeof payload === 'object' &&
      payload !== null &&
      'error' in payload &&
      typeof payload.error === 'string'
        ? payload.error
        : response.statusText;
    throw new Error('HTTP_' + response.status + ': ' + detail);
  }
  return (await response.json()) as T;
}

export const learnerService = {
  create(data: CreateLearnerRequest): Promise<Learner> {
    return request<Learner>(baseUrl, { method: 'POST', body: JSON.stringify(data) });
  },
  get(id: string): Promise<Learner> {
    return request<Learner>(baseUrl + '/' + encodeURIComponent(id));
  },
  replacePreferences(id: string, data: UpdatePreferencesRequest): Promise<Learner> {
    return request<Learner>(baseUrl + '/' + encodeURIComponent(id) + '/preferences', {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  },
};
