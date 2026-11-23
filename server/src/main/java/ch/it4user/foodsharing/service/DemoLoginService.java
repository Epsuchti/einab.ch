package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.EinAb;
import ch.it4user.foodsharing.domain.entity.Slot;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.EinAbCategory;
import ch.it4user.foodsharing.domain.enumtype.LanguageCode;
import ch.it4user.foodsharing.domain.enumtype.SlotStatus;
import ch.it4user.foodsharing.openapi.model.AuthResponse;
import ch.it4user.foodsharing.repository.BezirkRepository;
import ch.it4user.foodsharing.repository.EinAbRepository;
import ch.it4user.foodsharing.repository.SlotRepository;
import ch.it4user.foodsharing.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-tap sign-in for app testers and the Apple/Google store reviewers on the test server, who cannot receive the
 * Foodsharing message with the login link: every tap creates a new demo account (Neuling or EinAb-Geber:in).
 * <p>
 * Safety: off unless {@code app.auth.demo-login.enabled=true}. The application refuses to start when it is switched on
 * in the prod profile, outside the test/local profiles, or on the test server without the outbound restriction
 * ({@link OutboundMessageGuard}). It only signs in accounts it created itself in the demo ID range, never a real
 * Foodsharing account, and the demo IDs never reach Foodsharing (see {@link FoodsharingClient}).
 * <p>
 * The demo EinAb-Geber:innen in {@link DemoAccounts#SEED_TEACHERS} (no login) and their bookable EinAbs are
 * (re)created at startup and on every new account, so they survive a refresh of the test database from prod.
 */
@Service
public class DemoLoginService {

    private static final Logger log = LoggerFactory.getLogger(DemoLoginService.class);
    private static final int MAX_CREATED_ACCOUNTS_PER_IP = 20;
    private static final Duration CREATED_ACCOUNTS_WINDOW = Duration.ofHours(1);
    /** Each demo teacher keeps at least this many free future slots, so many testers can book at the same time. */
    private static final int MIN_FREE_SLOTS_PER_TEACHER = 8;
    private static final int MAX_FUTURE_EINABS_PER_TEACHER = 8;
    private static final int SLOTS_PER_EINAB = 4;
    private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
    /** Fictional number (Swiss 000 prefix is not assigned), so nobody real is ever called from the test server. */
    private static final String DEMO_PHONE_PREFIX = "+41 00 000 00 ";

    private final boolean enabled;
    private final AppProperties.DemoLogin config;
    private final AuthService authService;
    private final UserRepository userRepository;
    private final BezirkRepository bezirkRepository;
    private final EinAbRepository einAbRepository;
    private final SlotRepository slotRepository;
    private final TransactionTemplate transactionTemplate;
    private final Map<String, Deque<Instant>> createdAccountsByIp = new ConcurrentHashMap<>();

    public DemoLoginService(AppProperties appProperties,
                            Environment environment,
                            AuthService authService,
                            UserRepository userRepository,
                            BezirkRepository bezirkRepository,
                            EinAbRepository einAbRepository,
                            SlotRepository slotRepository,
                            TransactionTemplate transactionTemplate) {
        this.config = appProperties.getAuth().getDemoLogin();
        this.authService = authService;
        this.userRepository = userRepository;
        this.bezirkRepository = bezirkRepository;
        this.einAbRepository = einAbRepository;
        this.slotRepository = slotRepository;
        this.transactionTemplate = transactionTemplate;
        this.enabled = checkEnabled(config, environment, appProperties.getOutbound().isRestricted());
    }

    static boolean checkEnabled(AppProperties.DemoLogin config, Environment environment, boolean outboundRestricted) {
        if (!config.isEnabled()) {
            return false;
        }
        Set<String> profiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        if (profiles.contains("prod")) {
            throw new IllegalStateException("app.auth.demo-login.enabled must never be true in the prod profile");
        }
        if (!profiles.contains("test") && !profiles.contains("local")) {
            throw new IllegalStateException("app.auth.demo-login.enabled is only allowed in the test or local profile, active: " + profiles);
        }
        if (profiles.contains("test") && !outboundRestricted) {
            throw new IllegalStateException("app.auth.demo-login.enabled needs app.outbound.restricted=true on the test server");
        }
        log.warn("Demo login is ON: every tap on the login page creates a new demo account");
        return true;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The shared tester ID while the demo login is on, otherwise null. */
    public String sharedFoodsharingId() {
        return enabled ? config.getSharedFoodsharingId().trim() : null;
    }

    /** Whether this login ID is the shared tester ID (only while the demo login is on). */
    public boolean isSharedFoodsharingId(String foodsharingId) {
        return enabled && foodsharingId != null && foodsharingId.trim().equals(config.getSharedFoodsharingId().trim());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        if (!enabled) {
            return;
        }
        try {
            seed();
        } catch (RuntimeException exception) {
            // Never block the startup of the test server; the next demo login seeds again.
            log.error("Could not seed the demo accounts: {}", exception.getMessage(), exception);
        }
    }

    /**
     * One-tap sign-in for testers: creates a new demo account with the next free generated ID and signs it in, so any
     * number of testers each get their own account without a password. Test server only.
     */
    public AuthResponse createAccount(String sharedFoodsharingId, DemoAccounts.Role role, String clientIp) {
        if (!isSharedFoodsharingId(sharedFoodsharingId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND);
        }
        String ip = clientIp == null ? "unknown" : clientIp;
        if (countRecent(createdAccountsByIp, ip, CREATED_ACCOUNTS_WINDOW) >= MAX_CREATED_ACCOUNTS_PER_IP) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCode.TOO_MANY_LOGIN_REQUESTS);
        }
        record(createdAccountsByIp, ip);
        seed();
        String foodsharingId = createGeneratedUser(role == null ? DemoAccounts.Role.NEWCOMER : role);
        return authService.createDemoSession(foodsharingId);
    }

    /** Synchronized: the next ID is max + 1, and the test server runs as a single instance. */
    private synchronized String createGeneratedUser(DemoAccounts.Role role) {
        return transactionTemplate.execute(status -> {
            Bezirk bezirk = demoBezirk();
            long next = userRepository.findMaxFoodsharingIdBetween(
                            String.valueOf(DemoAccounts.FIRST_GENERATED_ID), String.valueOf(DemoAccounts.LAST_GENERATED_ID))
                    .map(Long::parseLong).map(max -> max + 1).orElse(DemoAccounts.FIRST_GENERATED_ID);
            if (next > DemoAccounts.LAST_GENERATED_ID) {
                throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.CONFLICT);
            }
            long number = next - DemoAccounts.FIRST_GENERATED_ID + 1;
            boolean teacher = role == DemoAccounts.Role.TEACHER;
            User user = new User();
            user.setFoodsharingId(String.valueOf(next));
            user.setName((teacher ? "Test-EinAb-Geber:in " : "Test-Neuling ") + number);
            user.setPhoneNumber("+41 00 0" + String.format("%06d", number % 1_000_000));
            user.setPreferredLanguage(LanguageCode.DE);
            user.setActive(true);
            user.setBezirk(bezirk);
            user.setCanGiveEinAbs(teacher);
            user.setVerified(teacher);
            userRepository.saveAndFlush(user);
            log.info("Created generated demo account {} ({})", user.getFoodsharingId(), user.getName());
            return user.getFoodsharingId();
        });
    }

    private void seed() {
        transactionTemplate.executeWithoutResult(status -> {
            Bezirk bezirk = demoBezirk();
            List<User> teachers = new ArrayList<>();
            int index = 0;
            for (DemoAccounts.Account account : DemoAccounts.SEED_TEACHERS) {
                index++;
                teachers.add(ensureUser(account, bezirk, index));
            }
            EinAbCategory category = demoCategory(bezirk);
            for (int t = 0; t < teachers.size(); t++) {
                ensureFutureEinAbs(teachers.get(t), bezirk, category, t);
            }
        });
    }

    private Bezirk demoBezirk() {
        String slug = config.getBezirkSlug() == null ? "" : config.getBezirkSlug().trim();
        if (!slug.isEmpty()) {
            return bezirkRepository.findBySlugAndActiveTrue(slug)
                    .orElseThrow(() -> new IllegalStateException("Demo Bezirk not found or inactive: " + slug));
        }
        return bezirkRepository.findAllByActiveTrueOrderBySortOrderAscNameAsc().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No active Bezirk for the demo accounts"));
    }

    private User ensureUser(DemoAccounts.Account account, Bezirk bezirk, int index) {
        User user = userRepository.findByFoodsharingIdIgnoreCaseForUpdate(account.foodsharingId()).orElseGet(() -> {
            User created = new User();
            created.setFoodsharingId(account.foodsharingId());
            created.setPreferredLanguage(LanguageCode.DE);
            created.setPhoneNumber(DEMO_PHONE_PREFIX + String.format("%02d", index));
            log.info("Created demo account {} ({})", account.foodsharingId(), account.name());
            return created;
        });
        user.setName(account.name());
        user.setActive(true);
        if (user.getBezirk() == null || !user.getBezirk().getId().equals(bezirk.getId())) {
            user.setBezirk(bezirk);
        }
        user.setCanGiveEinAbs(true);
        user.setVerified(true);
        // Demo accounts never get admin rights.
        user.setCanManageUsers(false);
        user.setCanUseAutomations(false);
        user.setCanSeeAllAutomationDecisions(false);
        user.setCanSeeUserPickupCountGrouping(false);
        user.setCanManageSurveys(false);
        return userRepository.save(user);
    }

    private static EinAbCategory demoCategory(Bezirk bezirk) {
        for (EinAbCategory candidate : List.of(EinAbCategory.SUPERMARKET, EinAbCategory.BAKERY, EinAbCategory.MARKET,
                EinAbCategory.TAKEOUT, EinAbCategory.RESTAURANT)) {
            if (!bezirk.getDisabledCategories().contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Keeps a few future EinAbs per demo teacher so the demo newcomers always have something to book. Saved directly,
     * without the "new EinAb" event, so no notification goes out to the (prod-copied) subscribers of the test server.
     */
    private void ensureFutureEinAbs(User teacher, Bezirk bezirk, EinAbCategory category, int teacherIndex) {
        if (category == null) {
            return;
        }
        long future = einAbRepository.findAllByTeacherAndBezirk(teacher, bezirk, false, PageRequest.of(0, 1)).getTotalElements();
        long free = slotRepository.countByTeacherAndStatusAndEinAbStartDateTimeAfter(teacher, SlotStatus.AVAILABLE, Instant.now());
        LocalDate today = LocalDate.now(ZURICH);
        for (long n = future; free < MIN_FREE_SLOTS_PER_TEACHER && n < MAX_FUTURE_EINABS_PER_TEACHER; n++, free += SLOTS_PER_EINAB) {
            Instant start = today.plusDays(3 + teacherIndex + 7 * n)
                    .atTime(LocalTime.of(18, 0)).atZone(ZURICH).toInstant();
            EinAb einAb = new EinAb();
            einAb.setBezirk(bezirk);
            einAb.setTeacher(teacher);
            einAb.setCategory(category);
            einAb.setStartDateTime(start);
            einAb.setBusinessName("Demo-Laden " + (teacherIndex + 1));
            einAb.setLocation("Teststrasse " + (teacherIndex + 1) + ", 8000 Zürich (Demo)");
            einAb.setPrivateAddress(einAb.getLocation());
            einAb.setPublicLocation("Zürich (Demo)");
            einAb.setPublicGeneralLocation("Zürich (Demo)");
            einAb.setPublicInfo("Demo-EinAb auf dem Test-Server. Findet nicht wirklich statt.");
            einAb.setPrivateInfo("Treffpunkt beim Eingang (Demo).");
            einAb.setSlotCount(SLOTS_PER_EINAB);
            EinAb saved = einAbRepository.save(einAb);
            List<Slot> slots = new ArrayList<>();
            for (int s = 0; s < SLOTS_PER_EINAB; s++) {
                Slot slot = new Slot();
                slot.setEinAb(saved);
                slot.setTeacher(teacher);
                slot.setStatus(SlotStatus.AVAILABLE);
                slots.add(slot);
            }
            slotRepository.saveAll(slots);
            log.info("Created demo EinAb for {} on {}", teacher.getFoodsharingId(), start);
        }
    }

    private static int countRecent(Map<String, Deque<Instant>> eventsByIp, String ip, Duration window) {
        Deque<Instant> events = eventsByIp.get(ip);
        if (events == null) {
            return 0;
        }
        synchronized (events) {
            Instant windowStart = Instant.now().minus(window);
            while (!events.isEmpty() && events.peekFirst().isBefore(windowStart)) {
                events.pollFirst();
            }
            return events.size();
        }
    }

    private static void record(Map<String, Deque<Instant>> eventsByIp, String ip) {
        Deque<Instant> events = eventsByIp.computeIfAbsent(ip, key -> new ArrayDeque<>());
        synchronized (events) {
            events.addLast(Instant.now());
        }
    }
}
