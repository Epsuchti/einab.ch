import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { InputTextModule } from 'primeng/inputtext';

import { AccountService } from '../../api';
import { resolveApiError } from '../../core/api-error';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { I18nService } from '../../core/i18n.service';
import { SessionService } from '../../core/session.service';

/**
 * Public page to delete an account without signing in (Google Play asks for a deletion link outside the app).
 * Step 1 sends a confirmation link as Foodsharing message; the link opens this page again with ?token=, step 2.
 */
@Component({
  selector: 'app-delete-account-page',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, ButtonModule, CardModule, InputTextModule],
  templateUrl: './delete-account-page.component.html'
})
export class DeleteAccountPageComponent implements OnInit {
  readonly i18n = inject(I18nService);
  protected readonly sessionService = inject(SessionService);
  protected readonly bezirkContext = inject(BezirkContextService);
  private readonly accountApi = inject(AccountService);
  private readonly route = inject(ActivatedRoute);

  protected readonly token = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly sent = signal(false);
  protected readonly done = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    foodsharingId: ['', [Validators.required, Validators.maxLength(100)]]
  });

  ngOnInit(): void {
    this.token.set(this.route.snapshot.queryParamMap.get('token'));
  }

  requestLink(): void {
    const foodsharingId = this.form.getRawValue().foodsharingId.trim();
    if (!foodsharingId || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.accountApi.requestAccountDeletion({ accountDeletionRequest: { foodsharingId } })
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: () => this.sent.set(true),
        error: (error) => this.error.set(resolveApiError(error, this.i18n))
      });
  }

  confirm(): void {
    const token = this.token();
    if (!token || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.accountApi.confirmAccountDeletion({ accountDeletionConfirmRequest: { token } })
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        // A session of the deleted account on this device ends with its next request (401).
        next: () => this.done.set(true),
        error: (error) => this.error.set(resolveApiError(error, this.i18n))
      });
  }
}
