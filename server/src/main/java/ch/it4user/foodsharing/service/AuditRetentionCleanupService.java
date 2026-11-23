package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.repository.FoodsharingOpenSlotAdvertisementAuditRepository;
import ch.it4user.foodsharing.repository.FoodsharingPickupAutomationAuditRepository;
import ch.it4user.foodsharing.repository.FoodsharingRequestAutomationAuditRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditRetentionCleanupService {
    private static final Logger log = LoggerFactory.getLogger(AuditRetentionCleanupService.class);

    private final AppProperties appProperties;
    private final FoodsharingPickupAutomationAuditRepository pickupAutomationAuditRepository;
    private final FoodsharingRequestAutomationAuditRepository requestAutomationAuditRepository;
    private final FoodsharingOpenSlotAdvertisementAuditRepository advertisementAuditRepository;
    private final FoodsharingMetricArchiveService metricArchiveService;

    public AuditRetentionCleanupService(
            AppProperties appProperties,
            FoodsharingPickupAutomationAuditRepository pickupAutomationAuditRepository,
            FoodsharingRequestAutomationAuditRepository requestAutomationAuditRepository,
            FoodsharingOpenSlotAdvertisementAuditRepository advertisementAuditRepository,
            FoodsharingMetricArchiveService metricArchiveService) {
        this.metricArchiveService = metricArchiveService;
        this.appProperties = appProperties;
        this.pickupAutomationAuditRepository = pickupAutomationAuditRepository;
        this.requestAutomationAuditRepository = requestAutomationAuditRepository;
        this.advertisementAuditRepository = advertisementAuditRepository;
    }

    @Scheduled(fixedDelayString = "${app.foodsharing.automation.audit-retention-cleanup-interval:PT24H}")
    @Transactional
    public void deleteExpiredAudits() {
        int retentionDays = Math.max(1, appProperties.getFoodsharing().getAutomation().getAuditRetentionDays());
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        // Keep anonymous daily totals for the metrics before the audits are gone.
        metricArchiveService.archivePickupAudits(() -> pickupAutomationAuditRepository.findAllByCreatedAtBefore(cutoff));
        metricArchiveService.archiveRequestAudits(() -> requestAutomationAuditRepository.findAllByCreatedAtBefore(cutoff));
        metricArchiveService.archiveAdvertisementAudits(() -> advertisementAuditRepository.findAllByCreatedAtBefore(cutoff));
        pickupAutomationAuditRepository.deleteAllByCreatedAtBefore(cutoff);
        requestAutomationAuditRepository.deleteAllByCreatedAtBefore(cutoff);
        advertisementAuditRepository.deleteAllByCreatedAtBefore(cutoff);
        log.info("Deleted audits older than {} day(s) before {}", retentionDays, cutoff);
    }
}
