import { BookingDetailResponse } from '../api';
import { I18nService } from './i18n.service';

/** Downloads a 90-minute calendar entry (.ics) for a booked EinAb, built in the browser. */
export function downloadBookingIcs(booking: BookingDetailResponse, i18n: I18nService): void {
  const start = new Date(booking.startDateTime);
  const end = new Date(start.getTime() + 90 * 60_000);
  const stamp = (date: Date) => date.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
  const escape = (text: string) => text.replace(/\\/g, '\\\\').replace(/[,;]/g, (match) => `\\${match}`).replace(/\r?\n/g, '\\n');
  const title = `EinAb ${i18n.categoryLabel(booking.category)}${booking.businessName ? ` · ${booking.businessName}` : ''}`;
  const description = [booking.privateInfo, booking.onlineCallLink, booking.teacherName && `${i18n.t('slots.teacher')}: ${booking.teacherName} ${booking.teacherPhoneNumber ?? ''}`]
    .filter(Boolean).join('\n');
  const lines = [
    'BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//einab.ch//EinAb//DE', 'BEGIN:VEVENT',
    `UID:${booking.slotId}@einab.ch`, `DTSTAMP:${stamp(new Date())}`, `DTSTART:${stamp(start)}`, `DTEND:${stamp(end)}`,
    `SUMMARY:${escape(title)}`,
    booking.location ? `LOCATION:${escape(booking.location)}` : '',
    description ? `DESCRIPTION:${escape(description)}` : '',
    'END:VEVENT', 'END:VCALENDAR'
  ].filter(Boolean);
  const url = URL.createObjectURL(new Blob([lines.join('\r\n')], { type: 'text/calendar;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = 'einab.ics';
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
