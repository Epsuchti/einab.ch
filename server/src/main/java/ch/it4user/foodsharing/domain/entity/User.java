package ch.it4user.foodsharing.domain.entity;

import ch.it4user.foodsharing.domain.enumtype.LanguageCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bezirk_id")
    private Bezirk bezirk;

    @Column(length = 255)
    private String email;

    @Column(nullable = false, unique = true, length = 100)
    private String foodsharingId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 50)
    private String phoneNumber;

    @Column(length = 2000)
    private String icalLink;

    @Column(name = "ical_feed_token", unique = true, length = 64)
    private String icalFeedToken;

    @Column(nullable = false)
    private boolean active = false;

    @Column(name = "privacy_consent_at")
    private Instant privacyConsentAt;

    @Column(name = "privacy_consent_version", length = 32)
    private String privacyConsentVersion;

    /** The Bezirk whose info page (onboarding step after the consent) the user has read. */
    @Column(name = "bezirk_info_seen_bezirk_id")
    private java.util.UUID bezirkInfoSeenBezirkId;

    @Column(name = "bezirk_info_seen_at")
    private Instant bezirkInfoSeenAt;

    /** Verified on Foodsharing (synced from the Bezirk admin account / fallback API) or set manually by an admin. */
    @Column(name = "verified", nullable = false)
    private boolean verified = false;

    /** Most recent Foodsharing verification date as reported by Foodsharing. */
    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    /** Last time the user logged in to this app (not the Foodsharing platform). */
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** Set manually by an admin once the user was contacted about the verification; null if no message was sent. */
    @Column(name = "verification_message_sent_at")
    private Instant verificationMessageSentAt;

    @Column(nullable = false)
    private boolean canGiveEinAbs = false;

    @Column(nullable = false)
    private boolean canManageUsers = false;

    @Column(nullable = false)
    private boolean canUseAutomations = false;

    @Column(nullable = false)
    private boolean canSeeUserPickupCountGrouping = false;

    @Column(nullable = false)
    private boolean canSeeAllAutomationDecisions = false;

    @Column(nullable = false)
    private boolean canManageSurveys = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private LanguageCode preferredLanguage = LanguageCode.DE;
}
