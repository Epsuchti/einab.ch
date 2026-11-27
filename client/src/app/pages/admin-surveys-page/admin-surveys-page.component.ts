import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { Menu, MenuModule } from 'primeng/menu';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { SurveyService, SurveySummary } from '../../api';
import { resolveApiError } from '../../core/api-error';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { confirmAction } from '../../core/confirm-action';
import { I18nService } from '../../core/i18n.service';
import { ZurichDateTimePipe } from '../../core/zurich-date-time.pipe';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { TableModule } from 'primeng/table';
import { TextareaModule } from 'primeng/textarea';
import { ConfirmationService, MenuItem, MessageService } from 'primeng/api';
import { SkeletonComponent, SkeletonRegionDirective } from '../../core/skeleton';

@Component({
  selector: 'app-admin-surveys-page',
  standalone: true,
  imports: [SkeletonComponent, SkeletonRegionDirective, CommonModule, FormsModule, RouterLink, ZurichDateTimePipe, ButtonModule, CardModule, DialogModule, InputTextModule, TableModule, TextareaModule, MenuModule],
  templateUrl: './admin-surveys-page.component.html'
})
export class AdminSurveysPageComponent implements OnInit {
  /** Edit and delete live in a menu, so delete is not as loud as the daily actions. */
  protected readonly surveyActionItems = signal<MenuItem[]>([]);
  private readonly surveyActionsMenu = viewChild<Menu>('surveyActionsMenu');
  private readonly router = inject(Router);

  openSurveyActions(event: Event, survey: SurveySummary): void {
    const items: MenuItem[] = [
      { label: this.i18n.t('common.edit'), icon: 'pi pi-pencil', command: () => void this.router.navigate(this.bezirkContext.route('admin', 'surveys', survey.id)) }
    ];
    if (survey.owner) {
      items.push({ separator: true }, { label: this.i18n.t('common.delete'), icon: 'pi pi-trash', styleClass: 'menu-item--danger', command: () => this.confirmDelete(survey) });
    }
    this.surveyActionItems.set(items);
    this.surveyActionsMenu()?.toggle(event);
  }

  readonly i18n = inject(I18nService);
  protected readonly bezirkContext = inject(BezirkContextService);
  protected readonly surveys = signal<SurveySummary[]>([]);
  protected readonly loading = signal(true);

  /** Survey whose share links are shown in the dialog. */
  protected readonly linkSurvey = signal<SurveySummary | null>(null);
  protected readonly storesText = signal('');
  protected readonly baseLink = computed(() => {
    const survey = this.linkSurvey();
    return survey ? this.surveyLink(survey.id) : '';
  });
  protected readonly storeLinks = computed(() => {
    const survey = this.linkSurvey();
    if (!survey) {
      return [];
    }
    return this.storesText()
      .split('\n')
      .map((store) => store.trim())
      .filter((store, index, all) => store.length > 0 && all.indexOf(store) === index)
      .map((store) => ({ store, link: `${this.surveyLink(survey.id)}?store=${encodeURIComponent(store)}` }));
  });

  private readonly surveyApi = inject(SurveyService);
  private readonly messageService = inject(MessageService);
  private readonly confirmationService = inject(ConfirmationService);

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.surveyApi.listMySurveys().subscribe({
      next: (response) => {
        this.surveys.set(response.surveys);
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.showError(error);
      }
    });
  }

  confirmDelete(survey: SurveySummary): void {
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.deleteSurvey.title'),
      acceptLabel: this.i18n.t('confirmDialog.delete.accept'),
      destructive: true,
      message: `«${survey.title}»\n\n${this.i18n.t('surveys.deleteConfirm')}`,
      accept: () => this.surveyApi.deleteSurvey({ surveyId: survey.id }).subscribe({
        next: () => {
          this.messageService.add({ severity: 'success', summary: this.i18n.t('surveys.deleted') });
          this.reload();
        },
        error: (error) => this.showError(error)
      })
    });
  }

  openLinks(survey: SurveySummary): void {
    this.storesText.set('');
    this.linkSurvey.set(survey);
  }

  closeLinks(): void {
    this.linkSurvey.set(null);
  }

  async copy(text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      this.messageService.add({ severity: 'success', summary: this.i18n.t('surveys.copied') });
    } catch {
      this.messageService.add({ severity: 'warn', summary: this.i18n.t('surveys.copyFailed') });
    }
  }

  copyAllStoreLinks(): void {
    void this.copy(this.storeLinks().map((entry) => `${entry.store}: ${entry.link}`).join('\n'));
  }

  private surveyLink(surveyId: string): string {
    return `${window.location.origin}/bezirke/${this.bezirkContext.currentSlug()}/survey/${surveyId}`;
  }

  private showError(error: unknown): void {
    this.messageService.add({ severity: 'error', summary: this.i18n.t('common.error'), detail: resolveApiError(error, this.i18n) });
  }
}
