package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "foodsharing_bezirk_message_connections", uniqueConstraints = @UniqueConstraint(
        name = "uk_foodsharing_bezirk_message_connections_bezirk",
        columnNames = "bezirk_id"))
@Getter
@Setter
@NoArgsConstructor
public class FoodsharingBezirkMessageConnection extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bezirk_id", nullable = false, unique = true)
    private Bezirk bezirk;

    @Column(nullable = false, length = 255)
    private String foodsharingEmail;

    @Column(length = 32600)
    private String foodsharingPasswordCiphertext;

    @Column(length = 32600)
    private String sessionCookieCiphertext;

    @Column(length = 32600)
    private String csrfTokenCiphertext;

    @Column
    private Instant authenticatedAt;

    /** Foodsharing user ID of this account, captured at login. */
    @Column(length = 64)
    private String foodsharingUserId;
}
