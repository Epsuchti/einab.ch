import { CommonModule } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { BezirkResponse } from '../../api';
import { resolveApiError } from '../../core/api-error';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { I18nService } from '../../core/i18n.service';
import { ButtonModule } from 'primeng/button';
import { SkeletonComponent, SkeletonRegionDirective } from '../../core/skeleton';

@Component({
  selector: 'app-bezirk-landing-page',
  standalone: true,
  imports: [SkeletonComponent, SkeletonRegionDirective, CommonModule, RouterLink, ButtonModule],
  templateUrl: './bezirk-landing-page.component.html',
  styleUrl: './bezirk-landing-page.component.scss'
})
export class BezirkLandingPageComponent implements OnInit {
  readonly i18n = inject(I18nService);
  protected readonly bezirke = signal<BezirkResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal('');
  protected readonly lastBezirk = signal<BezirkResponse | null>(null);

  private readonly bezirkContext = inject(BezirkContextService);

  ngOnInit(): void {
    this.loadBezirke();
  }

  retry(): void {
    this.loadBezirke();
  }

  private loadBezirke(): void {
    this.loading.set(true);
    this.error.set('');
    this.bezirkContext.selectedBezirk.set(null);
    this.bezirkContext.loadBezirke().subscribe({
      next: (bezirke) => {
        this.bezirke.set(bezirke);
        this.lastBezirk.set(this.bezirkContext.lastBezirk());
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(resolveApiError(error, this.i18n));
        this.loading.set(false);
      }
    });
  }
}
