import { CommonModule } from '@angular/common';
import { Component, HostListener, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IsActiveMatchOptions, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter, timer } from 'rxjs';

import { InfoNoteSeverity, PublicService, UserPermission, UserService } from '../api';
import { resolveApiError } from '../core/api-error';
import { BezirkContextService } from '../core/bezirk-context.service';
import { CLIENT_RELEASE } from '../core/client-info';
import { NavigationHistoryService } from '../core/navigation-history.service';
import { SupportBadgeService } from '../core/support-badge.service';
import { ConnectionStatusService } from '../core/connection-status.service';
import { PageErrorComponent } from '../core/page-error.component';
import { PageErrorService } from '../core/page-error.service';
import { isValidPhoneNumber } from '../core/phone-number';
import { isSurveyPageUrl } from '../core/return-url';
import { BananaLoaderComponent } from '../core/banana-loader/banana-loader.component';
import { HttpActivityService } from '../core/http-activity.service';
import { I18nService } from '../core/i18n.service';
import { InfoNotesService } from '../core/info-notes.service';
import { PushNotificationService } from '../core/push-notification.service';
import { SessionService } from '../core/session.service';
import { SkeletonActivityService } from '../core/skeleton';
import { ButtonModule } from 'primeng/button';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { ToastModule } from 'primeng/toast';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageService } from 'primeng/api';

const PHONE_POSTPONED_KEY = 'foodsharing.phonePromptPostponed';

function readSessionFlag(key: string): boolean {
  try {
    return typeof sessionStorage !== 'undefined' && sessionStorage.getItem(key) === '1';
  } catch {
    return false;
  }
}

