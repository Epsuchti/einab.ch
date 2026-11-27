import { CommonModule } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { SurveyPerson, SurveyQuestion, SurveyQuestionType, SurveySaveRequest, SurveyService } from '../../api';
import { resolveApiError } from '../../core/api-error';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { I18nService } from '../../core/i18n.service';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { CheckboxModule } from 'primeng/checkbox';
import { InputTextModule } from 'primeng/inputtext';
import { MultiSelectModule } from 'primeng/multiselect';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';
import { MessageService } from 'primeng/api';
import { SkeletonComponent, SkeletonRegionDirective } from '../../core/skeleton';

interface EditableQuestion {
  id?: string;
  type: SurveyQuestionType;
  label: string;
  required: boolean;
  /** One option per line. */
  optionsText: string;
}

@Component({
  selector: 'app-admin-survey-edit-page',
  standalone: true,
  imports: [SkeletonComponent, SkeletonRegionDirective, CommonModule, FormsModule, RouterLink, ButtonModule, CardModule, CheckboxModule, InputTextModule, MultiSelectModule, SelectModule, TextareaModule],
  templateUrl: './admin-survey-edit-page.component.html'
})
export class AdminSurveyEditPageComponent implements OnInit {
  readonly i18n = inject(I18nService);
  readonly SurveyQuestionType = SurveyQuestionType;
  protected readonly bezirkContext = inject(BezirkContextService);
  protected readonly questionTypes = [
    SurveyQuestionType.Text,
    SurveyQuestionType.SingleChoice,
    SurveyQuestionType.MultipleChoice,
    SurveyQuestionType.Boolean,
    SurveyQuestionType.Store
  ];
  protected typeLabel(type: SurveyQuestionType): string {
    return this.i18n.t('surveys.type.' + type);
  }

  protected typeOptions(): { label: string; value: SurveyQuestionType }[] {
    return this.questionTypes.map((type) => ({ label: this.i18n.t('surveys.type.' + type), value: type }));
  }
  protected readonly surveyId = signal<string | null>(null);
  protected readonly responseCount = signal(0);
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected title = '';
  protected description = '';
  /** Only the creator can change the co-authors; co-authors see them read-only. */
  protected readonly isOwner = signal(true);
  protected readonly ownerName = signal('');
  protected readonly coauthors = signal<SurveyPerson[]>([]);
  protected readonly coauthorCandidates = signal<SurveyPerson[]>([]);
  protected coauthorIds: string[] = [];
  protected readonly questions = signal<EditableQuestion[]>([this.emptyQuestion()]);

  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly surveyApi = inject(SurveyService);
  private readonly messageService = inject(MessageService);

  ngOnInit(): void {
    this.surveyApi.listSurveyCoauthorCandidates().subscribe({
      next: (response) => this.coauthorCandidates.set(this.mergePeople(response.users, this.coauthors())),
      error: (error) => this.showError(error)
    });
    const id = this.route.snapshot.paramMap.get('surveyId');
    if (!id || id === 'new') {
      return;
    }
    this.surveyId.set(id);
    this.loading.set(true);
    this.surveyApi.getMySurvey({ surveyId: id }).subscribe({
      next: (survey) => {
        this.title = survey.title;
        this.description = survey.description;
        this.responseCount.set(survey.responseCount);
        this.isOwner.set(survey.owner);
        this.ownerName.set(survey.ownerName || survey.ownerFoodsharingId);
        this.coauthors.set(survey.coauthors);
        this.coauthorIds = survey.coauthors.map((coauthor) => coauthor.foodsharingId);
        // A co-author who lost the survey permission is no candidate anymore but must stay visible until removed.
        this.coauthorCandidates.update((candidates) => this.mergePeople(candidates, survey.coauthors));
        this.questions.set(survey.questions.map((question) => ({
          id: question.id,
          type: question.type,
          label: question.label,
          required: question.required,
          optionsText: question.options.join('\n')
        })));
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.showError(error);
      }
    });
  }

