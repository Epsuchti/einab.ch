import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable, finalize } from 'rxjs';

import {
  AdminService,
  NewcomerFunnelResponse,
  NewcomerFunnelStep,
  NewcomerGap,
  NewcomerGapSeverity,
  TeacherService,
  UserPermission
} from '../../api';
import { resolveApiError } from '../../core/api-error';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { I18nService } from '../../core/i18n.service';
import { LoadingIndicatorComponent } from '../../core/loading-indicator.component';
import { SessionService } from '../../core/session.service';
import { ZurichDateTimePipe } from '../../core/zurich-date-time.pipe';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { SelectModule } from 'primeng/select';
import { TagModule } from 'primeng/tag';
import { MessageService } from 'primeng/api';
import { NewcomerFunnelStepperComponent, newcomerStepLabel } from './newcomer-funnel-stepper.component';
import { NewcomerNeedsComponent } from './newcomer-needs.component';

/**
 * Newcomer funnel of the Bezirk. Admins (CAN_MANAGE_USERS) see the people at each step,
 * EinAb givers only the counts. Both see what newcomers need and which EinAbs would help most.
 */
@Component({
  selector: 'app-newcomer-funnel-page',
  standalone: true,
  imports: [LoadingIndicatorComponent, CommonModule, FormsModule, RouterLink, ZurichDateTimePipe, ButtonModule, CardModule, SelectModule, TagModule, NewcomerFunnelStepperComponent, NewcomerNeedsComponent],
  templateUrl: './newcomer-funnel-page.component.html',
  styleUrl: './newcomer-funnel-page.component.scss'
})
export class NewcomerFunnelPageComponent implements OnInit {
  readonly i18n = inject(I18nService);
  readonly NewcomerGapSeverity = NewcomerGapSeverity;

  protected readonly funnel = signal<NewcomerFunnelResponse | null>(null);
  protected readonly loading = signal(false);
  /** 0 means all users, whenever they registered. */
  protected readonly days = signal(365);
  protected readonly showNames = computed(() => this.sessionService.hasPermission(UserPermission.CanManageUsers));
  protected readonly canGiveEinAbs = computed(() => this.sessionService.hasPermission(UserPermission.CanGiveEinAbs));
  protected readonly selectedIndex = signal<number | null>(null);
  protected readonly selectedStep = computed(() => {
    const index = this.selectedIndex();
    return index === null ? null : this.funnel()?.steps[index] ?? null;
  });
  protected readonly periodOptions = computed(() => [
    ...[90, 180, 365].map((days) => ({
      label: this.i18n.t('funnel.periodDays').replace('{days}', String(days)),
      value: days
    })),
    { label: this.i18n.t('funnel.periodAll'), value: 0 }
  ]);
  protected readonly problemGaps = computed(() =>
    (this.funnel()?.gaps ?? []).filter((gap) => gap.severity !== NewcomerGapSeverity.Ok));
  protected readonly okGaps = computed(() =>
    (this.funnel()?.gaps ?? []).filter((gap) => gap.severity === NewcomerGapSeverity.Ok));

  private readonly sessionService = inject(SessionService);
  protected readonly bezirkContext = inject(BezirkContextService);
  private readonly adminApi = inject(AdminService);
  private readonly teacherApi = inject(TeacherService);
  private readonly messageService = inject(MessageService);

  ngOnInit(): void {
    this.load();
  }

  selectStep(index: number): void {
    this.selectedIndex.set(this.selectedIndex() === index ? null : index);
  }

  setDays(days: number): void {
    this.days.set(days);
    this.load();
  }

  protected stepLabel(step: NewcomerFunnelStep): string {
    return newcomerStepLabel(this.i18n, step);
  }

  protected gapLabel(gap: NewcomerGap): string {
    const category = this.i18n.categoryLabel(gap.category);
    return gap.withFairteiler ? this.i18n.t('funnel.gap.withFairteiler').replace('{category}', category) : category;
  }

  protected gapSeverityLabel(severity: NewcomerGapSeverity): string {
    return severity === NewcomerGapSeverity.Missing
      ? this.i18n.t('funnel.gap.missing')
      : severity === NewcomerGapSeverity.Low ? this.i18n.t('funnel.gap.low') : this.i18n.t('funnel.gap.ok');
  }

  protected gapSeverityTag(severity: NewcomerGapSeverity): 'danger' | 'warn' | 'success' {
    return severity === NewcomerGapSeverity.Missing ? 'danger' : severity === NewcomerGapSeverity.Low ? 'warn' : 'success';
  }

  private load(): void {
    const params = { bezirkSlug: this.bezirkContext.currentSlug(), days: this.days() || undefined };
    const request: Observable<NewcomerFunnelResponse> = this.showNames()
      ? this.adminApi.getAdminNewcomerFunnel(params)
      : this.teacherApi.getTeacherNewcomerFunnel(params);
    this.loading.set(true);
    request.pipe(finalize(() => this.loading.set(false))).subscribe({
      next: (funnel) => {
        this.funnel.set(funnel);
        if (this.selectedIndex() === null) {
          const firstWithPeople = funnel.steps.findIndex((step) => step.userCount > 0);
          this.selectedIndex.set(firstWithPeople >= 0 ? firstWithPeople : null);
        }
      },
      error: (error) => this.messageService.add({ severity: 'error', summary: resolveApiError(error, this.i18n) })
    });
  }
}
