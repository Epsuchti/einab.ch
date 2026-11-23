import { Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { NewcomerFunnelResponse, NewcomerGapSeverity, NewcomerNeed, NewcomerNeedKind } from '../../api';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { I18nService } from '../../core/i18n.service';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';

type NeedsState = 'withoutPlace' | 'short' | 'covered' | 'nobody';

/**
 * What newcomers need right now, for EinAb givers: one headline that counts each newcomer once,
 * free versus needed places, and the EinAbs that would help most, ranked, each with a button to create one.
 */
@Component({
  selector: 'app-newcomer-needs',
  standalone: true,
  imports: [RouterLink, ButtonModule, TagModule],
  templateUrl: './newcomer-needs.component.html',
  styleUrl: './newcomer-needs.component.scss'
})
export class NewcomerNeedsComponent {
  readonly funnel = input.required<NewcomerFunnelResponse>();
  /** Most actions to show; null shows all that are short. */
  readonly maxActions = input<number | null>(3);
  /** Whether the viewer may give EinAbs, which shows the create buttons. */
  readonly canCreate = input(true);

  readonly i18n = inject(I18nService);
  protected readonly bezirkContext = inject(BezirkContextService);
  protected readonly NewcomerNeedKind = NewcomerNeedKind;
  protected readonly NewcomerGapSeverity = NewcomerGapSeverity;

  protected readonly state = computed<NeedsState>(() => {
    const f = this.funnel();
    if (f.newcomersNeedingEinAbs === 0) {
      return 'nobody';
    }
    if (f.newcomersWithoutPlace > 0) {
      return 'withoutPlace';
    }
    return this.actions().length ? 'short' : 'covered';
  });

  protected readonly actions = computed(() => {
    const max = this.maxActions();
    const short = this.funnel().needs.filter((need) => need.severity !== NewcomerGapSeverity.Ok);
    return max === null ? short : short.slice(0, max);
  });

  protected readonly missingPlaces = computed(() => Math.max(0, this.funnel().einAbsStillNeeded - this.funnel().openSlots));

  /** Share of the needed places that are free, for the bar. */
  protected readonly coverage = computed(() => {
    const f = this.funnel();
    return f.einAbsStillNeeded <= 0 ? 100 : Math.min(100, Math.round((f.openSlots / f.einAbsStillNeeded) * 100));
  });

  /** "9 Neulinge", highlighted, then the rest of the headline. */
  protected headlineWho(): string {
    const count = this.funnel().newcomersWithoutPlace;
    return this.plural('needs.newcomers', count).replace('{count}', String(count));
  }

  protected headlineRest(): string {
    return this.plural('needs.headlineWithoutPlace', this.funnel().newcomersWithoutPlace);
  }

  protected coveredText(): string {
    const f = this.funnel();
    return this.i18n.t('needs.coveredDetail')
      .replace('{free}', this.plural('needs.places', f.openSlots).replace('{count}', String(f.openSlots)))
      .replace('{needed}', String(f.einAbsStillNeeded))
      .replace('{days}', String(f.gapHorizonDays));
  }

  protected title(need: NewcomerNeed): string {
    return need.kind === NewcomerNeedKind.Fairteiler
      ? this.i18n.t('needs.actionFairteiler')
      : this.i18n.t('needs.actionCategory').replace('{category}', this.i18n.categoryLabel(need.category));
  }

  protected helpsText(need: NewcomerNeed): string {
    const key = need.kind === NewcomerNeedKind.Fairteiler ? 'needs.fairteilerNewcomers' : 'needs.helps';
    return this.plural(key, need.newcomers).replace('{count}', String(need.newcomers));
  }

  protected withoutPlaceText(need: NewcomerNeed): string {
    return this.plural('needs.ofThemWithoutPlace', need.withoutPlace).replace('{count}', String(need.withoutPlace));
  }

  protected factsText(need: NewcomerNeed): string {
    const parts = [this.plural('needs.placesFree', need.openSlots).replace('{count}', String(need.openSlots))];
    if (need.waitingLong > 0) {
      parts.push(this.plural('needs.waitingLong', need.waitingLong)
        .replace('{count}', String(need.waitingLong))
        .replace('{days}', String(this.funnel().waitingLongDays)));
    }
    return parts.join(' · ');
  }

  protected givesAlready(need: NewcomerNeed): boolean {
    return need.kind === NewcomerNeedKind.Category && !!need.category && this.funnel().viewerCategories.includes(need.category);
  }

  protected createLabel(need: NewcomerNeed): string {
    return need.kind === NewcomerNeedKind.Fairteiler
      ? this.i18n.t('needs.createFairteiler')
      : this.i18n.t('needs.createCategory').replace('{category}', this.i18n.categoryLabel(need.category));
  }

  protected createQuery(need: NewcomerNeed): Record<string, string> {
    return need.kind === NewcomerNeedKind.Fairteiler ? { newEinAb: '', fairteiler: '1' } : { newEinAb: need.category ?? '' };
  }

  protected track(need: NewcomerNeed): string {
    return need.kind + (need.category ?? '');
  }

  private plural(key: string, count: number): string {
    return this.i18n.t(count === 1 ? key + 'One' : key + 'Many');
  }
}
