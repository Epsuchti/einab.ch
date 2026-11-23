package ch.it4user.foodsharing.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Auth auth = new Auth();
    private final Frontend frontend = new Frontend();
    private final Foodsharing foodsharing = new Foodsharing();
    private final Mail mail = new Mail();
    private final Outbound outbound = new Outbound();
    private final Push push = new Push();

    public Auth getAuth() {
        return auth;
    }

    public Frontend getFrontend() {
        return frontend;
    }

    @Valid
    public Foodsharing getFoodsharing() {
        return foodsharing;
    }

    public Mail getMail() {
        return mail;
    }

    public Outbound getOutbound() {
        return outbound;
    }

    public Push getPush() {
        return push;
    }

    public static class Auth {
        private long tokenValidityDays = 365;
        private long loginTokenValidityMinutes = 30;
        private int maxLoginRequestsPerAccount = 3;
        private int maxLoginRequestsPerIp = 10;
        private long loginRequestWindowMinutes = 15;
        private final DemoLogin demoLogin = new DemoLogin();

        public DemoLogin getDemoLogin() {
            return demoLogin;
        }

        public long getTokenValidityDays() {
            return tokenValidityDays;
        }

        public void setTokenValidityDays(long tokenValidityDays) {
            this.tokenValidityDays = tokenValidityDays;
        }

        public long getLoginTokenValidityMinutes() {
            return loginTokenValidityMinutes;
        }

        public void setLoginTokenValidityMinutes(long loginTokenValidityMinutes) {
            this.loginTokenValidityMinutes = loginTokenValidityMinutes;
        }

        public int getMaxLoginRequestsPerAccount() {
            return maxLoginRequestsPerAccount;
        }

        public void setMaxLoginRequestsPerAccount(int maxLoginRequestsPerAccount) {
            this.maxLoginRequestsPerAccount = maxLoginRequestsPerAccount;
        }

        public int getMaxLoginRequestsPerIp() {
            return maxLoginRequestsPerIp;
        }

        public void setMaxLoginRequestsPerIp(int maxLoginRequestsPerIp) {
            this.maxLoginRequestsPerIp = maxLoginRequestsPerIp;
        }

        public long getLoginRequestWindowMinutes() {
            return loginRequestWindowMinutes;
        }

        public void setLoginRequestWindowMinutes(long loginRequestWindowMinutes) {
            this.loginRequestWindowMinutes = loginRequestWindowMinutes;
        }

    }

    /**
     * One-tap demo accounts (see {@link DemoAccounts}) for app testers and store reviewers on the test server, who
     * cannot receive Foodsharing messages. Off by default; {@link DemoLoginService} refuses to
     * start when it is switched on in prod or without the outbound restriction.
     */
    public static class DemoLogin {
        private boolean enabled = false;
        /** Bezirk of the demo accounts; empty takes the first active Bezirk. */
        private String bezirkSlug = "zuerich";
        /** Foodsharing ID that testers type into the normal login field to get a new demo account assigned. */
        private String sharedFoodsharingId = "1308";

        public String getSharedFoodsharingId() {
            return sharedFoodsharingId;
        }

        public void setSharedFoodsharingId(String sharedFoodsharingId) {
            this.sharedFoodsharingId = sharedFoodsharingId;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBezirkSlug() {
            return bezirkSlug;
        }

        public void setBezirkSlug(String bezirkSlug) {
            this.bezirkSlug = bezirkSlug;
        }
    }

    public static class Frontend {
        private String baseUrl = "http://localhost:4200";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class Foodsharing {
        private String baseUrl = "https://foodsharing.network";
        @NotBlank
        private String adminUser = "";
        @NotBlank
        private String adminPassword = "";
        private String tokenEncryptionKey = "";
        private String phoneNumberApiBaseUrl = "https://www.vserverli.de/fs";
        private String phoneNumberApiToken = "";
        private boolean sendMessages = true;
        private final Automation automation = new Automation();

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getAdminUser() { return adminUser; }
        public void setAdminUser(String adminUser) { this.adminUser = adminUser; }
        public String getAdminPassword() { return adminPassword; }
        public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
        public String getTokenEncryptionKey() { return tokenEncryptionKey; }
        public void setTokenEncryptionKey(String tokenEncryptionKey) { this.tokenEncryptionKey = tokenEncryptionKey; }
        public String getPhoneNumberApiBaseUrl() { return phoneNumberApiBaseUrl; }
        public void setPhoneNumberApiBaseUrl(String phoneNumberApiBaseUrl) { this.phoneNumberApiBaseUrl = phoneNumberApiBaseUrl; }
        public String getPhoneNumberApiToken() { return phoneNumberApiToken; }
        public void setPhoneNumberApiToken(String phoneNumberApiToken) { this.phoneNumberApiToken = phoneNumberApiToken; }
        public boolean isSendMessages() { return sendMessages; }
        public void setSendMessages(boolean sendMessages) { this.sendMessages = sendMessages; }
        public Automation getAutomation() { return automation; }
    }

    public static class Automation {
        private boolean enabled = true;
        private boolean dryRun = true;
        private int cleaningBackCheckMonths = 6;
        private int minimumFreeCleaningSlots = 4;
        private String pollInterval = "PT5M";
        private String futurePickupCacheTtl = "PT15M";
        private String storePickupCacheTtl = "PT1M";
        private String storeMembersCacheTtl = "PT1M";
        private int maxRequestApprovalsPerRun = 50;
        private int maxAdvertisementsPerRun = 50;
        private int auditRetentionDays = 7;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isDryRun() { return dryRun; }
        public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
        public int getCleaningBackCheckMonths() { return cleaningBackCheckMonths; }
        public void setCleaningBackCheckMonths(int cleaningBackCheckMonths) { this.cleaningBackCheckMonths = cleaningBackCheckMonths; }
        public int getMinimumFreeCleaningSlots() { return minimumFreeCleaningSlots; }
        public void setMinimumFreeCleaningSlots(int minimumFreeCleaningSlots) { this.minimumFreeCleaningSlots = minimumFreeCleaningSlots; }
        public String getPollInterval() { return pollInterval; }
        public void setPollInterval(String pollInterval) { this.pollInterval = pollInterval; }
        public String getFuturePickupCacheTtl() { return futurePickupCacheTtl; }
        public void setFuturePickupCacheTtl(String futurePickupCacheTtl) { this.futurePickupCacheTtl = futurePickupCacheTtl; }
        public String getStorePickupCacheTtl() { return storePickupCacheTtl; }
        public void setStorePickupCacheTtl(String storePickupCacheTtl) { this.storePickupCacheTtl = storePickupCacheTtl; }
        public String getStoreMembersCacheTtl() { return storeMembersCacheTtl; }
        public void setStoreMembersCacheTtl(String storeMembersCacheTtl) { this.storeMembersCacheTtl = storeMembersCacheTtl; }
        public int getMaxRequestApprovalsPerRun() { return maxRequestApprovalsPerRun; }
        public void setMaxRequestApprovalsPerRun(int maxRequestApprovalsPerRun) { this.maxRequestApprovalsPerRun = maxRequestApprovalsPerRun; }
        public int getMaxAdvertisementsPerRun() { return maxAdvertisementsPerRun; }
        public void setMaxAdvertisementsPerRun(int maxAdvertisementsPerRun) { this.maxAdvertisementsPerRun = maxAdvertisementsPerRun; }
        public int getAuditRetentionDays() { return auditRetentionDays; }
        public void setAuditRetentionDays(int auditRetentionDays) { this.auditRetentionDays = auditRetentionDays; }
    }

    public static class Mail {
        private String from = "noreply@foodsharing.network";

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }
    }

    /** See {@link OutboundMessageGuard}. Restricted unless a profile turns it off, so a new environment fails closed. */
    public static class Outbound {
        private boolean restricted = true;
        private java.util.List<String> allowedFoodsharingIds = new java.util.ArrayList<>();
        private java.util.List<String> allowedTelegramChatIds = new java.util.ArrayList<>();

        public boolean isRestricted() { return restricted; }
        public void setRestricted(boolean restricted) { this.restricted = restricted; }
        public java.util.List<String> getAllowedFoodsharingIds() { return allowedFoodsharingIds; }
        public void setAllowedFoodsharingIds(java.util.List<String> allowedFoodsharingIds) { this.allowedFoodsharingIds = allowedFoodsharingIds; }
        public java.util.List<String> getAllowedTelegramChatIds() { return allowedTelegramChatIds; }
        public void setAllowedTelegramChatIds(java.util.List<String> allowedTelegramChatIds) { this.allowedTelegramChatIds = allowedTelegramChatIds; }
    }

    /**
     * Push notifications for the store app (Firebase Cloud Messaging) and the files that let einab.ch links open in it.
     * Push is off until a Firebase service account is configured.
     */
    public static class Push {
        /** The Firebase service account JSON, plain or base64-encoded (from a secret environment variable; no key files). */
        private String firebaseCredentialsJson = "";
        /** Android package name of the app, for /.well-known/assetlinks.json. */
        private String androidPackageName = "ch.einab.app";
        /** SHA-256 fingerprints of the Android signing certificates (Play app signing key and upload key). */
        private java.util.List<String> androidSha256Fingerprints = new java.util.ArrayList<>();
        /** iOS app IDs ("TEAMID.ch.einab.app"), for /.well-known/apple-app-site-association. */
        private java.util.List<String> iosAppIds = new java.util.ArrayList<>();
        /** Firebase Cloud Messaging API address (only changed for tests against a fake server). */
        private String fcmBaseUrl = "https://fcm.googleapis.com";
        /** Devices the app has not reported for this many days are forgotten. */
        private int deviceRetentionDays = 180;

        public String getFirebaseCredentialsJson() { return firebaseCredentialsJson; }
        public void setFirebaseCredentialsJson(String firebaseCredentialsJson) { this.firebaseCredentialsJson = firebaseCredentialsJson; }
        public String getAndroidPackageName() { return androidPackageName; }
        public void setAndroidPackageName(String androidPackageName) { this.androidPackageName = androidPackageName; }
        public java.util.List<String> getAndroidSha256Fingerprints() { return androidSha256Fingerprints; }
        public void setAndroidSha256Fingerprints(java.util.List<String> androidSha256Fingerprints) { this.androidSha256Fingerprints = androidSha256Fingerprints; }
        public java.util.List<String> getIosAppIds() { return iosAppIds; }
        public void setIosAppIds(java.util.List<String> iosAppIds) { this.iosAppIds = iosAppIds; }
        public String getFcmBaseUrl() { return fcmBaseUrl; }
        public void setFcmBaseUrl(String fcmBaseUrl) { this.fcmBaseUrl = fcmBaseUrl; }
        public int getDeviceRetentionDays() { return deviceRetentionDays; }
        public void setDeviceRetentionDays(int deviceRetentionDays) { this.deviceRetentionDays = deviceRetentionDays; }
    }
}
