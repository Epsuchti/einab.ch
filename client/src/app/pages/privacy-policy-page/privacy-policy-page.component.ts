import { Component, inject } from '@angular/core';
import { CardModule } from 'primeng/card';

import { I18nService } from '../../core/i18n.service';

/** Public Einab.ch privacy policy. The legal text lives in the template (German, English); Swiss German shows German. */
@Component({
  selector: 'app-privacy-policy-page',
  standalone: true,
  imports: [CardModule],
  templateUrl: './privacy-policy-page.component.html',
  styleUrl: './privacy-policy-page.component.scss'
})
export class PrivacyPolicyPageComponent {
  readonly i18n = inject(I18nService);
}
