import { ConfirmationService } from 'primeng/api';

import { I18nService } from './i18n.service';

export interface ConfirmActionOptions {
  /** Short question as the dialog title, e.g. "Buchung stornieren?". */
  header: string;
  message: string;
  /** Button text that names the action, e.g. "Stornieren". */
  acceptLabel: string;
  /** Defaults to "Abbrechen". */
  rejectLabel?: string;
  /** Red accept button and focus on the safe choice. */
  destructive?: boolean;
  accept: () => void;
  reject?: () => void;
}

/**
 * Opens the shared confirm dialog with a title, action-named buttons and, for destructive
 * actions, a red accept button. Use this instead of calling the ConfirmationService directly,
 * so every confirm pop-up looks and reads the same.
 */
export function confirmAction(service: ConfirmationService, i18n: I18nService, options: ConfirmActionOptions): void {
  const rejectLabel = options.rejectLabel ?? i18n.t('common.cancel');
  service.confirm({
    header: options.header,
    message: options.message,
    icon: options.destructive ? 'pi pi-exclamation-triangle' : 'pi pi-question-circle',
    acceptLabel: options.acceptLabel,
    rejectLabel,
    acceptButtonProps: { label: options.acceptLabel, severity: options.destructive ? 'danger' : undefined },
    rejectButtonProps: { label: rejectLabel, severity: 'secondary', outlined: true },
    defaultFocus: options.destructive ? 'reject' : 'accept',
    closable: true,
    closeOnEscape: true,
    dismissableMask: true,
    accept: options.accept,
    reject: options.reject
  });
}