  isChoice(question: EditableQuestion): boolean {
    return question.type === SurveyQuestionType.SingleChoice || question.type === SurveyQuestionType.MultipleChoice;
  }

  addQuestion(): void {
    this.questions.update((questions) => [...questions, this.emptyQuestion()]);
  }

  removeQuestion(index: number): void {
    this.questions.update((questions) => questions.filter((_, current) => current !== index));
  }

  move(index: number, offset: number): void {
    const target = index + offset;
    this.questions.update((questions) => {
      if (target < 0 || target >= questions.length) {
        return questions;
      }
      const copy = [...questions];
      [copy[index], copy[target]] = [copy[target], copy[index]];
      return copy;
    });
  }

  save(): void {
    if (this.saving()) {
      return;
    }
    const request = this.buildRequest();
    if (!request) {
      return;
    }
    this.saving.set(true);
    const surveyId = this.surveyId();
    const call = surveyId
      ? this.surveyApi.updateSurvey({ surveyId, surveySaveRequest: request })
      : this.surveyApi.createSurvey({ surveySaveRequest: request });
    call.subscribe({
      next: () => {
        this.saving.set(false);
        this.messageService.add({ severity: 'success', summary: this.i18n.t('common.saved') });
        void this.router.navigate(this.bezirkContext.route('admin', 'surveys'));
      },
      error: (error) => {
        this.saving.set(false);
        this.showError(error);
      }
    });
  }

  private buildRequest(): SurveySaveRequest | null {
    const title = this.title.trim();
    const questions = this.questions();
    if (!title) {
      return this.invalid('surveys.validationTitle');
    }
    if (questions.length === 0 || questions.some((question) => !question.label.trim())) {
      return this.invalid('surveys.validationQuestions');
    }
    if (questions.filter((question) => question.type === SurveyQuestionType.Store).length > 1) {
      return this.invalid('surveys.validationStore');
    }
    const mapped: SurveyQuestion[] = questions.map((question) => ({
      id: question.id,
      type: question.type,
      label: question.label.trim(),
      required: question.type === SurveyQuestionType.Store || question.required,
      options: this.isChoice(question) ? this.parseOptions(question.optionsText) : []
    }));
    if (mapped.some((question) => (question.type === SurveyQuestionType.SingleChoice || question.type === SurveyQuestionType.MultipleChoice) && question.options.length < 2)) {
      return this.invalid('surveys.validationOptions');
    }
    const request: SurveySaveRequest = { title, description: this.description.trim(), questions: mapped };
    if (this.isOwner()) {
      request.coauthorFoodsharingIds = [...this.coauthorIds];
    }
    return request;
  }

  personLabel(person: SurveyPerson): string {
    return person.name ? `${person.name} (${person.foodsharingId})` : person.foodsharingId;
  }

  private mergePeople(people: SurveyPerson[], extra: SurveyPerson[]): SurveyPerson[] {
    const known = new Set(people.map((person) => person.foodsharingId.toLowerCase()));
    return [...people, ...extra.filter((person) => !known.has(person.foodsharingId.toLowerCase()))]
      .map((person) => ({ ...person, label: this.personLabel(person) }));
  }

  private parseOptions(text: string): string[] {
    return text.split('\n').map((option) => option.trim()).filter((option, index, all) => option.length > 0 && all.indexOf(option) === index);
  }

  private invalid(key: string): null {
    this.messageService.add({ severity: 'warn', summary: this.i18n.t(key) });
    return null;
  }

  private emptyQuestion(): EditableQuestion {
    return { type: SurveyQuestionType.Text, label: '', required: false, optionsText: '' };
  }

  private showError(error: unknown): void {
    this.messageService.add({ severity: 'error', summary: this.i18n.t('common.error'), detail: resolveApiError(error, this.i18n) });
  }
}
