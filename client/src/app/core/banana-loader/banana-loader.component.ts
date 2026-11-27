import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../i18n.service';

/**
 * Shown in the middle of the screen when skeletons have been visible for a while (see SkeletonActivityService):
 * a banana appears, is peeled, eaten bite by bite, the peel is thrown off screen and the next banana appears.
 * Decorative only; it never blocks clicks and the skeleton regions keep announcing "Loading" to screen readers.
 */
@Component({
  selector: 'app-banana-loader',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './banana-loader.component.html',
  styleUrl: './banana-loader.component.scss'
})
export class BananaLoaderComponent {
  protected readonly i18n = inject(I18nService);
  protected readonly bites = [
    { x: 60, y: 13 },
    { x: 50, y: 25 },
    { x: 70, y: 32 },
    { x: 54, y: 43 },
    { x: 68, y: 53 },
    { x: 52, y: 63 },
    { x: 66, y: 73 },
    { x: 58, y: 84 }
  ];
}
