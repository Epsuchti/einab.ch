import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { PrimeNG } from 'primeng/config';
import { firstValueFrom } from 'rxjs';

import { EinAbCategory, ErrorCode, Language, SlotStatus } from '../api';

type UiLanguage = 'de' | 'en' | 'gws';

/**
 * Built-in texts for the connection banner and the most basic errors. They are used
 * when the translation file itself could not be loaded, e.g. when the app starts offline.
 */
const FALLBACK_TRANSLATIONS: Record<UiLanguage, Record<string, string>> = {
  de: {
    'common.error': 'Fehler',
    'connection.offline': 'Du bist offline. Sobald du wieder Internet hast, geht es hier weiter.',
    'connection.serverUnreachable': 'Der Server ist gerade nicht erreichbar. Bitte versuche es in einem Moment nochmals.',
    'connection.retry': 'Neu laden',
    'connection.backOnline': 'Wieder verbunden.',
    'error.NETWORK_ERROR': 'Die Verbindung zum Server ist fehlgeschlagen.',
    'error.OFFLINE': 'Du bist offline. Bitte prüfe deine Internetverbindung.',
    'error.UNEXPECTED_ERROR': 'Unerwarteter Fehler.'
  },
  en: {
    'common.error': 'Error',
    'connection.offline': 'You are offline. Everything continues as soon as you are back online.',
    'connection.serverUnreachable': 'The server cannot be reached right now. Please try again in a moment.',
    'connection.retry': 'Reload',
    'connection.backOnline': 'Connected again.',
    'error.NETWORK_ERROR': 'The connection to the server failed.',
    'error.OFFLINE': 'You are offline. Please check your internet connection.',
    'error.UNEXPECTED_ERROR': 'Unexpected error.'
  },
  gws: {
    'common.error': 'Fehler',
    'connection.offline': 'Du bisch offline. Sobald s Internet wieder da isch, gaht s do witer.',
    'connection.serverUnreachable': 'De Server isch grad nöd erreichbar. Probier s bitte i somene Moment nomal.',
    'connection.retry': 'Neu lade',
    'connection.backOnline': 'Wieder verbunde.',
    'error.NETWORK_ERROR': 'D Verbindig zum Server isch gscheitert.',
    'error.OFFLINE': 'Du bisch offline. Bitte lueg dini Internetverbindig aa.',
    'error.UNEXPECTED_ERROR': 'Unerwartete Fähler.'
  }
};

@Injectable({ providedIn: 'root' })
export class I18nService {
  private readonly storageKey = 'foodsharing.language';
  private readonly http = inject(HttpClient);
  private readonly primeng = inject(PrimeNG);
  private readonly translations = signal<Record<string, string>>({});
  readonly language = signal<UiLanguage>(this.loadInitialLanguage());

  async initialize(): Promise<void> {
    this.setDocumentLanguage(this.language());
    await this.loadLanguage(this.language());
  }

  async setLanguage(language: UiLanguage): Promise<void> {
    this.language.set(language);
    this.setDocumentLanguage(language);
    localStorage.setItem(this.storageKey, language);
    await this.loadLanguage(language);
  }

  /** Screen readers, hyphenation and native date/time inputs follow <html lang>, so keep it on the UI language. */
  private setDocumentLanguage(language: UiLanguage): void {
    if (typeof document !== 'undefined') {
      document.documentElement.lang = language === 'en' ? 'en' : 'de-CH';
    }
  }

  t(key: string): string {
    return this.translations()[key] ?? FALLBACK_TRANSLATIONS[this.language()][key] ?? key;
  }

  apiLanguage(): Language {
    return this.language() === 'en' ? Language.En : this.language() === 'gws' ? Language.Gws : Language.De;
  }

  errorLabel(code: ErrorCode | string | undefined): string {
    return this.t(`error.${code ?? ''}`);
  }

  fieldLabel(field: string): string {
    const fieldKeys: Record<string, string> = {
      businessName: 'common.businessName',
      location: 'common.address',
      privateAddress: 'common.address',
      publicLocation: 'common.publicGeneralLocation',
      publicGeneralLocation: 'common.publicGeneralLocation',
      onlineCallLink: 'common.onlineCallLink',
      minimumPickupCount: 'common.minimumPickupCount',
      slotCount: 'common.slotCount'
    };
    const key = fieldKeys[field] ?? `common.${field}`;
    const translation = this.t(key);
    return translation === key ? field : translation;
  }

