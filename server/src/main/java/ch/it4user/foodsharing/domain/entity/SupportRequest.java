package ch.it4user.foodsharing.domain.entity;

import ch.it4user.foodsharing.domain.enumtype.SupportClientPlatform;
import ch.it4user.foodsharing.domain.enumtype.SupportRequestCategory;
import ch.it4user.foodsharing.domain.enumtype.SupportRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A question, problem or idea sent from the help page, with or without sign-in. Admins see them on the support page.
 * The client and server release are stored with it, so a problem can be matched to the release it happened in.
 */
@Entity
@Table(name = "support_requests")
@Getter
@Setter
@NoArgsConstructor
public class SupportRequest extends BaseEntity {

    /** Signed-in sender; null when the request was sent without sign-in (e.g. a login problem). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** Foodsharing ID to answer to: the signed-in user's, or the one typed in without sign-in. */
    @Column(name = "contact_foodsharing_id", nullable = false, length = 64)
    private String contactFoodsharingId;

    @Column(name = "contact_name", length = 255)
    private String contactName;

    /** Bezirk the user was in when sending; null outside a Bezirk. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bezirk_id")
    private Bezirk bezirk;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupportRequestCategory category;

    @Column(nullable = false, length = 4000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupportRequestStatus status = SupportRequestStatus.OPEN;

    /** Release the page was built with (what the footer showed). */
    @Column(name = "client_version", length = 50)
    private String clientVersion;

    /** Release the server was running when the request came in. */
    @Column(name = "server_version", length = 50)
    private String serverVersion;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SupportClientPlatform platform;

    @Column(name = "page_url", length = 500)
    private String pageUrl;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "done_by_name", length = 255)
    private String doneByName;
}
