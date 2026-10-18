import type { LearningBackground } from '../../shared/model/onboarding.model';

const baseUrl = '/api/v1/learners';

function url(learnerId: string, instrumentCode: string): string {
  return baseUrl + '/' + encodeURIComponent(learnerId)
    + '/instruments/' + encodeURIComponent(instrumentCode) + '/background';
}

async function call(
  learnerId: string,
  instrumentCode: string,
  options?: RequestInit,
): Promise<LearningBackground | null> {
  let response: Response;
  try {
    response = await fetch(url(learnerId, instrumentCode), {
      ...options,
      headers: { 'Content-Type': 'application/json', ...options?.headers },
    });
  } catch {
    throw new Error('NETWORK_UNAVAILABLE');
  }
  if (response.status === 204) return null;
  if (!response.ok) {
    const message = await response.text().catch(() => response.statusText);
    throw new Error('HTTP_' + response.status + ': ' + message);
  }
  return (await response.json()) as LearningBackground;
}

export const onboardingService = {
  get(learnerId: string, instrumentCode: string) {
    return call(learnerId, instrumentCode);
  },
  save(learnerId: string, instrumentCode: string, background: LearningBackground) {
    return call(learnerId, instrumentCode, {
      method: 'PUT',
      body: JSON.stringify(background),
    });
  },
};