  requiredExperienceLabel(count: number | null | undefined): string {
    if (!count) {
      return this.t('common.requiredExperienceNone');
    }
    return count === 1 ? this.t('common.requiredExperienceOne') : this.t('common.requiredExperienceMany').replace('{count}', String(count));
  }

  categoryLabel(category: EinAbCategory | string | undefined): string {
    const key = category ?? '';
    const labels: Record<string, string> = {
      [EinAbCategory.Supermarket]: this.t('category.supermarket'),
      [EinAbCategory.Takeout]: this.t('category.takeout'),
      [EinAbCategory.Market]: this.t('category.market'),
      [EinAbCategory.Bakery]: this.t('category.bakery'),
      [EinAbCategory.Restaurant]: this.t('category.restaurant'),
      [EinAbCategory.FairteilerCleaning]: this.t('category.fairteilerCleaning'),
      [EinAbCategory.Messe]: this.t('category.messe'),
      [EinAbCategory.Grossabholung]: this.t('category.grossabholung'),
      [EinAbCategory.Online]: this.t('category.online')
    };
    return labels[key] ?? key;
  }

  statusLabel(status: SlotStatus | string | undefined): string {
    const key = status ?? '';
    const labels: Record<string, string> = {
      [SlotStatus.Available]: this.t('status.available'),
      [SlotStatus.Booked]: this.t('status.booked'),
      [SlotStatus.Done]: this.t('status.done'),
      [SlotStatus.Cancelled]: this.t('status.cancelled')
    };
    return labels[key] ?? key;
  }

  private async loadLanguage(language: UiLanguage): Promise<void> {
    try {
      const translations = await firstValueFrom(this.http.get<Record<string, string>>(`/i18n/${language}.json`));
      this.translations.set(translations);
      this.applyComponentTranslations();
    } catch (error) {
      // Without the file (offline, server down) the app still starts with the built-in texts,
      // and tries again as soon as the browser is back online.
      console.error('Could not load translations', error);
      if (typeof window !== 'undefined') {
        window.addEventListener('online', () => {
          if (this.language() === language) {
            void this.loadLanguage(language);
          }
        }, { once: true });
      }
    }
  }

  /** Texts that PrimeNG renders itself (confirm buttons, multi-select summary, empty lists). */
  private applyComponentTranslations(): void {
    this.primeng.setTranslation({
      accept: this.t('common.yes'),
      reject: this.t('common.no'),
      cancel: this.t('common.cancel'),
      choose: this.t('primeng.choose'),
      selectionMessage: this.t('primeng.selectionMessage'),
      emptySelectionMessage: this.t('primeng.emptySelectionMessage'),
      emptyMessage: this.t('primeng.emptyMessage'),
      emptyFilterMessage: this.t('primeng.emptyFilterMessage'),
      emptySearchMessage: this.t('primeng.emptyFilterMessage'),
      searchMessage: this.t('primeng.searchMessage'),
      aria: { close: this.t('common.close') },
      ...this.calendarNames()
    });
  }

  /** Day and month names for the PrimeNG date picker, so it follows the app language instead of showing English. */
  private calendarNames() {
    const locale = this.language() === 'en' ? 'en-GB' : 'de-CH';
    // 2024-01-07 is a Sunday, so index 0..6 runs Sunday..Saturday, the order PrimeNG expects.
    const days = Array.from({ length: 7 }, (_, i) => new Date(Date.UTC(2024, 0, 7 + i)));
    const months = Array.from({ length: 12 }, (_, i) => new Date(Date.UTC(2024, i, 15)));
    const format = (date: Date, options: Intl.DateTimeFormatOptions) =>
      new Intl.DateTimeFormat(locale, { ...options, timeZone: 'UTC' }).format(date).replace(/\.$/, '');
    return {
      dayNames: days.map((date) => format(date, { weekday: 'long' })),
      dayNamesShort: days.map((date) => format(date, { weekday: 'short' })),
      dayNamesMin: days.map((date) => format(date, { weekday: 'short' }).slice(0, 2)),
      monthNames: months.map((date) => format(date, { month: 'long' })),
      monthNamesShort: months.map((date) => format(date, { month: 'short' })),
      firstDayOfWeek: 1
    };
  }

  private loadInitialLanguage(): UiLanguage {
    const stored = localStorage.getItem(this.storageKey);
    return stored === 'de' || stored === 'en' || stored === 'gws' ? stored : 'de';
  }
}
