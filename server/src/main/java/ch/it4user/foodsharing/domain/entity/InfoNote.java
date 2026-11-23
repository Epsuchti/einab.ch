package ch.it4user.foodsharing.domain.entity;

import ch.it4user.foodsharing.domain.enumtype.InfoNoteSeverity;
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
 * A note shown as a banner between {@link #startsAt} and {@link #endsAt}: to everyone when {@link #bezirk} is null
 * (e.g. an update or maintenance), otherwise to everyone who has that Bezirk selected. Users can dismiss it; that is
 * remembered in their browser only.
 */
@Entity
@Table(name = "info_notes")
@Getter
@Setter
@NoArgsConstructor
public class InfoNote extends BaseEntity {

    /** Null for a note on the whole site. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bezirk_id")
    private Bezirk bezirk;

    @Column(length = 200)
    private String title;

    @Column(nullable = false, length = 2000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InfoNoteSeverity severity = InfoNoteSeverity.INFO;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "created_by_name", length = 255)
    private String createdByName;
}
