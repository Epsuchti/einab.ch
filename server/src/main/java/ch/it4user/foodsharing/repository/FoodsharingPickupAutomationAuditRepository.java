package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.FoodsharingAdminConnection;
import ch.it4user.foodsharing.domain.entity.FoodsharingPickupAutomationAudit;
import ch.it4user.foodsharing.domain.enumtype.FoodsharingPickupAutomationDecision;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FoodsharingPickupAutomationAuditRepository extends JpaRepository<FoodsharingPickupAutomationAudit, UUID> {
    List<FoodsharingPickupAutomationAudit> findTop100ByBezirkOrderByCreatedAtDesc(Bezirk bezirk);
    List<FoodsharingPickupAutomationAudit> findTop100ByBezirkAndDecisionNotInOrderByCreatedAtDesc(Bezirk bezirk, Collection<FoodsharingPickupAutomationDecision> excludedDecisions);
    List<FoodsharingPickupAutomationAudit> findTop100ByBezirkAndAdminConnectionAndDecisionNotInOrderByCreatedAtDesc(Bezirk bezirk, FoodsharingAdminConnection adminConnection, Collection<FoodsharingPickupAutomationDecision> excludedDecisions);
    List<FoodsharingPickupAutomationAudit> findTop100ByBezirkAndAdminConnectionOrderByCreatedAtDesc(Bezirk bezirk, FoodsharingAdminConnection adminConnection);
    List<FoodsharingPickupAutomationAudit> findAllByAdminConnectionAndFoodsharingUserIdAndDecisionInAndPickupDateBetween(FoodsharingAdminConnection adminConnection, String foodsharingUserId, Collection<FoodsharingPickupAutomationDecision> decisions, java.time.Instant from, java.time.Instant to);
    void deleteAllByAdminConnection(FoodsharingAdminConnection adminConnection);
    void deleteAllByCreatedAtBefore(java.time.Instant createdAt);
    List<FoodsharingPickupAutomationAudit> findAllByCreatedAtBefore(java.time.Instant createdAt);
    List<FoodsharingPickupAutomationAudit> findAllByAdminConnection(FoodsharingAdminConnection adminConnection);
    List<FoodsharingPickupAutomationAudit> findAllByBezirk(Bezirk bezirk);
}
