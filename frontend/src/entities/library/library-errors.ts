import { asApiFailure } from '../../shared/api-failure';
import { LibraryFailure } from './community.service';
export function libraryError(e: unknown, t: (key:string, args?: Record<string,unknown>)=>string): string {
  const f=asApiFailure(e);
  const specific: Record<string,string>={CATALOG_ASSET_PUBLISHED:'referenced',CATALOG_LAST_SCORE:'lastScore',LIBRARY_QUOTA_EXCEEDED:'quota',LIBRARY_MATERIAL_ASSET_LIMIT:'limits',LIBRARY_MATERIAL_PHOTO_LIMIT:'limits',LIBRARY_MATERIAL_AUDIO_LIMIT:'limits',CATALOG_ASSET_NOT_READY:'unavailable'};
  if(f.code && specific[f.code])return t('community.'+specific[f.code]);
  if(f.status===429 && e instanceof LibraryFailure && e.retryAt>Date.now()) return t('community.errors.rateUntil',{time:new Date(e.retryAt).toLocaleTimeString()});
  const keys:Record<number,string>={400:'validation',401:'auth',403:'denied',404:'missing',409:'conflict',412:'version',428:'version',413:'size',415:'format',429:'rate',503:'disabled'};
  return t('community.errors.'+(f.status?keys[f.status]??'network':f.kind==='NETWORK'?'network':'invalid'));
}
