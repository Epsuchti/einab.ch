package ch.it4user.foodsharing.domain.enumtype;

/** Where a support request was sent from: browser tab, website installed to the home screen, or the store app. */
public enum SupportClientPlatform {
    WEB,
    PWA,
    ANDROID_APP,
    IOS_APP
}
