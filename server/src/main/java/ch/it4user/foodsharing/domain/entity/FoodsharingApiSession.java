package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "foodsharing_api_sessions")
@Getter
@Setter
@NoArgsConstructor
public class FoodsharingApiSession extends BaseEntity {

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String sessionCookieCiphertext;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String csrfTokenCiphertext;

    @Column
    private Instant authenticatedAt;

    /** Foodsharing user ID of the fallback account, captured at login. */
    @Column(length = 64)
    private String foodsharingUserId;
}
