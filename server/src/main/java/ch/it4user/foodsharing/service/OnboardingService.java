package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The steps a user goes through after the first login before the app opens up: the privacy consent and, when the home
 * Bezirk switched it on, the Bezirk's info page. Filling in a survey works without them (see the client's auth guard).
 */
@Service
public class OnboardingService {

    public boolean isPrivacyConsentRequired(User user) {
        return !AuthService.PRIVACY_POLICY_VERSION.equals(user.getPrivacyConsentVersion());
    }

    /** The active home Bezirk shows its info page as onboarding step and the user has not read it for this Bezirk. */
    public boolean isBezirkInfoRequired(User user) {
        Bezirk bezirk = user.getBezirk();
        if (bezirk == null || !bezirk.isActive() || !bezirk.isInfoStepEnabled() || !bezirk.hasInfo()) {
            return false;
        }
        // The verification state is refreshed from Foodsharing when the login link is requested, so it is current
        // before the user sees the first page.
        if (bezirk.isInfoStepOnlyUnverified() && user.isVerified()) {
            return false;
        }
        return !bezirk.getId().equals(user.getBezirkInfoSeenBezirkId());
    }

    public boolean isOnboardingRequired(User user) {
        return isPrivacyConsentRequired(user) || isBezirkInfoRequired(user);
    }

    /** Guards actions of the normal app use (e.g. booking an EinAb) against a client that skips the onboarding pages. */
    public void requireCompleted(User user) {
        if (isOnboardingRequired(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.ONBOARDING_REQUIRED);
        }
    }
}
