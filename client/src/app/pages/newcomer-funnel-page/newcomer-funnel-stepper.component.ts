import { NgTemplateOutlet } from '@angular/common';
import { Component, inject, input, output } from '@angular/core';

import { NewcomerFunnelStep, NewcomerStage } from '../../api';
import { I18nService } from '../../core/i18n.service';

/** Stepper of the newcomer funnel: one circle per step with the number of people currently at it. */
@Component({
  selector: 'app-newcomer-funnel-stepper',
  standalone: true,
  imports: [NgTemplateOutlet],
  templateUrl: './newcomer-funnel-stepper.component.html',
  styleUrl: './newcomer-funnel-stepper.component.scss'
})
export class NewcomerFunnelStepperComponent {
  readonly i18n = inject(I18nService);
  readonly steps = input.required<NewcomerFunnelStep[]>();
  /** Index of the highlighted step, or null for none. */
  readonly selectedIndex = input<number | null>(null);
  readonly selectable = input(false);
  /** Smaller variant for the widget on the EinAb page. */
  readonly compact = input(false);
  readonly stepSelected = output<number>();

  select(index: number): void {
    if (this.selectable()) {
      this.stepSelected.emit(index);
    }
  }

  stalledText(step: NewcomerFunnelStep): string {
    const key = step.stalledCount === 1 ? 'funnel.step.stalledOne' : 'funnel.step.stalledMany';
    return this.i18n.t(key).replace('{count}', '' + step.stalledCount);
  }

  stalledTooltip(step: NewcomerFunnelStep): string {
    const key = step.stalledCount === 1 ? 'funnel.step.stalledTooltipOne' : 'funnel.step.stalledTooltipMany';
    return this.i18n.t(key).replace('{count}', '' + step.stalledCount);
  }

  stepLabel(step: NewcomerFunnelStep): string {
    return newcomerStepLabel(this.i18n, step);
  }

  protected isVerified(step: NewcomerFunnelStep): boolean {
    return step.stage === NewcomerStage.Verified;
  }
}

export function newcomerStepLabel(i18n: I18nService, step: NewcomerFunnelStep): string {
  switch (step.stage) {
    case NewcomerStage.Registered:
      return i18n.t('funnel.stage.registered');
    case NewcomerStage.FirstBooked:
      return i18n.t('funnel.stage.firstBooked');
    case NewcomerStage.InProgress:
      return i18n.t(step.einAbCount === 1 ? 'funnel.stage.inProgressOne' : 'funnel.stage.inProgress').replace('{count}', String(step.einAbCount ?? 0));
    case NewcomerStage.FairteilerMissing:
      return i18n.t('funnel.stage.fairteilerMissing');
    case NewcomerStage.Complete:
      return i18n.t('funnel.stage.complete');
    default:
      return i18n.t('funnel.stage.verified');
  }
}
