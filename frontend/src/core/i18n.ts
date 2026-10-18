import { createI18n } from 'vue-i18n';
import en from '../i18n/en.json';
import ru from '../i18n/ru.json';

export const i18n = createI18n({
  legacy: false,
  locale: 'ru',
  fallbackLocale: 'en',
  messages: { ru, en },
});
