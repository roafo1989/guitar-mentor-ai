import type { HomeNextStep } from './home-summary';
export function homeNextTarget(step?: HomeNextStep): string {
  return step === 'profile' ? '/profile' : step === 'onboarding' ? '/onboarding'
    : step === 'result' ? '/lessons/new' : '/diagnostics';
}
