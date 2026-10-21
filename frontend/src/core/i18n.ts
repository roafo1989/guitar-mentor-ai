import { createI18n } from 'vue-i18n';
import { watch } from 'vue';
import { readStoredLocale, saveLocale } from './locale-storage';
import en from '../i18n/en.json';
import ru from '../i18n/ru.json';

export const i18n = createI18n({
  legacy: false,
  locale: readStoredLocale(),
  fallbackLocale: 'en',
  messages: { ru, en },
});

// A single global listener follows every locale switch, independently of routes.
watch(i18n.global.locale, nextLocale => {
  saveLocale(nextLocale);
});