/** Pages that stay open while the onboarding (privacy consent, Bezirk info step) is not done; see onboardingGuard. */
const OPEN_DURING_ONBOARDING = /^\/(?:bezirke\/[^/]+\/(?:privacy-consent|login|verify-login|unsubscribe|survey\/[^/?#]+)|privacy|konto-loeschen)(?:[?#/]|$)/;

const LOGIN_PAGE = /^\/bezirke\/[^/]+\/(?:login|verify-login|privacy-consent)(?:[?#]|$)/;

/** The survey page itself, or the sign-in / consent pages on the way to it (returnUrl points to a survey). */
function isSurveyUrl(url: string): boolean {
  if (isSurveyPageUrl(url)) {
    return true;
  }
  if (!LOGIN_PAGE.test(url)) {
    return false;
  }
  const returnUrl = new URLSearchParams(url.split('?')[1]?.split('#')[0] ?? '').get('returnUrl');
  return isSurveyPageUrl(returnUrl);
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterOutlet, RouterLink, RouterLinkActive, ButtonModule, ToastModule, ConfirmDialogModule, DialogModule, InputTextModule, PageErrorComponent, BananaLoaderComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class App {
  readonly i18n = inject(I18nService);
  readonly httpActivity = inject(HttpActivityService);
  /** Skeletons on screen; after a long wait the banana loader keeps the user company. */
  readonly skeletons = inject(SkeletonActivityService);
  /** Offline / server-unreachable banner below the header. */
  readonly connection = inject(ConnectionStatusService);
  /** Set when the page the user opened could not be loaded. */
  readonly pageError = inject(PageErrorService);
  readonly sessionService = inject(SessionService);
  private readonly pushNotifications = inject(PushNotificationService);
  readonly bezirkContext = inject(BezirkContextService);
  readonly UserPermission = UserPermission;
  /** Admin notes (updates, maintenance, Bezirk news) shown below the header until dismissed. */
  readonly infoNotes = inject(InfoNotesService);
  readonly InfoNoteSeverity = InfoNoteSeverity;
  /** Release number shown in the footer (also sent with support requests). */
  readonly release = CLIENT_RELEASE;
  /** Started here so the help page knows which page the user came from. */
  protected readonly navigationHistory = inject(NavigationHistoryService);
  /** Open support requests, shown as a badge on "Admin". */
  readonly supportBadge = inject(SupportBadgeService);
  readonly mobileMenuOpen = signal(false);
  readonly userMenuOpen = signal(false);
  /** Name shown on the user menu button; empty (icon only) when signed out. */
  readonly userMenuLabel = computed(() => (this.sessionService.isAuthenticated() ? this.sessionService.session()?.displayName?.trim() ?? '' : ''));
  /** False while foodsharing.at is down: logging in and approving sign-ups is not possible then. */
  readonly foodsharingOnline = signal(true);
  readonly phoneNumber = signal('');
  readonly isValidPhoneNumber = isValidPhoneNumber;
  readonly phoneNumberSaving = signal(false);
  /** Survey fill-out mode: no app menu or footer, and no phone number prompt in the way of the survey. */
  readonly surveyMode = signal(false);
  /** Onboarding still open: the menus show only language and logout, so nothing leads away from the consent page. */
  readonly onboarding = computed(() => this.sessionService.onboardingRequired() && !this.surveyMode());
  /** The current Bezirk has an info page: offered in the menu (and on the help page). */
  readonly bezirkInfoAvailable = computed(() => this.sessionService.isAuthenticated() && this.bezirkContext.selectedBezirk()?.infoAvailable === true);
  /** While the privacy consent is pending, the consent page asks for the number itself. */
  readonly phoneNumberPromptVisible = computed(() =>
    !this.surveyMode() && !this.onProfilePage() && !this.phoneNumberPostponed() && this.sessionService.isAuthenticated() && this.sessionService.session()?.phoneNumberRequired === true
      && this.sessionService.session()?.privacyConsentRequired !== true
  );

  private readonly router = inject(Router);
  private readonly userApi = inject(UserService);
  private readonly publicApi = inject(PublicService);
  private readonly messageService = inject(MessageService);

  /** The Abholungen menu entry is only shown while the user is on the pickups page itself. */
  readonly onPickupsPage = signal(false);
  /** The profile page has its own phone number field, so the prompt stays away there. */
  readonly onProfilePage = signal(false);
  /** "Später" on the phone prompt hides it for the rest of this browser session. */
  readonly phoneNumberPostponed = signal(readSessionFlag(PHONE_POSTPONED_KEY));
  /** Phone width: toasts move to the bottom and stay shorter. */
  readonly isMobile = signal(typeof window !== 'undefined' && window.matchMedia?.('(max-width: 640px)').matches === true);
  /** Bottom tab bar on phones: up to three main destinations of the role, plus the menu. */
  readonly bottomNav = computed<{ label: string; icon: string; route: string[]; exact: boolean }[]>(() => {
    if (!this.sessionService.isAuthenticated() || !this.bezirkContext.selectedBezirk() || this.surveyMode() || this.onboarding()) {
      return [];
    }
    const has = (permission: UserPermission) => this.sessionService.hasPermission(permission);
    // Profile, language and logout live in the Menü tab, so the bar only holds the main pages.
    const items: { label: string; icon: string; route: string[]; exact: boolean }[] = [
      { label: 'nav.slotsShort', icon: 'pi pi-search', route: this.bezirkContext.route(), exact: true }
    ];
    if (has(UserPermission.CanGiveEinAbs)) {
      items.push({ label: 'nav.teacherShort', icon: 'pi pi-calendar', route: this.bezirkContext.route('teacher'), exact: true });
    } else {
      items.push({ label: 'nav.bookingsShort', icon: 'pi pi-bookmark', route: this.bezirkContext.route('my-bookings'), exact: false });
    }
    if (has(UserPermission.CanManageUsers)) {
      items.push({ label: 'nav.admin', icon: 'pi pi-users', route: this.bezirkContext.route('admin'), exact: false });
    }
    return items;
  });

  /** Tab bar matching: ignore ?tab= and #fragments, so e.g. the admin tab stays lit on every admin tab. */
  readonly tabExact: IsActiveMatchOptions = { paths: 'exact', queryParams: 'ignored', fragment: 'ignored', matrixParams: 'ignored' };
  readonly tabSubset: IsActiveMatchOptions = { paths: 'subset', queryParams: 'ignored', fragment: 'ignored', matrixParams: 'ignored' };
  /** Pages reached from the Menü tab (profile, help, pickups, ...): the Menü tab is lit there. */
  readonly onMenuPage = signal(false);

  /** Bezirke offered in the menu switcher; hidden while there is only one to choose. */
  readonly menuBezirke = computed(() => (this.bezirkContext.bezirke().length > 1 ? this.bezirkContext.bezirke() : []));

  constructor() {
    this.bezirkContext.loadBezirke().subscribe({ error: () => { /* the menu just shows no Bezirk switcher */ } });
    timer(0, 60_000)
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.publicApi.getFoodsharingAvailability().subscribe({
        next: (status) => this.foodsharingOnline.set(status.online),
        error: () => { /* keep the last known state */ }
      }));
    this.router.events
      .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd), takeUntilDestroyed())
      .subscribe((event) => {
        this.onPickupsPage.set(/^\/bezirke\/[^/]+\/pickups(?:[?#]|$)/.test(event.urlAfterRedirects));
        this.onProfilePage.set(/^\/bezirke\/[^/]+\/profile(?:[?#]|$)/.test(event.urlAfterRedirects));
        this.onMenuPage.set(/^\/(?:bezirke\/[^/]+\/)?(?:profile|help|bezirk-info|pickups|privacy|konto-loeschen)(?:[?#/]|$)/.test(event.urlAfterRedirects));
        this.closeMobileMenu();
        this.surveyMode.set(isSurveyUrl(event.urlAfterRedirects));
        this.closeUserMenu();
      });
    // The session refresh can report an open onboarding after the page was already shown (e.g. a new privacy
    // policy version, or an admin switched on the Bezirk info step): move to the onboarding page then.
    effect(() => {
      if (!this.sessionService.onboardingRequired()) {
        return;
      }
      untracked(() => {
        const url = this.router.url;
        const slug = this.sessionService.homeBezirkSlug() ?? this.bezirkContext.selectedBezirk()?.slug;
        if (slug && this.router.navigated && !OPEN_DURING_ONBOARDING.test(url)) {
          void this.router.navigate(['/bezirke', slug, 'privacy-consent'], { queryParams: url.startsWith('/bezirke/') ? { returnUrl: url } : {} });
        }
      });
    });
  }

  @HostListener('window:resize')
  updateViewport(): void {
    this.isMobile.set(window.matchMedia?.('(max-width: 640px)').matches === true);
  }

  postponePhoneNumber(): void {
    this.phoneNumberPostponed.set(true);
    try {
      sessionStorage.setItem(PHONE_POSTPONED_KEY, '1');
    } catch {
      /* private mode: the prompt just comes back after a reload */
    }
  }

  goToBezirk(slug: string): void {
    if (!slug || this.isCurrentBezirk(slug)) {
      return;
    }
    this.closeMobileMenu();
    void this.router.navigate(['/bezirke', slug]);
  }

  scrollToTop(): void {
    if (this.mobileMenuOpen()) {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }
  }

  reloadPage(): void {
    window.location.reload();
  }

  setLanguage(language: 'de' | 'en' | 'gws'): void {
    void this.i18n.setLanguage(language);
  }

  toggleMobileMenu(): void {
    this.mobileMenuOpen.update((current) => !current);
  }

  closeMobileMenu(): void {
    this.mobileMenuOpen.set(false);
  }

  toggleUserMenu(event: Event): void {
    event.stopPropagation();
    this.userMenuOpen.update((current) => !current);
  }

  @HostListener('document:click')
  @HostListener('document:keydown.escape')
  closeUserMenu(): void {
    this.userMenuOpen.set(false);
  }

  async logout(): Promise<void> {
    this.closeUserMenu();
    this.closeMobileMenu();
    await this.pushNotifications.unregisterBeforeLogout();
    this.sessionService.clearSession();
  }

  homeLink(): string[] {
    const slug = this.sessionService.homeBezirkSlug() ?? this.bezirkContext.selectedBezirk()?.slug;
    return slug ? ['/bezirke', slug] : ['/'];
  }

  isCurrentBezirk(slug: string): boolean {
    return this.bezirkContext.selectedBezirk()?.slug === slug;
  }

  switchBezirk(): void {
    this.closeMobileMenu();
    this.closeUserMenu();
    this.bezirkContext.selectedBezirk.set(null);
    void this.router.navigate(['/']);
  }

  savePhoneNumber(): void {
    const phoneNumber = this.phoneNumber().trim();
    if (!isValidPhoneNumber(phoneNumber) || phoneNumber.length > 50 || this.phoneNumberSaving()) {
      return;
    }
    this.phoneNumberSaving.set(true);
    this.userApi.updateMyProfile({ updateMyProfileRequest: { phoneNumber } }).subscribe({
      next: () => {
        this.phoneNumberSaving.set(false);
        this.sessionService.markPhoneNumberProvided();
        this.messageService.add({ severity: 'success', summary: this.i18n.t('profile.saveSuccess') });
      },
      error: (error) => {
        this.phoneNumberSaving.set(false);
        this.messageService.add({ severity: 'error', summary: this.i18n.t('common.error'), detail: resolveApiError(error, this.i18n) });
      }
    });
  }
}
