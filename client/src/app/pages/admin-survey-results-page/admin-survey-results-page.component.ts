import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';

import { SurveyQuestion, SurveyQuestionType, SurveyResultRow, SurveyResultsResponse, SurveyService } from '../../api';
import { resolveApiError } from '../../core/api-error';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { I18nService } from '../../core/i18n.service';
import { LoadingIndicatorComponent } from '../../core/loading-indicator.component';
import { ZurichDateTimePipe } from '../../core/zurich-date-time.pipe';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { InputTextModule } from 'primeng/inputtext';
import { TableModule } from 'primeng/table';
import { TabsModule } from 'primeng/tabs';
import { TagModule } from 'primeng/tag';
import { MessageService } from 'primeng/api';

type ResultsTab = 'questions' | 'replies' | 'table';

interface ChartBar {
  label: string;
  count: number;
  /** Share of the people who answered the question, 0-100. */
  percent: number;
  tone?: 'yes' | 'no';
}

interface TextReply {
  text: string;
  name: string;
  submittedAt: string;
}

interface QuestionSummary {
  question: SurveyQuestion;
  index: number;
  answered: number;
  bars: ChartBar[];
  texts: TextReply[];
}

@Component({
  selector: 'app-admin-survey-results-page',
  standalone: true,
  imports: [LoadingIndicatorComponent, CommonModule, FormsModule, RouterLink, ZurichDateTimePipe, ButtonModule, CardModule, InputTextModule, TableModule, TabsModule, TagModule],
  templateUrl: './admin-survey-results-page.component.html'
})
export class AdminSurveyResultsPageComponent implements OnInit {
  readonly i18n = inject(I18nService);
  protected readonly bezirkContext = inject(BezirkContextService);
  readonly SurveyQuestionType = SurveyQuestionType;
  protected readonly results = signal<SurveyResultsResponse | null>(null);
  protected readonly loading = signal(true);
  protected readonly activeTab = signal<ResultsTab>('questions');
  protected readonly replySearch = signal('');
  /** Text questions whose full reply list is shown (the others show the first few replies). */
  protected readonly expandedTexts = signal<ReadonlySet<string>>(new Set());
  protected readonly textPreviewCount = 5;

  /** Newest reply first, numbered in submission order. */
  protected readonly replies = computed(() => {
    const results = this.results();
    if (!results) {
      return [];
    }
    return results.responses.map((row, index) => ({ row, number: index + 1 })).reverse();
  });

  protected readonly filteredReplies = computed(() => {
    const search = this.replySearch().trim().toLowerCase();
    const results = this.results();
    if (!search || !results) {
      return this.replies();
    }
    return this.replies().filter(({ row }) =>
      [row.name, row.foodsharingId, ...results.questions.map((question) => this.cell(row, question.id, question.type))]
        .some((value) => value.toLowerCase().includes(search))
    );
  });

  protected readonly summaries = computed<QuestionSummary[]>(() => {
    const results = this.results();
    if (!results) {
      return [];
    }
    return results.questions.map((question, index) => this.summarize(question, index, results.responses));
  });

  private readonly route = inject(ActivatedRoute);
  private readonly surveyApi = inject(SurveyService);
  private readonly messageService = inject(MessageService);

  ngOnInit(): void {
    const surveyId = this.route.snapshot.paramMap.get('surveyId') ?? '';
    this.surveyApi.getSurveyResults({ surveyId }).pipe(finalize(() => this.loading.set(false))).subscribe({
      next: (results) => this.results.set(results),
      error: (error) => this.messageService.add({ severity: 'error', summary: this.i18n.t('common.error'), detail: resolveApiError(error, this.i18n) })
    });
  }

  onTabChange(value: string | number | undefined): void {
    this.activeTab.set((value as ResultsTab) ?? 'questions');
  }

  toggleTexts(questionId: string | undefined): void {
    if (!questionId) {
      return;
    }
    this.expandedTexts.update((current) => {
      const next = new Set(current);
      if (!next.delete(questionId)) {
        next.add(questionId);
      }
      return next;
    });
  }

