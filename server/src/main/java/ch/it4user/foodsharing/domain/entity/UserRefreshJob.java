package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One queued refresh of a user's data from Foodsharing, processed slowly by {@code UserRefreshQueueService}. */
@Entity
@Table(name = "user_refresh_jobs")
@Getter
@Setter
@NoArgsConstructor
public class UserRefreshJob extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bezirk_id", nullable = false)
    private Bezirk bezirk;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(nullable = false, length = 16)
    private String status = PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "error_code", length = 64)
    private String errorCode;
}
