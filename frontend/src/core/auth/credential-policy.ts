export const MIN_PASSWORD_LENGTH = 6;
export const MAX_PASSWORD_LENGTH = 128;
export function normalizeLogin(value: string): string {
  return value.trim().normalize('NFC').toLowerCase().normalize('NFC');
}
export function isValidLogin(value: string): boolean {
  const chars = Array.from(normalizeLogin(value));
  if (chars.length < 3 || chars.length > 64) return false;
  let afterLetter = false;
  return chars.every((c, index) => {
    const letter = /\p{Letter}/u.test(c) && /[\p{Script=Latin}\p{Script=Cyrillic}]/u.test(c);
    const digit = /^[0-9]$/.test(c);
    const mark = afterLetter && /[\p{Nonspacing_Mark}\p{Spacing_Mark}]/u.test(c)
      && /[\p{Script=Inherited}\p{Script=Latin}\p{Script=Cyrillic}]/u.test(c);
    const valid = letter || digit || (index > 0 && (mark || /^[._-]$/.test(c)));
    afterLetter = letter || mark;
    return valid;
  });
}
export function isValidPassword(value: string): boolean {
  const count = Array.from(value).length;
  return count >= MIN_PASSWORD_LENGTH && count <= MAX_PASSWORD_LENGTH;
}
