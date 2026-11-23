import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, ErrorHandler, isDevMode, provideAppInitializer, provideBrowserGlobalErrorListeners, inject } from '@angular/core';
import { provideAnimations } from '@angular/platform-browser/animations';
import { NavigationError, RouteReuseStrategy, provideRouter, withNavigationErrorHandler } from '@angular/router';
import Aura from '@primeuix/themes/aura';
import { definePreset } from '@primeuix/themes';
import { ConfirmationService, MessageService } from 'primeng/api';
import { providePrimeNG } from 'primeng/config';
import { provideServiceWorker } from '@angular/service-worker';

import { provideApi } from './api/provide-api';
import { AppUpdateService } from './core/app-update.service';
import { AuthFacadeService } from './core/auth-facade.service';
import { authInterceptor } from './core/auth.interceptor';
import { BezirkRouteReuseStrategy } from './core/bezirk-route-reuse.strategy';
import { connectionInterceptor } from './core/connection.interceptor';
import { GlobalErrorHandler } from './core/global-error-handler';
import { PageErrorService } from './core/page-error.service';
import { I18nService } from './core/i18n.service';
import { loadingInterceptor } from './core/loading.interceptor';
import { isNativeApp } from './core/native-app';
import { PushNotificationService } from './core/push-notification.service';
import { routes } from './app.routes';
import { environment } from '../environments/environment';

/**
 * One shared PrimeNG palette keeps buttons, form controls, overlays and data
 * components visually aligned with the application shell.  Page styles build
 * on these tokens instead of defining another colour system per view.
 */
const FoodsharingPreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '#effaf2',
      100: '#d8f3df',
      200: '#b4e7c2',
      300: '#82d59b',
      400: '#4cba70',
      500: '#25834a',
      600: '#1e713f',
      700: '#195d35',
      800: '#174b2d',
      900: '#123d25',
      950: '#082116'
    }
  }
});

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    { provide: ErrorHandler, useClass: GlobalErrorHandler },
    provideAnimations(),
    provideHttpClient(withInterceptors([authInterceptor, connectionInterceptor, loadingInterceptor])),
    providePrimeNG({
      translation: {
        firstDayOfWeek: 1,
        dateFormat: 'dd.mm.yy'
      },
      theme: {
        preset: FoodsharingPreset,
        options: {
          darkModeSelector: 'none'
        }
      }
    }),
    provideApi(environment.apiBaseUrl),
    // A page that cannot be opened (e.g. its guard got a server error) shows a full-page error instead of staying blank.
    provideRouter(routes, withNavigationErrorHandler((navigationError: NavigationError) => inject(PageErrorService).show(navigationError.url, navigationError.error))),
    { provide: RouteReuseStrategy, useClass: BezirkRouteReuseStrategy },
    MessageService,
    ConfirmationService,
    // Caches the app and picks up new releases by itself (see AppUpdateService). Not in the store app: it loads
    // einab.ch fresh on every start, and a cached page there would miss the native bridge.
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode() && !isNativeApp(),
      registrationStrategy: 'registerWhenStable:30000'
    }),
    provideAppInitializer(() => {
      const i18n = inject(I18nService);
      inject(AuthFacadeService).startSessionRefresh();
      inject(AppUpdateService).start();
      inject(PushNotificationService).start();
      return i18n.initialize();
    })
  ]
};
