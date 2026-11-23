import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';

import { NewcomerFunnelResponse, TeacherService } from '../../api';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { I18nService } from '../../core/i18n.service';
import { LoadingIndicatorComponent } from '../../core/loading-indicator.component';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { NewcomerFunnelStepperComponent } from './newcomer-funnel-stepper.component';
import { NewcomerNeedsComponent } from './newcomer-needs.component';

/** Newcomer funnel summary on the EinAb page: counts per step and what newcomers need most, with a link to the full page. */
@Component({
  selector: 'app-newcomer-funnel-widget',
  standalone: true,
  imports: [LoadingIndicatorComponent, RouterLink, ButtonModule, CardModule, NewcomerFunnelStepperComponent, NewcomerNeedsComponent],
  templateUrl: './newcomer-funnel-widget.component.html',
  styleUrl: './newcomer-funnel-widget.component.scss'
})
export class NewcomerFunnelWidgetComponent implements OnInit {
  readonly i18n = inject(I18nService);
  protected readonly bezirkContext = inject(BezirkContextService);
  protected readonly funnel = signal<NewcomerFunnelResponse | null>(null);
  protected readonly loading = signal(true);

  private readonly teacherApi = inject(TeacherService);

  ngOnInit(): void {
    this.teacherApi.getTeacherNewcomerFunnel({ bezirkSlug: this.bezirkContext.currentSlug(), days: 365 }).pipe(finalize(() => this.loading.set(false))).subscribe({
      next: (funnel) => this.funnel.set(funnel),
      error: () => this.funnel.set(null)
    });
  }
}
