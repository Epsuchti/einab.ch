package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.FoodsharingAdminConnection;
import ch.it4user.foodsharing.domain.entity.FoodsharingCleaningRuleExemption;
import ch.it4user.foodsharing.domain.entity.FoodsharingFuturePickupUsersCache;
import ch.it4user.foodsharing.domain.entity.FoodsharingPickupAutomationAudit;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreMembersCache;
import ch.it4user.foodsharing.domain.entity.FoodsharingStorePickupsCache;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreAutomation;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreAutomationExcludedDate;
import ch.it4user.foodsharing.domain.entity.FoodsharingGapStoreGroup;
import ch.it4user.foodsharing.domain.entity.FoodsharingGapStoreGroupMember;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreAutomationLinkedStore;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreAutomationLinkedStoreExemption;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreAutomationLinkedStoreMember;
import ch.it4user.foodsharing.domain.entity.FoodsharingRequestAutomation;
import ch.it4user.foodsharing.domain.entity.FoodsharingRequestAutomationAudit;
import ch.it4user.foodsharing.domain.entity.FoodsharingOpenSlotAdvertisementAutomation;
import ch.it4user.foodsharing.domain.entity.FoodsharingOpenSlotAdvertisementAudit;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.FoodsharingPickupAutomationDecision;
import ch.it4user.foodsharing.domain.enumtype.UserPermission;
import ch.it4user.foodsharing.repository.FoodsharingAdminConnectionRepository;
import ch.it4user.foodsharing.repository.FoodsharingCleaningRuleExemptionRepository;
import ch.it4user.foodsharing.repository.FoodsharingPickupAutomationAuditRepository;
import ch.it4user.foodsharing.repository.FoodsharingFuturePickupUsersCacheRepository;
import ch.it4user.foodsharing.repository.FoodsharingStoreMembersCacheRepository;
import ch.it4user.foodsharing.repository.FoodsharingStorePickupsCacheRepository;
import ch.it4user.foodsharing.repository.FoodsharingStoreAutomationRepository;
import ch.it4user.foodsharing.repository.FoodsharingStoreAutomationExcludedDateRepository;
import ch.it4user.foodsharing.repository.FoodsharingGapStoreGroupMemberRepository;
import ch.it4user.foodsharing.repository.FoodsharingGapStoreGroupRepository;
import ch.it4user.foodsharing.repository.FoodsharingStoreAutomationLinkedStoreExemptionRepository;
import ch.it4user.foodsharing.repository.FoodsharingStoreAutomationLinkedStoreMemberRepository;
import ch.it4user.foodsharing.repository.FoodsharingStoreAutomationLinkedStoreRepository;
import ch.it4user.foodsharing.repository.FoodsharingRequestAutomationRepository;
import ch.it4user.foodsharing.repository.FoodsharingRequestAutomationAuditRepository;
import ch.it4user.foodsharing.repository.FoodsharingOpenSlotAdvertisementAutomationRepository;
import ch.it4user.foodsharing.repository.FoodsharingOpenSlotAdvertisementAuditRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FoodsharingPickupAutomationService {
    private static final ZoneId SWISS_ZONE = ZoneId.of("Europe/Zurich");
    private static final long CLEANING_NEW_USER_MESSAGE_AFTER_MONTHS = 6;
    private static final int MAXIMUM_LINKED_STORES = 2;
    private static final int MAXIMUM_STORES_PER_LINK = 5;
    private static final int MINIMUM_LINKED_STORE_GRACE_MINUTES = 20;
    private static final int MAXIMUM_LINKED_STORE_WINDOW_DAYS = 120;
    private static final int MAXIMUM_LINKED_STORE_EXEMPTIONS = 200;
    /** Days of the cached pickup history that are read again, in case a past slot was still changed afterwards. */
    private static final int PICKUP_HISTORY_REFETCH_DAYS = 2;
    private static final int MAXIMUM_STORES_PER_GAP_GROUP = 10;
    private static final Logger log = LoggerFactory.getLogger(FoodsharingPickupAutomationService.class);
    private final AppProperties appProperties;
    private final BezirkService bezirkService;
    private final CurrentActorService currentActorService;
    private final CryptoService cryptoService;
    private final FoodsharingPickupApiClient client;
    private final FoodsharingClient foodsharingClient;
    private final FoodsharingAdminConnectionRepository connectionRepository;
    private final FoodsharingStoreAutomationRepository automationRepository;
    private final FoodsharingStoreAutomationExcludedDateRepository excludedDateRepository;
    private final FoodsharingStoreAutomationLinkedStoreRepository linkedStoreRepository;
    private final FoodsharingStoreAutomationLinkedStoreMemberRepository linkedStoreMemberRepository;
    private final FoodsharingStoreAutomationLinkedStoreExemptionRepository linkedStoreExemptionRepository;
    private final OutboundMessageGuard outboundGuard;
    private final FoodsharingGapStoreGroupRepository gapStoreGroupRepository;
    private final FoodsharingGapStoreGroupMemberRepository gapStoreGroupMemberRepository;
    private final FoodsharingStoreAccessService storeAccessService;
    private final FoodsharingAvailability availability;
    private final SwissPublicHolidayCalendar publicHolidayCalendar;
    private final FoodsharingRequestAutomationRepository requestAutomationRepository;
    private final FoodsharingRequestAutomationAuditRepository requestAutomationAuditRepository;
    private final FoodsharingOpenSlotAdvertisementAutomationRepository advertisementAutomationRepository;
    private final FoodsharingOpenSlotAdvertisementAuditRepository advertisementAuditRepository;
    private final FoodsharingPickupAutomationAuditRepository auditRepository;
    private final FoodsharingFuturePickupUsersCacheRepository futurePickupUsersCacheRepository;
    private final FoodsharingStoreMembersCacheRepository storeMembersCacheRepository;
    private final FoodsharingStorePickupsCacheRepository storePickupsCacheRepository;
    private final ch.it4user.foodsharing.repository.FoodsharingStorePickupHistoryCacheRepository pickupHistoryCacheRepository;
    /** Own short transaction for pickups cache writes, so a lost race on a cache row never rolls back an automation run. */
    private final TransactionTemplate cacheTransaction;
    /** One transaction per store in a slot run, so a database error in one store cannot roll back the audits of the others. */
    private final TransactionTemplate storeTransaction;
    private final FoodsharingCleaningRuleExemptionRepository cleaningRuleExemptionRepository;
    private final FoodsharingMessageService messageService;
    private final MessageSource messageSource;
    private final FoodsharingMetricArchiveService metricArchiveService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate scheduledPhaseTransaction;
    private final TransactionTemplate telegramAdvertisementAuditTransaction;
    private final FoodsharingStoreInfoService storeInfoService;
    private final RestClient telegramClient = RestClient.create("https://api.telegram.org");
    private final AtomicBoolean scheduledRunInProgress = new AtomicBoolean(false);
    private final AtomicBoolean scheduledRecoveryPending = new AtomicBoolean(false);

    public FoodsharingPickupAutomationService(AppProperties appProperties, BezirkService bezirkService, CurrentActorService currentActorService, CryptoService cryptoService, FoodsharingPickupApiClient client, FoodsharingClient foodsharingClient, FoodsharingAdminConnectionRepository connectionRepository, FoodsharingStoreAutomationRepository automationRepository, FoodsharingStoreAutomationExcludedDateRepository excludedDateRepository, SwissPublicHolidayCalendar publicHolidayCalendar, FoodsharingRequestAutomationRepository requestAutomationRepository, FoodsharingRequestAutomationAuditRepository requestAutomationAuditRepository, FoodsharingOpenSlotAdvertisementAutomationRepository advertisementAutomationRepository, FoodsharingOpenSlotAdvertisementAuditRepository advertisementAuditRepository, FoodsharingPickupAutomationAuditRepository auditRepository, FoodsharingFuturePickupUsersCacheRepository futurePickupUsersCacheRepository, FoodsharingStoreMembersCacheRepository storeMembersCacheRepository, FoodsharingStorePickupsCacheRepository storePickupsCacheRepository, FoodsharingCleaningRuleExemptionRepository cleaningRuleExemptionRepository, FoodsharingMessageService messageService, MessageSource messageSource, ObjectMapper objectMapper, PlatformTransactionManager transactionManager, FoodsharingStoreInfoService storeInfoService, FoodsharingMetricArchiveService metricArchiveService, FoodsharingStoreAutomationLinkedStoreRepository linkedStoreRepository, FoodsharingStoreAutomationLinkedStoreMemberRepository linkedStoreMemberRepository, FoodsharingGapStoreGroupRepository gapStoreGroupRepository, FoodsharingGapStoreGroupMemberRepository gapStoreGroupMemberRepository, FoodsharingAvailability availability, FoodsharingStoreAccessService storeAccessService, ch.it4user.foodsharing.repository.FoodsharingStorePickupHistoryCacheRepository pickupHistoryCacheRepository, FoodsharingStoreAutomationLinkedStoreExemptionRepository linkedStoreExemptionRepository, OutboundMessageGuard outboundGuard) {
        this.outboundGuard = outboundGuard;
        this.linkedStoreExemptionRepository = linkedStoreExemptionRepository;
        this.pickupHistoryCacheRepository = pickupHistoryCacheRepository;
        this.gapStoreGroupRepository = gapStoreGroupRepository;
        this.gapStoreGroupMemberRepository = gapStoreGroupMemberRepository;
        this.metricArchiveService = metricArchiveService;
        this.linkedStoreRepository = linkedStoreRepository;
        this.storeAccessService = storeAccessService;
        this.linkedStoreMemberRepository = linkedStoreMemberRepository;
        this.availability = availability;
        this.storeInfoService = storeInfoService;
        this.appProperties = appProperties; this.bezirkService = bezirkService; this.currentActorService = currentActorService; this.cryptoService = cryptoService; this.client = client; this.foodsharingClient = foodsharingClient; this.connectionRepository = connectionRepository; this.automationRepository = automationRepository; this.excludedDateRepository = excludedDateRepository; this.publicHolidayCalendar = publicHolidayCalendar; this.requestAutomationRepository = requestAutomationRepository; this.requestAutomationAuditRepository = requestAutomationAuditRepository; this.advertisementAutomationRepository = advertisementAutomationRepository; this.advertisementAuditRepository = advertisementAuditRepository; this.auditRepository = auditRepository; this.futurePickupUsersCacheRepository = futurePickupUsersCacheRepository; this.storeMembersCacheRepository = storeMembersCacheRepository; this.storePickupsCacheRepository = storePickupsCacheRepository; this.cleaningRuleExemptionRepository = cleaningRuleExemptionRepository; this.messageService = messageService; this.messageSource = messageSource; this.objectMapper = objectMapper; this.scheduledPhaseTransaction = new TransactionTemplate(transactionManager); this.cacheTransaction = new TransactionTemplate(transactionManager); this.cacheTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); this.storeTransaction = new TransactionTemplate(transactionManager); this.storeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.telegramAdvertisementAuditTransaction = new TransactionTemplate(transactionManager);
        this.telegramAdvertisementAuditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public ConnectionStatus connect(String email, String password, String telegramBotToken) {
        User admin = currentActorService.requireAutomationUser();
        var session = client.login(email, password);
        FoodsharingAdminConnection c = connectionRepository.findByAdminUser(admin).orElseGet(FoodsharingAdminConnection::new);
        c.setAdminUser(admin); c.setFoodsharingEmail(email); c.setFoodsharingPasswordCiphertext(cryptoService.encrypt(password)); c.setFoodsharingUserId(session.foodsharingUserId()); c.setSessionCookieCiphertext(cryptoService.encrypt(session.cookie())); c.setCsrfTokenCiphertext(cryptoService.encrypt(session.csrf())); c.setAuthenticatedAt(Instant.now());
        // A null token keeps the saved one, so changing the password does not drop the Telegram bot.
        if (telegramBotToken != null) {
            c.setTelegramBotTokenCiphertext(telegramBotToken.isBlank() ? null : cryptoService.encrypt(telegramBotToken.trim()));
        }
        connectionRepository.save(c);
        storeAccessService.forget(c);
        log.info("Foodsharing automation connection saved admin={} foodsharingUserId={} email={}", admin.getId(), session.foodsharingUserId(), email);
        return status(c);
    }

    @Transactional
    public void saveTelegramBotToken(String telegramBotToken) {
        FoodsharingAdminConnection connection = requireConnection();
        connection.setTelegramBotTokenCiphertext(telegramBotToken == null || telegramBotToken.isBlank() ? null : cryptoService.encrypt(telegramBotToken.trim()));
        connectionRepository.save(connection);
    }

    @Transactional
    public void disconnect() {
        User admin = currentActorService.requireAutomationUser();
        connectionRepository.findByAdminUser(admin).ifPresent(connection -> {
            log.info("Foodsharing automation connection removed admin={} foodsharingUserId={} email={}", admin.getId(), connection.getFoodsharingUserId(), connection.getFoodsharingEmail());
            metricArchiveService.archivePickupAudits(() -> auditRepository.findAllByAdminConnection(connection));
            requestAutomationRepository.findAll().stream()
                    .filter(automation -> automation.getAdminConnection().getId().equals(connection.getId()))
                    .forEach(automation -> metricArchiveService.archiveRequestAudits(() -> requestAutomationAuditRepository.findAllByAutomation(automation)));
            advertisementAutomationRepository.findAll().stream()
                    .filter(automation -> automation.getAdminConnection().getId().equals(connection.getId()))
                    .forEach(automation -> metricArchiveService.archiveAdvertisementAudits(() -> advertisementAuditRepository.findAllByAutomation(automation)));
            auditRepository.deleteAllByAdminConnection(connection);
            futurePickupUsersCacheRepository.deleteAllByAdminConnection(connection);
            storeMembersCacheRepository.deleteAllByAdminConnection(connection);
            storePickupsCacheRepository.deleteAllByAdminConnection(connection);
            pickupHistoryCacheRepository.deleteAllByAdminConnection(connection);
            automationRepository.findAll().stream()
                    .filter(automation -> automation.getAdminConnection().getId().equals(connection.getId()))
                    .forEach(automation -> {
                        excludedDateRepository.deleteAllByAutomation(automation);
                        deleteLinkedStores(automation);
                    });
            gapStoreGroupMemberRepository.deleteAllByGroupAdminConnection(connection);
            gapStoreGroupMemberRepository.flush();
            gapStoreGroupRepository.deleteAllByAdminConnection(connection);
            gapStoreGroupRepository.flush();
            automationRepository.deleteAllByAdminConnection(connection);
            requestAutomationRepository.deleteAllByAdminConnection(connection);
            advertisementAutomationRepository.deleteAllByAdminConnection(connection);
            storeInfoService.deleteForConnection(connection);
            storeAccessService.forget(connection);
            connectionRepository.delete(connection);
        });
    }
    public ConnectionStatus status() { return connectionRepository.findByAdminUser(currentActorService.requireAutomationUser()).map(this::status).orElse(new ConnectionStatus(false, null, null, null, false, appProperties.getFoodsharing().getAutomation().isEnabled(), appProperties.getFoodsharing().getAutomation().isDryRun())); }
    private ConnectionStatus status(FoodsharingAdminConnection c) { return new ConnectionStatus(true, c.getFoodsharingEmail(), c.getFoodsharingUserId(), c.getAuthenticatedAt(), c.getTelegramBotTokenCiphertext() != null && !c.getTelegramBotTokenCiphertext().isBlank(), appProperties.getFoodsharing().getAutomation().isEnabled(), appProperties.getFoodsharing().getAutomation().isDryRun()); }

    @Transactional
    public StoreOverviewView stores() {
        FoodsharingAdminConnection c = requireConnection();
        return stores(c, requireBezirk(c.getAdminUser()));
    }

    @Transactional
    public StoreOverviewView stores(String bezirkSlug) {
        FoodsharingAdminConnection c = requireConnection();
        return stores(c, requireBezirk(bezirkSlug, c.getAdminUser()));
    }

    private StoreOverviewView stores(FoodsharingAdminConnection c, Bezirk bezirk) {
        List<FoodsharingPickupModels.Store> managedStores = managedStores(c);
        Map<Long, FoodsharingPickupModels.Store> managedStoresById = managedStores.stream()
                .collect(java.util.stream.Collectors.toMap(FoodsharingPickupModels.Store::id, store -> store, (left, right) -> left, LinkedHashMap::new));
        List<FoodsharingStoreAutomation> configuredAutomations = automationRepository.findAllByBezirk(bezirk);
        // Other accounts' automations (owner, rules, exemptions) are only shown read-only to people who may see all decisions.
        boolean seeAll = canSeeAllAutomations();
        List<StoreAutomationView> automations = configuredAutomations.stream()
                .filter(automation -> automation.getAdminConnection().getId().equals(c.getId())
                        ? managedStoresById.containsKey(automation.getStoreId())
                        : seeAll)
                .sorted(Comparator.comparing(FoodsharingStoreAutomation::getStoreName, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparingLong(FoodsharingStoreAutomation::getStoreId))
                .map(automation -> view(
                        automation.getStoreId(),
                        managedStoresById.getOrDefault(automation.getStoreId(), new FoodsharingPickupModels.Store(automation.getStoreId(), automation.getStoreName(), false)).name(),
                        automation,
                        c))
                .toList();
        List<ManagedStoreView> availableStores = managedStores.stream()
                .filter(store -> configuredAutomations.stream().noneMatch(automation -> automation.getStoreId() == store.id()))
                .map(store -> new ManagedStoreView(store.id(), store.name()))
                .sorted(Comparator.comparing(ManagedStoreView::storeName, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparingLong(ManagedStoreView::storeId))
                .toList();
        List<GapStoreGroupView> gapStoreGroups = gapStoreGroupRepository.findAllByAdminConnection(c).stream()
                .map(group -> new GapStoreGroupView(group.getMinimumGapDays(), gapStoreGroupMembers(group).stream()
                        .map(member -> new LinkedStoreRef(member.getStoreId(), member.getStoreName()))
                        .toList()))
                .toList();
        return new StoreOverviewView(automations, availableStores, userMessage("message.automation.linked-store.default-message"), gapStoreGroups, List.copyOf(managedStoresById.keySet()));
    }

    @Transactional
    public StoreAutomationView save(long storeId, StoreAutomationRequest request) {
        FoodsharingAdminConnection c = requireConnection();
        return save(c, requireBezirk(c.getAdminUser()), storeId, request);
    }

    @Transactional
    public StoreAutomationView save(String bezirkSlug, long storeId, StoreAutomationRequest request) {
        FoodsharingAdminConnection c = requireConnection();
        return save(c, requireBezirk(bezirkSlug, c.getAdminUser()), storeId, request);
    }

    private StoreAutomationView save(FoodsharingAdminConnection c, Bezirk bezirk, long storeId, StoreAutomationRequest request) {
        FoodsharingStoreAutomation a = automationRepository.findByStoreId(storeId)
                .map(existing -> {
                    if (!existing.getBezirk().getId().equals(bezirk.getId())
                            || !existing.getAdminConnection().getId().equals(c.getId())) {
                        User owner = existing.getAdminConnection().getAdminUser();
                        String ownerName = owner == null ? null : owner.getName();
                        String ownerEmail = owner == null ? existing.getAdminConnection().getFoodsharingEmail() : owner.getEmail();
                        String ownerLabel = ownerName == null || ownerName.isBlank()
                                ? ownerEmail
                                : ownerEmail == null || ownerEmail.isBlank() ? ownerName : ownerName + " (" + ownerEmail + ")";
                        throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_FAILED, List.of(
                                "Für diesen Store besteht bereits eine Automation bei " + (ownerLabel == null || ownerLabel.isBlank() ? "einem anderen Benutzer" : ownerLabel) + "."));
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    storeAccessService.requireAutomatableStore(c, storeId);
                    gapStoreGroupMemberRepository.findByStoreId(storeId)
                            .filter(member -> !member.getGroup().getAdminConnection().getId().equals(c.getId()))
                            .ifPresent(member -> {
                                // The other account's group could not check this automation's sign-ups.
                                throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_FAILED, List.of(
                                        "Dieser Betrieb ist in der Abstandsregel eines anderen Kontos. Dort muss er zuerst aus der Liste entfernt werden."));
                            });
                    var n = new FoodsharingStoreAutomation(); n.setBezirk(bezirk); n.setAdminConnection(c); n.setStoreId(storeId); return n;
                });
        a.setStoreName(request.storeName() == null ? a.getStoreName() : request.storeName());
        a.setEnabled(request.enabled());
        a.setDryRunEnabled(request.dryRunEnabled());
        a.setGapRuleEnabled(request.gapRuleEnabled());
        a.setMinimumGapDays(Math.max(0, request.minimumGapDays()));
        if (request.cleaningRuleEnabled() && !a.isCleaningRuleEnabled() && !foodsharingClient.hasUserInfoSource(bezirk)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of(
                    "Die Reinigungsregel braucht eine Quelle für Benutzerinfos (Foodsharing-Konto des Bezirks oder User-Info-API)."));
        }
        a.setCleaningRuleEnabled(request.cleaningRuleEnabled() && bezirk.getCleaningStoreId() != null);
        a.setExperienceRuleEnabled(request.experienceRuleEnabled());
        a.setPublicHolidayRuleEnabled(request.publicHolidayRuleEnabled());
        List<LinkedStoreDraft> linkedStores = request.linkedStores() == null
                ? null
                : validateLinkedStores(c, a, storeId, request.linkedStores());
        FoodsharingStoreAutomation saved = automationRepository.save(a);
        if (linkedStores != null) {
            deleteLinkedStores(saved);
            for (LinkedStoreDraft draft : linkedStores) {
                draft.link().setAutomation(saved);
                FoodsharingStoreAutomationLinkedStore link = linkedStoreRepository.save(draft.link());
                for (LinkedStoreRef ref : draft.stores()) {
                    FoodsharingStoreAutomationLinkedStoreMember member = new FoodsharingStoreAutomationLinkedStoreMember();
                    member.setLink(link);
                    member.setStoreId(ref.storeId());
                    member.setStoreName(ref.storeName());
                    linkedStoreMemberRepository.save(member);
                }
                for (LinkedStoreExemption entry : draft.exemptions()) {
                    FoodsharingStoreAutomationLinkedStoreExemption exemption = new FoodsharingStoreAutomationLinkedStoreExemption();
                    exemption.setLink(link);
                    exemption.setFoodsharingId(entry.foodsharingId());
                    exemption.setReason(entry.reason());
                    linkedStoreExemptionRepository.save(exemption);
                }
            }
        }
        applyGapStoreGroup(c, saved, request);
        futurePickupUsersCacheRepository.deleteByAdminConnectionAndBezirk(c, bezirk);
        return view(storeId, saved.getStoreName(), saved, c);
    }

    @Transactional
    public void delete(long storeId) {
        FoodsharingAdminConnection connection = requireConnection();
        delete(connection, requireBezirk(connection.getAdminUser()), storeId);
    }

    @Transactional
    public void delete(String bezirkSlug, long storeId) {
        FoodsharingAdminConnection connection = requireConnection();
        delete(connection, requireBezirk(bezirkSlug, connection.getAdminUser()), storeId);
    }

    private void delete(FoodsharingAdminConnection connection, Bezirk bezirk, long storeId) {
        FoodsharingStoreAutomation automation = automationRepository.findByBezirkAndStoreId(bezirk, storeId)
                .filter(entry -> entry.getAdminConnection().getId().equals(connection.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, List.of("Store automation not found.")));
        excludedDateRepository.deleteAllByAutomation(automation);
        deleteLinkedStores(automation);
        automationRepository.delete(automation);
        futurePickupUsersCacheRepository.deleteByAdminConnectionAndBezirk(connection, bezirk);
    }

    @Transactional(readOnly = true)
    public List<ExcludedDateView> excludedDates(String bezirkSlug) {
        FoodsharingAdminConnection connection = requireConnection();
        Bezirk bezirk = requireBezirk(bezirkSlug, connection.getAdminUser());
        boolean seeAll = canSeeAllAutomations();
        List<ExcludedDateView> result = new ArrayList<>(excludedDateRepository.findAllByAutomationBezirkOrderByAutomationStoreNameAscExcludedDateAsc(bezirk).stream()
                .filter(date -> seeAll || date.getAutomation().getAdminConnection().getId().equals(connection.getId()))
                .map(date -> new ExcludedDateView(date.getId(), date.getAutomation().getStoreId(), date.getAutomation().getStoreName(), date.getExcludedDate(), null, true))
                .toList());
        LocalDate today = LocalDate.now(SWISS_ZONE);
        try {
            Map<LocalDate, String> holidays = new HashMap<>();
            holidays.putAll(publicHolidayCalendar.holidays(today.getYear()));
            holidays.putAll(publicHolidayCalendar.holidays(today.plusYears(1).getYear()));
            automationRepository.findAllByBezirk(bezirk).stream()
                    .filter(FoodsharingStoreAutomation::isPublicHolidayRuleEnabled)
                    .filter(automation -> automation.getAdminConnection().getId().equals(connection.getId()))
                    .forEach(automation -> holidays.entrySet().stream()
                            .filter(holiday -> !holiday.getKey().isBefore(today))
                            .map(holiday -> new ExcludedDateView(null, automation.getStoreId(), automation.getStoreName(), holiday.getKey(), holiday.getValue(), false))
                            .forEach(result::add));
        } catch (SwissPublicHolidayCalendar.PublicHolidayCalendarUnavailableException exception) {
            log.warn("Foodsharing public-holiday calendar unavailable while listing excluded dates", exception);
        }
        return result.stream()
                .sorted(Comparator.comparing(ExcludedDateView::excludedDate).thenComparing(ExcludedDateView::storeName))
                .toList();
    }

    @Transactional
    public ExcludedDateView saveExcludedDate(String bezirkSlug, ExcludedDateRequest request) {
        FoodsharingAdminConnection connection = requireConnection();
        Bezirk bezirk = requireBezirk(bezirkSlug, connection.getAdminUser());
        FoodsharingStoreAutomation automation = automationRepository.findByBezirkAndStoreId(bezirk, request.storeId())
                .filter(entry -> entry.getAdminConnection().getId().equals(connection.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, List.of("Store automation not found.")));
        if (request.excludedDate() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Excluded date is required."));
        }
        FoodsharingStoreAutomationExcludedDate excludedDate = excludedDateRepository.findByAutomationAndExcludedDate(automation, request.excludedDate())
                .orElseGet(FoodsharingStoreAutomationExcludedDate::new);
        excludedDate.setAutomation(automation);
        excludedDate.setExcludedDate(request.excludedDate());
        FoodsharingStoreAutomationExcludedDate saved = excludedDateRepository.save(excludedDate);
        return new ExcludedDateView(saved.getId(), automation.getStoreId(), automation.getStoreName(), saved.getExcludedDate(), null, true);
    }

    @Transactional
    public void deleteExcludedDate(String bezirkSlug, UUID excludedDateId) {
        FoodsharingAdminConnection connection = requireConnection();
        Bezirk bezirk = requireBezirk(bezirkSlug, connection.getAdminUser());
        FoodsharingStoreAutomationExcludedDate excludedDate = excludedDateRepository.findByIdAndAutomationBezirk(excludedDateId, bezirk)
                .filter(entry -> entry.getAutomation().getAdminConnection().getId().equals(connection.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, List.of("Excluded date not found.")));
        excludedDateRepository.delete(excludedDate);
    }



    @Transactional(readOnly = true)
    public List<RequestAutomationView> requestAutomationOverview() {
        return requestAutomationOverview(null);
    }

    /** Own request automations; with CAN_SEE_ALL_AUTOMATION_DECISIONS also other accounts' ones (read-only). */
    @Transactional(readOnly = true)
    public List<RequestAutomationView> requestAutomationOverview(String bezirkSlug) {
        FoodsharingAdminConnection connection = requireConnection();
        BezirkStores bezirkStores = bezirkStores(bezirkScope(bezirkSlug, connection.getAdminUser()));
        boolean seeAll = canSeeAllAutomations();
        return requestAutomationRepository.findAll().stream()
                .filter(a -> seeAll || a.getAdminConnection().getId().equals(connection.getId()))
                .filter(a -> bezirkStores.contains(a.getStoreId(), a.getAdminConnection()))
                .map(a -> requestView(a, a.getAdminConnection().getId().equals(connection.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdvertisementAutomationView> openSlotAdvertisementOverview() {
        return openSlotAdvertisementOverview(null);
    }

    /** Own advertisement automations; with CAN_SEE_ALL_AUTOMATION_DECISIONS also other accounts' ones (read-only). */
    @Transactional(readOnly = true)
    public List<AdvertisementAutomationView> openSlotAdvertisementOverview(String bezirkSlug) {
        FoodsharingAdminConnection connection = requireConnection();
        BezirkStores bezirkStores = bezirkStores(bezirkScope(bezirkSlug, connection.getAdminUser()));
        boolean seeAll = canSeeAllAutomations();
        return advertisementAutomationRepository.findAll().stream()
                .filter(a -> seeAll || a.getAdminConnection().getId().equals(connection.getId()))
                .filter(a -> bezirkStores.contains(a.getStoreId(), a.getAdminConnection()))
                .map(a -> advertisementView(a, a.getAdminConnection().getId().equals(connection.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ExtraAutomationOverviewView extraAutomationOverview() {
        return extraAutomationOverview(null);
    }

    @Transactional(readOnly = true)
    public ExtraAutomationOverviewView extraAutomationOverview(String bezirkSlug) {
        return new ExtraAutomationOverviewView(requestAutomationOverview(bezirkSlug), openSlotAdvertisementOverview(bezirkSlug));
    }

    @Transactional
    public RequestAutomationView saveRequestAutomation(long storeId, RequestAutomationRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        FoodsharingAdminConnection c = requireConnection();
        FoodsharingRequestAutomation a = requestAutomationRepository.findByStoreId(storeId)
                .map(existing -> {
                    if (!existing.getAdminConnection().getId().equals(c.getId())) throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_FAILED, List.of("A request automation already exists for this store."));
                    return existing;
                })
                .orElseGet(() -> {
                    storeAccessService.requireAutomatableStore(c, storeId);
                    var n = new FoodsharingRequestAutomation(); n.setAdminConnection(c); n.setStoreId(storeId); return n;
                });
        validateRequestAutomation(request);
        a.setStoreName(request.storeName() == null ? a.getStoreName() : request.storeName());
        a.setEnabled(request.enabled());
        a.setDryRunEnabled(request.dryRunEnabled());
        a.setDistanceRuleEnabled(request.distanceRuleEnabled());
        a.setMaximumDistanceKm(normalizeDistance(request.maximumDistanceKm()));
        a = requestAutomationRepository.save(a);
        return requestView(a, a.getAdminConnection().getId().equals(c.getId()));
    }

    @Transactional
    public void deleteRequestAutomation(long storeId) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        FoodsharingAdminConnection connection = requireConnection();
        FoodsharingRequestAutomation automation = requestAutomationRepository.findByStoreId(storeId)
                .filter(entry -> entry.getAdminConnection().getId().equals(connection.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, List.of("Request automation not found.")));
        metricArchiveService.archiveRequestAudits(() -> requestAutomationAuditRepository.findAllByAutomation(automation));
        requestAutomationAuditRepository.deleteAllByAutomation(automation);
        requestAutomationRepository.delete(automation);
    }

    @Transactional
    public AdvertisementAutomationView saveAdvertisementAutomation(long storeId, int advertNumber, AdvertisementAutomationRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        if (advertNumber < 1 || advertNumber > 3) throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Advert number must be between 1 and 3."));
        FoodsharingAdminConnection c = requireConnection();
        // All adverts of a store belong to one account, so a store never has automations of two accounts.
        if (advertisementAutomationRepository.findAllByStoreIdOrderByAdvertNumberAsc(storeId).stream()
                .anyMatch(existing -> !existing.getAdminConnection().getId().equals(c.getId()))) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_FAILED, List.of("An advertisement automation already exists for this store."));
        }
        FoodsharingOpenSlotAdvertisementAutomation a = advertisementAutomationRepository.findByStoreIdAndAdvertNumber(storeId, advertNumber)
                .orElseGet(() -> {
                    if (advertisementAutomationRepository.findAllByStoreIdOrderByAdvertNumberAsc(storeId).isEmpty()) {
                        storeAccessService.requireAutomatableStore(c, storeId);
                    }
                    var n = new FoodsharingOpenSlotAdvertisementAutomation(); n.setAdminConnection(c); n.setStoreId(storeId); n.setAdvertNumber(advertNumber); return n;
                });
        if (!a.getAdminConnection().getId().equals(c.getId())) throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_FAILED, List.of("An advertisement automation already exists for this store."));
        a.setStoreName(request.storeName() == null ? a.getStoreName() : request.storeName());
        a.setEnabled(request.enabled()); a.setDryRunEnabled(request.dryRunEnabled()); a.setTriggerHoursBefore(Math.max(1, request.triggerHoursBefore()));
        a.setSendToStoreChat(request.sendToStoreChat()); a.setSendToTelegram(request.sendToTelegram()); a.setTelegramChatId(blankToNull(request.telegramChatId()));
        if (a.isSendToStoreChat() && isBlankMessages(request.storeMessages())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Store chat messages are required when store chat is enabled."));
        }
        if (a.isSendToTelegram() && isBlankMessages(request.telegramMessages())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Telegram messages are required when Telegram is enabled."));
        }
        validateTemplateVariables(request.storeMessages(), request.telegramMessages());
        a.setStoreMessagesJson(serializeMessages(request.storeMessages()));
        a.setTelegramMessagesJson(serializeMessages(request.telegramMessages()));
        String lateCancellationMessage = normalize(request.lateCancellationMessage());
        if (request.sendLateCancellationMessage() && lateCancellationMessage.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Late cancellation message is required when messaging the person who released the slot is enabled."));
        }
        if (!lateCancellationMessage.isBlank()) {
            validateTemplateVariables(List.of(lateCancellationMessage));
        }
        a.setLateCancellationMessage(lateCancellationMessage);
        a.setSendLateCancellationMessage(request.sendLateCancellationMessage());
        a.setLateCancellationMessageMaximumHoursBeforePickup(validateLateCancellationMessageMaximumHoursBeforePickup(request.lateCancellationMessageMaximumHoursBeforePickup()));
        a.setSendLatestAdvertisementAfterLateCancellation(request.sendLatestAdvertisementAfterLateCancellation());
        a = advertisementAutomationRepository.save(a);
        List<FoodsharingOpenSlotAdvertisementAutomation> storeAutomations = advertisementAutomationRepository.findAllByStoreIdOrderByAdvertNumberAsc(storeId);
        for (FoodsharingOpenSlotAdvertisementAutomation storeAutomation : storeAutomations) {
            if (!storeAutomation.getId().equals(a.getId())) {
                storeAutomation.setLateCancellationMessage(a.getLateCancellationMessage());
                storeAutomation.setSendLateCancellationMessage(a.isSendLateCancellationMessage());
                storeAutomation.setLateCancellationMessageMaximumHoursBeforePickup(a.getLateCancellationMessageMaximumHoursBeforePickup());
                storeAutomation.setSendLatestAdvertisementAfterLateCancellation(a.isSendLatestAdvertisementAfterLateCancellation());
            }
        }
        advertisementAutomationRepository.saveAll(storeAutomations);
        return advertisementView(a, true);
    }

    @Transactional
    public void deleteAdvertisementAutomation(long storeId, int advertNumber) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        if (advertNumber < 1 || advertNumber > 3) throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Advert number must be between 1 and 3."));
        FoodsharingAdminConnection connection = requireConnection();
        FoodsharingOpenSlotAdvertisementAutomation automation = advertisementAutomationRepository.findByStoreIdAndAdvertNumber(storeId, advertNumber)
                .filter(entry -> entry.getAdminConnection().getId().equals(connection.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, List.of("Advertisement automation not found.")));
        metricArchiveService.archiveAdvertisementAudits(() -> advertisementAuditRepository.findAllByAutomation(automation));
        advertisementAuditRepository.deleteAllByAutomation(automation);
        advertisementAutomationRepository.delete(automation);
    }

    @Transactional
    public AutomationRunSummary runRequestAutomations() {
        return runRequestAutomations(false);
    }

    @Transactional
    public AutomationRunSummary runRequestAutomations(boolean dryRunOverride) {
        if (!appProperties.getFoodsharing().getAutomation().isEnabled()) {
            return new AutomationRunSummary(0, 0, 1, true, List.of("Request automation skipped: global automation is disabled."));
        }
        boolean globalDryRun = dryRunOverride || appProperties.getFoodsharing().getAutomation().isDryRun();
        int evaluated = 0;
        int acted = 0;
        int skipped = 0;
        List<String> messages = new ArrayList<>();
        int maxApprovals = Math.max(0, appProperties.getFoodsharing().getAutomation().getMaxRequestApprovalsPerRun());
        for (FoodsharingRequestAutomation a : requestAutomationRepository.findAllByEnabledTrue()) {
            boolean dryRun = globalDryRun || a.isDryRunEnabled();
            try {
            List<FoodsharingPickupApiClient.RequestUser> requests = client.requests(a.getAdminConnection(), a.getStoreId());
            if (requests.isEmpty()) {
                skipped++;
                String message = "No open requests for " + a.getStoreName() + " (" + a.getStoreId() + ").";
                messages.add(message);
            }
            for (FoodsharingPickupApiClient.RequestUser user : requests) {
                if (acted >= maxApprovals) {
                    skipped++;
                    String message = "Request automation limit reached (" + maxApprovals + ").";
                    messages.add(message);
                    break;
                }
                evaluated++;
                log.debug("Foodsharing request automation approving dryRun={} storeId={} userId={} userName={}", dryRun, a.getStoreId(), user.id(), user.name());
                if (user.id() == null || user.id().isBlank()) { skipped++; continue; }
                if (a.isDistanceRuleEnabled()) {
                    Double distanceInKm = user.distanceInKm();
                    if (distanceInKm == null) {
                        skipped++;
                        continue;
                    }
                    if (distanceInKm > a.getMaximumDistanceKm()) {
                        String message = "Die Anfrage wurde automatisch abgelehnt, weil die Entfernung von " + formatDistance(distanceInKm) + " km das Maximum von " + formatDistance(a.getMaximumDistanceKm()) + " km überschreitet.";
                        if (!dryRun) {
                            client.declineRequest(a.getAdminConnection(), a.getStoreId(), user.id(), message);
                            invalidateStoreCaches(a.getAdminConnection(), a.getStoreId());
                            acted++;
                            saveRequestAudit(a, user, "DECLINED", message, message, null);
                        } else {
                            skipped++;
                        }
                        messages.add((dryRun ? "Würde ablehnen: " : "Abgelehnt: ") + message);
                        continue;
                    }
                }
                try {
                    if (!dryRun) { client.approveRequest(a.getAdminConnection(), a.getStoreId(), user.id()); invalidateStoreCaches(a.getAdminConnection(), a.getStoreId()); }
                    acted++;
                    String reason = (dryRun ? "Would approve request." : "Request approved.");
                    if (!dryRun) {
                        saveRequestAudit(a, user, "APPROVED", reason, null, null);
                    }
                    messages.add((dryRun ? "Would approve " : "Approved ") + user.name() + " (" + user.id() + ") for " + a.getStoreName() + ".");
                } catch (RuntimeException ex) {
                    if (availability.reportFailure("request", ex)) throw ex;
                    log.error("Foodsharing request automation failed to approve userId={} userName={} for store {} ({})", user.id(), user.name(), a.getStoreId(), a.getStoreName(), ex);
                    skipped++;
                    messages.add("Failed to approve " + user.name() + " (" + user.id() + "): " + ex.getMessage());
                }
            }
            } catch (RuntimeException ex) {
                if (storeFailureStopsRun("request", a.getStoreId(), a.getStoreName(), ex)) break;
                skipped++;
                messages.add("Request automation failed for " + a.getStoreName() + ": " + ex.getMessage());
            }
        }
        return new AutomationRunSummary(evaluated, acted, skipped, globalDryRun, messages);
    }

    @Scheduled(fixedDelayString = "#{T(java.time.Duration).parse('${app.foodsharing.automation.poll-interval:PT5M}').toMillis()}")
    public void scheduledAutomationRun() {
        runScheduledAutomations("poll");
    }

    @Scheduled(fixedDelay = 60 * 60 * 1000L, initialDelay = 60 * 60 * 1000L)
    public void scheduledAutomationRecoveryRun() {
        if (!scheduledRecoveryPending.compareAndSet(true, false)) {
            return;
        }
        log.info("Foodsharing automation recovery started after a previous failed run.");
        runScheduledAutomations("recovery");
    }

    private void runScheduledAutomations(String trigger) {
        if (!scheduledRunInProgress.compareAndSet(false, true)) {
            log.info("Foodsharing automation {} skipped because another run is still in progress.", trigger);
            if ("recovery".equals(trigger)) {
                scheduledRecoveryPending.set(true);
            }
            return;
        }
        boolean failed = false;
        try {
            if (availability.shouldSkipRun()) {
                // Nothing was started, so nothing is lost: the first run after the site is back picks up everything.
                return;
            }
            failed |= !runScheduledPhase("request", this::runRequestAutomations);
            failed |= !availability.isDown() && !runScheduledPhase("slot", this::scheduledRun);
            failed |= !availability.isDown() && !runScheduledPhase("advertisement", () -> runAdvertisementAutomations(false));
            failed |= !availability.isDown() && !runScheduledPhase("storeInfo", storeInfoService::runScheduled);
            if (!availability.isDown() && !failed) {
                availability.markRunSucceeded();
            }
        } finally {
            if (availability.isDown()) {
                // The circuit breaker resumes the automations as soon as a probe succeeds; no hourly recovery run needed.
                scheduledRecoveryPending.set(false);
            } else if (failed) {
                scheduledRecoveryPending.set(true);
                log.warn("Foodsharing automation failed; scheduling a recovery run within one hour.");
            } else {
                scheduledRecoveryPending.set(false);
            }
            scheduledRunInProgress.set(false);
        }
    }

    private boolean runScheduledPhase(String phaseName, Runnable phase) {
        try {
            scheduledPhaseTransaction.executeWithoutResult(status -> phase.run());
            return true;
        } catch (RuntimeException ex) {
            if (availability.reportFailure(phaseName, ex)) {
                return true;
            }
            log.error("Foodsharing {} automation failed; other automation types will continue.", phaseName, ex);
            return false;
        }
    }

    /**
     * Handles a failure of one store so it cannot stop the others.
     *
     * @return true if Foodsharing is offline and the whole run has to stop
     */
    private boolean storeFailureStopsRun(String automationType, long storeId, String storeName, RuntimeException ex) {
        if (availability.reportFailure(automationType, ex)) {
            return true;
        }
        log.error("Foodsharing {} automation failed for store {} ({}); continuing with the next store.", automationType, storeId, storeName, ex);
        return false;
    }

    @Transactional
    public AutomationRunSummary runAdvertisementAutomations() {
        return runAdvertisementAutomations(false);
    }

    @Transactional
    public AutomationRunSummary runAdvertisementAutomations(boolean dryRunOverride) {
        if (!appProperties.getFoodsharing().getAutomation().isEnabled()) {
            return new AutomationRunSummary(0, 0, 1, true, List.of("Open-slot advertisement automation skipped: global automation is disabled."));
        }
        boolean globalDryRun = dryRunOverride || appProperties.getFoodsharing().getAutomation().isDryRun();
        Instant now = Instant.now();
        int evaluated = 0;
        int acted = 0;
        int skipped = 0;
        int advertisementsSent = 0;
        Set<String> lateCancellationMessageSent = new java.util.HashSet<>();
        Set<String> lateCancellationAdvertisementSent = new java.util.HashSet<>();
        int maxAdvertisements = Math.max(0, appProperties.getFoodsharing().getAutomation().getMaxAdvertisementsPerRun());
        List<FoodsharingOpenSlotAdvertisementAutomation> enabledAutomations = advertisementAutomationRepository.findAllByEnabledTrue();
        List<FoodsharingOpenSlotAdvertisementAutomation> allAutomations = advertisementAutomationRepository.findAll();
        Map<String, FoodsharingOpenSlotAdvertisementAutomation> lateCancellationSettings = new HashMap<>();
        for (FoodsharingOpenSlotAdvertisementAutomation automation : allAutomations) {
            if (!globalDryRun && !automation.isDryRunEnabled()) {
                deleteTelegramAdvertisementsForPassedSlots(automation, now);
            }
            String settingsKey = lateCancellationSettingsKey(automation);
            FoodsharingOpenSlotAdvertisementAutomation current = lateCancellationSettings.get(settingsKey);
            if (current == null || automation.getAdvertNumber() < current.getAdvertNumber()) {
                lateCancellationSettings.put(settingsKey, automation);
            }
        }
        for (FoodsharingOpenSlotAdvertisementAutomation a : enabledAutomations) {
            try {
            boolean dryRun = globalDryRun || a.isDryRunEnabled();
            List<String> storeMessages = deserializeMessages(a.getStoreMessagesJson());
            List<String> telegramMessages = deserializeMessages(a.getTelegramMessagesJson());
            int checkedSlots = 0;
            int automationActed = 0;
            if ((a.isSendToStoreChat() && storeMessages.isEmpty()) || (a.isSendToTelegram() && telegramMessages.isEmpty())) {
                skipped++;
                if (dryRun) {
                    saveAdvertisementRunSummaryAudit(a, now, true, 0, 0, 0, "Advertisement skipped: missing messages for a selected channel.");
                }
                continue;
            }
            if (!a.isSendToStoreChat() && !a.isSendToTelegram()) {
                skipped++;
                if (dryRun) {
                    saveAdvertisementRunSummaryAudit(a, now, true, 0, 0, 0, "Advertisement skipped: no output target selected.");
                }
                continue;
            }
            for (FoodsharingPickupModels.Pickup p : storePickups(a.getAdminConnection(), a.getStoreId())) {
                evaluated++;
                checkedSlots++;
                Instant triggerAt = p.date().minus(Duration.ofHours(a.getTriggerHoursBefore()));
                if (triggerAt.isAfter(now) || now.isAfter(p.date())) {
                    skipped++;
                    continue;
                }
                var existingAudit = advertisementAuditRepository.findAllByAutomationAndPickupDateAndTriggerHoursBefore(a, p.date(), a.getTriggerHoursBefore()).stream().findFirst();
                if (isPickupFilled(p)) {
                    if (!dryRun) {
                        existingAudit.filter(audit -> audit.getFilledAt() == null
                                        && FoodsharingMetricArchiveService.SENT_ADVERTISEMENT_STATUSES.contains(audit.getStatus()))
                                .ifPresent(audit -> updateAdvertisementAudit(audit.getId(), changed -> changed.setFilledAt(now)));
                    }
                    // Only approved pickups: a pending sign-up may still be removed by the slot automation, which is no late cancellation.
                    if (!dryRun && existingAudit.isEmpty() && p.users().stream().allMatch(FoodsharingPickupModels.PickupUser::confirmed)) {
                        FoodsharingPickupModels.PickupUser pickupUser = p.users().getFirst();
                        saveAdvertisementAudit(a, p.date(), false, "OCCUPIED_AT_TRIGGER", "Pickup was occupied when this notification became due.", null, null, null, pickupUser.id(), pickupUser.name());
                    }
                    if (!dryRun) {
                        deleteTelegramAdvertisementsForFilledSlot(a, p.date());
                    }
                    skipped++;
                    continue;
                }
                if (existingAudit.isPresent()) {
                    FoodsharingOpenSlotAdvertisementAudit audit = existingAudit.get();
                    if (!dryRun && audit.getFilledAt() != null) {
                        // Someone left the pickup again after it was filled: only count fills that last.
                        updateAdvertisementAudit(audit.getId(), changed -> changed.setFilledAt(null));
                    }
                    if ("OCCUPIED_AT_TRIGGER".equals(audit.getStatus())) {
                        String reminderKey = a.getAdminConnection().getId() + ":" + p.date();
                        FoodsharingOpenSlotAdvertisementAutomation lateCancellationAutomation = lateCancellationSettings.get(lateCancellationSettingsKey(a));
                        boolean messageApplicable = lateCancellationAutomation != null
                                && lateCancellationAutomation.isSendLateCancellationMessage()
                                && !p.date().isAfter(now.plus(Duration.ofHours(lateCancellationAutomation.getLateCancellationMessageMaximumHoursBeforePickup())))
                                && !lateCancellationMessageSent.contains(reminderKey)
                                && !advertisementAuditRepository.existsByAutomationAdminConnectionAndPickupDateAndStatusIn(a.getAdminConnection(), p.date(), List.of("LATE_CANCELLATION_REMINDER_SENT", "LATE_CANCELLATION_REMINDER_AND_ADVERTISEMENT_SENT"));
                        // The audit only remembers one of the people signed in at trigger time, so the reminder goes to whoever
                        // actually signed out (store log), and never to someone who is still signed in for this pickup.
                        List<LateCancellationRecipient> recipients = messageApplicable ? lateCancellationRecipients(a, p, audit, now) : List.of();
                        boolean sendMessage = messageApplicable && !recipients.isEmpty();
                        boolean sendAdvertisement = lateCancellationAutomation != null
                                && lateCancellationAutomation.isSendLatestAdvertisementAfterLateCancellation()
                                && isLatestDueLateCancellationAdvertisement(a, enabledAutomations, p.date(), now)
                                && advertisementsSent < maxAdvertisements
                                && !lateCancellationAdvertisementSent.contains(reminderKey)
                                && !advertisementAuditRepository.existsByAutomationAdminConnectionAndPickupDateAndStatusIn(a.getAdminConnection(), p.date(), List.of("LATE_CANCELLATION_ADVERTISEMENT_SENT", "LATE_CANCELLATION_REMINDER_AND_ADVERTISEMENT_SENT"));
                        if (!sendMessage && !sendAdvertisement) {
                            if (!dryRun) {
                                updateAdvertisementAudit(audit.getId(), changed -> {
                                    changed.setStatus("LATE_CANCELLATION_NO_ACTION");
                                    changed.setReason("No late-cancellation action is enabled or applicable for this pickup.");
                                });
                            }
                            skipped++;
                            continue;
                        }
                        String reminder = sendMessage ? renderAdvertisement(lateCancellationAutomation.getLateCancellationMessage(), p.date(), adminFoodsharingId(a.getAdminConnection())) : null;
                        String storeMessage = sendAdvertisement && a.isSendToStoreChat()
                                ? renderAdvertisement(storeMessages.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(storeMessages.size())), p.date(), adminFoodsharingId(a.getAdminConnection())) : null;
                        String telegramMessage = sendAdvertisement && a.isSendToTelegram()
                                ? renderAdvertisement(telegramMessages.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(telegramMessages.size())), p.date(), adminFoodsharingId(a.getAdminConnection())) : null;
                        if (!dryRun) {
                            if (sendMessage) {
                                for (LateCancellationRecipient recipient : recipients) {
                                    client.sendUserMessage(a.getAdminConnection(), recipient.id(), reminder);
                                }
                            }
                            if (storeMessage != null) client.sendStoreChatMessage(a.getAdminConnection(), a.getStoreId(), storeMessage);
                            Integer telegramMessageId = telegramMessage != null && a.getTelegramChatId() != null ? sendTelegram(a, telegramMessage) : null;
                            persistLateCancellationAdvertisementAudit(
                                    audit.getId(),
                                    sendMessage && sendAdvertisement ? "LATE_CANCELLATION_REMINDER_AND_ADVERTISEMENT_SENT" : sendMessage ? "LATE_CANCELLATION_REMINDER_SENT" : "LATE_CANCELLATION_ADVERTISEMENT_SENT",
                                    "Pickup was released after this notification time.",
                                    sendMessage ? reminder : combineAdvertisementMessages(storeMessage, telegramMessage),
                                    telegramMessageId,
                                    sendMessage ? recipients : List.of());
                            if (telegramMessageId != null) {
                                deletePreviousTelegramAdvertisementsSafely(a, p.date(), audit.getId());
                            }
                        }
                        if (sendMessage) lateCancellationMessageSent.add(reminderKey);
                        if (sendAdvertisement) {
                            lateCancellationAdvertisementSent.add(reminderKey);
                            advertisementsSent++;
                        }
                        acted += (sendMessage ? 1 : 0) + (sendAdvertisement ? 1 : 0);
                        automationActed += (sendMessage ? 1 : 0) + (sendAdvertisement ? 1 : 0);
                        continue;
                    }
                    skipped++;
                    continue;
                }
                if (advertisementsSent >= maxAdvertisements) {
                    skipped++;
                    break;
                }
                String storeMessage = a.isSendToStoreChat()
                        ? renderAdvertisement(storeMessages.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(storeMessages.size())), p.date(), adminFoodsharingId(a.getAdminConnection()))
                        : null;
                String telegramMessage = a.isSendToTelegram()
                        ? renderAdvertisement(telegramMessages.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(telegramMessages.size())), p.date(), adminFoodsharingId(a.getAdminConnection()))
                        : null;
                if (!dryRun && storeMessage != null) client.sendStoreChatMessage(a.getAdminConnection(), a.getStoreId(), storeMessage);
                Integer telegramMessageId = null;
                UUID advertisementAuditId = null;
                if (!dryRun && telegramMessage != null && a.getTelegramChatId() != null) {
                    telegramMessageId = sendTelegram(a, telegramMessage);
                    advertisementAuditId = persistAdvertisementAudit(a, p.date(), "SENT", "Advertisement sent.", combineAdvertisementMessages(storeMessage, telegramMessage), telegramMessageId);
                    if (telegramMessageId != null) deletePreviousTelegramAdvertisementsSafely(a, p.date(), advertisementAuditId);
                }
                if (dryRun) {
                    automationActed++;
                } else if (advertisementAuditId == null) {
                    saveAdvertisementAudit(a, p.date(), false, "SENT", "Advertisement sent.", combineAdvertisementMessages(storeMessage, telegramMessage), null, telegramMessageId, null, null);
                }
                acted++;
                advertisementsSent++;
            }
            if (dryRun) {
                saveAdvertisementRunSummaryAudit(a, now, true, checkedSlots, automationActed, Math.max(0, checkedSlots - automationActed), "Checked " + checkedSlots + " slot" + (checkedSlots == 1 ? "" : "s") + ".");
            }
            } catch (RuntimeException ex) {
                if (storeFailureStopsRun("advertisement", a.getStoreId(), a.getStoreName(), ex)) break;
                skipped++;
            }
        }
        List<String> runMessages = List.of(((globalDryRun ? "Would send " : "Processed ") + acted + " message" + (acted == 1 ? "" : "s") + " after checking " + evaluated + " slot" + (evaluated == 1 ? "" : "s") + "."));
        return new AutomationRunSummary(evaluated, acted, skipped, globalDryRun, runMessages);
    }

    public void sendTelegramTestMessage(String chatId, String message) {
        FoodsharingAdminConnection connection = requireConnection();
        String token = telegramToken(connection);
        if (token == null || token.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Telegram bot token is not configured."));
        if (!outboundGuard.allowTelegramChat(normalizeRequired(chatId), message)) return;
        try {
            telegramClient.post().uri("/bot{token}/sendMessage", token).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", normalizeRequired(chatId), "text", normalize(message).isBlank() ? "Foodsharing automation test message" : normalize(message)))
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Telegram test message failed: " + ex.getMessage()));
        }
    }


    private void saveRequestAudit(FoodsharingRequestAutomation automation, FoodsharingPickupApiClient.RequestUser user, String status, String reason, String message, String error) {
        saveRequestAudit(automation, user.id(), user.name(), status, reason, message, error);
    }

    private void saveRequestAudit(FoodsharingRequestAutomation automation, String foodsharingUserId, String foodsharingUserName, String status, String reason, String message, String error) {
        FoodsharingRequestAutomationAudit audit = new FoodsharingRequestAutomationAudit();
        audit.setAutomation(automation);
        audit.setStoreId(automation.getStoreId());
        audit.setStoreName(automation.getStoreName());
        audit.setFoodsharingUserId(foodsharingUserId == null || foodsharingUserId.isBlank() ? "unknown" : foodsharingUserId);
        audit.setFoodsharingUserName(foodsharingUserName);
        audit.setDryRun(false);
        audit.setStatus(status);
        audit.setReason(reason);
        audit.setMessage(message);
        audit.setError(error);
        requestAutomationAuditRepository.save(audit);
    }

    record LateCancellationRecipient(String id, String name) {}

    /**
     * The people who released this pickup after it was full at trigger time. Read from the store log (sign-outs for
     * exactly this pickup since the audit was written); anybody still signed in for the pickup is left out. If the
     * log cannot be read or shows nobody, the person stored on the audit is used, but only if they left the pickup.
     */
    private List<LateCancellationRecipient> lateCancellationRecipients(
            FoodsharingOpenSlotAdvertisementAutomation automation,
            FoodsharingPickupModels.Pickup pickup,
            FoodsharingOpenSlotAdvertisementAudit audit,
            Instant now) {
        Set<String> stillSignedIn = new java.util.HashSet<>();
        for (FoodsharingPickupModels.PickupUser user : pickup.users()) {
            if (user.id() != null) stillSignedIn.add(user.id());
        }
        Instant since = audit.getCreatedAt() == null ? pickup.date().minus(Duration.ofDays(7)) : audit.getCreatedAt();
        Map<String, LateCancellationRecipient> fromLog = new LinkedHashMap<>();
        try {
            for (FoodsharingPickupModels.StoreLogEntry entry : client.storeLog(automation.getAdminConnection(), automation.getStoreId(),
                    since.minus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(1)), FoodsharingStoreInfoService.SIGN_OUT_SLOT_ACTION)) {
                if (entry.actionType() != FoodsharingStoreInfoService.SIGN_OUT_SLOT_ACTION
                        || entry.actorId() == null || entry.actorId().isBlank()
                        || entry.dateReference() == null || entry.performedAt() == null) continue;
                if (!sameMinute(entry.dateReference(), pickup.date())) continue;
                if (entry.performedAt().isBefore(since.minus(Duration.ofMinutes(5)))) continue;
                if (stillSignedIn.contains(entry.actorId())) continue;
                fromLog.putIfAbsent(entry.actorId(), new LateCancellationRecipient(entry.actorId(), entry.actorName()));
            }
        } catch (RuntimeException ex) {
            log.warn("Could not read store log for late-cancellation reminder storeId={} pickup={}", automation.getStoreId(), pickup.date(), ex);
        }
        if (!fromLog.isEmpty()) return List.copyOf(fromLog.values());
        String auditUserId = audit.getFoodsharingUserId();
        if (auditUserId != null && !auditUserId.isBlank() && !stillSignedIn.contains(auditUserId)) {
            return List.of(new LateCancellationRecipient(auditUserId, audit.getFoodsharingUserName()));
        }
        return List.of();
    }

    private static boolean sameMinute(Instant a, Instant b) {
        return a.truncatedTo(ChronoUnit.MINUTES).equals(b.truncatedTo(ChronoUnit.MINUTES));
    }

    private boolean isLatestDueLateCancellationAdvertisement(
            FoodsharingOpenSlotAdvertisementAutomation automation,
            List<FoodsharingOpenSlotAdvertisementAutomation> enabledAutomations,
            Instant pickupDate,
            Instant now) {
        return enabledAutomations.stream()
                .filter(candidate -> candidate.getAdminConnection().getId().equals(automation.getAdminConnection().getId())
                        && candidate.getStoreId() == automation.getStoreId()
                        && !pickupDate.minus(Duration.ofHours(candidate.getTriggerHoursBefore())).isAfter(now))
                .max(Comparator.comparing((FoodsharingOpenSlotAdvertisementAutomation candidate) -> pickupDate.minus(Duration.ofHours(candidate.getTriggerHoursBefore())))
                        .thenComparingInt(FoodsharingOpenSlotAdvertisementAutomation::getAdvertNumber))
                .map(candidate -> candidate.getId().equals(automation.getId()))
                .orElse(false);
    }

    private String lateCancellationSettingsKey(FoodsharingOpenSlotAdvertisementAutomation automation) {
        return automation.getAdminConnection().getId() + ":" + automation.getStoreId();
    }

    private void saveAdvertisementAudit(FoodsharingOpenSlotAdvertisementAutomation automation, Instant pickupDate, boolean dryRun, String status, String reason, String message, String error, Integer telegramMessageId, String foodsharingUserId, String foodsharingUserName) {
        FoodsharingOpenSlotAdvertisementAudit audit = new FoodsharingOpenSlotAdvertisementAudit();
        audit.setAutomation(automation);
        audit.setStoreId(automation.getStoreId());
        audit.setStoreName(automation.getStoreName());
        audit.setPickupDate(pickupDate);
        audit.setTriggerHoursBefore(automation.getTriggerHoursBefore());
        audit.setFoodsharingUserId(foodsharingUserId);
        audit.setFoodsharingUserName(foodsharingUserName);
        audit.setTelegramMessageId(telegramMessageId);
        audit.setStatus(status);
        audit.setDryRun(dryRun);
        audit.setMessage(message);
        audit.setReason(reason);
        audit.setError(error);
        advertisementAuditRepository.save(audit);
    }

    private UUID persistAdvertisementAudit(FoodsharingOpenSlotAdvertisementAutomation automation, Instant pickupDate, String status, String reason, String message, Integer telegramMessageId) {
        return telegramAdvertisementAuditTransaction.execute(transactionStatus -> {
            FoodsharingOpenSlotAdvertisementAudit audit = new FoodsharingOpenSlotAdvertisementAudit();
            audit.setAutomation(advertisementAutomationRepository.getReferenceById(automation.getId()));
            audit.setStoreId(automation.getStoreId());
            audit.setStoreName(automation.getStoreName());
            audit.setPickupDate(pickupDate);
            audit.setTriggerHoursBefore(automation.getTriggerHoursBefore());
            audit.setTelegramMessageId(telegramMessageId);
            audit.setStatus(status);
            audit.setReason(reason);
            audit.setMessage(message);
            return advertisementAuditRepository.saveAndFlush(audit).getId();
        });
    }

    /**
     * Changes an existing audit in its own short transaction. The advertisement run is one long transaction, and the same
     * audit is also changed in short transactions (e.g. {@link #persistLateCancellationAdvertisementAudit}); a copy made dirty
     * in the long one would fail its version check at commit and roll back the whole run.
     */
    private void updateAdvertisementAudit(UUID auditId, java.util.function.Consumer<FoodsharingOpenSlotAdvertisementAudit> change) {
        telegramAdvertisementAuditTransaction.executeWithoutResult(transactionStatus -> advertisementAuditRepository.findById(auditId)
                .ifPresent(audit -> {
                    change.accept(audit);
                    advertisementAuditRepository.save(audit);
                }));
    }

    private void persistLateCancellationAdvertisementAudit(UUID auditId, String status, String reason, String message, Integer telegramMessageId, List<LateCancellationRecipient> recipients) {
        telegramAdvertisementAuditTransaction.executeWithoutResult(transactionStatus -> {
            FoodsharingOpenSlotAdvertisementAudit audit = advertisementAuditRepository.findById(auditId).orElseThrow();
            if (!recipients.isEmpty()) {
                // Record who was actually reminded instead of the person that happened to be listed first at trigger time.
                audit.setFoodsharingUserId(recipients.getFirst().id());
                audit.setFoodsharingUserName(recipients.stream()
                        .map(r -> r.name() == null || r.name().isBlank() ? r.id() : r.name())
                        .collect(java.util.stream.Collectors.joining(", ")));
            }
            audit.setStatus(status);
            audit.setReason(reason);
            audit.setMessage(message);
            audit.setTelegramMessageId(telegramMessageId);
            audit.setError(null);
            audit.setSentAt(Instant.now());
            audit.setFilledAt(null);
            advertisementAuditRepository.saveAndFlush(audit);
        });
    }

    private void saveAdvertisementRunSummaryAudit(FoodsharingOpenSlotAdvertisementAutomation automation, Instant pickupDate, boolean dryRun, int checkedSlots, int messagesCount, int skippedSlots, String reason) {
        FoodsharingOpenSlotAdvertisementAudit audit = new FoodsharingOpenSlotAdvertisementAudit();
        audit.setAutomation(automation);
        audit.setStoreId(automation.getStoreId());
        audit.setStoreName(automation.getStoreName());
        audit.setPickupDate(pickupDate);
        audit.setTriggerHoursBefore(automation.getTriggerHoursBefore());
        audit.setStatus(dryRun ? "WOULD_SUMMARY" : "SUMMARY_SENT");
        audit.setDryRun(dryRun);
        audit.setReason(reason + " Checked " + checkedSlots + " slot" + (checkedSlots == 1 ? "" : "s") + ", would send " + messagesCount + " message" + (messagesCount == 1 ? "" : "s") + ", skipped " + skippedSlots + " slot" + (skippedSlots == 1 ? "" : "s") + ".");
        audit.setMessage("Messages that would be sent: " + messagesCount + ".");
        advertisementAuditRepository.save(audit);
    }

    private void validateRequestAutomation(RequestAutomationRequest request) {
        if (request.maximumDistanceKm() != null && request.maximumDistanceKm() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Maximum distance must not be negative."));
        }
        if (request.distanceRuleEnabled() && (request.maximumDistanceKm() == null || request.maximumDistanceKm() <= 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Maximum distance must be greater than 0 when the distance rule is enabled."));
        }
    }

    private double normalizeDistance(Double value) {
        return value == null ? 0.0 : value;
    }

    private int validateLateCancellationMessageMaximumHoursBeforePickup(int maximumHoursBeforePickup) {
        if (maximumHoursBeforePickup < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    List.of("Late-cancellation message maximum hours before pickup must not be negative."));
        }
        return maximumHoursBeforePickup;
    }

    private String formatDistance(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private void validateTemplateVariables(List<String>... messageGroups) {
        java.util.Set<String> allowed = java.util.Set.of("date", "dateDe", "weekday", "time", "datetime", "datetimeDe", "adminFoodsharingId", "adminProfileUrl");
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{\\{\\s*([^}\\s]+)\\s*}} ".trim());
        for (List<String> messages : messageGroups == null ? List.<List<String>>of() : java.util.Arrays.asList(messageGroups)) {
            for (String message : messages == null ? List.<String>of() : messages) {
                java.util.regex.Matcher matcher = pattern.matcher(message == null ? "" : message);
                while (matcher.find()) {
                    if (!allowed.contains(matcher.group(1))) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Unknown template variable: {{" + matcher.group(1) + "}}"));
                    }
                }
            }
        }
    }

    private String combineAdvertisementMessages(String storeMessage, String telegramMessage) {
        if (storeMessage == null || storeMessage.isBlank()) {
            return telegramMessage;
        }
        if (telegramMessage == null || telegramMessage.isBlank()) {
            return storeMessage;
        }
        return "Store chat:\n" + storeMessage + "\n\nTelegram:\n" + telegramMessage;
    }

    private boolean isBlankMessages(List<String> messages) {
        return messages == null || messages.stream().allMatch(message -> message == null || message.isBlank());
    }

    private void invalidateStoreCaches(FoodsharingAdminConnection connection, long storeId) {
        deleteStorePickupsCache(connection, storeId);
        deleteStoreMembersCache(connection, storeId);
    }

    private Integer sendTelegram(FoodsharingOpenSlotAdvertisementAutomation automation, String text) {
        String token = telegramToken(automation.getAdminConnection());
        if (token == null || token.isBlank()) return null;
        if (!outboundGuard.allowTelegramChat(automation.getTelegramChatId(), text)) return null;
        Object response = telegramClient.post().uri("/bot{token}/sendMessage", token).contentType(MediaType.APPLICATION_JSON).body(Map.of("chat_id", automation.getTelegramChatId(), "text", text)).retrieve().body(Object.class);
        if (response instanceof Map<?, ?> root && root.get("result") instanceof Map<?, ?> result && result.get("message_id") instanceof Number messageId) {
            return messageId.intValue();
        }
        return null;
    }

    private void deleteTelegramAdvertisementsForFilledSlot(FoodsharingOpenSlotAdvertisementAutomation automation, Instant pickupDate) {
        deleteTelegramAdvertisements(automation, advertisementAuditRepository.findAllByAutomationAndPickupDateAndTelegramMessageIdIsNotNullAndTelegramDeletedAtIsNull(automation, pickupDate));
    }

    private void deletePreviousTelegramAdvertisementsSafely(FoodsharingOpenSlotAdvertisementAutomation automation, Instant pickupDate, UUID currentAuditId) {
        for (FoodsharingOpenSlotAdvertisementAudit audit : advertisementAuditRepository.findAllByAutomationAdminConnectionAndPickupDateAndTelegramMessageIdIsNotNullAndTelegramDeletedAtIsNull(automation.getAdminConnection(), pickupDate)) {
            if (audit.getId().equals(currentAuditId)) continue;
            try {
                deleteTelegramAdvertisements(audit.getAutomation(), List.of(audit));
            } catch (RuntimeException exception) {
                log.warn("Could not delete previous Telegram advertisement message {} for pickup {}. The newly sent advertisement is retained.", audit.getTelegramMessageId(), pickupDate, exception);
            }
        }
    }

    private void deleteTelegramAdvertisementsForPassedSlots(FoodsharingOpenSlotAdvertisementAutomation automation, Instant now) {
        deleteTelegramAdvertisements(automation, advertisementAuditRepository.findAllByAutomationAndPickupDateBeforeAndTelegramMessageIdIsNotNullAndTelegramDeletedAtIsNull(automation, now));
    }

    private void deleteTelegramAdvertisements(FoodsharingOpenSlotAdvertisementAutomation automation, List<FoodsharingOpenSlotAdvertisementAudit> audits) {
        if (automation.getTelegramChatId() == null || audits.isEmpty()) return;
        if (!outboundGuard.allowTelegramChat(automation.getTelegramChatId(), "delete " + audits.size() + " advertisement message(s)")) return;
        String token = telegramToken(automation.getAdminConnection());
        if (token == null || token.isBlank()) return;
        for (FoodsharingOpenSlotAdvertisementAudit audit : audits) {
            try {
                telegramClient.post().uri("/bot{token}/deleteMessage", token).contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("chat_id", automation.getTelegramChatId(), "message_id", audit.getTelegramMessageId()))
                        .retrieve().toBodilessEntity();
            } catch (HttpClientErrorException exception) {
                if (!isTelegramMessageAlreadyGone(exception)) {
                    throw exception;
                }
                log.info("Telegram advertisement message {} was already deleted; marking cleanup complete.", audit.getTelegramMessageId());
            }
            Instant deletedAt = Instant.now();
            updateAdvertisementAudit(audit.getId(), changed -> changed.setTelegramDeletedAt(deletedAt));
        }
    }

    private boolean isTelegramMessageAlreadyGone(HttpClientErrorException exception) {
        return exception.getStatusCode().value() == 400
                && exception.getResponseBodyAsString().contains("message to delete not found");
    }


    public List<TelegramChatView> telegramChats() {
        FoodsharingAdminConnection connection = requireConnection();
        String token = telegramToken(connection);
        if (token == null || token.isBlank()) return List.of();
        Object response = telegramClient.get().uri("/bot{token}/getUpdates", token).retrieve().body(Object.class);
        Map<String, TelegramChatView> chats = new LinkedHashMap<>();
        if (response instanceof Map<?, ?> root && root.get("result") instanceof List<?> updates) {
            for (Object update : updates) {
                if (!(update instanceof Map<?, ?> updateMap)) continue;
                Object message = firstPresent(updateMap, "message", "channel_post", "my_chat_member");
                if (message instanceof Map<?, ?> messageMap) {
                    Object chat = messageMap.get("chat");
                    if (chat instanceof Map<?, ?> chatMap) {
                        String id = String.valueOf(chatMap.get("id"));
                        String title = String.valueOf(firstPresent(chatMap, "title", "username", "first_name"));
                        String type = String.valueOf(firstPresent(chatMap, "type"));
                        if (!id.isBlank()) chats.put(id, new TelegramChatView(id, title == null || title.equals("null") ? id : title, type == null || type.equals("null") ? "" : type));
                    }
                }
            }
        }
        return new ArrayList<>(chats.values());
    }

    private String telegramToken(FoodsharingAdminConnection connection) {
        if (connection.getTelegramBotTokenCiphertext() != null && !connection.getTelegramBotTokenCiphertext().isBlank()) {
            return cryptoService.decrypt(connection.getTelegramBotTokenCiphertext());
        }
        return null;
    }

    private Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) return map.get(key);
        }
        return null;
    }

    @Transactional(readOnly = true)
    public List<AuditView> audit(boolean onlyMine) {
        User user = currentActorService.requireAutomationUser();
        return audit(requireBezirk(user), onlyMine);
    }

    @Transactional(readOnly = true)
    public List<AuditView> audit(String bezirkSlug, boolean onlyMine) {
        return audit(bezirkSlug, onlyMine, false);
    }

    @Transactional(readOnly = true)
    public List<AuditView> audit(String bezirkSlug, boolean onlyMine, boolean excludeConfirmed) {
        User user = currentActorService.requireAutomationUser();
        return audit(requireBezirk(bezirkSlug, user), onlyMine, excludeConfirmed);
    }

    private List<AuditView> audit(Bezirk bezirk, boolean onlyMine) {
        return audit(bezirk, onlyMine, false);
    }

    private List<AuditView> audit(Bezirk bezirk, boolean onlyMine, boolean excludeConfirmed) {
        if (excludeConfirmed) {
            // Hide routine confirmations (and their dry-run counterpart) so the remaining decisions can be reviewed.
            List<FoodsharingPickupAutomationDecision> excluded = List.of(FoodsharingPickupAutomationDecision.CONFIRMED, FoodsharingPickupAutomationDecision.WOULD_CONFIRM);
            Map<Long, String> storeNames = automationRepository.findAllByBezirk(bezirk).stream()
                    .collect(java.util.stream.Collectors.toMap(FoodsharingStoreAutomation::getStoreId, FoodsharingStoreAutomation::getStoreName, (left, right) -> left));
            List<FoodsharingPickupAutomationAudit> audits = onlyOwnDecisions(onlyMine)
                    ? currentConnection().map(connection -> auditRepository.findTop100ByBezirkAndAdminConnectionAndDecisionNotInOrderByCreatedAtDesc(bezirk, connection, excluded)).orElseGet(List::of)
                    : auditRepository.findTop100ByBezirkAndDecisionNotInOrderByCreatedAtDesc(bezirk, excluded);
            return audits.stream().map(a -> view(a, storeNames)).toList();
        }
        Map<Long, String> storeNames = automationRepository.findAllByBezirk(bezirk).stream()
                .collect(java.util.stream.Collectors.toMap(FoodsharingStoreAutomation::getStoreId, FoodsharingStoreAutomation::getStoreName, (left, right) -> left));
        List<FoodsharingPickupAutomationAudit> audits = onlyOwnDecisions(onlyMine)
                ? currentConnection().map(connection -> auditRepository.findTop100ByBezirkAndAdminConnectionOrderByCreatedAtDesc(bezirk, connection)).orElseGet(List::of)
                : auditRepository.findTop100ByBezirkOrderByCreatedAtDesc(bezirk);
        return audits.stream().map(a -> view(a, storeNames)).toList();
    }

    @Transactional
    public List<StorePickupUserView> futurePickupUsers() {
        FoodsharingAdminConnection connection = requireConnection();
        return futurePickupUsers(connection, requireBezirk(connection.getAdminUser()));
    }

    @Transactional
    public List<StorePickupUserView> futurePickupUsers(String bezirkSlug) {
        return futurePickupUsers(bezirkSlug, false);
    }

    @Transactional
    public List<StorePickupUserView> futurePickupUsers(String bezirkSlug, boolean forceRefresh) {
        FoodsharingAdminConnection connection = requireConnection();
        return futurePickupUsers(connection, requireBezirk(bezirkSlug, connection.getAdminUser()), forceRefresh);
    }

    private List<StorePickupUserView> futurePickupUsers(FoodsharingAdminConnection connection, Bezirk bezirk) {
        return futurePickupUsers(connection, bezirk, false);
    }

    private List<StorePickupUserView> futurePickupUsers(FoodsharingAdminConnection connection, Bezirk bezirk, boolean forceRefresh) {
        Duration ttl = Duration.parse(appProperties.getFoodsharing().getAutomation().getFuturePickupCacheTtl());
        Instant now = Instant.now();
        var existingCache = futurePickupUsersCacheRepository.findByAdminConnectionAndBezirk(connection, bezirk);
        if (!forceRefresh && existingCache.isPresent()) {
            FoodsharingFuturePickupUsersCache cache = existingCache.get();
            if (cache.getRefreshedAt() != null && cache.getRefreshedAt().plus(ttl).isAfter(now)) {
                try {
                    List<StorePickupUserView> users = deserializeFuturePickupUsers(cache.getPayloadJson());
                    if (users.stream().flatMap(user -> user.futurePickups().stream()).allMatch(pickup -> pickup.validationErrors() != null)) {
                        return users;
                    }
                } catch (RuntimeException ignored) {
                    // Fall through and refresh from Foodsharing if the cached payload is unreadable.
                }
            }
        }
        List<StorePickupUserView> fresh = loadFuturePickupUsers(connection, bezirk);
        String payloadJson = serializeFuturePickupUsers(fresh);
        // Own short transaction: loading takes long, and another request may refresh the same row meanwhile.
        try {
            cacheTransaction.executeWithoutResult(status -> {
                FoodsharingFuturePickupUsersCache cache = futurePickupUsersCacheRepository.findByAdminConnectionAndBezirk(connection, bezirk)
                        .orElseGet(FoodsharingFuturePickupUsersCache::new);
                cache.setAdminConnection(connection);
                cache.setBezirk(bezirk);
                cache.setRefreshedAt(now);
                cache.setPayloadJson(payloadJson);
                futurePickupUsersCacheRepository.save(cache);
            });
        } catch (RuntimeException ex) {
            log.info("Foodsharing future pickups cache for bezirk {} not saved; it was changed at the same time ({}).", bezirk.getSlug(), ex.getClass().getSimpleName());
        }
        return fresh;
    }

    private List<StorePickupUserView> loadFuturePickupUsers(FoodsharingAdminConnection connection, Bezirk bezirk) {
        Map<String, StorePickupUserBuilder> users = new LinkedHashMap<>();
        Map<Long, FoodsharingStoreAutomation> automationsByStoreId = automationRepository.findAllByBezirkAndEnabledTrue(bezirk).stream()
                .filter(automation -> automation.getAdminConnection().getId().equals(connection.getId()))
                .collect(java.util.stream.Collectors.toMap(FoodsharingStoreAutomation::getStoreId, automation -> automation));
        Map<String, List<FoodsharingPickupModels.Pickup>> initialPickupsCache = new HashMap<>();
        Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache = new HashMap<>();
        Map<String, FoodsharingUserInfo> foodsharingUserInfoCache = new HashMap<>();
        Instant now = Instant.now();
        for (FoodsharingPickupModels.Store store : managedStores(connection)) {
            List<FoodsharingPickupModels.Pickup> storePickups = storePickups(connection, store.id());
            String cacheKey = cacheKey(connection, store.id());
            initialPickupsCache.put(cacheKey, copyPickups(storePickups));
            livePickupsCache.put(cacheKey, copyPickups(storePickups));
            FoodsharingStoreAutomation automation = automationsByStoreId.get(store.id());
            for (FoodsharingPickupModels.Pickup pickup : storePickups) {
                if (pickup.date().isBefore(now)) {
                    continue;
                }
                for (FoodsharingPickupModels.PickupUser pickupUser : pickup.users()) {
                    if (pickupUser.id().isBlank()) {
                        continue;
                    }
                    StorePickupUserBuilder builder = users.computeIfAbsent(pickupUser.id(), id -> new StorePickupUserBuilder(id, pickupUser.name()));
                    builder.addPickup(new StorePickupView(store.id(), store.name(), pickup.date(), pickupUser.confirmed(), validationErrors(automation, pickup.date(), pickupUser.id(), livePickupsCache, initialPickupsCache, foodsharingUserInfoCache)));
                }
            }
        }
        return users.values().stream()
                .map(StorePickupUserBuilder::build)
                .sorted(Comparator.comparingInt(StorePickupUserView::futurePickupCount).reversed()
                        .thenComparing(StorePickupUserView::name, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(StorePickupUserView::foodsharingUserId))
                .toList();
    }

    private List<String> validationErrors(FoodsharingStoreAutomation automation, Instant pickupDate, String userId, Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache, Map<String, List<FoodsharingPickupModels.Pickup>> initialPickupsCache, Map<String, FoodsharingUserInfo> foodsharingUserInfoCache) {
        if (automation == null) {
            return List.of();
        }
        FoodsharingPickupModels.Decision decision = evaluate(automation, pickupDate, userId, livePickupsCache, initialPickupsCache, foodsharingUserInfoCache);
        return decision.allowed() ? List.of() : decision.reasons();
    }

    /** Stores the account manages or is Botschafter of. */
    private List<FoodsharingPickupModels.Store> managedStores(FoodsharingAdminConnection connection) {
        return storeAccessService.accessibleStores(connection);
    }

    private List<StorePickupUserView> deserializeFuturePickupUsers(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<List<StorePickupUserView>>() {});
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.UNEXPECTED_ERROR, List.of("Could not read cached future pickup users."));
        }
    }

    private String serializeFuturePickupUsers(List<StorePickupUserView> users) {
        try {
            return objectMapper.writeValueAsString(users);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.UNEXPECTED_ERROR, List.of("Could not store future pickup users cache."));
        }
    }

    /**
     * Upcoming pickups per store from the pickup caches of the Bezirk's automation accounts. Reads only cached data,
     * so it never calls Foodsharing; the caches are refreshed by the automations. The cleaning store is left out.
     */
    @Transactional(readOnly = true)
    public List<StoreUpcomingPickupsView> upcomingStorePickups(Bezirk bezirk, Instant from, Instant to) {
        Map<Long, String> storeNames = new HashMap<>();
        automationRepository.findAllByBezirk(bezirk).forEach(automation -> putStoreName(storeNames, automation.getStoreId(), automation.getStoreName()));
        advertisementAutomationRepository.findAll().forEach(automation -> putStoreName(storeNames, automation.getStoreId(), automation.getStoreName()));
        requestAutomationRepository.findAll().forEach(automation -> putStoreName(storeNames, automation.getStoreId(), automation.getStoreName()));
        Map<Long, FoodsharingStorePickupsCache> latestByStore = new HashMap<>();
        for (FoodsharingStorePickupsCache cache : storePickupsCacheRepository.findAllByAdminConnectionAdminUserBezirk(bezirk)) {
            if (bezirk.getCleaningStoreId() != null && bezirk.getCleaningStoreId() == cache.getStoreId()) continue;
            latestByStore.merge(cache.getStoreId(), cache, (left, right) -> right.getRefreshedAt() != null
                    && (left.getRefreshedAt() == null || right.getRefreshedAt().isAfter(left.getRefreshedAt())) ? right : left);
        }
        List<StoreUpcomingPickupsView> result = new ArrayList<>();
        for (FoodsharingStorePickupsCache cache : latestByStore.values()) {
            List<FoodsharingPickupModels.Pickup> pickups;
            try {
                pickups = deserializeStorePickups(cache.getPayloadJson());
            } catch (RuntimeException exception) {
                continue;
            }
            int pickupCount = 0;
            int totalSlots = 0;
            int occupiedSlots = 0;
            int pickupsWithOpenSlots = 0;
            for (FoodsharingPickupModels.Pickup pickup : pickups) {
                if (pickup.date().isBefore(from) || !pickup.date().isBefore(to)) continue;
                int occupied = pickup.users().size();
                int total = Math.max(pickup.totalSlots(), occupied);
                pickupCount++;
                totalSlots += total;
                occupiedSlots += occupied;
                if (occupied < total) pickupsWithOpenSlots++;
            }
            if (pickupCount > 0) {
                result.add(new StoreUpcomingPickupsView(cache.getStoreId(), storeNames.getOrDefault(cache.getStoreId(), "#" + cache.getStoreId()),
                        cache.getRefreshedAt(), pickupCount, pickupsWithOpenSlots, totalSlots, occupiedSlots));
            }
        }
        return result;
    }

    private static void putStoreName(Map<Long, String> storeNames, long storeId, String storeName) {
        if (storeName != null && !storeName.isBlank()) storeNames.putIfAbsent(storeId, storeName);
    }

    public record StoreUpcomingPickupsView(long storeId, String storeName, Instant refreshedAt, int pickups,
            int pickupsWithOpenSlots, int totalSlots, int occupiedSlots) {}

    private List<FoodsharingPickupModels.Pickup> deserializeStorePickups(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<List<FoodsharingPickupModels.Pickup>>() {});
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.UNEXPECTED_ERROR, List.of("Could not read cached store pickups."));
        }
    }

    private String serializeStorePickups(List<FoodsharingPickupModels.Pickup> pickups) {
        try {
            return objectMapper.writeValueAsString(pickups);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.UNEXPECTED_ERROR, List.of("Could not store cached store pickups."));
        }
    }

    private List<FoodsharingPickupModels.StoreMember> storeMembers(FoodsharingAdminConnection connection, long storeId) {
        Duration ttl = Duration.parse(appProperties.getFoodsharing().getAutomation().getStoreMembersCacheTtl());
        Instant now = Instant.now();
        var cached = storeMembersCacheRepository.findSnapshot(connection, storeId);
        if (cached.isPresent() && cached.get().getRefreshedAt() != null && cached.get().getRefreshedAt().plus(ttl).isAfter(now)) {
            try {
                return deserializeStoreMembers(cached.get().getPayloadJson());
            } catch (RuntimeException ignored) {
                // Fall through and refresh from Foodsharing if the cached payload is unreadable.
            }
        }
        List<FoodsharingPickupModels.StoreMember> fresh = client.members(connection, storeId);
        String payloadJson = serializeStoreMembers(fresh);
        try {
            cacheTransaction.executeWithoutResult(status -> {
                FoodsharingStoreMembersCache cache = storeMembersCacheRepository.findByAdminConnectionAndStoreId(connection, storeId)
                        .orElseGet(FoodsharingStoreMembersCache::new);
                cache.setAdminConnection(connection);
                cache.setStoreId(storeId);
                cache.setRefreshedAt(now);
                cache.setPayloadJson(payloadJson);
                storeMembersCacheRepository.save(cache);
            });
        } catch (RuntimeException ex) {
            log.info("Foodsharing members cache for store {} not saved; it was changed at the same time ({}).", storeId, ex.getClass().getSimpleName());
        }
        return fresh;
    }

    private void deleteStoreMembersCache(FoodsharingAdminConnection connection, long storeId) {
        try {
            cacheTransaction.executeWithoutResult(status -> storeMembersCacheRepository.deleteByAdminConnectionAndStoreId(connection, storeId));
        } catch (RuntimeException ex) {
            log.warn("Foodsharing members cache for store {} could not be dropped.", storeId, ex);
        }
    }

    private List<FoodsharingPickupModels.StoreMember> deserializeStoreMembers(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<List<FoodsharingPickupModels.StoreMember>>() {});
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.UNEXPECTED_ERROR, List.of("Could not read cached store members."));
        }
    }

    private String serializeStoreMembers(List<FoodsharingPickupModels.StoreMember> members) {
        try {
            return objectMapper.writeValueAsString(members);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.UNEXPECTED_ERROR, List.of("Could not store cached store members."));
        }
    }


    @Transactional(readOnly = true)
    public List<CleaningRuleExemptionView> cleaningRuleExemptions() {
        User user = currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        return cleaningRuleExemptions(requireBezirk(user));
    }

    @Transactional(readOnly = true)
    public List<CleaningRuleExemptionView> cleaningRuleExemptions(String bezirkSlug) {
        User user = currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        return cleaningRuleExemptions(requireBezirk(bezirkSlug, user));
    }

    private List<CleaningRuleExemptionView> cleaningRuleExemptions(Bezirk bezirk) {
        return cleaningRuleExemptionRepository.findAllByBezirkOrderByFoodsharingIdAsc(bezirk).stream()
                .map(this::view)
                .toList();
    }

    @Transactional
    public CleaningRuleExemptionView saveCleaningRuleExemption(CleaningRuleExemptionRequest request) {
        User user = currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        return saveCleaningRuleExemption(requireBezirk(user), request);
    }

    @Transactional
    public CleaningRuleExemptionView saveCleaningRuleExemption(String bezirkSlug, CleaningRuleExemptionRequest request) {
        User user = currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        return saveCleaningRuleExemption(requireBezirk(bezirkSlug, user), request);
    }

    private CleaningRuleExemptionView saveCleaningRuleExemption(Bezirk bezirk, CleaningRuleExemptionRequest request) {
        String foodsharingId = normalizeRequired(request.foodsharingId());
        String reason = normalizeRequired(request.reason());
        FoodsharingCleaningRuleExemption exemption = cleaningRuleExemptionRepository.findByBezirkAndFoodsharingId(bezirk, foodsharingId)
                .orElseGet(FoodsharingCleaningRuleExemption::new);
        exemption.setBezirk(bezirk);
        exemption.setFoodsharingId(foodsharingId);
        exemption.setReason(reason);
        return view(cleaningRuleExemptionRepository.save(exemption));
    }

    @Transactional
    public void deleteCleaningRuleExemption(UUID exemptionId) {
        User user = currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        deleteCleaningRuleExemption(requireBezirk(user), exemptionId);
    }

    @Transactional
    public void deleteCleaningRuleExemption(String bezirkSlug, UUID exemptionId) {
        User user = currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        deleteCleaningRuleExemption(requireBezirk(bezirkSlug, user), exemptionId);
    }

    private void deleteCleaningRuleExemption(Bezirk bezirk, UUID exemptionId) {
        FoodsharingCleaningRuleExemption exemption = cleaningRuleExemptionRepository.findByIdAndBezirk(exemptionId, bezirk)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND));
        cleaningRuleExemptionRepository.delete(exemption);
    }

    @Transactional
    public FoodsharingPickupModels.RunResult run(boolean dryRun) {
        User user = currentActorService.requireAutomationSlotApprovalUser();
        return run(requireBezirk(user), dryRun);
    }

    @Transactional
    public FoodsharingPickupModels.RunResult run(String bezirkSlug, boolean dryRun) {
        User user = currentActorService.requireAutomationSlotApprovalUser();
        return run(requireBezirk(bezirkSlug, user), dryRun);
    }

    private FoodsharingPickupModels.RunResult run(Bezirk bezirk, boolean dryRun) {
        return run(automationRepository.findAllByBezirkAndEnabledTrue(bezirk),
                dryRun || appProperties.getFoodsharing().getAutomation().isDryRun());
    }

    @Transactional
    public void scheduledRun() {
        if (!appProperties.getFoodsharing().getAutomation().isEnabled()) {
            return;
        }
        boolean dryRun = appProperties.getFoodsharing().getAutomation().isDryRun();
        List<FoodsharingStoreAutomation> automations = automationRepository.findAllByEnabledTrue();
        log.debug("Foodsharing automation poll started (dryRun={}, enabledAutomations={})", dryRun, automations.size());
        if (automations.isEmpty()) {
            log.debug("Foodsharing automation poll skipped (dryRun={}, enabledAutomations=0)", dryRun);
            return;
        }
        FoodsharingPickupModels.RunResult result = run(automations, dryRun);
        // Confirms and declines are in the audit; the log only shows a run when something failed.
        if (result.failed() > 0) {
            log.warn("Foodsharing automation poll finished (evaluated={}, confirmed={}, declined={}, failed={}, dryRun={})", result.evaluated(), result.confirmed(), result.declined(), result.failed(), result.dryRun());
        } else {
            log.debug("Foodsharing automation poll finished (evaluated={}, confirmed={}, declined={}, dryRun={})", result.evaluated(), result.confirmed(), result.declined(), result.dryRun());
        }
    }

    private FoodsharingPickupModels.RunResult run(List<FoodsharingStoreAutomation> automations, boolean forcedDryRun) {
        int evaluated=0, confirmed=0, declined=0, failed=0; List<String> messages = new ArrayList<>();
        if (automations.isEmpty()) {
            log.debug("Foodsharing automation run skipped (dryRun={}, automations=0)", forcedDryRun);
            return new FoodsharingPickupModels.RunResult(0, 0, 0, 0, forcedDryRun, messages);
        }
        Map<java.util.UUID, FoodsharingAdminConnection> connectionsById = automations.stream()
                .map(FoodsharingStoreAutomation::getAdminConnection)
                .collect(java.util.stream.Collectors.toMap(
                        FoodsharingAdminConnection::getId,
                        connection -> connection,
                        (left, right) -> left,
                        LinkedHashMap::new));
        String connectionSummary = connectionsById.values().stream()
                .map(connection -> "admin=" + connection.getAdminUser().getId() + ",foodsharingUserId=" + connection.getFoodsharingUserId() + ",email=" + connection.getFoodsharingEmail())
                .collect(java.util.stream.Collectors.joining(" | "));
        log.debug("Foodsharing automation run started (dryRun={}, automations={}, connections={})", forcedDryRun, automations.size(), connectionSummary);
        Map<String, List<FoodsharingPickupModels.Pickup>> initialPickupsCache = new HashMap<>();
        // Live stores decide on what really happened; dry-run stores also see the other dry-run stores' simulated decisions.
        PickupCache livePickupsCache = new PickupCache(false);
        PickupCache simulatedPickupsCache = new PickupCache(true);
        Map<String, FoodsharingUserInfo> foodsharingUserInfoCache = new HashMap<>();
        boolean effectiveDryRunUsed = forcedDryRun;
        for (FoodsharingStoreAutomation a : automations) {
            boolean effectiveDryRun = forcedDryRun || a.isDryRunEnabled();
            effectiveDryRunUsed = effectiveDryRunUsed || effectiveDryRun;
            log.debug("Foodsharing automation evaluating storeId={} storeName={} admin={} foodsharingUserId={} email={} dryRun={}", a.getStoreId(), a.getStoreName(), a.getAdminConnection().getAdminUser().getId(), a.getAdminConnection().getFoodsharingUserId(), a.getAdminConnection().getFoodsharingEmail(), effectiveDryRun);
            StoreRunTally tally;
            try {
                tally = storeTransaction.execute(status -> runStore(a, effectiveDryRun, initialPickupsCache, livePickupsCache, simulatedPickupsCache, foodsharingUserInfoCache));
            } catch (RuntimeException ex) {
                if (storeFailureStopsRun("slot", a.getStoreId(), a.getStoreName(), ex)) break;
                failed++;
                messages.add("Store " + a.getStoreName() + ": " + ex.getMessage());
                continue;
            }
            evaluated += tally.evaluated;
            confirmed += tally.confirmed;
            declined += tally.declined;
            failed += tally.failed;
            messages.addAll(tally.messages);
            if (tally.siteOffline) break;
        }
        log.debug("Foodsharing automation run finished (evaluated={}, confirmed={}, declined={}, failed={}, dryRun={}, connections={})", evaluated, confirmed, declined, failed, effectiveDryRunUsed, connectionSummary);
        return new FoodsharingPickupModels.RunResult(evaluated, confirmed, declined, failed, effectiveDryRunUsed, messages);
    }

    /** Counts of one store's slot run. {@code siteOffline}: Foodsharing went down, so the whole run stops after this store. */
    private static final class StoreRunTally {
        int evaluated, confirmed, declined, failed;
        boolean siteOffline;
        final List<String> messages = new ArrayList<>();
    }

    /** Runs one store's slot automation. Called in its own transaction by {@code run}. */
    private StoreRunTally runStore(FoodsharingStoreAutomation a, boolean effectiveDryRun, Map<String, List<FoodsharingPickupModels.Pickup>> initialPickupsCache, PickupCache livePickupsCache, PickupCache simulatedPickupsCache, Map<String, FoodsharingUserInfo> foodsharingUserInfoCache) {
        StoreRunTally tally = new StoreRunTally();
        String cacheKey = cacheKey(a.getAdminConnection(), a.getStoreId());
        List<FoodsharingPickupModels.Pickup> storePickups = storePickups(a.getAdminConnection(), a.getStoreId()).stream()
                .sorted(Comparator.comparing(FoodsharingPickupModels.Pickup::date))
                .toList();
        initialPickupsCache.putIfAbsent(cacheKey, copyPickups(storePickups));
        livePickupsCache.putIfAbsent(cacheKey, copyPickups(storePickups));
        simulatedPickupsCache.putIfAbsent(cacheKey, copyPickups(storePickups));
        PickupCache decisionCache = effectiveDryRun ? simulatedPickupsCache : livePickupsCache;
        for (var p : storePickups) for (var u : p.users()) if (!u.confirmed()) {
        tally.evaluated++;
        try {
            var decision = evaluate(a, p.date(), u.id(), u.name(), decisionCache, initialPickupsCache, foodsharingUserInfoCache); String reason = String.join("\n", decision.reasons());
            if (decision.deferred()) {
                // Waiting for the grace period of a linked-store rule: decided on a later run, so no audit yet.
                log.debug("Foodsharing automation decision=defer dryRun={} storeId={} pickupDate={} userId={} reason={}", effectiveDryRun, a.getStoreId(), p.date(), u.id(), reason);
                continue;
            }
            if (decision.skipped()) {
                log.debug("Foodsharing automation decision=skip dryRun={} admin={} foodsharingUserId={} email={} storeId={} pickupDate={} userId={} userName={}", effectiveDryRun, a.getAdminConnection().getAdminUser().getId(), a.getAdminConnection().getFoodsharingUserId(), a.getAdminConnection().getFoodsharingEmail(), a.getStoreId(), p.date(), u.id(), u.name());
                saveAudit(a, u.id(), u.name(), p.date(), effectiveDryRun, FoodsharingPickupAutomationDecision.SKIPPED, reason, null, null);
                continue;
            }
            if (!decision.allowed()) {
                // Foodsharing wraps the text in its own "Liebe/r …, wir haben dich aus der …-Abholung … ausgetragen.": no second greeting.
                reason = decision.reasons().stream().map(FoodsharingPickupAutomationService::withoutLeadingGreeting).collect(java.util.stream.Collectors.joining("\n"));
                reason += "\n\n" + declineFooter();
            }
            String userMessage = decision.userMessage() != null ? decision.userMessage() : (decision.allowed() ? null : reason);
            if (decision.allowed()) {
                log.debug("Foodsharing automation decision=confirm dryRun={} admin={} foodsharingUserId={} email={} storeId={} pickupDate={} userId={} userName={}", effectiveDryRun, a.getAdminConnection().getAdminUser().getId(), a.getAdminConnection().getFoodsharingUserId(), a.getAdminConnection().getFoodsharingEmail(), a.getStoreId(), p.date(), u.id(), u.name());
                if (!effectiveDryRun) {
                    client.confirm(a.getAdminConnection(), a.getStoreId(), p.date(), u.id());
                    if (userMessage != null && !userMessage.isBlank()) {
                        messageService.send(u.id(), userMessage("message.automation.subject"), userMessage);
                    }
                }
                tally.confirmed++;
                saveAudit(a, u.id(), u.name(), p.date(), effectiveDryRun, effectiveDryRun ? FoodsharingPickupAutomationDecision.WOULD_CONFIRM : FoodsharingPickupAutomationDecision.CONFIRMED, reason, userMessage, null);
                if (!effectiveDryRun) updateLiveCache(livePickupsCache, cacheKey, p.date(), u.id(), true);
                updateLiveCache(simulatedPickupsCache, cacheKey, p.date(), u.id(), true);
            }
            else {
                log.debug("Foodsharing automation decision=decline dryRun={} admin={} foodsharingUserId={} email={} storeId={} pickupDate={} userId={} userName={}", effectiveDryRun, a.getAdminConnection().getAdminUser().getId(), a.getAdminConnection().getFoodsharingUserId(), a.getAdminConnection().getFoodsharingEmail(), a.getStoreId(), p.date(), u.id(), u.name());
                if (!effectiveDryRun) client.decline(a.getAdminConnection(), a.getStoreId(), p.date(), u.id(), reason);
                tally.declined++;
                saveAudit(a, u.id(), u.name(), p.date(), effectiveDryRun, effectiveDryRun ? FoodsharingPickupAutomationDecision.WOULD_DECLINE : FoodsharingPickupAutomationDecision.DECLINED, reason, userMessage, null);
                if (!effectiveDryRun) updateLiveCache(livePickupsCache, cacheKey, p.date(), u.id(), false);
                updateLiveCache(simulatedPickupsCache, cacheKey, p.date(), u.id(), false);
            }
        } catch (RuntimeException ex) {
            // Site offline: nothing was changed for this sign-up, so no FAILED audit; it is retried once Foodsharing is back.
            if (availability.reportFailure("slot", ex)) { tally.siteOffline = true; return tally; }
            log.error("Foodsharing slot automation failed for store {} ({}) pickupDate={} userId={} userName={}", a.getStoreId(), a.getStoreName(), p.date(), u.id(), u.name(), ex);
            tally.failed++; tally.messages.add(ex.getMessage()); saveAudit(a, u.id(), u.name(), p.date(), effectiveDryRun, FoodsharingPickupAutomationDecision.FAILED, "Automation failed", null, ex.getMessage()); }
        }
        return tally;
    }

    private FoodsharingPickupModels.Decision evaluate(FoodsharingStoreAutomation a, Instant pickupDate, String userId, Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache, Map<String, List<FoodsharingPickupModels.Pickup>> initialPickupsCache) {
        return evaluate(a, pickupDate, userId, null, livePickupsCache, initialPickupsCache, new HashMap<>());
    }

    private FoodsharingPickupModels.Decision evaluate(FoodsharingStoreAutomation a, Instant pickupDate, String userId, Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache, Map<String, List<FoodsharingPickupModels.Pickup>> initialPickupsCache, Map<String, FoodsharingUserInfo> foodsharingUserInfoCache) {
        return evaluate(a, pickupDate, userId, null, livePickupsCache, initialPickupsCache, foodsharingUserInfoCache);
    }

    private FoodsharingPickupModels.Decision evaluate(FoodsharingStoreAutomation a, Instant pickupDate, String userId, String userName, Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache, Map<String, List<FoodsharingPickupModels.Pickup>> initialPickupsCache, Map<String, FoodsharingUserInfo> foodsharingUserInfoCache) {
        return evaluate(a, pickupDate, userId, userName, livePickupsCache, initialPickupsCache, foodsharingUserInfoCache, Set.of(a.getStoreId()));
    }

    /**
     * {@code evaluatingStores} are the stores whose pending pickup led to this evaluation (linked-store look-ahead).
     * A pending pickup in one of them counts for the linked-store rule, so mutually linked sign-ups can be confirmed together.
     */
    private FoodsharingPickupModels.Decision evaluate(FoodsharingStoreAutomation a, Instant pickupDate, String userId, String userName, Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache, Map<String, List<FoodsharingPickupModels.Pickup>> initialPickupsCache, Map<String, FoodsharingUserInfo> foodsharingUserInfoCache, Set<Long> evaluatingStores) {
        List<String> reasons = new ArrayList<>();
        String cleaningOverrideMessage = null;
        Instant now = Instant.now();
        LocalDate pickupLocalDate = pickupDate.atZone(SWISS_ZONE).toLocalDate();
        if (excludedDateRepository.existsByAutomationAndExcludedDate(a, pickupLocalDate)) {
            return new FoodsharingPickupModels.Decision(false, true, List.of(userMessage("message.automation.manual-date-exclusion")), null);
        }
        if (a.isPublicHolidayRuleEnabled()) {
            try {
                java.util.Optional<String> holidayName = publicHolidayCalendar.holidayName(pickupLocalDate);
                if (holidayName.isPresent()) {
                    return new FoodsharingPickupModels.Decision(false, true, List.of(userMessage("message.automation.public-holiday", holidayName.get())), null);
                }
            } catch (SwissPublicHolidayCalendar.PublicHolidayCalendarUnavailableException exception) {
                log.warn("Foodsharing public-holiday calendar unavailable for bezirk={} date={}; skipping automatic approval", a.getBezirk().getSlug(), pickupLocalDate, exception);
                return new FoodsharingPickupModels.Decision(false, true, List.of(userMessage("message.automation.public-holiday-calendar-unavailable")), null);
            }
        }
        if (rulesAreSkipped(pickupDate, now)) {
            reasons.add(userMessage("message.automation.rules-skipped-from-two-days-before"));
            return new FoodsharingPickupModels.Decision(true, false, reasons, null);
        }
        FoodsharingGapStoreGroup gapGroup = gapStoreGroup(a);
        String gapPendingReason = null;
        if (a.isGapRuleEnabled()) {
                int minimumGapDays = a.getMinimumGapDays();
                FoodsharingPickupModels.PickupUser ownPickupUser = pickupUser(livePickupsCache, a, pickupDate, userId);
                Instant ownSignUp = ownPickupUser == null ? null : ownPickupUser.signUpDate();
                // Of two pending sign-ups in this store the later one loses, the same as across the stores of a gap group.
                pickups(livePickupsCache, a.getAdminConnection(), a.getStoreId()).stream()
                    .filter(p -> !p.date().equals(pickupDate))
                    .filter(p -> !rulesAreSkipped(p.date(), now))
                    .filter(p -> p.users().stream().filter(u -> u.id().equals(userId)).anyMatch(u -> blocksBySignUpOrder(u, ownSignUp)))
                    .map(FoodsharingPickupModels.Pickup::date)
                    .map(d -> calendarGapDays(d, pickupDate))
                    .filter(gapDays -> gapDays < minimumGapDays)
                    .findAny()
                    .ifPresent(gapDays -> reasons.add(userMessage("message.automation.gap", daySuffix(gapDays), daySuffix(minimumGapDays))));
        }
        if (gapGroup != null) {
            try {
                GapGroupResult gapResult = gapGroupReason(a, gapGroup, pickupDate, userId, livePickupsCache, initialPickupsCache, foodsharingUserInfoCache, evaluatingStores, now);
                if (gapResult.declineReason() != null) {
                    reasons.add(gapResult.declineReason());
                }
                gapPendingReason = gapResult.pendingReason();
            } catch (RuntimeException ex) {
                if (availability.reportFailure("slot", ex)) throw ex;
                // Without the other stores' pickups nobody can be approved or removed correctly; retry on the next run.
                log.warn("Foodsharing gap group could not be read for store {} ({}) pickupDate={} userId={}", a.getStoreId(), a.getStoreName(), pickupDate, userId, ex);
                return new FoodsharingPickupModels.Decision(false, true, List.of(userMessage("message.automation.gap-group-unavailable")), null, true);
            }
        }
        if (a.isExperienceRuleEnabled()) {
            List<FoodsharingPickupModels.StoreMember> storeMembers = storeMembers(a.getAdminConnection(), a.getStoreId());
            if (!hasStoreExperience(storeMembers, userId) && !hasExperiencedCoPickup(a, pickupDate, userId, livePickupsCache, storeMembers)) {
                reasons.add(userMessage("message.automation.experience"));
            }
        }
        if (a.isCleaningRuleEnabled() && !cleaningRuleExemptionRepository.existsByBezirkAndFoodsharingId(a.getBezirk(), userId)) {
            Long cleaningStoreId = a.getBezirk().getCleaningStoreId();
            if (cleaningStoreId == null) {
                reasons.add(userMessage("message.automation.no-cleaning-store", a.getBezirk().getName()));
            } else {
                long backCheckMonths = Math.max(0, appProperties.getFoodsharing().getAutomation().getCleaningBackCheckMonths());
                Instant cleaningThreshold = now.atZone(SWISS_ZONE).minusMonths(backCheckMonths).toInstant();
                List<FoodsharingPickupModels.StoreMember> cleaningMembers = storeMembers(a.getAdminConnection(), cleaningStoreId);
                Instant lastCleaning = cleaningMembers.stream()
                        .filter(member -> String.valueOf(member.id()).equals(userId))
                        .map(FoodsharingPickupModels.StoreMember::lastFetch)
                        .filter(java.util.Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null);
                boolean past = lastCleaning != null && !lastCleaning.isBefore(cleaningThreshold);
                List<FoodsharingPickupModels.Pickup> cleaningPickups = pickups(initialPickupsCache, a.getAdminConnection(), cleaningStoreId);
                // A booked cleaning counts from its slot on, not only while it is still ahead: Foodsharing sets lastFetch
                // only some time after the cleaning, and until then today's (already started) slot is the only proof.
                boolean booked = cleaningPickups.stream()
                        .filter(p -> p.users().stream().anyMatch(u -> u.id().equals(userId)))
                        .anyMatch(p -> !p.date().isBefore(cleaningThreshold));
                if (!past && !booked) {
                    String userInfoCacheKey = a.getBezirk().getId() + ":" + userId;
                    FoodsharingUserInfo userInfo = foodsharingUserInfoCache.computeIfAbsent(userInfoCacheKey,
                            ignored -> foodsharingClient.fetchUserInfo(userId, a.getBezirk()));
                    if (userInfo.verificationLookupFailed()) {
                        // Without the verification date a new user could be declined wrongly; retry on the next run.
                        return new FoodsharingPickupModels.Decision(false, true, List.of(userMessage("message.automation.verification-lookup-failed")), null);
                    }
                    Instant verificationThreshold = now.atZone(SWISS_ZONE).minusMonths(backCheckMonths).toInstant();
                    boolean verificationExempt = userInfo.verificationDate() != null
                            && !userInfo.verificationDate().isAfter(now)
                            && !userInfo.verificationDate().isBefore(verificationThreshold);
                    long freeCleaningSlots = cleaningPickups.stream()
                            .filter(p -> !p.date().isBefore(now))
                            .filter(p -> p.users().isEmpty())
                            .count();
                    String openCleaningSlots = formatOpenCleaningSlots(cleaningPickups, now);
                    String cleaningStoreUrl = appProperties.getFoodsharing().getBaseUrl().replaceFirst("/+$", "") + "/store/" + cleaningStoreId;
                    if (!verificationExempt) {
                        String cleaningHistory = lastCleaning == null
                                ? userMessage("message.automation.no-cleaning-yet")
                                : userMessage("message.automation.cleaning-override-last-cleaning", formatSwissDateTime(lastCleaning));
                        cleaningOverrideMessage = userMessage("message.automation.cleaning-override", firstName(userName), formatSwissDateTime(pickupDate), a.getStoreName(), monthSuffix(backCheckMonths), cleaningHistory, freeCleaningSlots, openCleaningSlots, cleaningStoreUrl);
                        if (freeCleaningSlots < appProperties.getFoodsharing().getAutomation().getMinimumFreeCleaningSlots()) {
                            reasons.add(cleaningOverrideMessage);
                        } else {
                            String historyText;
                            if (lastCleaning == null) {
                                historyText = userMessage("message.automation.no-cleaning-yet");
                            } else {
                                long lastCleaningMonths = monthsAgo(lastCleaning, now);
                                historyText = userMessage("message.automation.last-cleaning", monthSuffix(lastCleaningMonths));
                            }
                            reasons.add(userMessage("message.automation.cleaning-required", monthSuffix(backCheckMonths), historyText, openCleaningSlots, monthSuffix(backCheckMonths), cleaningStoreUrl));
                        }
                    } else if (!userInfo.verificationDate().atZone(SWISS_ZONE)
                            .plusMonths(CLEANING_NEW_USER_MESSAGE_AFTER_MONTHS).toInstant().isAfter(now)) {
                        cleaningOverrideMessage = userMessage("message.automation.cleaning-new-user", firstName(userName), formatSwissDateTime(pickupDate), a.getStoreName(), monthSuffix(backCheckMonths), formatSwissDateTime(userInfo.verificationDate()), formatSwissDateTime(userInfo.verificationDate().atZone(SWISS_ZONE).plusMonths(backCheckMonths).toInstant()), cleaningStoreUrl);
                        reasons.add(cleaningOverrideMessage);
                    }
                }
            }
        }
        String linkedStorePendingReason = null;
        List<LinkedStoreRule> linkedStoreRules = linkedStoreRules(a);
        if (!linkedStoreRules.isEmpty()) {
            FoodsharingPickupModels.PickupUser pickupUser = pickupUser(livePickupsCache, a, pickupDate, userId);
            String displayName = userName != null ? userName : pickupUser == null ? null : pickupUser.name();
            Instant signUpDate = pickupUser == null ? null : pickupUser.signUpDate();
            for (LinkedStoreRule rule : linkedStoreRules) {
                // Members on the link's exception list don't have to pick up in the linked stores.
                if (rule.exemptFoodsharingIds().contains(userId)) continue;
                List<LinkedPickup> linkedPickups = matchedLinkedPickup(a, rule, pickupDate, userId, livePickupsCache, now);
                if (!linkedPickups.isEmpty()) {
                    // Both sign-ups have to be approved: wait while the linked pickups are all still pending.
                    if (linkedPickups.stream().anyMatch(linkedPickup -> linkedPickup.confirmed()
                            || evaluatingStores.contains(linkedPickup.storeId())
                            || linkedPickupWouldBeConfirmed(a, linkedPickup, userId, livePickupsCache, initialPickupsCache, foodsharingUserInfoCache, evaluatingStores))) {
                        continue;
                    }
                    linkedStorePendingReason = userMessage("message.automation.linked-store.awaiting-confirmation", rule.requiredStoreNames());
                    continue;
                }
                if (signUpDate == null) {
                    linkedStorePendingReason = userMessage("message.automation.linked-store.missing-sign-up-date", rule.requiredStoreNames());
                    continue;
                }
                // Time during which Foodsharing was offline does not count: nobody could book the linked store then.
                Instant deadline = availability.extendDeadline(signUpDate, signUpDate.plus(Duration.ofMinutes(Math.max(MINIMUM_LINKED_STORE_GRACE_MINUTES, rule.graceMinutes()))));
                if (now.isBefore(deadline)) {
                    linkedStorePendingReason = userMessage("message.automation.linked-store.pending", formatSwissDateTime(deadline), rule.requiredStoreNames(), linkedStoreRequirement(rule));
                    continue;
                }
                // Re-read the linked stores once before removing someone, so a booking made after the cache was filled counts.
                rule.requiredStores().forEach(requiredStore -> refreshLinkedStorePickups(livePickupsCache, a.getAdminConnection(), requiredStore.storeId()));
                if (!matchedLinkedPickup(a, rule, pickupDate, userId, livePickupsCache, now).isEmpty()) {
                    // Booked in the meantime: decided on the next run, once the fresh pickup can be checked for approval.
                    linkedStorePendingReason = userMessage("message.automation.linked-store.awaiting-confirmation", rule.requiredStoreNames());
                    continue;
                }
                String reason = renderLinkedStoreMessage(rule, a.getStoreName(), pickupDate, displayName);
                String claimedNote = linkedPickupsClaimedNote(a, rule, pickupDate, userId, livePickupsCache, now);
                String removedNote = removedLinkedPickupsNote(a, rule, pickupDate, userId, livePickupsCache);
                if (claimedNote != null) reason += "\n\n" + claimedNote;
                if (removedNote != null) reason += "\n\n" + removedNote;
                reasons.add(reason);
            }
        }
        if (reasons.isEmpty()) reasons.add(userMessage("message.automation.all-rules-passed"));
        String selectedCleaningOverrideMessage = cleaningOverrideMessage;
        boolean onlyAllowedReasons = reasons.stream().allMatch(reason -> reason.equals(userMessage("message.automation.all-rules-passed")) || reason.equals(selectedCleaningOverrideMessage));
        String pendingReason = linkedStorePendingReason != null ? linkedStorePendingReason : gapPendingReason;
        if (onlyAllowedReasons && pendingReason != null) {
            return new FoodsharingPickupModels.Decision(false, true, List.of(pendingReason), null, true);
        }
        String cleaningOverrideUserMessage = reasons.contains(selectedCleaningOverrideMessage)
                ? selectedCleaningOverrideMessage + "\n\n" + declineFooter()
                : null;
        return new FoodsharingPickupModels.Decision(onlyAllowedReasons, false, reasons, cleaningOverrideUserMessage);
    }

    private record GapGroupConflict(long storeId, Instant date, long gapDays) {}

    /** {@code conflict}: a pickup that blocks this sign-up. {@code waitingFor}: an earlier pending sign-up nobody has decided on yet. */
    private record GapGroupCheck(GapGroupConflict conflict, GapGroupConflict waitingFor) {}

    /** {@code declineReason} removes the sign-up; {@code pendingReason} defers it to a later run. Both null: the group allows it. */
    private record GapGroupResult(String declineReason, String pendingReason) {}

    /** What a store's automation would do now with a pending sign-up. */
    private enum PendingOutcome { CONFIRMED, DECLINED, UNDECIDED }

    /** The store's gap group, unless the group belongs to another account: only that account's automations can read its stores. */
    private FoodsharingGapStoreGroup gapStoreGroup(FoodsharingStoreAutomation automation) {
        return gapStoreGroupMemberRepository.findByStoreId(automation.getStoreId())
                .map(FoodsharingGapStoreGroupMember::getGroup)
                .filter(group -> group.getAdminConnection().getId().equals(automation.getAdminConnection().getId()))
                .orElse(null);
    }

    private List<FoodsharingGapStoreGroupMember> gapStoreGroupMembers(FoodsharingGapStoreGroup group) {
        return gapStoreGroupMemberRepository.findAllByGroupOrderByStoreNameAscStoreIdAsc(group);
    }

    private GapGroupResult gapGroupReason(FoodsharingStoreAutomation a, FoodsharingGapStoreGroup group, Instant pickupDate, String userId, Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache, Map<String, List<FoodsharingPickupModels.Pickup>> initialPickupsCache, Map<String, FoodsharingUserInfo> foodsharingUserInfoCache, Set<Long> evaluatingStores, Instant now) {
        List<FoodsharingGapStoreGroupMember> members = gapStoreGroupMembers(group);
        GapGroupCheck check = gapGroupConflict(a, group, members, pickupDate, userId, livePickupsCache, initialPickupsCache, foodsharingUserInfoCache, evaluatingStores, now);
        if (check.conflict() != null) {
            // Re-read the other stores once before removing someone: the other pickup may have just been cancelled.
            members.stream().filter(member -> member.getStoreId() != a.getStoreId())
                    .forEach(member -> refreshLinkedStorePickups(livePickupsCache, a.getAdminConnection(), member.getStoreId()));
            check = gapGroupConflict(a, group, members, pickupDate, userId, livePickupsCache, initialPickupsCache, foodsharingUserInfoCache, evaluatingStores, now);
        }
        GapGroupConflict conflict = check.conflict();
        if (conflict == null) {
            GapGroupConflict waitingFor = check.waitingFor();
            return waitingFor == null
                    ? new GapGroupResult(null, null)
                    : new GapGroupResult(null, userMessage("message.automation.gap-group.awaiting-decision", formatSwissDateTime(waitingFor.date()), gapGroupStoreName(members, waitingFor.storeId())));
        }
        List<String> names = members.stream().map(FoodsharingGapStoreGroupMember::getStoreName).toList();
        String allNames = String.join(", ", names.subList(0, names.size() - 1)) + " und " + names.get(names.size() - 1);
        return new GapGroupResult(userMessage("message.automation.gap-group", formatSwissDateTime(conflict.date()), gapGroupStoreName(members, conflict.storeId()), allNames, daySuffix(group.getMinimumGapDays()), daySuffix(conflict.gapDays())), null);
    }

    private String gapGroupStoreName(List<FoodsharingGapStoreGroupMember> members, long storeId) {
        return members.stream().filter(member -> member.getStoreId() == storeId)
                .map(FoodsharingGapStoreGroupMember::getStoreName).findFirst().orElse(String.valueOf(storeId));
    }

    /** A confirmed pickup always blocks; of two pending sign-ups the earlier one wins, and a missing sign-up date blocks. */
    private boolean blocksBySignUpOrder(FoodsharingPickupModels.PickupUser other, Instant ownSignUp) {
        return other.confirmed() || ownSignUp == null || other.signUpDate() == null || !other.signUpDate().isAfter(ownSignUp);
    }

    /**
     * The person's pickup in another store of the group that is too close. A confirmed pickup always counts. Of two pending
     * sign-ups the later one loses, so the outcome does not depend on which store happens to be evaluated first. An earlier
     * pending sign-up only counts once its store's automation would confirm it; one it would remove does not count, and one
     * left to a person (no automation, dry-run, excluded date, waiting for another rule) makes this sign-up wait.
     */
    private GapGroupCheck gapGroupConflict(FoodsharingStoreAutomation a, FoodsharingGapStoreGroup group, List<FoodsharingGapStoreGroupMember> members, Instant pickupDate, String userId, Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache, Map<String, List<FoodsharingPickupModels.Pickup>> initialPickupsCache, Map<String, FoodsharingUserInfo> foodsharingUserInfoCache, Set<Long> evaluatingStores, Instant now) {
        FoodsharingPickupModels.PickupUser ownUser = pickupUser(livePickupsCache, a, pickupDate, userId);
        Instant ownSignUp = ownUser == null ? null : ownUser.signUpDate();
        GapGroupConflict waitingFor = null;
        for (FoodsharingGapStoreGroupMember member : members) {
            if (member.getStoreId() == a.getStoreId()) continue;
            for (FoodsharingPickupModels.Pickup pickup : List.copyOf(pickups(livePickupsCache, a.getAdminConnection(), member.getStoreId()))) {
                if (rulesAreSkipped(pickup.date(), now)) continue;
                long gapDays = calendarGapDays(pickup.date(), pickupDate);
                if (gapDays >= group.getMinimumGapDays()) continue;
                FoodsharingPickupModels.PickupUser other = pickup.users().stream().filter(user -> user.id().equals(userId)).findFirst().orElse(null);
                if (other == null || !blocksBySignUpOrder(other, ownSignUp)) continue;
                GapGroupConflict conflict = new GapGroupConflict(member.getStoreId(), pickup.date(), gapDays);
                // Inside a look-ahead from that store: its sign-up is the one being decided, so it simply counts.
                if (other.confirmed() || evaluatingStores.contains(member.getStoreId())) {
                    return new GapGroupCheck(conflict, null);
                }
                PendingOutcome outcome = pendingOutcome(a, member.getStoreId(), pickup.date(), userId, livePickupsCache, initialPickupsCache, foodsharingUserInfoCache, evaluatingStores);
                if (outcome == PendingOutcome.CONFIRMED) {
                    return new GapGroupCheck(conflict, null);
                }
                if (outcome == PendingOutcome.UNDECIDED && waitingFor == null) {
                    waitingFor = conflict;
                }
            }
        }
        return new GapGroupCheck(null, waitingFor);
    }

    /**
     * What the store's own automation of the same account would now do with the person's pending sign-up. UNDECIDED when it
     * has no such automation, the automation is off or only a dry-run (for a live decision), or it would skip or defer it.
     */
    private PendingOutcome pendingOutcome(FoodsharingStoreAutomation from, long storeId, Instant date, String userId, Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache, Map<String, List<FoodsharingPickupModels.Pickup>> initialPickupsCache, Map<String, FoodsharingUserInfo> foodsharingUserInfoCache, Set<Long> evaluatingStores) {
        FoodsharingStoreAutomation automation = automationRepository.findByStoreId(storeId)
                .filter(FoodsharingStoreAutomation::isEnabled)
                .filter(candidate -> candidate.getAdminConnection().getId().equals(from.getAdminConnection().getId()))
                .filter(candidate -> decisionsCount(candidate, livePickupsCache))
                .orElse(null);
        if (automation == null) {
            return PendingOutcome.UNDECIDED;
        }
        Set<Long> nextEvaluatingStores = new java.util.HashSet<>(evaluatingStores);
        nextEvaluatingStores.add(storeId);
        FoodsharingPickupModels.Decision decision = evaluate(automation, date, userId, null, livePickupsCache, initialPickupsCache, foodsharingUserInfoCache, nextEvaluatingStores);
        if (decision.deferred() || decision.skipped()) {
            return PendingOutcome.UNDECIDED;
        }
        return decision.allowed() ? PendingOutcome.CONFIRMED : PendingOutcome.DECLINED;
    }

    /**
     * {@code gapStores} null keeps the saved list; an empty list takes the store out of its group. The group's gap is separate
     * from the store's own gap rule, which stays as it is. The other stores of the group stay together.
     */
    private void applyGapStoreGroup(FoodsharingAdminConnection connection, FoodsharingStoreAutomation automation, StoreAutomationRequest request) {
        long storeId = automation.getStoreId();
        List<LinkedStoreRef> requested = request.gapStores();
        boolean wantsList = requested != null && !requested.isEmpty();
        FoodsharingGapStoreGroupMember membership = gapStoreGroupMemberRepository.findByStoreId(storeId).orElse(null);
        if (membership != null && !membership.getGroup().getAdminConnection().getId().equals(connection.getId())) {
            if (wantsList) {
                throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_FAILED, List.of(automation.getStoreName() + " ist bereits in der Abstandsregel eines anderen Kontos."));
            }
            return;
        }
        if (requested != null && requested.isEmpty()) {
            if (membership != null) {
                removeGapStoreGroupMember(membership);
            }
            return;
        }
        if (requested == null) {
            if (membership != null && request.gapStoresMinimumDays() != null) {
                membership.getGroup().setMinimumGapDays(requireGapForGroup(request.gapStoresMinimumDays()));
                validateGapGroupAgainstLinks(membership.getGroup());
            }
            return;
        }
        int groupGap = requireGapForGroup(request.gapStoresMinimumDays());
        Map<Long, String> managedStoreNames = managedStores(connection).stream()
                .collect(java.util.stream.Collectors.toMap(FoodsharingPickupModels.Store::id, FoodsharingPickupModels.Store::name, (left, right) -> left));
        Map<Long, String> wanted = new LinkedHashMap<>();
        wanted.put(storeId, automation.getStoreName());
        for (LinkedStoreRef ref : requested) {
            if (!managedStoreNames.containsKey(ref.storeId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Die Betriebe der Abstandsregel müssen deine Betriebe sein (verantwortlich oder Botschafter)."));
            }
            boolean otherAccountsAutomation = automationRepository.findByStoreId(ref.storeId())
                    .filter(other -> !other.getAdminConnection().getId().equals(connection.getId()))
                    .isPresent();
            if (otherAccountsAutomation) {
                // Its sign-ups would be decided by the other account's automation, which cannot see this group.
                throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_FAILED, List.of(managedStoreNames.get(ref.storeId())
                        + " hat eine Slot-Automation eines anderen Kontos und kann deshalb nicht in deiner Abstandsregel sein."));
            }
            wanted.put(ref.storeId(), managedStoreNames.get(ref.storeId()));
        }
        if (wanted.size() > MAXIMUM_STORES_PER_GAP_GROUP) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Die Abstandsregel kann höchstens " + MAXIMUM_STORES_PER_GAP_GROUP + " Betriebe umfassen."));
        }
        FoodsharingGapStoreGroup group = membership != null ? membership.getGroup() : new FoodsharingGapStoreGroup();
        group.setAdminConnection(connection);
        group.setMinimumGapDays(groupGap);
        FoodsharingGapStoreGroup savedGroup = gapStoreGroupRepository.save(group);
        // Add first, then remove: the group must not drop below two stores while it is being changed.
        for (Map.Entry<Long, String> store : wanted.entrySet()) {
            FoodsharingGapStoreGroupMember existing = gapStoreGroupMemberRepository.findByStoreId(store.getKey()).orElse(null);
            if (existing != null && existing.getGroup().getId().equals(savedGroup.getId())) {
                existing.setStoreName(store.getValue());
                continue;
            }
            if (existing != null) {
                if (!existing.getGroup().getAdminConnection().getId().equals(connection.getId())) {
                    throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_FAILED, List.of(store.getValue() + " ist bereits in der Abstandsregel eines anderen Kontos."));
                }
                removeGapStoreGroupMember(existing);
            }
            FoodsharingGapStoreGroupMember member = new FoodsharingGapStoreGroupMember();
            member.setGroup(savedGroup);
            member.setStoreId(store.getKey());
            member.setStoreName(store.getValue());
            gapStoreGroupMemberRepository.saveAndFlush(member);
        }
        for (FoodsharingGapStoreGroupMember member : gapStoreGroupMembers(savedGroup)) {
            if (!wanted.containsKey(member.getStoreId())) {
                removeGapStoreGroupMember(member);
            }
        }
        validateGapGroupAgainstLinks(savedGroup);
    }

    private int requireGapForGroup(Integer minimumGapDays) {
        if (minimumGapDays == null || minimumGapDays < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Der Abstand zwischen mehreren Betrieben muss mindestens 1 Tag sein."));
        }
        return minimumGapDays;
    }

    private void removeGapStoreGroupMember(FoodsharingGapStoreGroupMember member) {
        FoodsharingGapStoreGroup group = member.getGroup();
        gapStoreGroupMemberRepository.delete(member);
        gapStoreGroupMemberRepository.flush();
        List<FoodsharingGapStoreGroupMember> remaining = gapStoreGroupMembers(group);
        if (remaining.size() < 2) {
            gapStoreGroupMemberRepository.deleteAll(remaining);
            gapStoreGroupMemberRepository.flush();
            gapStoreGroupRepository.delete(group);
            gapStoreGroupRepository.flush();
        }
    }

    /** A link between two stores of a group has to allow a pickup that respects the gap, otherwise nobody can meet both rules. */
    private void validateGapGroupAgainstLinks(FoodsharingGapStoreGroup group) {
        Set<Long> storeIds = gapStoreGroupMembers(group).stream().map(FoodsharingGapStoreGroupMember::getStoreId).collect(java.util.stream.Collectors.toSet());
        for (long storeId : storeIds) {
            FoodsharingStoreAutomation automation = automationRepository.findByStoreId(storeId)
                    .filter(candidate -> candidate.getAdminConnection().getId().equals(group.getAdminConnection().getId()))
                    .orElse(null);
            if (automation == null) continue;
            for (FoodsharingStoreAutomationLinkedStore link : linkedStoreRepository.findAllByAutomationOrderByCreatedAtAscIdAsc(automation)) {
                // The longest window still leaves (window - 1) free days between the two pickups.
                boolean fits = FoodsharingStoreAutomationLinkedStore.WINDOW_LAST_DAYS.equals(link.getWindowMode())
                        && Math.max(link.getWindowDaysBefore(), link.getWindowDaysAfter()) - 1 >= group.getMinimumGapDays();
                if (fits) continue;
                List<LinkedStoreRef> refs = linkedStoreRefs(link);
                List<LinkedStoreRef> inGroup = refs.stream().filter(ref -> storeIds.contains(ref.storeId())).toList();
                // A link to "B or C" with C outside the group can still be met at C. Its bidirectional side is a link to this store alone.
                boolean conflicts = !inGroup.isEmpty() && (inGroup.size() == refs.size() || link.isBidirectional());
                if (conflicts) {
                    String names = inGroup.stream().map(LinkedStoreRef::storeName).collect(java.util.stream.Collectors.joining(", "));
                    throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of(automation.getStoreName() + " ist mit " + names
                            + " gekoppelt. Das widerspricht dem Mindestabstand von " + daySuffix(group.getMinimumGapDays()) + ": Niemand könnte beide Regeln erfüllen. Entferne den Betrieb aus der Liste oder ändere die Kopplung."));
                }
            }
        }
    }

    private record LinkedStoreRule(List<LinkedStoreRef> requiredStores, String windowMode, int windowDaysBefore, int windowDaysAfter, int graceMinutes, String message, Set<String> exemptFoodsharingIds) {
        /** "X", or "X, Y oder Z" when any one of several stores is enough. */
        String requiredStoreNames() {
            List<String> names = requiredStores.stream().map(LinkedStoreRef::storeName).toList();
            return names.size() <= 1 ? String.join("", names)
                    : String.join(", ", names.subList(0, names.size() - 1)) + " oder " + names.get(names.size() - 1);
        }
    }

    private record LinkedStoreDraft(FoodsharingStoreAutomationLinkedStore link, List<LinkedStoreRef> stores, List<LinkedStoreExemption> exemptions) {}

    private List<LinkedStoreExemption> linkedStoreExemptions(FoodsharingStoreAutomationLinkedStore link) {
        return linkedStoreExemptionRepository.findAllByLinkOrderByCreatedAtAscIdAsc(link).stream()
                .map(exemption -> new LinkedStoreExemption(exemption.getFoodsharingId(), exemption.getReason()))
                .toList();
    }

    private Set<String> exemptFoodsharingIds(FoodsharingStoreAutomationLinkedStore link) {
        return linkedStoreExemptions(link).stream().map(LinkedStoreExemption::foodsharingId).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private List<LinkedStoreRef> linkedStoreRefs(FoodsharingStoreAutomationLinkedStore link) {
        return linkedStoreMemberRepository.findAllByLinkOrderByStoreNameAscStoreIdAsc(link).stream()
                .map(member -> new LinkedStoreRef(member.getStoreId(), member.getStoreName()))
                .toList();
    }

    private void deleteLinkedStores(FoodsharingStoreAutomation automation) {
        linkedStoreExemptionRepository.deleteAllByLinkAutomation(automation);
        linkedStoreExemptionRepository.flush();
        linkedStoreMemberRepository.deleteAllByLinkAutomation(automation);
        linkedStoreMemberRepository.flush();
        linkedStoreRepository.deleteAllByAutomation(automation);
        linkedStoreRepository.flush();
    }

    /** The automation's own rules plus bidirectional rules from other stores of the same account that include it. */
    private List<LinkedStoreRule> linkedStoreRules(FoodsharingStoreAutomation automation) {
        List<LinkedStoreRule> rules = new ArrayList<>();
        for (FoodsharingStoreAutomationLinkedStore link : linkedStoreRepository.findAllByAutomationOrderByCreatedAtAscIdAsc(automation)) {
            List<LinkedStoreRef> stores = linkedStoreRefs(link);
            if (!stores.isEmpty()) {
                rules.add(new LinkedStoreRule(stores, link.getWindowMode(), link.getWindowDaysBefore(), link.getWindowDaysAfter(), link.getGraceMinutes(), customLinkedStoreMessage(link.getMessage()), exemptFoodsharingIds(link)));
            }
        }
        for (FoodsharingStoreAutomationLinkedStore link : incomingLinkedStores(automation)) {
            if (!link.isBidirectional()) continue;
            FoodsharingStoreAutomation source = link.getAutomation();
            rules.add(new LinkedStoreRule(List.of(new LinkedStoreRef(source.getStoreId(), source.getStoreName())), link.getWindowMode(), link.getWindowDaysAfter(), link.getWindowDaysBefore(), link.getGraceMinutes(), customLinkedStoreMessage(link.getMessage()), exemptFoodsharingIds(link)));
        }
        return List.copyOf(rules);
    }

    /** Rules of other stores of the same account that include this store; only bidirectional ones apply to its pickups. */
    private List<FoodsharingStoreAutomationLinkedStore> incomingLinkedStores(FoodsharingStoreAutomation automation) {
        return incomingLinkedStores(automation.getStoreId(), automation.getAdminConnection().getId());
    }

    private List<FoodsharingStoreAutomationLinkedStore> incomingLinkedStores(long storeId, UUID connectionId) {
        return linkedStoreMemberRepository.findAllByStoreId(storeId).stream()
                .map(FoodsharingStoreAutomationLinkedStoreMember::getLink)
                .filter(link -> link.getAutomation().getStoreId() != storeId)
                .filter(link -> link.getAutomation().getAdminConnection().getId().equals(connectionId))
                .toList();
    }

    private List<LinkedStoreDraft> validateLinkedStores(FoodsharingAdminConnection connection, FoodsharingStoreAutomation automation, long storeId, List<LinkedStoreRuleRequest> requests) {
        Map<Long, String> managedStoreNames = managedStores(connection).stream()
                .collect(java.util.stream.Collectors.toMap(FoodsharingPickupModels.Store::id, FoodsharingPickupModels.Store::name, (left, right) -> left));
        if (requests.size() > MAXIMUM_LINKED_STORES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("A store can have at most " + MAXIMUM_LINKED_STORES + " links."));
        }
        List<LinkedStoreDraft> drafts = new ArrayList<>();
        java.util.Set<Long> allLinkedStoreIds = new java.util.HashSet<>();
        for (LinkedStoreRuleRequest request : requests) {
            List<LinkedStoreRef> requestedStores = request.linkedStores() == null ? List.of() : request.linkedStores();
            if (requestedStores.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Select at least one store to link."));
            }
            if (requestedStores.size() > MAXIMUM_STORES_PER_LINK) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("A link can include at most " + MAXIMUM_STORES_PER_LINK + " stores."));
            }
            List<LinkedStoreRef> stores = new ArrayList<>();
            for (LinkedStoreRef requestedStore : requestedStores) {
                if (requestedStore.storeId() == storeId) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("A store cannot be linked to itself."));
                }
                if (!managedStoreNames.containsKey(requestedStore.storeId())) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("The linked store must be one of your stores (responsible or Botschafter)."));
                }
                if (!allLinkedStoreIds.add(requestedStore.storeId())) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Each store can only be linked once."));
                }
                stores.add(new LinkedStoreRef(requestedStore.storeId(), managedStoreNames.get(requestedStore.storeId())));
            }
            String windowMode = FoodsharingStoreAutomationLinkedStore.WINDOW_LAST_DAYS.equals(request.windowMode())
                    ? FoodsharingStoreAutomationLinkedStore.WINDOW_LAST_DAYS
                    : FoodsharingStoreAutomationLinkedStore.WINDOW_SAME_DAY;
            if (request.windowMode() != null && !request.windowMode().equals(windowMode)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Unknown linked store window: " + request.windowMode()));
            }
            if (windowMode.equals(FoodsharingStoreAutomationLinkedStore.WINDOW_LAST_DAYS)
                    && (request.windowDaysBefore() < 0 || request.windowDaysBefore() > MAXIMUM_LINKED_STORE_WINDOW_DAYS
                    || request.windowDaysAfter() < 0 || request.windowDaysAfter() > MAXIMUM_LINKED_STORE_WINDOW_DAYS
                    || request.windowDaysBefore() + request.windowDaysAfter() < 1)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("The days before and after must be between 0 and " + MAXIMUM_LINKED_STORE_WINDOW_DAYS + ", and at least one of them 1 or more."));
            }
            if (request.graceMinutes() < MINIMUM_LINKED_STORE_GRACE_MINUTES) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("The time to book the linked store must be at least " + MINIMUM_LINKED_STORE_GRACE_MINUTES + " minutes."));
            }
            String message = blankToNull(request.message());
            validateLinkedStoreMessage(message);
            FoodsharingStoreAutomationLinkedStore link = new FoodsharingStoreAutomationLinkedStore();
            link.setBidirectional(request.bidirectional());
            link.setWindowMode(windowMode);
            boolean lastDays = windowMode.equals(FoodsharingStoreAutomationLinkedStore.WINDOW_LAST_DAYS);
            link.setWindowDaysBefore(lastDays ? request.windowDaysBefore() : 0);
            link.setWindowDaysAfter(lastDays ? request.windowDaysAfter() : 0);
            link.setGraceMinutes(request.graceMinutes());
            link.setMessage(message);
            drafts.add(new LinkedStoreDraft(link, stores, validateLinkedStoreExemptions(request.exemptions())));
        }
        // A pair of stores has one link. If a linked store already links here, that link has to be made bidirectional instead.
        for (FoodsharingStoreAutomationLinkedStoreMember incoming : linkedStoreMemberRepository.findAllByStoreId(storeId)) {
            FoodsharingStoreAutomation source = incoming.getLink().getAutomation();
            if (source.getStoreId() != storeId && allLinkedStoreIds.contains(source.getStoreId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of(source.getStoreName()
                        + " is already linked to this store. Change that link in " + source.getStoreName() + " to bidirectional instead."));
            }
        }
        // Bidirectional links from other stores count towards this store's limit, and ours towards theirs.
        long incomingBidirectional = incomingLinkedStores(storeId, connection.getId()).stream().filter(FoodsharingStoreAutomationLinkedStore::isBidirectional).count();
        if (drafts.size() + incomingBidirectional > MAXIMUM_LINKED_STORES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("A store can have at most " + MAXIMUM_LINKED_STORES + " links (including bidirectional links from other stores)."));
        }
        for (LinkedStoreDraft draft : drafts) {
            if (!draft.link().isBidirectional()) continue;
            for (LinkedStoreRef target : draft.stores()) {
                long targetOwn = automationRepository.findByStoreId(target.storeId())
                        .map(targetAutomation -> (long) linkedStoreRepository.findAllByAutomationOrderByCreatedAtAscIdAsc(targetAutomation).size())
                        .orElse(0L);
                long targetIncoming = incomingLinkedStores(target.storeId(), connection.getId()).stream()
                        .filter(FoodsharingStoreAutomationLinkedStore::isBidirectional)
                        .filter(link -> link.getAutomation().getStoreId() != storeId)
                        .count();
                if (targetOwn + targetIncoming + 1 > MAXIMUM_LINKED_STORES) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of(target.storeName() + " already has " + MAXIMUM_LINKED_STORES + " links; a bidirectional link is not possible."));
                }
            }
        }
        return drafts;
    }

    private List<LinkedStoreExemption> validateLinkedStoreExemptions(List<LinkedStoreExemption> requested) {
        if (requested == null || requested.isEmpty()) return List.of();
        if (requested.size() > MAXIMUM_LINKED_STORE_EXEMPTIONS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("A link can have at most " + MAXIMUM_LINKED_STORE_EXEMPTIONS + " exceptions."));
        }
        List<LinkedStoreExemption> exemptions = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (LinkedStoreExemption entry : requested) {
            String foodsharingId = entry == null || entry.foodsharingId() == null ? "" : entry.foodsharingId().trim();
            String reason = entry == null || entry.reason() == null ? "" : entry.reason().trim();
            if (!foodsharingId.matches("\\d{1,18}")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Exception list: the Foodsharing id must be a number (\"" + foodsharingId + "\")."));
            }
            if (reason.isEmpty() || reason.length() > 1000) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Exception list: please enter a reason (at most 1000 characters) for " + foodsharingId + "."));
            }
            if (!ids.add(foodsharingId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Exception list: " + foodsharingId + " is listed twice."));
            }
            exemptions.add(new LinkedStoreExemption(foodsharingId, reason));
        }
        return exemptions;
    }

    private void validateLinkedStoreMessage(String message) {
        if (message == null) return;
        java.util.Set<String> allowed = java.util.Set.of("name", "storeName", "linkedStoreName", "pickupDate", "requirement", "graceMinutes");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{\\{\\s*([^}\\s]+)\\s*}}").matcher(message);
        while (matcher.find()) {
            if (!allowed.contains(matcher.group(1))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Unknown template variable: {{" + matcher.group(1) + "}}"));
            }
        }
    }

    private FoodsharingPickupModels.PickupUser pickupUser(Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache, FoodsharingStoreAutomation automation, Instant pickupDate, String userId) {
        return pickups(livePickupsCache, automation.getAdminConnection(), automation.getStoreId()).stream()
                .filter(pickup -> pickup.date().equals(pickupDate))
                .flatMap(pickup -> pickup.users().stream())
                .filter(user -> user.id().equals(userId))
                .findFirst()
                .orElse(null);
    }

    private record LinkedPickup(long storeId, Instant date, boolean confirmed) {}

    /**
     * A pending linked pickup counts once the linked store's own enabled automation would confirm it now; the pickup is then
     * confirmed in this run or the next. Without such an automation the linked pickup has to be approved by hand first.
     */
    private boolean linkedPickupWouldBeConfirmed(FoodsharingStoreAutomation automation, LinkedPickup linkedPickup, String userId, Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache, Map<String, List<FoodsharingPickupModels.Pickup>> initialPickupsCache, Map<String, FoodsharingUserInfo> foodsharingUserInfoCache, Set<Long> evaluatingStores) {
        return pendingOutcome(automation, linkedPickup.storeId(), linkedPickup.date(), userId, livePickupsCache, initialPickupsCache, foodsharingUserInfoCache, evaluatingStores) == PendingOutcome.CONFIRMED;
    }

    /**
     * Every pickup of the person in this store needs its own linked pickup, so one linked pickup never covers two of them.
     * The person's pickups here are matched to linked pickups in order of priority (approved first, then earliest sign-up);
     * the result holds the linked pickup matched to {@code pickupDate}, or is empty when there is none left for it.
     */
    private List<LinkedPickup> matchedLinkedPickup(FoodsharingStoreAutomation a, LinkedStoreRule rule, Instant pickupDate, String userId, Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache, Instant now) {
        LinkedPickup result = linkedPickupMatching(a, rule, pickupDate, userId, livePickupsCache, now).matched().get(pickupDate);
        return result == null ? List.of() : List.of(result);
    }

    /** Linked pickups per own pickup date ({@code candidates}), the one matched to each own pickup ({@code matched}). */
    private record LinkedPickupMatching(Map<Instant, List<LinkedPickup>> candidates, Map<Instant, LinkedPickup> matched) {}

    /**
     * Explains a decline although the person has a linked pickup in the window: each of them already counts for another
     * of their pickups in this store. Null when there is no linked pickup in the window at all.
     */
    private String linkedPickupsClaimedNote(FoodsharingStoreAutomation a, LinkedStoreRule rule, Instant pickupDate, String userId, Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache, Instant now) {
        LinkedPickupMatching matching = linkedPickupMatching(a, rule, pickupDate, userId, livePickupsCache, now);
        List<String> claims = new ArrayList<>();
        for (LinkedPickup candidate : matching.candidates().getOrDefault(pickupDate, List.of())) {
            matching.matched().entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(pickupDate) && entry.getValue().equals(candidate))
                    .findFirst()
                    .ifPresent(entry -> claims.add(userMessage("message.automation.linked-store.claimed.item",
                            linkedStoreName(rule, candidate.storeId()), formatSwissDateTime(candidate.date()), a.getStoreName(), formatSwissDateTime(entry.getKey()))));
        }
        return claims.isEmpty() ? null : userMessage("message.automation.linked-store.claimed", String.join("\n", claims), rule.requiredStoreNames());
    }

    /**
     * Explains a decline when the person did book a linked store in the window, but the automation removed that pickup (for
     * example because of the gap rule). Null when there is no such removal.
     */
    private String removedLinkedPickupsNote(FoodsharingStoreAutomation a, LinkedStoreRule rule, Instant pickupDate, String userId, Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache) {
        LocalDate pickupDay = pickupDate.atZone(SWISS_ZONE).toLocalDate();
        boolean lastDays = FoodsharingStoreAutomationLinkedStore.WINDOW_LAST_DAYS.equals(rule.windowMode());
        Instant from = pickupDay.minusDays(lastDays ? Math.max(0, rule.windowDaysBefore()) : 0).atStartOfDay(SWISS_ZONE).toInstant();
        Instant to = pickupDay.plusDays((lastDays ? Math.max(0, rule.windowDaysAfter()) : 0) + 1L).atStartOfDay(SWISS_ZONE).toInstant().minusNanos(1);
        List<FoodsharingPickupAutomationDecision> decisions = livePickupsCache instanceof PickupCache pickupCache && pickupCache.simulated
                ? List.of(FoodsharingPickupAutomationDecision.DECLINED, FoodsharingPickupAutomationDecision.WOULD_DECLINE)
                : List.of(FoodsharingPickupAutomationDecision.DECLINED);
        Set<Long> requiredStoreIds = rule.requiredStores().stream().map(LinkedStoreRef::storeId).collect(java.util.stream.Collectors.toSet());
        List<String> items = auditRepository.findAllByAdminConnectionAndFoodsharingUserIdAndDecisionInAndPickupDateBetween(a.getAdminConnection(), userId, decisions, from, to).stream()
                .filter(audit -> requiredStoreIds.contains(audit.getStoreId()))
                .map(audit -> audit.getStoreId() + "@" + audit.getPickupDate())
                .distinct()
                .map(key -> {
                    long storeId = Long.parseLong(key.substring(0, key.indexOf('@')));
                    Instant date = Instant.parse(key.substring(key.indexOf('@') + 1));
                    return userMessage("message.automation.linked-store.removed.item", linkedStoreName(rule, storeId), formatSwissDateTime(date));
                })
                .toList();
        return items.isEmpty() ? null : userMessage("message.automation.linked-store.removed", String.join("\n", items));
    }

    private String linkedStoreName(LinkedStoreRule rule, long storeId) {
        return rule.requiredStores().stream().filter(store -> store.storeId() == storeId).map(LinkedStoreRef::storeName).findFirst().orElse(String.valueOf(storeId));
    }

    private LinkedPickupMatching linkedPickupMatching(FoodsharingStoreAutomation a, LinkedStoreRule rule, Instant pickupDate, String userId, Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache, Instant now) {
        record Own(Instant date, boolean confirmed, Instant signUp) {}
        List<Own> own = new ArrayList<>();
        for (FoodsharingPickupModels.Pickup pickup : pickups(livePickupsCache, a.getAdminConnection(), a.getStoreId())) {
            pickup.users().stream().filter(user -> user.id().equals(userId)).findFirst()
                    .ifPresent(user -> own.add(new Own(pickup.date(), user.confirmed(), user.signUpDate())));
        }
        if (own.stream().noneMatch(entry -> entry.date().equals(pickupDate))) {
            own.add(new Own(pickupDate, false, null));
        }
        own.sort(java.util.Comparator.comparing((Own entry) -> !entry.confirmed())
                .thenComparing(Own::signUp, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(Own::date));
        Map<Instant, List<LinkedPickup>> candidates = new java.util.HashMap<>();
        for (Own entry : own) {
            candidates.put(entry.date(), linkedStorePickups(a.getAdminConnection(), rule, entry.date(), userId, livePickupsCache, now));
        }
        // linked pickup (store id + date) -> own pickup date holding it
        Map<String, Instant> owner = new java.util.HashMap<>();
        Map<Instant, LinkedPickup> matched = new java.util.HashMap<>();
        for (Own entry : own) {
            matchLinkedPickup(entry.date(), candidates, owner, matched, new java.util.HashSet<>());
        }
        return new LinkedPickupMatching(candidates, matched);
    }

    private boolean matchLinkedPickup(Instant ownDate, Map<Instant, List<LinkedPickup>> candidates, Map<String, Instant> owner, Map<Instant, LinkedPickup> matched, Set<String> visited) {
        for (LinkedPickup candidate : candidates.getOrDefault(ownDate, List.of())) {
            String key = candidate.storeId() + "@" + candidate.date();
            if (!visited.add(key)) continue;
            Instant holder = owner.get(key);
            if (holder == null || matchLinkedPickup(holder, candidates, owner, matched, visited)) {
                owner.put(key, ownDate);
                matched.put(ownDate, candidate);
                return true;
            }
        }
        return false;
    }

    /** The user's pickups in any of the rule's stores on the pickup day, or within the configured days before or after it. Past pickups count as approved. */
    private List<LinkedPickup> linkedStorePickups(FoodsharingAdminConnection connection, LinkedStoreRule rule, Instant pickupDate, String userId, Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache, Instant now) {
        LocalDate pickupDay = pickupDate.atZone(SWISS_ZONE).toLocalDate();
        boolean lastDays = FoodsharingStoreAutomationLinkedStore.WINDOW_LAST_DAYS.equals(rule.windowMode());
        int daysBefore = lastDays ? Math.max(0, rule.windowDaysBefore()) : 0;
        int daysAfter = lastDays ? Math.max(0, rule.windowDaysAfter()) : 0;
        Instant from = pickupDay.minusDays(daysBefore).atStartOfDay(SWISS_ZONE).toInstant();
        Instant to = pickupDay.plusDays(daysAfter + 1L).atStartOfDay(SWISS_ZONE).toInstant();
        java.util.function.Predicate<FoodsharingPickupModels.Pickup> inWindow = pickup -> !pickup.date().isBefore(from) && pickup.date().isBefore(to);
        List<LinkedPickup> result = new ArrayList<>();
        for (LinkedStoreRef requiredStore : rule.requiredStores()) {
            long requiredStoreId = requiredStore.storeId();
            for (FoodsharingPickupModels.Pickup pickup : pickups(livePickupsCache, connection, requiredStoreId)) {
                if (!inWindow.test(pickup)) continue;
                pickup.users().stream()
                        .filter(user -> user.id().equals(userId))
                        .findFirst()
                        .ifPresent(user -> result.add(new LinkedPickup(requiredStoreId, pickup.date(), user.confirmed() || pickup.date().isBefore(now))));
            }
            if (!from.isBefore(now)) {
                continue;
            }
            // Past pickups are not part of the regular pickup list. The live cache map holds them under a separate key.
            String historyKey = "history:" + cacheKey(connection, requiredStoreId);
            boolean forceHistoryRefresh = livePickupsCache.containsKey("refreshed:" + cacheKey(connection, requiredStoreId));
            List<FoodsharingPickupModels.Pickup> history = livePickupsCache.computeIfAbsent(historyKey, key -> pickupHistory(connection, requiredStoreId, now, forceHistoryRefresh));
            history.stream()
                    .filter(inWindow)
                    .filter(pickup -> pickup.users().stream().anyMatch(user -> user.id().equals(userId)))
                    .forEach(pickup -> result.add(new LinkedPickup(requiredStoreId, pickup.date(), true)));
        }
        return result;
    }

    /**
     * Past pickups of the store for the longest possible window, cached in the database. The history only changes once
     * the store's next pickup has passed, so the cache is valid until then. After that only the days since the last read
     * are fetched, with a short overlap for slots changed shortly after the pickup. {@code force} skips the validity,
     * e.g. right before someone is removed.
     */
    private List<FoodsharingPickupModels.Pickup> pickupHistory(FoodsharingAdminConnection connection, long storeId, Instant now, boolean force) {
        Instant oldest = now.minus(Duration.ofDays(MAXIMUM_LINKED_STORE_WINDOW_DAYS + 1L));
        var cached = pickupHistoryCacheRepository.findSnapshot(connection, storeId);
        List<FoodsharingPickupModels.Pickup> cachedPickups = null;
        if (cached.isPresent()) {
            try {
                cachedPickups = deserializeStorePickups(cached.get().getPayloadJson());
            } catch (RuntimeException ignored) {
                // Unreadable payload: read the full history again.
            }
        }
        if (cachedPickups != null && !force && cached.get().getValidUntil().isAfter(now)) {
            return cachedPickups;
        }
        Instant fetchedUntil = cachedPickups == null ? null : cached.get().getFetchedUntil();
        Instant fetchFrom = fetchedUntil == null || fetchedUntil.isBefore(oldest)
                ? oldest
                : fetchedUntil.minus(Duration.ofDays(PICKUP_HISTORY_REFETCH_DAYS));
        List<FoodsharingPickupModels.Pickup> pickups = new ArrayList<>();
        if (cachedPickups != null) {
            cachedPickups.stream()
                    .filter(pickup -> !pickup.date().isBefore(oldest) && pickup.date().isBefore(fetchFrom))
                    .forEach(pickups::add);
        }
        pickups.addAll(client.pickupHistory(connection, storeId, fetchFrom, now));
        List<FoodsharingPickupModels.Pickup> result = List.copyOf(pickups);
        savePickupHistoryCache(connection, storeId, now, nextPickupTime(connection, storeId, now), serializeStorePickups(result));
        return result;
    }

    /** The store's next pickup, after which the history changes. Without a known one, the regular pickups cache TTL applies. */
    private Instant nextPickupTime(FoodsharingAdminConnection connection, long storeId, Instant now) {
        return storePickups(connection, storeId).stream()
                .map(FoodsharingPickupModels.Pickup::date)
                .filter(date -> date.isAfter(now))
                .min(Comparator.naturalOrder())
                .orElseGet(() -> now.plus(Duration.parse(appProperties.getFoodsharing().getAutomation().getStorePickupCacheTtl())));
    }

    private void savePickupHistoryCache(FoodsharingAdminConnection connection, long storeId, Instant fetchedUntil, Instant validUntil, String payloadJson) {
        try {
            cacheTransaction.executeWithoutResult(status -> {
                var cache = pickupHistoryCacheRepository.findByAdminConnectionAndStoreId(connection, storeId)
                        .orElseGet(ch.it4user.foodsharing.domain.entity.FoodsharingStorePickupHistoryCache::new);
                cache.setAdminConnection(connection);
                cache.setStoreId(storeId);
                cache.setFetchedUntil(fetchedUntil);
                cache.setValidUntil(validUntil);
                cache.setPayloadJson(payloadJson);
                pickupHistoryCacheRepository.save(cache);
            });
        } catch (RuntimeException ex) {
            log.info("Foodsharing pickup history cache for store {} not saved; it was changed at the same time ({}).", storeId, ex.getClass().getSimpleName());
        }
    }

    private void refreshLinkedStorePickups(Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache, FoodsharingAdminConnection connection, long storeId) {
        String refreshedKey = "refreshed:" + cacheKey(connection, storeId);
        if (livePickupsCache.containsKey(refreshedKey)) {
            return;
        }
        livePickupsCache.put(refreshedKey, List.of());
        String cacheKey = cacheKey(connection, storeId);
        // Before removing someone, also read the newest history again (the "refreshed:" key makes it skip the cache validity).
        livePickupsCache.remove("history:" + cacheKey);
        livePickupsCache.put(cacheKey, copyPickups(storePickups(connection, storeId, true)));
        if (livePickupsCache instanceof PickupCache pickupCache) {
            // The fresh read knows nothing of this run's dry-run decisions: apply them again. Real ones are already in it.
            pickupCache.changes.stream().filter(change -> change.cacheKey().equals(cacheKey))
                    .forEach(change -> applyCacheChange(pickupCache, change));
        }
    }

    /** Pickups read in one run. {@code simulated}: dry-run decisions count as made. Changes are kept to re-apply after a re-read. */
    private static final class PickupCache extends HashMap<String, List<FoodsharingPickupModels.Pickup>> {
        private final boolean simulated;
        private final List<CacheChange> changes = new ArrayList<>();

        private PickupCache(boolean simulated) {
            this.simulated = simulated;
        }
    }

    private record CacheChange(String cacheKey, Instant pickupDate, String userId, boolean confirmed) {}

    /**
     * Whether the automation's decisions count for an evaluation that uses this cache: a dry-run store never really confirms
     * or removes anyone, so a live store must not rely on it. In a simulated (dry-run) evaluation they count.
     */
    private boolean decisionsCount(FoodsharingStoreAutomation automation, Map<String, List<FoodsharingPickupModels.Pickup>> cache) {
        if (cache instanceof PickupCache pickupCache && pickupCache.simulated) {
            return true;
        }
        return !automation.isDryRunEnabled() && !appProperties.getFoodsharing().getAutomation().isDryRun();
    }

    private String linkedStoreRequirement(LinkedStoreRule rule) {
        if (!FoodsharingStoreAutomationLinkedStore.WINDOW_LAST_DAYS.equals(rule.windowMode())) {
            return userMessage("message.automation.linked-store.requirement.same-day");
        }
        int before = rule.windowDaysBefore();
        int after = rule.windowDaysAfter();
        if (before == after) {
            return before == 1
                    ? userMessage("message.automation.linked-store.requirement.last-days.one")
                    : userMessage("message.automation.linked-store.requirement.last-days.other", before);
        }
        if (before == 0) {
            return after == 1
                    ? userMessage("message.automation.linked-store.requirement.after.one")
                    : userMessage("message.automation.linked-store.requirement.after.other", after);
        }
        if (after == 0) {
            return before == 1
                    ? userMessage("message.automation.linked-store.requirement.before.one")
                    : userMessage("message.automation.linked-store.requirement.before.other", before);
        }
        return userMessage("message.automation.linked-store.requirement.range", before, after);
    }

    private String renderLinkedStoreMessage(LinkedStoreRule rule, String storeName, Instant pickupDate, String userName) {
        String template = rule.message() == null || rule.message().isBlank()
                ? userMessage("message.automation.linked-store.default-message")
                : rule.message();
        return normalize(template)
                .replace("{{name}}", firstName(userName))
                .replace("{{storeName}}", normalize(storeName))
                .replace("{{linkedStoreName}}", normalize(rule.requiredStoreNames()))
                .replace("{{pickupDate}}", formatSwissDateTime(pickupDate))
                .replace("{{requirement}}", linkedStoreRequirement(rule))
                .replace("{{graceMinutes}}", String.valueOf(Math.max(MINIMUM_LINKED_STORE_GRACE_MINUTES, rule.graceMinutes())));
    }

    /** The default text before it dropped its own greeting; links saved with it verbatim now use the current default. */
    private static final String PREVIOUS_LINKED_STORE_DEFAULT_MESSAGE = "Hallo {{name}}\n\nDu hast dich für die Abholung bei {{storeName}} am {{pickupDate}} eingetragen. Für diesen Betrieb gilt eine Kopplung: Wer bei {{storeName}} abholt, muss {{requirement}} auch bei {{linkedStoreName}} abholen.\n\nInnerhalb von {{graceMinutes}} Minuten nach deiner Anmeldung haben wir keine passende Abholung bei {{linkedStoreName}} gefunden. Deshalb haben wir dich wieder aus diesem Slot ausgetragen.\n\nDu kannst dich gerne erneut eintragen. Trag dich dann bitte gleich auch für eine passende Abholung bei {{linkedStoreName}} ein.\n\nDanke für dein Verständnis und deinen Einsatz!";

    /** A link's own message, or null when it is empty or still the previous default text. */
    private static String customLinkedStoreMessage(String message) {
        if (message == null || message.isBlank()) return null;
        return collapseWhitespace(message).equals(collapseWhitespace(PREVIOUS_LINKED_STORE_DEFAULT_MESSAGE)) ? null : message;
    }

    private static String collapseWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static final java.util.regex.Pattern LEADING_GREETING = java.util.regex.Pattern.compile(
            "^\\s*(?i:hallo|hoi|hey|hi|hello|sali|salü|grüezi|guten tag|liebe|lieber|liebe/r)(?=[\\s,!])[^\\n.?]{0,40}\\R\\s*");

    /** Drops a greeting line such as "Hallo Roland" at the start; Foodsharing's removal notice already greets the person. */
    static String withoutLeadingGreeting(String text) {
        if (text == null) return null;
        java.util.regex.Matcher matcher = LEADING_GREETING.matcher(text);
        return matcher.lookingAt() && matcher.end() < text.length() ? text.substring(matcher.end()) : text;
    }

    private boolean rulesAreSkipped(Instant pickupDate, Instant now) {
        Instant rulesSkippedFrom = pickupDate.atZone(SWISS_ZONE).toLocalDate().minusDays(2).atStartOfDay(SWISS_ZONE).toInstant();
        return !now.isBefore(rulesSkippedFrom);
    }

    private List<FoodsharingPickupModels.Pickup> storePickups(FoodsharingAdminConnection connection, long storeId) {
        return storePickups(connection, storeId, false);
    }

    /** {@code forceRefresh}: skip the cached payload and read the store from Foodsharing again. */
    private List<FoodsharingPickupModels.Pickup> storePickups(FoodsharingAdminConnection connection, long storeId, boolean forceRefresh) {
        Duration ttl = Duration.parse(appProperties.getFoodsharing().getAutomation().getStorePickupCacheTtl());
        Instant now = Instant.now();
        if (!forceRefresh) {
            var cached = storePickupsCacheRepository.findSnapshot(connection, storeId);
            if (cached.isPresent() && cached.get().getRefreshedAt() != null && cached.get().getRefreshedAt().plus(ttl).isAfter(now)) {
                try {
                    return deserializeStorePickups(cached.get().getPayloadJson());
                } catch (RuntimeException ignored) {
                    // Fall through and refresh from Foodsharing if the cached payload is unreadable.
                }
            }
        }
        List<FoodsharingPickupModels.Pickup> fresh = client.pickups(connection, storeId);
        saveStorePickupsCache(connection, storeId, now, serializeStorePickups(fresh));
        return fresh;
    }

    /**
     * Writes the cache row in its own transaction. Another run or an admin request may refresh or drop the same row
     * at the same time; losing that race only costs a cache entry, so it is logged and ignored.
     */
    private void saveStorePickupsCache(FoodsharingAdminConnection connection, long storeId, Instant refreshedAt, String payloadJson) {
        try {
            cacheTransaction.executeWithoutResult(status -> {
                FoodsharingStorePickupsCache cache = storePickupsCacheRepository.findByAdminConnectionAndStoreId(connection, storeId)
                        .orElseGet(FoodsharingStorePickupsCache::new);
                cache.setAdminConnection(connection);
                cache.setStoreId(storeId);
                cache.setRefreshedAt(refreshedAt);
                cache.setPayloadJson(payloadJson);
                storePickupsCacheRepository.save(cache);
            });
        } catch (RuntimeException ex) {
            log.info("Foodsharing pickups cache for store {} not saved; it was changed at the same time ({}).", storeId, ex.getClass().getSimpleName());
        }
    }

    private void deleteStorePickupsCache(FoodsharingAdminConnection connection, long storeId) {
        try {
            cacheTransaction.executeWithoutResult(status -> storePickupsCacheRepository.deleteByAdminConnectionAndStoreId(connection, storeId));
        } catch (RuntimeException ex) {
            log.warn("Foodsharing pickups cache for store {} could not be dropped.", storeId, ex);
        }
    }

    private List<FoodsharingPickupModels.Pickup> pickups(Map<String, List<FoodsharingPickupModels.Pickup>> cache, FoodsharingAdminConnection connection, long storeId) {
        return cache.computeIfAbsent(cacheKey(connection, storeId), key -> storePickups(connection, storeId));
    }

    private String cacheKey(FoodsharingAdminConnection connection, long storeId) {
        return connection.getId() + ":" + storeId;
    }

    private List<FoodsharingPickupModels.Pickup> copyPickups(List<FoodsharingPickupModels.Pickup> pickups) {
        return pickups.stream()
                .map(pickup -> new FoodsharingPickupModels.Pickup(pickup.storeId(), pickup.date(), pickup.totalSlots(), new ArrayList<>(pickup.users())))
                .toList();
    }

    private boolean isPickupFilled(FoodsharingPickupModels.Pickup pickup) {
        return pickup.totalSlots() > 0
                ? pickup.users().size() >= pickup.totalSlots()
                : !pickup.users().isEmpty();
    }

    private void updateLiveCache(Map<String, List<FoodsharingPickupModels.Pickup>> cache, String cacheKey, Instant pickupDate, String userId, boolean confirmed) {
        CacheChange change = new CacheChange(cacheKey, pickupDate, userId, confirmed);
        if (cache instanceof PickupCache pickupCache) {
            pickupCache.changes.add(change);
        }
        applyCacheChange(cache, change);
    }

    private void applyCacheChange(Map<String, List<FoodsharingPickupModels.Pickup>> cache, CacheChange change) {
        String cacheKey = change.cacheKey();
        Instant pickupDate = change.pickupDate();
        String userId = change.userId();
        boolean confirmed = change.confirmed();
        List<FoodsharingPickupModels.Pickup> current = cache.get(cacheKey);
        if (current == null) {
            return;
        }
        List<FoodsharingPickupModels.Pickup> updated = new ArrayList<>();
        for (FoodsharingPickupModels.Pickup pickup : current) {
            if (!pickup.date().equals(pickupDate)) {
                updated.add(pickup);
                continue;
            }
            List<FoodsharingPickupModels.PickupUser> users = new ArrayList<>(pickup.users());
            if (confirmed) {
                for (int i = 0; i < users.size(); i++) {
                    FoodsharingPickupModels.PickupUser currentUser = users.get(i);
                    if (currentUser.id().equals(userId)) {
                        users.set(i, new FoodsharingPickupModels.PickupUser(currentUser.id(), currentUser.name(), true, currentUser.signUpDate()));
                        break;
                    }
                }
            } else {
                users.removeIf(currentUser -> currentUser.id().equals(userId));
            }
            updated.add(new FoodsharingPickupModels.Pickup(pickup.storeId(), pickup.date(), pickup.totalSlots(), users));
        }
        cache.put(cacheKey, updated);
    }

    private boolean hasExperiencedCoPickup(FoodsharingStoreAutomation automation, Instant pickupDate, String userId, Map<String, List<FoodsharingPickupModels.Pickup>> livePickupsCache, List<FoodsharingPickupModels.StoreMember> storeMembers) {
        return pickups(livePickupsCache, automation.getAdminConnection(), automation.getStoreId()).stream()
                .filter(pickup -> pickup.date().equals(pickupDate))
                .flatMap(pickup -> pickup.users().stream())
                .filter(pickupUser -> !pickupUser.id().equals(userId))
                .anyMatch(pickupUser -> hasStoreExperience(storeMembers, pickupUser.id()));
    }

    private boolean hasStoreExperience(List<FoodsharingPickupModels.StoreMember> storeMembers, String userId) {
        return storeMembers.stream()
                .filter(member -> String.valueOf(member.id()).equals(userId))
                .anyMatch(member -> member.fetchCount() > 0 || member.lastFetch() != null);
    }

    private long calendarGapDays(Instant left, Instant right) {
        LocalDate leftDate = left.atZone(SWISS_ZONE).toLocalDate();
        LocalDate rightDate = right.atZone(SWISS_ZONE).toLocalDate();
        return Math.max(0, Math.abs(ChronoUnit.DAYS.between(leftDate, rightDate)) - 1);
    }

    private CleaningRuleExemptionView view(FoodsharingCleaningRuleExemption exemption) { return new CleaningRuleExemptionView(exemption.getId(), exemption.getFoodsharingId(), exemption.getReason()); }
    private AdvertisementAutomationView advertisementView(FoodsharingOpenSlotAdvertisementAutomation a, boolean editable) {
        User owner = a.getAdminConnection().getAdminUser();
        return new AdvertisementAutomationView(a.getStoreId(), a.getStoreName(), a.getAdvertNumber(), a.isEnabled(), a.isDryRunEnabled(), a.getTriggerHoursBefore(), a.isSendToStoreChat(), a.isSendToTelegram(), a.getTelegramChatId(), deserializeMessages(a.getStoreMessagesJson()), deserializeMessages(a.getTelegramMessagesJson()), a.getLateCancellationMessage(), a.isSendLateCancellationMessage(), a.getLateCancellationMessageMaximumHoursBeforePickup(), a.isSendLatestAdvertisementAfterLateCancellation(), editable,
                owner == null ? null : owner.getName(), owner == null ? a.getAdminConnection().getFoodsharingEmail() : owner.getEmail());
    }

    private RequestAutomationView requestView(FoodsharingRequestAutomation a, boolean editable) {
        User owner = a.getAdminConnection().getAdminUser();
        return new RequestAutomationView(a.getStoreId(), a.getStoreName(), a.isEnabled(), a.isDryRunEnabled(), a.isDistanceRuleEnabled(), a.getMaximumDistanceKm(), editable,
                owner == null ? null : owner.getName(), owner == null ? a.getAdminConnection().getFoodsharingEmail() : owner.getEmail());
    }

    private String serializeMessages(List<String> messages) {
        try { return objectMapper.writeValueAsString(messages == null ? List.of() : messages.stream().map(this::normalize).filter(v -> !v.isBlank()).toList()); }
        catch (Exception ex) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.UNEXPECTED_ERROR, List.of("Could not store advertisement messages.")); }
    }

    private List<String> deserializeMessages(String json) {
        try { return objectMapper.readValue(json == null || json.isBlank() ? "[]" : json, new TypeReference<List<String>>() {}); }
        catch (Exception ex) { return List.of(); }
    }

    private String adminFoodsharingId(FoodsharingAdminConnection connection) {
        String connectionFoodsharingId = normalize(connection.getFoodsharingUserId());
        return connectionFoodsharingId.isBlank() ? normalize(connection.getAdminUser().getFoodsharingId()) : connectionFoodsharingId;
    }

    private String renderAdvertisement(String template, Instant pickupDate, String adminFoodsharingId) {
        var zdt = pickupDate.atZone(SWISS_ZONE);
        String normalizedAdminFoodsharingId = adminFoodsharingId == null ? "" : adminFoodsharingId.trim();
        String adminProfileUrl = normalizedAdminFoodsharingId.isBlank() ? "" : "https://foodsharing.de/user/" + normalizedAdminFoodsharingId + "/profile";
        String time = zdt.toLocalTime().truncatedTo(ChronoUnit.MINUTES).toString();
        String dateDe = zdt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN));
        String weekday = zdt.format(DateTimeFormatter.ofPattern("EEEE", Locale.GERMAN));
        return normalize(template).replace("{{date}}", zdt.toLocalDate().toString())
                .replace("{{dateDe}}", dateDe)
                .replace("{{weekday}}", weekday)
                .replace("{{time}}", time)
                .replace("{{datetime}}", zdt.toLocalDate() + " " + time)
                .replace("{{datetimeDe}}", weekday + ", " + dateDe + " um " + time)
                .replace("{{adminFoodsharingId}}", normalizedAdminFoodsharingId)
                .replace("{{adminProfileUrl}}", adminProfileUrl);
    }

    private String formatSwissDateTime(Instant instant) {
        var zdt = instant.atZone(SWISS_ZONE);
        return zdt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMAN));
    }

    private String firstName(String userName) {
        String normalizedName = normalize(userName);
        int separatorIndex = normalizedName.indexOf(' ');
        return separatorIndex < 0 ? normalizedName : normalizedName.substring(0, separatorIndex);
    }

    private String formatOpenCleaningSlots(List<FoodsharingPickupModels.Pickup> cleaningPickups, Instant now) {
        List<String> openSlots = cleaningPickups.stream()
                .filter(pickup -> !pickup.date().isBefore(now))
                .filter(pickup -> pickup.users().isEmpty())
                .sorted(Comparator.comparing(FoodsharingPickupModels.Pickup::date))
                .limit(3)
                .map(pickup -> formatSwissDateTime(pickup.date()))
                .toList();
        return openSlots.isEmpty() ? userMessage("message.automation.no-open-cleaning-slots") : String.join("\n", openSlots);
    }

    private String normalize(String value) { return value == null ? "" : value.trim(); }
    private String blankToNull(String value) { String normalized = normalize(value); return normalized.isBlank() ? null : normalized; }

    private String normalizeRequired(String value) { if (value == null || value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED); return value.trim(); }
    private void saveAudit(FoodsharingStoreAutomation a, String userId, String userName, Instant pickupDate, boolean dryRun, FoodsharingPickupAutomationDecision decision, String reasons, String userMessage, String error) { var audit = new FoodsharingPickupAutomationAudit(); audit.setBezirk(a.getBezirk()); audit.setAdminConnection(a.getAdminConnection()); audit.setStoreId(a.getStoreId()); audit.setFoodsharingUserId(userId); audit.setFoodsharingUserName(userName == null || userName.isBlank() ? null : userName); audit.setPickupDate(pickupDate); audit.setDryRun(dryRun); audit.setDecision(decision); audit.setReasons(reasons); audit.setUserMessage(userMessage); audit.setFoodsharingError(error); auditRepository.save(audit); }
    private boolean canSeeAllAutomations() { return currentActorService.hasPermission(UserPermission.CAN_SEE_ALL_AUTOMATION_DECISIONS); }

    /**
     * The Bezirk a request/advertisement list is limited to. A requested slug goes through {@link #requireBezirk(String, User)},
     * so only admins may pick another Bezirk. Without a slug admins keep the legacy unscoped view (null), everybody else
     * is limited to their own Bezirk.
     */
    private Bezirk bezirkScope(String bezirkSlug, User user) {
        if (bezirkSlug != null && !bezirkSlug.isBlank()) return requireBezirk(bezirkSlug, user);
        return user.isCanManageUsers() ? null : requireBezirk(user);
    }

    /**
     * Request and advertisement automations have no Bezirk of their own: a store belongs to the Bezirk of its slot
     * automation, otherwise to the Bezirk of the account automating it (same mapping as the metric archive).
     */
    private record BezirkStores(Bezirk bezirk, FoodsharingMetricArchiveService.StoreBezirks stores) {
        boolean contains(long storeId, FoodsharingAdminConnection owner) {
            return bezirk == null || bezirk.getId().equals(stores.of(storeId, FoodsharingMetricArchiveService.bezirkId(owner)));
        }
    }

    private BezirkStores bezirkStores(Bezirk bezirk) {
        return new BezirkStores(bezirk, bezirk == null ? null : metricArchiveService.storeBezirks());
    }

    private Set<Long> bezirkStoreIds(Bezirk bezirk) {
        BezirkStores bezirkStores = bezirkStores(bezirk);
        Set<Long> storeIds = new java.util.HashSet<>();
        bezirkStores.stores().byStore().forEach((storeId, bezirkId) -> { if (bezirk.getId().equals(bezirkId)) storeIds.add(storeId); });
        requestAutomationRepository.findAll().stream()
                .filter(a -> bezirkStores.contains(a.getStoreId(), a.getAdminConnection()))
                .forEach(a -> storeIds.add(a.getStoreId()));
        advertisementAutomationRepository.findAll().stream()
                .filter(a -> bezirkStores.contains(a.getStoreId(), a.getAdminConnection()))
                .forEach(a -> storeIds.add(a.getStoreId()));
        return storeIds;
    }

    private boolean onlyOwnDecisions(boolean onlyMine) { return onlyMine || !currentActorService.hasPermission(UserPermission.CAN_SEE_ALL_AUTOMATION_DECISIONS); }
    private java.util.Optional<FoodsharingAdminConnection> currentConnection() { return connectionRepository.findByAdminUser(currentActorService.requireAutomationUser()); }
    private FoodsharingAdminConnection requireConnection() { return connectionRepository.findByAdminUser(currentActorService.requireAutomationUser()).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED)); }
    private Bezirk requireBezirk(User user) { if (user.getBezirk() == null) throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("User has no Bezirk.")); return user.getBezirk(); }
    private Bezirk requireBezirk(String bezirkSlug, User user) {
        Bezirk requested = bezirkService.requireActive(bezirkSlug);
        if (user.isCanManageUsers()) {
            return requested;
        }
        Bezirk assigned = requireBezirk(user);
        if (!assigned.getId().equals(requested.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BEZIRK_NOT_FOUND);
        }
        return requested;
    }
    private StoreAutomationView view(long storeId, String storeName, FoodsharingStoreAutomation automation, FoodsharingAdminConnection currentConnection) {
        if (automation == null) {
            return new StoreAutomationView(storeId, storeName, false, true, false, 0, false, false, false, true, null, null, List.of(), List.of(), List.of(), null);
        }
        User owner = automation.getAdminConnection().getAdminUser();
        FoodsharingGapStoreGroup gapGroup = gapStoreGroup(automation);
        return new StoreAutomationView(
                storeId,
                storeName,
                automation.isEnabled(),
                automation.isDryRunEnabled(),
                automation.isGapRuleEnabled(),
                automation.getMinimumGapDays(),
                automation.isCleaningRuleEnabled(),
                automation.isExperienceRuleEnabled(),
                automation.isPublicHolidayRuleEnabled(),
                automation.getAdminConnection().getId().equals(currentConnection.getId()),
                owner == null ? null : owner.getName(),
                owner == null ? null : owner.getEmail(),
                linkedStoreRepository.findAllByAutomationOrderByCreatedAtAscIdAsc(automation).stream()
                        .map(link -> new LinkedStoreRuleView(linkedStoreRefs(link), link.isBidirectional(), link.getWindowMode(), link.getWindowDaysBefore(), link.getWindowDaysAfter(), link.getGraceMinutes(), customLinkedStoreMessage(link.getMessage()), linkedStoreExemptions(link)))
                        .toList(),
                incomingLinkedStores(automation).stream()
                        // Links saved on other accounts' automations are only shown to people who may see all automations.
                        .filter(link -> link.getAutomation().getAdminConnection().getId().equals(currentConnection.getId()) || canSeeAllAutomations())
                        .map(link -> new IncomingLinkedStoreRuleView(link.getAutomation().getStoreId(), link.getAutomation().getStoreName(), link.isBidirectional(), link.getWindowMode(), link.getWindowDaysAfter(), link.getWindowDaysBefore(), link.getGraceMinutes()))
                        .toList(),
                gapGroup == null ? List.of() : gapStoreGroupMembers(gapGroup).stream()
                        .filter(member -> member.getStoreId() != storeId)
                        .map(member -> new LinkedStoreRef(member.getStoreId(), member.getStoreName()))
                        .toList(),
                gapGroup == null ? null : gapGroup.getMinimumGapDays());
    }
    private AuditView view(FoodsharingPickupAutomationAudit a, Map<Long, String> storeNames) { return new AuditView(a.getStoreId(), storeNames.getOrDefault(a.getStoreId(), String.valueOf(a.getStoreId())), a.getFoodsharingUserId(), a.getFoodsharingUserName(), a.getPickupDate(), a.isDryRun(), a.getDecision().name(), a.getReasons(), a.getUserMessage(), a.getFoodsharingError(), a.getCreatedAt()); }
    private long monthsAgo(Instant value, Instant now) { return value == null ? 0 : ChronoUnit.MONTHS.between(value.atZone(SWISS_ZONE).toLocalDate(), now.atZone(SWISS_ZONE).toLocalDate()); }
    private String userMessage(String key, Object... arguments) { return messageSource.getMessage(key, arguments, Locale.GERMAN); }
    private String declineFooter() { return userMessage("message.automation.decline-footer", appProperties.getFrontend().getBaseUrl().replaceFirst("/+$", "") + "/help"); }
    private String monthSuffix(long months) { return userMessage(months == 1 ? "message.automation.month.one" : "message.automation.month.other", months); }
    private String daySuffix(long days) { return userMessage(days == 1 ? "message.automation.day.one" : "message.automation.day.other", days); }

    public record CleaningRuleExemptionRequest(String foodsharingId, String reason) {}
    public record CleaningRuleExemptionView(UUID id, String foodsharingId, String reason) {}
    public record ConnectionStatus(boolean connected, String email, String foodsharingUserId, Instant authenticatedAt, boolean telegramBotTokenConfigured, boolean automationEnabled, boolean automationDryRun) {}
    public record ManagedStoreView(long storeId, String storeName) {}
    public record StoreOverviewView(List<StoreAutomationView> automations, List<ManagedStoreView> availableStores, String linkedStoreDefaultMessage, List<GapStoreGroupView> gapStoreGroups, List<Long> managedStoreIds) {}
    public record GapStoreGroupView(int minimumGapDays, List<LinkedStoreRef> stores) {}
    /** {@code linkedStores} null keeps the saved links unchanged; an empty list removes them. Each link holds a list of stores, one pickup in any of them satisfies it. {@code gapStores} are the other stores sharing {@code gapStoresMinimumDays}, with the same meaning of null and empty. */
    public record StoreAutomationRequest(String storeName, boolean enabled, boolean dryRunEnabled, boolean gapRuleEnabled, int minimumGapDays, boolean cleaningRuleEnabled, boolean experienceRuleEnabled, boolean publicHolidayRuleEnabled, List<LinkedStoreRuleRequest> linkedStores, List<LinkedStoreRef> gapStores, Integer gapStoresMinimumDays) {}
    public record StoreAutomationView(long storeId, String storeName, boolean enabled, boolean dryRunEnabled, boolean gapRuleEnabled, int minimumGapDays, boolean cleaningRuleEnabled, boolean experienceRuleEnabled, boolean publicHolidayRuleEnabled, boolean editable, String ownerName, String ownerEmail, List<LinkedStoreRuleView> linkedStores, List<IncomingLinkedStoreRuleView> incomingLinkedStores, List<LinkedStoreRef> gapStores, Integer gapStoresMinimumDays) {}
    public record LinkedStoreRef(long storeId, String storeName) {}
    public record LinkedStoreRuleRequest(List<LinkedStoreRef> linkedStores, boolean bidirectional, String windowMode, int windowDaysBefore, int windowDaysAfter, int graceMinutes, String message, List<LinkedStoreExemption> exemptions) {}
    public record LinkedStoreExemption(String foodsharingId, String reason) {}
    public record LinkedStoreRuleView(List<LinkedStoreRef> linkedStores, boolean bidirectional, String windowMode, int windowDaysBefore, int windowDaysAfter, int graceMinutes, String message, List<LinkedStoreExemption> exemptions) {}
    /** A link saved on another store's automation that points at this store; a bidirectional one also applies to this store. */
    public record IncomingLinkedStoreRuleView(long sourceStoreId, String sourceStoreName, boolean bidirectional, String windowMode, int windowDaysBefore, int windowDaysAfter, int graceMinutes) {}
    public record ExcludedDateRequest(long storeId, LocalDate excludedDate) {}
    public record ExcludedDateView(UUID id, long storeId, String storeName, LocalDate excludedDate, String eventName, boolean manual) {}
    public record AuditView(long storeId, String storeName, String foodsharingUserId, String foodsharingUserName, Instant pickupDate, boolean dryRun, String decision, String reasons, String userMessage, String error, Instant createdAt) {}
    public record StorePickupUserView(String foodsharingUserId, String name, int futurePickupCount, List<StorePickupView> futurePickups) {}
    public record StorePickupView(long storeId, String storeName, Instant pickupDate, boolean confirmed, List<String> validationErrors) {}

    private static final class StorePickupUserBuilder {
        private final String foodsharingUserId;
        private String name;
        private final List<StorePickupView> futurePickups = new ArrayList<>();

        private StorePickupUserBuilder(String foodsharingUserId, String name) {
            this.foodsharingUserId = foodsharingUserId;
            this.name = name;
        }

        private void addPickup(StorePickupView pickup) {
            futurePickups.add(pickup);
        }

        private StorePickupUserView build() {
            List<StorePickupView> sortedPickups = futurePickups.stream()
                    .sorted(Comparator.comparing(StorePickupView::pickupDate))
                    .toList();
            return new StorePickupUserView(foodsharingUserId, name, sortedPickups.size(), sortedPickups);
        }
    }

    @Transactional(readOnly = true)
    public List<ExtraAutomationAuditView> extraAutomationAudit(boolean onlyMine) {
        return extraAutomationAudit(null, onlyMine);
    }

    @Transactional(readOnly = true)
    public List<ExtraAutomationAuditView> extraAutomationAudit(String bezirkSlug, boolean onlyMine) {
        List<ExtraAutomationAuditView> result = new ArrayList<>();
        result.addAll(requestAutomationAudit(bezirkSlug, onlyMine));
        result.addAll(openSlotAdvertisementAudit(bezirkSlug, onlyMine));
        return result.stream().sorted(Comparator.comparing(ExtraAutomationAuditView::createdAt).reversed()).limit(100).toList();
    }

    @Transactional(readOnly = true)
    public List<ExtraAutomationAuditView> requestAutomationAudit(boolean onlyMine) {
        return requestAutomationAudit(null, onlyMine);
    }

    @Transactional(readOnly = true)
    public List<ExtraAutomationAuditView> requestAutomationAudit(String bezirkSlug, boolean onlyMine) {
        Bezirk bezirk = bezirkScope(bezirkSlug, currentActorService.requireAutomationUser());
        Set<Long> storeIds = bezirk == null ? null : bezirkStoreIds(bezirk);
        if (storeIds != null && storeIds.isEmpty()) return List.of();
        List<FoodsharingRequestAutomationAudit> audits = onlyOwnDecisions(onlyMine)
                ? currentConnection().map(connection -> storeIds == null
                        ? requestAutomationAuditRepository.findTop100ByAutomationAdminConnectionOrderByCreatedAtDesc(connection)
                        : requestAutomationAuditRepository.findTop100ByAutomationAdminConnectionAndStoreIdInOrderByCreatedAtDesc(connection, storeIds)).orElseGet(List::of)
                : storeIds == null
                        ? requestAutomationAuditRepository.findTop100ByOrderByCreatedAtDesc()
                        : requestAutomationAuditRepository.findTop100ByStoreIdInOrderByCreatedAtDesc(storeIds);
        return audits.stream()
                .map(a -> new ExtraAutomationAuditView("REQUEST", a.getStoreId(), a.getStoreName(), null, null, a.getFoodsharingUserId(), a.getFoodsharingUserName(), a.isDryRun(), a.getStatus(), a.getReason(), null, a.getError(), a.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExtraAutomationAuditView> openSlotAdvertisementAudit(boolean onlyMine) {
        return openSlotAdvertisementAudit(null, onlyMine);
    }

    @Transactional(readOnly = true)
    public List<ExtraAutomationAuditView> openSlotAdvertisementAudit(String bezirkSlug, boolean onlyMine) {
        List<ExtraAutomationAuditView> result = new ArrayList<>(openSlotAdvertisementSentAudit(bezirkSlug, onlyMine));
        result.addAll(plannedOpenSlotAdvertisementNotifications(bezirkSlug, onlyMine));
        return result.stream()
                .sorted(Comparator.comparing((ExtraAutomationAuditView audit) -> !"PLANNED".equals(audit.status()))
                        .thenComparing(ExtraAutomationAuditView::notificationDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ExtraAutomationAuditView::createdAt, Comparator.reverseOrder()))
                .limit(100)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExtraAutomationAuditView> openSlotAdvertisementSentAudit(boolean onlyMine) {
        return openSlotAdvertisementSentAudit(null, onlyMine);
    }

    @Transactional(readOnly = true)
    public List<ExtraAutomationAuditView> openSlotAdvertisementSentAudit(String bezirkSlug, boolean onlyMine) {
        Bezirk bezirk = bezirkScope(bezirkSlug, currentActorService.requireAutomationUser());
        Set<Long> storeIds = bezirk == null ? null : bezirkStoreIds(bezirk);
        if (storeIds != null && storeIds.isEmpty()) return List.of();
        List<FoodsharingOpenSlotAdvertisementAudit> audits = onlyOwnDecisions(onlyMine)
                ? currentConnection().map(connection -> storeIds == null
                        ? advertisementAuditRepository.findTop100ByAutomationAdminConnectionAndStatusNotOrderByCreatedAtDesc(connection, "OCCUPIED_AT_TRIGGER")
                        : advertisementAuditRepository.findTop100ByAutomationAdminConnectionAndStoreIdInAndStatusNotOrderByCreatedAtDesc(connection, storeIds, "OCCUPIED_AT_TRIGGER")).orElseGet(List::of)
                : storeIds == null
                        ? advertisementAuditRepository.findTop100ByStatusNotOrderByCreatedAtDesc("OCCUPIED_AT_TRIGGER")
                        : advertisementAuditRepository.findTop100ByStoreIdInAndStatusNotOrderByCreatedAtDesc(storeIds, "OCCUPIED_AT_TRIGGER");
        return audits.stream()
                .map(a -> new ExtraAutomationAuditView("ADVERTISEMENT", a.getStoreId(), a.getStoreName(), a.getPickupDate(), a.getPickupDate().minus(Duration.ofHours(a.getTriggerHoursBefore())), a.getFoodsharingUserId(), a.getFoodsharingUserName(), a.isDryRun(), a.getStatus(), a.getReason(), a.getMessage(), a.getError(), a.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExtraAutomationAuditView> plannedOpenSlotAdvertisementNotifications(boolean onlyMine) {
        return plannedOpenSlotAdvertisementNotifications(null, onlyMine);
    }

    @Transactional(readOnly = true)
    public List<ExtraAutomationAuditView> plannedOpenSlotAdvertisementNotifications(String bezirkSlug, boolean onlyMine) {
        return plannedAdvertisementNotifications(bezirkScope(bezirkSlug, currentActorService.requireAutomationUser()), onlyMine);
    }

    private List<ExtraAutomationAuditView> plannedAdvertisementNotifications(Bezirk bezirk, boolean onlyMine) {
        Instant now = Instant.now();
        BezirkStores bezirkStores = bezirkStores(bezirk);
        List<FoodsharingOpenSlotAdvertisementAutomation> automations = advertisementAutomationRepository.findAllByEnabledTrue().stream()
                .filter(automation -> bezirkStores.contains(automation.getStoreId(), automation.getAdminConnection()))
                .toList();
        if (onlyOwnDecisions(onlyMine)) {
            FoodsharingAdminConnection connection = currentConnection().orElse(null);
            if (connection == null) return List.of();
            automations = automations.stream()
                    .filter(automation -> automation.getAdminConnection().getId().equals(connection.getId()))
                    .toList();
        }
        List<ExtraAutomationAuditView> planned = new ArrayList<>();
        for (FoodsharingOpenSlotAdvertisementAutomation automation : automations) {
            boolean dryRun = appProperties.getFoodsharing().getAutomation().isDryRun() || automation.isDryRunEnabled();
            try {
                for (FoodsharingPickupModels.Pickup pickup : storePickups(automation.getAdminConnection(), automation.getStoreId())) {
                    Instant notificationDate = pickup.date().minus(Duration.ofHours(automation.getTriggerHoursBefore()));
                    if (!pickup.date().isAfter(now) || isPickupFilled(pickup)
                            || advertisementAuditRepository.existsByAutomationAndPickupDateAndTriggerHoursBefore(automation, pickup.date(), automation.getTriggerHoursBefore())) {
                        continue;
                    }
                    planned.add(new ExtraAutomationAuditView("ADVERTISEMENT", automation.getStoreId(), automation.getStoreName(), pickup.date(), notificationDate, null, null, dryRun, "PLANNED", "Notification is pending while this pickup remains empty.", null, null, now));
                }
            } catch (RuntimeException exception) {
                log.warn("Could not load planned advertisements for storeId={}", automation.getStoreId(), exception);
            }
        }
        return planned;
    }

    public record RequestAutomationRequest(String storeName, boolean enabled, boolean dryRunEnabled, boolean distanceRuleEnabled, Double maximumDistanceKm) {}
    public record RequestAutomationView(long storeId, String storeName, boolean enabled, boolean dryRunEnabled, boolean distanceRuleEnabled, double maximumDistanceKm, boolean editable, String ownerName, String ownerEmail) {}
    public record AdvertisementAutomationRequest(String storeName, boolean enabled, boolean dryRunEnabled, int triggerHoursBefore, boolean sendToStoreChat, boolean sendToTelegram, String telegramChatId, List<String> storeMessages, List<String> telegramMessages, String lateCancellationMessage, boolean sendLateCancellationMessage, int lateCancellationMessageMaximumHoursBeforePickup, boolean sendLatestAdvertisementAfterLateCancellation) {}
    public record AdvertisementAutomationView(long storeId, String storeName, int advertNumber, boolean enabled, boolean dryRunEnabled, int triggerHoursBefore, boolean sendToStoreChat, boolean sendToTelegram, String telegramChatId, List<String> storeMessages, List<String> telegramMessages, String lateCancellationMessage, boolean sendLateCancellationMessage, int lateCancellationMessageMaximumHoursBeforePickup, boolean sendLatestAdvertisementAfterLateCancellation, boolean editable, String ownerName, String ownerEmail) {}
    public record TelegramChatView(String id, String title, String type) {}
    public record AutomationRunSummary(int evaluated, int acted, int skipped, boolean dryRun, List<String> messages) {}
    public record ExtraAutomationOverviewView(List<RequestAutomationView> requestAutomations, List<AdvertisementAutomationView> advertisementAutomations) {}
    public record ExtraAutomationAuditView(String automationType, long storeId, String storeName, Instant pickupDate, Instant notificationDate, String foodsharingUserId, String foodsharingUserName, boolean dryRun, String status, String reason, String message, String error, Instant createdAt) {}
}