  isExpanded(questionId: string | undefined): boolean {
    return !!questionId && this.expandedTexts().has(questionId);
  }

  answerValues(row: SurveyResultRow, questionId: string | undefined): string[] {
    return row.answers.find((answer) => answer.questionId === questionId)?.values ?? [];
  }

  cell(row: SurveyResultRow, questionId: string | undefined, type: SurveyQuestionType): string {
    const values = this.answerValues(row, questionId);
    if (values.length === 0) {
      return '-';
    }
    if (type === SurveyQuestionType.Boolean) {
      return this.i18n.t(values[0] === 'true' ? 'common.yes' : 'common.no');
    }
    return values.join(', ');
  }

  isYes(row: SurveyResultRow, questionId: string | undefined): boolean {
    return this.answerValues(row, questionId)[0] === 'true';
  }

  initials(name: string, fallback: string): string {
    const parts = (name || fallback).trim().split(/\s+/).filter((part) => part.length > 0);
    return parts.slice(0, 2).map((part) => part[0].toUpperCase()).join('') || '?';
  }

  questionIcon(type: SurveyQuestionType): string {
    switch (type) {
      case SurveyQuestionType.Boolean:
        return 'pi pi-check-square';
      case SurveyQuestionType.SingleChoice:
        return 'pi pi-circle';
      case SurveyQuestionType.MultipleChoice:
        return 'pi pi-list-check';
      case SurveyQuestionType.Store:
        return 'pi pi-shop';
      default:
        return 'pi pi-align-left';
    }
  }

  exportCsv(): void {
    const results = this.results();
    if (!results) {
      return;
    }
    const escape = (value: string) => `"${value.replace(/"/g, '""')}"`;
    const header = [this.i18n.t('surveys.submittedAt'), this.i18n.t('common.name'), this.i18n.t('surveys.foodsharingId'), ...results.questions.map((question) => question.label)];
    const lines = results.responses.map((row) => [
      row.submittedAt,
      row.name,
      row.foodsharingId,
      ...results.questions.map((question) => this.cell(row, question.id, question.type))
    ]);
    const csv = [header, ...lines].map((line) => line.map((value) => escape(String(value))).join(';')).join('\r\n');
    const url = URL.createObjectURL(new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `${results.title.replace(/[^\w\-]+/g, '_')}.csv`;
    link.click();
    URL.revokeObjectURL(url);
  }

  private summarize(question: SurveyQuestion, index: number, responses: SurveyResultRow[]): QuestionSummary {
    const answeredRows = responses.filter((row) => this.answerValues(row, question.id).length > 0);
    const answered = answeredRows.length;
    const counts = new Map<string, number>();
    const texts: TextReply[] = [];
    for (const row of answeredRows) {
      const values = this.answerValues(row, question.id);
      if (question.type === SurveyQuestionType.Text) {
        texts.push({ text: values[0], name: row.name || row.foodsharingId, submittedAt: row.submittedAt });
        continue;
      }
      for (const value of values) {
        counts.set(value, (counts.get(value) ?? 0) + 1);
      }
    }
    const bar = (label: string, count: number, tone?: 'yes' | 'no'): ChartBar => ({
      label,
      count,
      percent: answered === 0 ? 0 : Math.round((count / answered) * 100),
      tone
    });

    let bars: ChartBar[] = [];
    switch (question.type) {
      case SurveyQuestionType.Boolean:
        bars = [bar(this.i18n.t('common.yes'), counts.get('true') ?? 0, 'yes'), bar(this.i18n.t('common.no'), counts.get('false') ?? 0, 'no')];
        break;
      case SurveyQuestionType.SingleChoice:
      case SurveyQuestionType.MultipleChoice: {
        // Defined options in their order (also unchosen ones), then values of options that were removed later.
        const labels = [...question.options, ...[...counts.keys()].filter((value) => !question.options.includes(value))];
        bars = labels.map((label) => bar(label, counts.get(label) ?? 0));
        break;
      }
      case SurveyQuestionType.Store:
        bars = [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map(([label, count]) => bar(label, count));
        break;
    }
    return { question, index, answered, bars, texts: texts.reverse() };
  }
}
