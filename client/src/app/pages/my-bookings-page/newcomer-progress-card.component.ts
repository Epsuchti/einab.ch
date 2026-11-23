import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';

import { NewcomerNextStep, NewcomerProgressResponse, NewcomerStage, UserService } from '../../api';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { I18nService } from '../../core/i18n.service';
import { LoadingIndicatorComponent } from '../../core/loading-indicator.component';
import { ButtonModule } from 'primeng/button';

type StepState = 'done' | 'booked' | 'open';

interface ProgressStep {
  label: string;
  state: StepState;
  fairteiler: boolean;
}

/** Shows a newcomer how far they are on the way to verification. Hidden when the progress cannot be loaded. */
@Component({
  selector: 'app-newcomer-progress-card',
  standalone: true,
  imports: [LoadingIndicatorComponent, CommonModule, RouterLink, ButtonModule],
  templateUrl: './newcomer-progress-card.component.html',
  styleUrl: './newcomer-progress-card.component.scss',
})
export class NewcomerProgressCardComponent implements OnInit {
  private static readonly RING_RADIUS = 52;

  readonly i18n = inject(I18nService);
  readonly NewcomerNextStep = NewcomerNextStep;
  protected readonly bezirkContext = inject(BezirkContextService);
  protected readonly progress = signal<NewcomerProgressResponse | null>(null);
  protected readonly loading = signal(true);
  protected readonly ringCircumference = 2 * Math.PI * NewcomerProgressCardComponent.RING_RADIUS;
  protected readonly ringRadius = NewcomerProgressCardComponent.RING_RADIUS;

  protected readonly steps = computed<ProgressStep[]>(() => {
    const progress = this.progress();
    if (!progress) {
      return [];
    }
    const steps: ProgressStep[] = [];
    for (let index = 0; index < progress.targetEinAbs; index++) {
      const state: StepState = index < progress.countedEinAbs
        ? 'done'
        : index < progress.countedEinAbs + progress.upcomingEinAbs ? 'booked' : 'open';
      steps.push({
        label: this.i18n.t('progress.einAbNumber').replace('{number}', String(index + 1)),
        state,
        fairteiler: false
      });
    }
    if (progress.fairteilerRequired) {
      steps.push({
        label: this.i18n.t('progress.fairteilerVisit'),
        state: progress.fairteilerDone ? 'done' : progress.fairteilerBooked ? 'booked' : 'open',
        fairteiler: true
      });
    }
    steps.push({
      label: this.i18n.t('progress.verified'),
      state: progress.verified ? 'done' : progress.stage === NewcomerStage.Complete ? 'booked' : 'open',
      fairteiler: false
    });
    return steps;
  });

  /** Share of the needed EinAbs done, 0..1, matching the "2/3" in the ring. Verified fills the ring. */
  protected readonly ratio = computed(() => {
    const progress = this.progress();
    if (!progress) {
      return 0;
    }
    if (progress.verified) {
      return 1;
    }
    return progress.targetEinAbs ? Math.min(1, progress.countedEinAbs / progress.targetEinAbs) : 0;
  });

  protected readonly ringOffset = computed(() => this.ringCircumference * (1 - this.ratio()));

  protected readonly nextStepText = computed(() => {
    const progress = this.progress();
    if (!progress) {
      return '';
    }
    switch (progress.nextStep) {
      case NewcomerNextStep.BookEinab:
        return this.i18n.t('progress.next.bookEinAb');
      case NewcomerNextStep.BookFairteilerEinab:
        return this.i18n.t('progress.next.bookFairteilerEinAb');
      case NewcomerNextStep.AttendBooked:
        return this.i18n.t('progress.next.attendBooked');
      case NewcomerNextStep.WaitForVerification:
        return this.i18n.t('progress.next.waitForVerification');
      default:
        return this.i18n.t('progress.next.done');
    }
  });

  private readonly userApi = inject(UserService);

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.userApi.getMyProgress({ bezirkSlug: this.bezirkContext.currentSlug() }).pipe(finalize(() => this.loading.set(false))).subscribe({
      next: (progress) => this.progress.set(progress),
      error: () => this.progress.set(null)
    });
  }

  protected stepStateLabel(state: StepState): string {
    return state === 'done'
      ? this.i18n.t('progress.state.done')
      : state === 'booked' ? this.i18n.t('progress.state.booked') : this.i18n.t('progress.state.open');
  }
}
