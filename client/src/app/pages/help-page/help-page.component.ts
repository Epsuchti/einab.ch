import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';

import { SupportRequestCategory, SupportService, UserPermission } from '../../api';
import { resolveApiError } from '../../core/api-error';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { CLIENT_RELEASE, clientPlatform } from '../../core/client-info';
import { I18nService } from '../../core/i18n.service';
import { isNativeApp } from '../../core/native-app';
import { NavigationHistoryService } from '../../core/navigation-history.service';
import { SessionService } from '../../core/session.service';

/**
 * Help page (footer "Hilfe"): the guide for EinAb-Geber:innen (explainer video and cheat sheet, only for them) and the
 * "Feedback und Support" form. A request goes to the admins of the Bezirk as a Foodsharing message and onto their
 * support page, together with the release and platform the user had.
 */
@Component({
  selector: 'app-help-page',
  standalone: true,
  imports: [RouterLink, FormsModule, ButtonModule, DialogModule, InputTextModule, TextareaModule],
  templateUrl: './help-page.component.html',
  styleUrl: './help-page.component.scss'
})
export class HelpPageComponent {
  readonly i18n = inject(I18nService);
  protected readonly bezirkContext = inject(BezirkContextService);
  protected readonly sessionService = inject(SessionService);
  private readonly supportApi = inject(SupportService);
  private readonly history = inject(NavigationHistoryService);

  protected readonly videoUrl = '/media/teacher-guide/einab-erklaervideo.mp4';
  protected readonly posterUrl = '/media/teacher-guide/einab-erklaervideo-poster.webp';
  protected readonly pdfUrl = '/media/teacher-guide/einab-spickzettel.pdf';
  protected readonly sheetImageUrl = '/media/teacher-guide/einab-spickzettel.webp';

  /** The guide is for EinAb-Geber:innen only. */
  protected readonly isTeacher = computed(() => this.sessionService.isAuthenticated() && this.sessionService.hasPermission(UserPermission.CanGiveEinAbs));
  /**
   * The app's web view cannot show PDFs (Android) or has no way back from one (iOS),
   * so there the cheat sheet is only shown as an image inside the page.
   */
  protected readonly showPdfLinks = !isNativeApp();
  protected readonly sheetZoomVisible = signal(false);

  protected readonly release = CLIENT_RELEASE;
  protected readonly categories = [
    { value: SupportRequestCategory.Question, label: 'help.categoryQuestion', icon: 'pi pi-question-circle' },
    { value: SupportRequestCategory.Problem, label: 'help.categoryProblem', icon: 'pi pi-exclamation-triangle' },
    { value: SupportRequestCategory.Idea, label: 'help.categoryIdea', icon: 'pi pi-lightbulb' }
  ];
  protected readonly category = signal<SupportRequestCategory>(SupportRequestCategory.Question);
  protected readonly message = signal('');
  protected readonly contactFoodsharingId = signal('');
  protected readonly contactName = signal('');
  protected readonly messagePlaceholder = computed(() => {
    switch (this.category()) {
      case SupportRequestCategory.Problem:
        return 'help.messagePlaceholderProblem';
      case SupportRequestCategory.Idea:
        return 'help.messagePlaceholderIdea';
      default:
        return 'help.messagePlaceholderQuestion';
    }
  });
  protected readonly sending = signal(false);
  protected readonly sent = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly canSend = computed(() =>
    !this.sending() && this.message().trim().length > 0
      && (this.sessionService.isAuthenticated() || this.contactFoodsharingId().trim().length > 0));

  send(): void {
    if (!this.canSend()) {
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    const signedIn = this.sessionService.isAuthenticated();
    this.supportApi.createSupportRequest({
      supportRequestCreateRequest: {
        category: this.category(),
        message: this.message().trim(),
        bezirkSlug: this.bezirkContext.selectedBezirk()?.slug ?? null,
        contactFoodsharingId: signedIn ? null : this.contactFoodsharingId().trim(),
        contactName: signedIn ? null : this.contactName().trim() || null,
        clientVersion: this.release,
        platform: clientPlatform(),
        pageUrl: this.history.previousUrl(),
        userAgent: typeof navigator === 'undefined' ? null : navigator.userAgent.slice(0, 500)
      }
    }).pipe(finalize(() => this.sending.set(false))).subscribe({
      next: () => {
        this.sent.set(true);
        this.message.set('');
      },
      error: (error) => this.error.set(resolveApiError(error, this.i18n))
    });
  }

  writeAnother(): void {
    this.sent.set(false);
    this.category.set(SupportRequestCategory.Question);
  }
}
