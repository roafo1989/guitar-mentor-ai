import type { CreateLearnerRequest, Learner, UpdatePreferencesRequest } from '../../shared/model/learner.model';
import { apiRequest, isArrayOf, isObject, isString, isUuid } from '../../shared/api-failure';

const baseUrl = '/api/v1/learners';
function validLearner(value: unknown): value is Learner {
  return isObject(value) && isUuid(value.id) && isString(value.displayName)
    && isArrayOf(value.instruments, instrument =>
      isObject(instrument) && isUuid(instrument.id)
      && isString(instrument.instrumentCode) && isString(instrument.techniqueCode)
      && isArrayOf(instrument.preferences, preference =>
        isObject(preference) && isString(preference.styleCode)
        && ['LIKE', 'NEUTRAL', 'EXCLUDE'].includes(String(preference.rating))));
}
export const learnerService = {
  create(data: CreateLearnerRequest): Promise<Learner> {
    return apiRequest(baseUrl, { method: 'POST', body: JSON.stringify(data) }, validLearner);
  },
  get(id: string): Promise<Learner> {
    return apiRequest(baseUrl + '/' + encodeURIComponent(id), undefined, validLearner);
  },
  replacePreferences(id: string, data: UpdatePreferencesRequest): Promise<Learner> {
    return apiRequest(baseUrl + '/' + encodeURIComponent(id) + '/preferences',
      { method: 'PUT', body: JSON.stringify(data) }, validLearner);
  },
};
