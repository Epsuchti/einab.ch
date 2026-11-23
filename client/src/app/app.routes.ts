import { Routes } from '@angular/router';

import { UserPermission } from './api';
import { authGuard } from './core/auth.guard';
import { bezirkGuard } from './core/bezirk.guard';
import { landingGuard } from './core/landing.guard';
import { onboardingGuard } from './core/onboarding.guard';
import { AdminDashboardPageComponent } from './pages/admin-dashboard-page/admin-dashboard-page.component';
import { AdminSurveyEditPageComponent } from './pages/admin-survey-edit-page/admin-survey-edit-page.component';
import { AdminSurveyResultsPageComponent } from './pages/admin-survey-results-page/admin-survey-results-page.component';
import { AdminSurveysPageComponent } from './pages/admin-surveys-page/admin-surveys-page.component';
import { SurveyFillPageComponent } from './pages/survey-fill-page/survey-fill-page.component';
import { AdminFoodsharingAutomationPageComponent } from './pages/admin-foodsharing-automation-page/admin-foodsharing-automation-page.component';
import { BezirkLandingPageComponent } from './pages/bezirk-landing-page/bezirk-landing-page.component';
import { FoodsharingIdHelpPageComponent } from './pages/foodsharing-id-help-page/foodsharing-id-help-page.component';
import { LoginPageComponent } from './pages/login-page/login-page.component';
import { MyBookingsPageComponent } from './pages/my-bookings-page/my-bookings-page.component';
import { NewcomerFunnelPageComponent } from './pages/newcomer-funnel-page/newcomer-funnel-page.component';
import { MyPickupsPageComponent } from './pages/my-pickups-page/my-pickups-page.component';
import { PublicSlotsPageComponent } from './pages/public-slots-page/public-slots-page.component';
import { UnsubscribePageComponent } from './pages/unsubscribe-page/unsubscribe-page.component';
import { TeacherDashboardPageComponent } from './pages/teacher-dashboard-page/teacher-dashboard-page.component';
import { HelpPageComponent } from './pages/help-page/help-page.component';
import { VerifyLoginPageComponent } from './pages/verify-login-page/verify-login-page.component';
import { DeleteAccountPageComponent } from './pages/delete-account-page/delete-account-page.component';
import { PrivacyPolicyPageComponent } from './pages/privacy-policy-page/privacy-policy-page.component';
import { PrivacyConsentPageComponent } from './pages/privacy-consent-page/privacy-consent-page.component';
import { BezirkInfoPageComponent } from './pages/bezirk-info-page/bezirk-info-page.component';
import { ProfilePageComponent } from './pages/profile-page/profile-page.component';

export const routes: Routes = [
  { path: '', component: BezirkLandingPageComponent, canActivate: [landingGuard] },
  {
    path: 'bezirke/:bezirkSlug',
    canActivate: [bezirkGuard],
    canActivateChild: [onboardingGuard],
    children: [
      { path: '', component: PublicSlotsPageComponent },
      { path: 'foodsharing-id', component: FoodsharingIdHelpPageComponent },
      { path: 'login', component: LoginPageComponent },
      { path: 'verify-login', component: VerifyLoginPageComponent },
      { path: 'privacy-consent', component: PrivacyConsentPageComponent, canActivate: [authGuard] },
      { path: 'profile', component: ProfilePageComponent, canActivate: [authGuard] },
      { path: 'unsubscribe', component: UnsubscribePageComponent },
      { path: 'my-bookings', component: MyBookingsPageComponent, canActivate: [authGuard] },
      { path: 'pickups', component: MyPickupsPageComponent, canActivate: [authGuard] },
      { path: 'teacher', component: TeacherDashboardPageComponent, canActivate: [authGuard], data: { permissions: [UserPermission.CanGiveEinAbs] } },
      { path: 'teacher/bookings', redirectTo: 'teacher', pathMatch: 'full' },
      { path: 'teacher/guide', redirectTo: 'help', pathMatch: 'full' },
      { path: 'help', component: HelpPageComponent },
      { path: 'bezirk-info', component: BezirkInfoPageComponent, canActivate: [authGuard] },
      { path: 'admin', component: AdminDashboardPageComponent, canActivate: [authGuard], data: { permissions: [UserPermission.CanManageUsers] } },
      { path: 'teacher/newcomers', component: NewcomerFunnelPageComponent, canActivate: [authGuard], data: { permissions: [UserPermission.CanManageUsers, UserPermission.CanGiveEinAbs] } },
      { path: 'survey/:surveyId', component: SurveyFillPageComponent, canActivate: [authGuard] },
      { path: 'admin/surveys', component: AdminSurveysPageComponent, canActivate: [authGuard], data: { permissions: [UserPermission.CanManageSurveys] } },
      { path: 'admin/surveys/:surveyId/results', component: AdminSurveyResultsPageComponent, canActivate: [authGuard], data: { permissions: [UserPermission.CanManageSurveys] } },
      { path: 'admin/surveys/:surveyId', component: AdminSurveyEditPageComponent, canActivate: [authGuard], data: { permissions: [UserPermission.CanManageSurveys] } },
      { path: 'admin/foodsharing-automation', component: AdminFoodsharingAutomationPageComponent, canActivate: [authGuard], data: { permissions: [UserPermission.CanUseAutomations, UserPermission.CanSeeUserPickupCountGrouping, UserPermission.CanSeeAllAutomationDecisions] } }
    ]
  },
  { path: 'privacy', component: PrivacyPolicyPageComponent },
  { path: 'konto-loeschen', component: DeleteAccountPageComponent },
  { path: 'help', component: HelpPageComponent, canActivate: [onboardingGuard] },
  { path: '**', redirectTo: '' }
];
