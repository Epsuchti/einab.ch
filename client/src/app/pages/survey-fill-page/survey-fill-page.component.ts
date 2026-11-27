import { CommonModule } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';

import { SurveyAnswerValue, SurveyForm, SurveyQuestion, SurveyQuestionType, SurveyService } from '../../api';
import { resolveApiError } from '../../core/api-error';
import { I18nService } from '../../core/i18n.service';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { CheckboxModule } from 'primeng/checkbox';
import { InputTextModule } from 'primeng/inputtext';
import { RadioButtonModule } from 'primeng/radiobutton';
import { TextareaModule } from 'primeng/textarea';
import { MessageService } from 'primeng/api';
import { SkeletonComponent, SkeletonRegionDirective } from '../../core/skeleton';

/**
 * Survey for respondents. The page is opened from a link (optionally with ?store=<name>); the user signs in
 * through the normal Foodsharing login first (auth guard) and comes back here. The store question shows the
 * value from the link read-only.
 */
@Component({
  selector: 'app-survey-fill-page',
  standalone: true,
  imports: [SkeletonComponent, SkeletonRegionDirective, CommonModule, FormsModule, ButtonModule, CardModule, CheckboxModule, InputTextModule, RadioButtonModule, TextareaModule],
  templateUrl: './survey-fill-page.component.html'
})
export class SurveyFillPageComponent implements OnInit {
  readonly i18n = inject(I18nService);
  readonly SurveyQuestionType = SurveyQuestionType;
  protected readonly survey = signal<SurveyForm | null>(null);
  protected readonly loading = signal(true);
  protected readonly submitting = signal(false);
  protected readonly submitted = signal(false);
  protected readonly errorMessage = signal('');
  protected readonly store = signal('');
  /** Entered text / chosen option / 'true' or 'false' per question id. */
  protected readonly values: Record<string, string> = {};
  protected readonly selected: Record<string, string[]> = {};

  private readonly route = inject(ActivatedRoute);
  private readonly surveyApi = inject(SurveyService);
  private readonly messageService = inject(MessageService);
  private surveyId = '';

  ngOnInit(): void {
    this.surveyId = this.route.snapshot.paramMap.get('surveyId') ?? '';
    this.store.set((this.route.snapshot.queryParamMap.get('store') ?? '').trim());
    this.surveyApi.getSurveyForm({ surveyId: this.surveyId }).subscribe({
      next: (survey) => {
        survey.questions.forEach((question) => (this.selected[question.id!] = []));
        this.survey.set(survey);
        this.submitted.set(survey.alreadySubmitted);
        this.loading.set(false);
      },
      error: (error) => {
        this.errorMessage.set(resolveApiError(error, this.i18n));
        this.loading.set(false);
      }
    });
  }

  /** Required questions left empty on the last submit attempt; marked inline until the next attempt. */
  protected readonly missingQuestions = signal<ReadonlySet<string>>(new Set());

  hasStoreQuestion(): boolean {
    return this.survey()?.questions.some((question) => question.type === SurveyQuestionType.Store) ?? false;
  }

  /** A store question can only be answered through the link. */
  storeMissing(): boolean {
    return this.hasStoreQuestion() && !this.store();
  }

  submit(): void {
    const survey = this.survey();
    if (!survey || this.submitting() || this.storeMissing()) {
      return;
    }
    const answers: SurveyAnswerValue[] = [];
    const missing: string[] = [];
    for (const question of survey.questions) {
      const questionId = question.id!;
      const values = this.answerValues(question);
      if (values.length === 0 && question.required) {
        missing.push(questionId);
      }
      if (values.length > 0) {
        answers.push({ questionId, values });
      }
    }
    this.missingQuestions.set(new Set(missing));
    if (missing.length) {
      this.messageService.add({ severity: 'warn', summary: this.i18n.t('survey.requiredMissingCount').replace('{0}', String(missing.length)) });
      document.getElementById('question-' + missing[0])?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      return;
    }
    this.submitting.set(true);
    this.surveyApi.submitSurveyResponse({ surveyId: this.surveyId, surveySubmitRequest: { answers } }).subscribe({
      next: () => {
        this.submitting.set(false);
        this.submitted.set(true);
      },
      error: (error) => {
        this.submitting.set(false);
        this.messageService.add({ severity: 'error', summary: this.i18n.t('common.error'), detail: resolveApiError(error, this.i18n) });
      }
    });
  }

  private answerValues(question: SurveyQuestion): string[] {
    const questionId = question.id!;
    switch (question.type) {
      case SurveyQuestionType.Store:
        return this.store() ? [this.store()] : [];
      case SurveyQuestionType.MultipleChoice:
        return this.selected[questionId] ?? [];
      default: {
        const value = (this.values[questionId] ?? '').trim();
        return value ? [value] : [];
      }
    }
  }
}
