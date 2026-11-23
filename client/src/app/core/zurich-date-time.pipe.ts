import { Pipe, PipeTransform, inject } from '@angular/core';

import { I18nService } from './i18n.service';

/**
 * - `medium` (default): weekday, date and time, e.g. "Fr., 13.11., 18:00"; the year only when it is not the current one.
 * - `short`: compact numeric date and time, e.g. "13.11.26, 18:00".
 * - `date`: weekday and date without time, e.g. "Fr., 13.11.".
 * - `time`: time only, e.g. "18:00".
 * - `day`: "heute" / "morgen" or the weekday with day and month name, e.g. "Fr., 13. November" (for group headings).
 */
export type ZurichDateTimeVariant = 'short' | 'medium' | 'date' | 'time' | 'day';

@Pipe({
  name: 'zurichDateTime',
  standalone: true
})
export class ZurichDateTimePipe implements PipeTransform {
  private readonly i18n = inject(I18nService);

  transform(value: string | Date | null | undefined, variant: ZurichDateTimeVariant = 'medium'): string {
    if (!value) {
      return '';
    }

    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) {
      return '';
    }

    const locale = this.i18n.language() === 'en' ? 'en-CH' : 'de-CH';
    const timeZone = 'Europe/Zurich';
    const sameYear = zurichYear(date) === zurichYear(new Date());
    const year = sameYear ? undefined : 'numeric';

    switch (variant) {
      case 'short':
        return new Intl.DateTimeFormat(locale, { dateStyle: 'short', timeStyle: 'short', timeZone }).format(date);
      case 'time':
        return new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', timeZone }).format(date);
      case 'date':
        return new Intl.DateTimeFormat(locale, { weekday: 'short', day: '2-digit', month: '2-digit', year, timeZone }).format(date);
      case 'day': {
        const offset = dayOffset(date);
        if (offset === 0) {
          return capitalize(this.i18n.t('common.today'));
        }
        if (offset === 1) {
          return capitalize(this.i18n.t('common.tomorrow'));
        }
        return new Intl.DateTimeFormat(locale, { weekday: 'long', day: 'numeric', month: 'long', year, timeZone }).format(date);
      }
      default:
        return new Intl.DateTimeFormat(locale, {
          weekday: 'short',
          day: '2-digit',
          month: '2-digit',
          year,
          hour: '2-digit',
          minute: '2-digit',
          timeZone
        }).format(date);
    }
  }
}

function zurichYear(date: Date): string {
  return new Intl.DateTimeFormat('de-CH', { year: 'numeric', timeZone: 'Europe/Zurich' }).format(date);
}

/** Calendar date in Zurich as YYYY-MM-DD, for grouping and comparing days. */
export function zurichDayKey(value: string | Date): string {
  const date = value instanceof Date ? value : new Date(value);
  return new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: 'Europe/Zurich' }).format(date);
}

function dayOffset(date: Date): number {
  const day = Date.parse(zurichDayKey(date));
  const today = Date.parse(zurichDayKey(new Date()));
  return Math.round((day - today) / 86_400_000);
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}
