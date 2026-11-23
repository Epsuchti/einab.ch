package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.EinAb;
import ch.it4user.foodsharing.domain.entity.BookingComment;
import ch.it4user.foodsharing.domain.entity.PrivateInfoTemplate;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.entity.Slot;
import ch.it4user.foodsharing.domain.enumtype.EinAbCategory;
import ch.it4user.foodsharing.domain.enumtype.LanguageCode;
import ch.it4user.foodsharing.domain.enumtype.SlotStatus;
import ch.it4user.foodsharing.repository.EinAbRepository;
import ch.it4user.foodsharing.repository.BookingCommentRepository;
import ch.it4user.foodsharing.repository.PrivateInfoTemplateRepository;
import ch.it4user.foodsharing.service.EinAbCreatedEvent;
import ch.it4user.foodsharing.repository.UserRepository;
import ch.it4user.foodsharing.repository.SlotRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherService {

    static final Set<SlotStatus> BLOCKING_STATUSES =Set.of(SlotStatus.BOOKED, SlotStatus.DONE);
    static final int TEACHER_HISTORICAL_EINAB_MAX_AGE_DAYS = 90;

    private final UserRepository userRepository;
    private final EinAbRepository einAbRepository;
    private final SlotRepository slotRepository;
    private final BookingCommentRepository bookingCommentRepository;
    private final PrivateInfoTemplateRepository privateInfoTemplateRepository;
    private final FoodsharingMessageService foodsharingMessageService;
    private final MessageTemplateService messageTemplateService;
    private final AppProperties appProperties;
    private final IcalImportService icalImportService;
    private final BezirkService bezirkService;
    private final BookingUserService bookingUserService;
    private final ApplicationEventPublisher eventPublisher;

    public TeacherService(UserRepository userRepository,
                          EinAbRepository einAbRepository,
                          SlotRepository slotRepository,
                          BookingCommentRepository bookingCommentRepository,
                          PrivateInfoTemplateRepository privateInfoTemplateRepository,
                          FoodsharingMessageService foodsharingMessageService,
                          MessageTemplateService messageTemplateService,
                          AppProperties appProperties,
                          IcalImportService icalImportService,
                          BezirkService bezirkService,
                          BookingUserService bookingUserService,
                          ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.einAbRepository = einAbRepository;
        this.slotRepository = slotRepository;
        this.bookingCommentRepository = bookingCommentRepository;
        this.privateInfoTemplateRepository = privateInfoTemplateRepository;
        this.foodsharingMessageService = foodsharingMessageService;
        this.messageTemplateService = messageTemplateService;
        this.appProperties = appProperties;
        this.icalImportService = icalImportService;
        this.bezirkService = bezirkService;
        this.bookingUserService = bookingUserService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public User updateProfile(String bezirkSlug, User teacher, String phoneNumber, String icalLink, LanguageCode language) {
        bezirkService.requireActive(bezirkSlug);
        return updateProfile(teacher, phoneNumber, icalLink, language);
    }

    @Transactional
    public User updateProfile(User teacher, String phoneNumber, String icalLink, LanguageCode language) {
        User managedTeacher = userRepository.findWithBezirkById(teacher.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.TEACHER_NOT_FOUND));
        if (phoneNumber != null) {
            managedTeacher.setPhoneNumber(phoneNumber.trim());
        }
        if (icalLink != null && !icalLink.isBlank() && !IcalImportService.isAllowedLink(icalLink)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    List.of("The iCal link must be an https link from foodsharing.network or foodsharing.de."));
        }
        managedTeacher.setIcalLink(icalLink == null || icalLink.isBlank() ? null : icalLink.trim());
        managedTeacher.setPreferredLanguage(language);
        return managedTeacher;
    }

    @Transactional
    public User assignBezirk(User teacher, String bezirkSlug) {
        User managedTeacher = userRepository.findWithBezirkById(teacher.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.TEACHER_NOT_FOUND));
        if (managedTeacher.getBezirk() != null && managedTeacher.getBezirk().isActive()) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.USER_BEZIRK_MISMATCH);
        }
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        bookingUserService.assignToBezirk(managedTeacher, bezirk);
        return managedTeacher;
    }

    public Page<EinAb> findTeacherEinAbs(String bezirkSlug, User teacher, int page, int size, boolean pastOnly) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        return einAbRepository.findAllByTeacherAndBezirk(
                teacher,
                bezirk,
                pastOnly,
                PageRequest.of(Math.max(page, 0), normalizeSize(size)));
    }

    @Transactional(readOnly = true)
    public EinAb findTeacherEinAb(String bezirkSlug, User teacher, UUID einAbId, boolean admin) {
        return requireTeacherEinAb(teacher, einAbId, admin, bezirkService.requireActive(bezirkSlug));
    }

    public List<User> findAssignableTeachers(String bezirkSlug, User teacher) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        return userRepository.findAllAssignableTeachersByBezirk(bezirk);
    }

    public Page<Slot> findTeacherBookings(String bezirkSlug, User teacher, int page, int size) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        return slotRepository.findAllByTeacherAndStatusesAndBezirk(
                teacher,
                Set.of(SlotStatus.BOOKED, SlotStatus.DONE),
                bezirk,
                PageRequest.of(Math.max(page, 0), normalizeSize(size)));
    }

    public List<BookingComment> findSlotComments(String bezirkSlug, User teacher, UUID slotId) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        Slot slot = requireTeacherBookedSlot(slotId, teacher, bezirk);
        return bookingCommentRepository.findAllBySlotAndBookingUserOrderByCreatedAtAsc(slot, slot.getBookingUser());
    }

    @Transactional
    public BookingComment addSlotComment(String bezirkSlug, User teacher, UUID slotId, String comment) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        Slot slot = requireTeacherBookedSlot(slotId, teacher, bezirk);
        BookingComment bookingComment = new BookingComment();
        bookingComment.setBookingUser(slot.getBookingUser());
        bookingComment.setSlot(slot);
        bookingComment.setTeacher(teacher);
        bookingComment.setComment(comment.trim());
        return bookingCommentRepository.save(bookingComment);
    }

    public List<ch.it4user.foodsharing.openapi.model.IcalCandidate> getIcalCandidates(String bezirkSlug, User teacher) {
        bezirkService.requireActive(bezirkSlug);
        return getIcalCandidates(teacher);
    }

    public List<ch.it4user.foodsharing.openapi.model.IcalCandidate> getIcalCandidates(User teacher) {
        return icalImportService.loadCandidates(teacher.getIcalLink());
    }

    public List<PrivateInfoTemplate> findPrivateInfoTemplates(User teacher) {
        return privateInfoTemplateRepository.findAllByTeacherIdOrderByStoreKeyAsc(teacher.getId());
    }

    @Transactional
    public EinAb createEinAb(String bezirkSlug,
                             User teacher,
                             EinAbCategory category,
                             Instant startDateTime,
                             String location,
                             String publicLocation,
                             String businessName,
                             String privateAddress,
                             String publicGeneralLocation,
                             String onlineCallLink,
                             String privateInfo,
                             String publicInfo,
                             boolean savePrivateInfoAsTemplate,
                             boolean visitFairteiler,
                             int slotCount,
                             Integer minimumPickupCount) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        ensureTeacherActive(teacher);
        bezirkService.requireEnabledCategory(bezirk, category, null);
        String address = privateAddress == null ? location : privateAddress;
        String generalLocation = publicGeneralLocation == null ? publicLocation : publicGeneralLocation;
        validateEinAb(category, slotCount, address, generalLocation, businessName, onlineCallLink, minimumPickupCount);
        EinAb einAb = new EinAb();
        einAb.setBezirk(bezirk);
        einAb.setTeacher(teacher);
        einAb.setCategory(category);
        einAb.setStartDateTime(startDateTime);
        einAb.setLocation(isOnline(category) ? null : normalizeRequiredLocation(address, ApiErrorCode.LOCATION_REQUIRED));
        einAb.setPublicLocation(isOnline(category) ? null : normalizeRequiredLocation(generalLocation, ApiErrorCode.PUBLIC_LOCATION_REQUIRED));
        einAb.setBusinessName(isOnline(category) ? null : normalizeInfo(businessName));
        einAb.setPrivateAddress(isOnline(category) ? null : normalizeRequiredLocation(address, ApiErrorCode.LOCATION_REQUIRED));
        einAb.setPublicGeneralLocation(isOnline(category) ? null : normalizeRequiredLocation(generalLocation, ApiErrorCode.PUBLIC_LOCATION_REQUIRED));
        einAb.setOnlineCallLink(isOnline(category) ? normalizeRequiredOnlineCallLink(onlineCallLink) : null);
        einAb.setPrivateInfo(normalizeInfo(privateInfo));
        savePrivateInfoTemplateIfRequested(teacher, category, einAb.getBusinessName(), einAb.getPrivateInfo(), savePrivateInfoAsTemplate);
        einAb.setPublicInfo(normalizeInfo(publicInfo));
        einAb.setVisitFairteiler(isOnline(category) ? false : visitFairteiler);
        einAb.setSlotCount(isOnline(category) ? 1 : slotCount);
        einAb.setMinimumPickupCount(isOnline(category) ? null : normalizeMinimumPickupCount(minimumPickupCount));
        EinAb savedEinAb = einAbRepository.save(einAb);
        createSlots(savedEinAb, isOnline(category) ? 1 : slotCount);
        EinAb reloadedEinAb = reloadEinAbWithTeacher(savedEinAb.getId(), bezirk);
        eventPublisher.publishEvent(new EinAbCreatedEvent(reloadedEinAb.getId()));
        return reloadedEinAb;
    }

    @Transactional
    public EinAb createHistoricalEinAb(String bezirkSlug,
                                       User teacher,
                                       EinAbCategory category,
                                       Instant startDateTime,
                                       String businessName,
                                       boolean visitFairteiler) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        ensureTeacherActive(teacher);
        String normalizedBusinessName = normalizeInfo(businessName);
        if (normalizedBusinessName == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("businessName"));
        }
        // A "past" EinAb is counted as done at once, so it must really lie in the past (admins included).
        if (startDateTime == null || startDateTime.isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.HISTORICAL_EINAB_MUST_BE_PAST);
        }
        EinAb einAb = new EinAb();
        einAb.setBezirk(bezirk);
        einAb.setTeacher(teacher);
        einAb.setCategory(category);
        einAb.setStartDateTime(startDateTime);
        einAb.setLocation(null);
        einAb.setPublicLocation(null);
        einAb.setBusinessName(normalizedBusinessName);
        einAb.setPrivateAddress(null);
        einAb.setPublicGeneralLocation(null);
        einAb.setOnlineCallLink(null);
        einAb.setPrivateInfo(null);
        einAb.setPublicInfo(null);
        einAb.setVisitFairteiler(!isOnline(category) && visitFairteiler);
        einAb.setSlotCount(1);
        einAb.setMinimumPickupCount(null);
        EinAb savedEinAb = einAbRepository.save(einAb);
        createSlots(savedEinAb, 1);
        // No EinAbCreatedEvent: it notifies notification subscribers, which must never happen for past EinAbs.
        return reloadEinAbWithTeacher(savedEinAb.getId(), bezirk);
    }

    /**
     * A teacher records an EinAb that was organised spontaneously outside the app. Narrower than the admin
     * version: the teacher is always the giver, the person must be an active member of the same Bezirk and not
     * the teacher, the category must be enabled, and the date must lie within the last
     * {@link #TEACHER_HISTORICAL_EINAB_MAX_AGE_DAYS} days.
     */
    @Transactional
    public EinAb recordSpontaneousEinAb(String bezirkSlug,
                                        User teacher,
                                        UUID bookingUserId,
                                        EinAbCategory category,
                                        Instant startDateTime,
                                        String businessName,
                                        boolean visitFairteiler,
                                        String comment) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        ensureTeacherActive(teacher);
        bezirkService.requireEnabledCategory(bezirk, category, null);
        if (startDateTime != null
                && startDateTime.isBefore(Instant.now().minus(TEACHER_HISTORICAL_EINAB_MAX_AGE_DAYS, java.time.temporal.ChronoUnit.DAYS))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.HISTORICAL_EINAB_TOO_OLD);
        }
        User bookingUser = userRepository.findByIdForUpdate(bookingUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BOOKING_USER_NOT_FOUND));
        if (bookingUser.getId().equals(teacher.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.HISTORICAL_EINAB_NOT_FOR_YOURSELF);
        }
        if (bookingUser.getBezirk() == null || !bookingUser.getBezirk().getId().equals(bezirk.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.USER_BEZIRK_MISMATCH);
        }
        if (!bookingUser.isActive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.BOOKING_USER_DISABLED);
        }
        // The lock on the person above makes this check safe against a double submit.
        if (slotRepository.existsByBookingUserAndStatusInAndTeacherAndEinAbStartDateTime(
                bookingUser, BLOCKING_STATUSES, teacher, startDateTime)) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.USER_ALREADY_BOOKED_IN_EINAB);
        }
        EinAb einAb = createHistoricalEinAb(bezirkSlug, teacher, category, startDateTime, businessName, visitFairteiler);
        Slot slot = slotRepository.findAllByEinAbOrderByCreatedAtAsc(einAb).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, ApiErrorCode.BOOKING_NOT_FOUND));
        slot.setBookingUser(bookingUser);
        slot.setStatus(SlotStatus.DONE);
        slot.setDoneAt(Instant.now());
        String trimmedComment = comment == null ? "" : comment.trim();
        if (trimmedComment.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("comment"));
        }
        {
            BookingComment bookingComment = new BookingComment();
            bookingComment.setBookingUser(bookingUser);
            bookingComment.setSlot(slot);
            bookingComment.setTeacher(teacher);
            bookingComment.setComment(trimmedComment);
            bookingCommentRepository.save(bookingComment);
        }
        return einAb;
    }

    /** Search for the person of a spontaneous EinAb: at least 2 characters, at most 10 hits, own Bezirk only. */
    @Transactional(readOnly = true)
    public List<User> searchBookingUsers(String bezirkSlug, User teacher, String query) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        ensureTeacherActive(teacher);
        String text = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (text.length() < 2) {
            return List.of();
        }
        String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return userRepository.searchActiveByBezirk(
                bezirk, teacher.getId(), "%" + escaped + "%", escaped + "%", PageRequest.of(0, 10));
    }

    @Transactional
    public EinAb updateEinAb(String bezirkSlug,
                             User teacher,
                             UUID einAbId,
                             EinAbCategory category,
                             Instant startDateTime,
                             String location,
                             String publicLocation,
                             String businessName,
                             String privateAddress,
                             String publicGeneralLocation,
                             String onlineCallLink,
                             String privateInfo,
                             String publicInfo,
                             boolean savePrivateInfoAsTemplate,
                             boolean visitFairteiler,
                             int slotCount,
                             Integer minimumPickupCount,
                             boolean admin) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        EinAb einAb = requireTeacherEinAb(teacher, einAbId, admin, bezirk);
        if (einAb.getStartDateTime().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.PAST_EINAB_NOT_EDITABLE);
        }
        if (!admin) {
            ensureTeacherActive(teacher);
        }
        bezirkService.requireEnabledCategory(bezirk, category, einAb.getCategory());
        String address = privateAddress == null ? location : privateAddress;
        String generalLocation = publicGeneralLocation == null ? publicLocation : publicGeneralLocation;
        validateEinAb(category, slotCount, address, generalLocation, businessName, onlineCallLink, minimumPickupCount);
        List<Slot> activeSlots = slotRepository.findAllForUpdateByEinAb(einAb).stream()
                .filter(slot -> slot.getStatus() != SlotStatus.CANCELLED)
                .toList();
        int existingSlots = activeSlots.size();
        List<Slot> freeSlots = activeSlots.stream()
                .filter(slot -> slot.getStatus() == SlotStatus.AVAILABLE && slot.getBookingUser() == null)
                .toList();
        // Lowering the count only removes free places; a booked place is never dropped.
        if (!isOnline(category) && existingSlots - slotCount > freeSlots.size()) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.SLOT_COUNT_REDUCTION_NOT_SUPPORTED);
        }

        einAb.setCategory(category);
        einAb.setStartDateTime(startDateTime);
        einAb.setLocation(isOnline(category) ? null : normalizeRequiredLocation(address, ApiErrorCode.LOCATION_REQUIRED));
        einAb.setPublicLocation(isOnline(category) ? null : normalizeRequiredLocation(generalLocation, ApiErrorCode.PUBLIC_LOCATION_REQUIRED));
        einAb.setBusinessName(isOnline(category) ? null : normalizeInfo(businessName));
        einAb.setPrivateAddress(isOnline(category) ? null : normalizeRequiredLocation(address, ApiErrorCode.LOCATION_REQUIRED));
        einAb.setPublicGeneralLocation(isOnline(category) ? null : normalizeRequiredLocation(generalLocation, ApiErrorCode.PUBLIC_LOCATION_REQUIRED));
        einAb.setOnlineCallLink(isOnline(category) ? normalizeRequiredOnlineCallLink(onlineCallLink) : null);
        einAb.setPrivateInfo(normalizeInfo(privateInfo));
        savePrivateInfoTemplateIfRequested(teacher, category, einAb.getBusinessName(), einAb.getPrivateInfo(), savePrivateInfoAsTemplate);
        einAb.setPublicInfo(normalizeInfo(publicInfo));
        einAb.setVisitFairteiler(isOnline(category) ? false : visitFairteiler);
        einAb.setSlotCount(isOnline(category) ? 1 : slotCount);
        einAb.setMinimumPickupCount(isOnline(category) ? null : normalizeMinimumPickupCount(minimumPickupCount));
        if (isOnline(category)) {
            cancelAdditionalAvailableOnlineSlots(einAb);
        } else if (slotCount > existingSlots) {
            createSlots(einAb, slotCount - existingSlots);
        } else if (slotCount < existingSlots) {
            removeFreeSlots(freeSlots, existingSlots - slotCount);
        }
        eventPublisher.publishEvent(new EinAbUpdatedEvent(einAb.getId()));
        return reloadEinAbWithTeacher(einAb.getId(), bezirk);
    }

    @Transactional
    public void deleteEinAb(String bezirkSlug, User teacher, UUID einAbId, boolean admin) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        EinAb einAb = requireTeacherEinAb(teacher, einAbId, admin, bezirk);
        if (einAb.getStartDateTime().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.PAST_EINAB_NOT_DELETABLE);
        }
        if (slotRepository.existsByEinAbAndStatusIn(einAb, BLOCKING_STATUSES)) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.BOOKED_SLOTS_PREVENT_DELETE);
        }
        List<Slot> slots = slotRepository.findAllByEinAbOrderByCreatedAtAsc(einAb);
        slotRepository.deleteAll(slots);
        einAbRepository.delete(einAb);
    }

    /**
     * Admin deletion: booked people are removed first and get a foodsharing message,
     * so an admin can delete an EinAb even when places are already booked.
     */
    @Transactional
    public void deleteEinAbAsAdmin(String bezirkSlug, User admin, UUID einAbId) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        EinAb einAb = requireTeacherEinAb(admin, einAbId, true, bezirk);
        if (einAb.getStartDateTime().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.PAST_EINAB_NOT_DELETABLE);
        }
        List<Slot> slots = slotRepository.findAllByEinAbOrderByCreatedAtAsc(einAb);
        slots.stream()
                .filter(slot -> slot.getStatus() == SlotStatus.BOOKED && slot.getBookingUser() != null)
                .forEach(slot -> sendAdminBookingRemovalMessage(slot, admin, true));
        if (!slots.isEmpty()) {
            bookingCommentRepository.detachFromSlots(slots);
        }
        slotRepository.deleteAll(slots);
        einAbRepository.delete(einAb);
    }

    /** Admin removes the booked person from an upcoming EinAb; the place becomes free again. */
    @Transactional
    public Slot removeBookingAsAdmin(String bezirkSlug, User admin, UUID slotId) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        Slot slot = slotRepository.findForUpdateByIdAndBezirk(slotId, bezirk)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.SLOT_NOT_FOUND));
        if (slot.getStatus() != SlotStatus.BOOKED || slot.getBookingUser() == null) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.ONLY_BOOKED_APPOINTMENTS_CANCELLABLE);
        }
        if (!slot.getEinAb().getStartDateTime().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.PAST_BOOKING_NOT_CANCELLABLE);
        }
        sendAdminBookingRemovalMessage(slot, admin, false);
        slot.setStatus(SlotStatus.AVAILABLE);
        slot.setBookingUser(null);
        slot.setBookedAt(null);
        slot.setDoneAt(null);
        return slot;
    }

    public Page<EinAb> findAllEinAbs(String bezirkSlug, int page, int size) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        return einAbRepository.findAllByBezirkOrderByStartDateTimeAsc(
                bezirk,
                PageRequest.of(Math.max(page, 0), normalizeSize(size)));
    }

    public Page<User> findAllTeachers(String bezirkSlug, int page, int size) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        return userRepository.findAllTeachersByBezirk(
                bezirk,
                PageRequest.of(Math.max(page, 0), normalizeSize(size)));
    }

    @Transactional
    public User setTeacherActive(UUID teacherId, boolean active) {
        User teacher = userRepository.findWithBezirkById(teacherId)
                .filter(User::isCanGiveEinAbs)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.TEACHER_NOT_FOUND));
        teacher.setCanGiveEinAbs(active);
        if (active) {
            teacher.setActive(true);
        }
        return teacher;
    }

    @Transactional
    public User setTeacherAdmin(UUID teacherId, boolean admin) {
        User teacher = userRepository.findWithBezirkById(teacherId)
                .filter(User::isCanGiveEinAbs)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.TEACHER_NOT_FOUND));
        teacher.setCanManageUsers(admin);
        if (admin) {
            teacher.setActive(true);
            teacher.setCanGiveEinAbs(true);
        }
        return teacher;
    }

    @Transactional
    public Slot cancelBookedSlot(String bezirkSlug, User teacher, UUID slotId, boolean admin) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        Slot slot = slotRepository.findForUpdateByIdAndBezirk(slotId, bezirk)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.SLOT_NOT_FOUND));
        if (!admin && !isSlotTeacher(teacher, slot)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.ONLY_OWN_EINABS_MANAGEABLE);
        }
        if (slot.getStatus() != SlotStatus.BOOKED || slot.getBookingUser() == null) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.ONLY_BOOKED_APPOINTMENTS_CANCELLABLE);
        }
        if (slot.getEinAb().getStartDateTime().isAfter(Instant.now())) {
            sendTeacherCancellationMessage(slot);
            slot.setStatus(SlotStatus.AVAILABLE);
            slot.setBookingUser(null);
            slot.setBookedAt(null);
            slot.setDoneAt(null);
        } else {
            slot.setStatus(SlotStatus.CANCELLED);
        }
        return slot;
    }

    @Transactional
    public Slot countCancelledSlot(String bezirkSlug, User teacher, UUID slotId, boolean admin) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        Slot slot = slotRepository.findForUpdateByIdAndBezirk(slotId, bezirk)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.SLOT_NOT_FOUND));
        if (!admin && !isSlotTeacher(teacher, slot)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.ONLY_OWN_EINABS_MANAGEABLE);
        }
        if (slot.getStatus() != SlotStatus.CANCELLED || slot.getBookingUser() == null) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.ONLY_CANCELLED_APPOINTMENTS_COUNTABLE);
        }
        slot.setStatus(SlotStatus.BOOKED);
        slot.setDoneAt(null);
        return slot;
    }

    @Transactional
    public EinAb assignTeacherToEinAb(String bezirkSlug, User teacher, UUID einAbId, UUID assignedTeacherId) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        ensureTeacherActive(teacher);
        EinAb einAb = einAbRepository.findWithTeacherForUpdateByIdAndBezirk(einAbId, bezirk)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.EINAB_NOT_FOUND));
        if (einAb.getStartDateTime().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.PAST_EINAB_NOT_REASSIGNABLE);
        }
        if (!einAb.getTeacher().getId().equals(teacher.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.ONLY_OWN_EINABS_MANAGEABLE);
        }
        User assignedTeacher = userRepository.findWithBezirkById(assignedTeacherId)
                .filter(User::isActive)
                .filter(User::isCanGiveEinAbs)
                .filter(user -> user.getBezirk() != null && user.getBezirk().getId().equals(bezirk.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.ASSIGNABLE_TEACHER_NOT_FOUND));
        einAb.setTeacher(assignedTeacher);
        slotRepository.findAllForUpdateByEinAb(einAb).forEach(slot -> slot.setTeacher(assignedTeacher));
        return einAb;
    }

    private void createSlots(EinAb einAb, int count) {
        List<Slot> slots = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Slot slot = new Slot();
            slot.setEinAb(einAb);
            slot.setTeacher(einAb.getTeacher());
            slot.setStatus(SlotStatus.AVAILABLE);
            slots.add(slot);
        }
        slotRepository.saveAll(slots);
    }

    private void removeFreeSlots(List<Slot> freeSlots, int count) {
        List<Slot> toRemove = freeSlots.subList(freeSlots.size() - count, freeSlots.size());
        bookingCommentRepository.detachFromSlots(toRemove);
        slotRepository.deleteAll(toRemove);
    }

    /** Online EinAbs are unlimited: exactly one free slot is kept open, and booking it opens the next one. */
    private void cancelAdditionalAvailableOnlineSlots(EinAb einAb) {
        boolean availableSlotRetained = false;
        for (Slot slot : slotRepository.findAllForUpdateByEinAb(einAb)) {
            if (slot.getStatus() != SlotStatus.AVAILABLE) {
                continue;
            }
            if (!availableSlotRetained) {
                availableSlotRetained = true;
            } else {
                slot.setStatus(SlotStatus.CANCELLED);
            }
        }
        if (!availableSlotRetained) {
            // e.g. a fully booked EinAb switched to online: open a free place again.
            createSlots(einAb, 1);
        }
    }

    /** Saves the template for the EinAb's store; an online EinAb (no store) saves its category template. */
    private void savePrivateInfoTemplateIfRequested(User teacher,
                                                    EinAbCategory category,
                                                    String storeName,
                                                    String privateInfo,
                                                    boolean saveAsTemplate) {
        if (!saveAsTemplate) {
            return;
        }
        if (privateInfo == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("privateInfo"));
        }
        String storeKey = PrivateInfoTemplate.storeKeyFor(storeName);
        boolean storeTemplate = !isOnline(category) && !storeKey.isEmpty();
        String key = storeTemplate ? storeKey : PrivateInfoTemplate.categoryKeyFor(category);
        PrivateInfoTemplate template = privateInfoTemplateRepository.findByTeacherIdAndStoreKey(teacher.getId(), key)
                .orElseGet(PrivateInfoTemplate::new);
        template.setTeacher(teacher);
        template.setCategory(category);
        template.setStoreName(storeTemplate ? storeName.trim().replaceAll("\\s+", " ") : null);
        template.setStoreKey(key);
        template.setContent(privateInfo);
        privateInfoTemplateRepository.save(template);
    }

    private void ensureTeacherActive(User teacher) {
        if (!teacher.isActive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.TEACHER_INACTIVE);
        }
    }

    private void validateSlotCount(int slotCount) {
        if (slotCount < 1 || slotCount > 3) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.SLOT_COUNT_OUT_OF_RANGE);
        }
    }

    private void validateEinAb(EinAbCategory category, int slotCount, String location, String publicLocation, String businessName, String onlineCallLink, Integer minimumPickupCount) {
        if (isOnline(category)) {
            normalizeRequiredOnlineCallLink(onlineCallLink);
        } else {
            validateSlotCount(slotCount);
            normalizeRequiredLocation(location, ApiErrorCode.LOCATION_REQUIRED);
            normalizeRequiredLocation(publicLocation, ApiErrorCode.PUBLIC_LOCATION_REQUIRED);
            if (normalizeInfo(businessName) == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("businessName"));
            }
            normalizeMinimumPickupCount(minimumPickupCount);
        }
    }

    private boolean isOnline(EinAbCategory category) {
        return category == EinAbCategory.ONLINE;
    }

    private String normalizeLocation(String location) {
        return location == null || location.isBlank() ? null : location.trim();
    }

    private String normalizeRequiredLocation(String location, ApiErrorCode errorCode) {
        String normalized = normalizeLocation(location);
        if (normalized == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, errorCode);
        }
        return normalized;
    }

    private String normalizeRequiredOnlineCallLink(String onlineCallLink) {
        String normalized = normalizeLocation(onlineCallLink);
        if (normalized == null || normalized.length() > 2000
                || !(normalized.startsWith("https://") || normalized.startsWith("http://"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("onlineCallLink"));
        }
        return normalized;
    }

    private void sendTeacherCancellationMessage(Slot slot) {
        User bookingUser = slot.getBookingUser();
        LanguageCode language = bookingUser.getPreferredLanguage() == null ? LanguageCode.DE : bookingUser.getPreferredLanguage();
        String manageUrl = appProperties.getFrontend().getBaseUrl()
                + "/bezirke/" + slot.getEinAb().getBezirk().getSlug()
                + "/my-bookings";
        foodsharingMessageService.send(
                bookingUser.getFoodsharingId(),
                messageTemplateService.teacherCancellationSubject(language),
                messageTemplateService.teacherCancellationBody(language, slot, manageUrl));
    }

    private void sendAdminBookingRemovalMessage(Slot slot, User admin, boolean einAbDeleted) {
        User bookingUser = slot.getBookingUser();
        LanguageCode language = bookingUser.getPreferredLanguage() == null ? LanguageCode.DE : bookingUser.getPreferredLanguage();
        String manageUrl = appProperties.getFrontend().getBaseUrl()
                + "/bezirke/" + slot.getEinAb().getBezirk().getSlug()
                + "/my-bookings";
        foodsharingMessageService.send(
                bookingUser.getFoodsharingId(),
                messageTemplateService.teacherCancellationSubject(language),
                messageTemplateService.adminBookingRemovalBody(language, slot, admin.getName(), einAbDeleted, manageUrl));
    }

    private String normalizeInfo(String info) {
        return info == null || info.isBlank() ? null : info.trim();
    }

    private Integer normalizeMinimumPickupCount(Integer minimumPickupCount) {
        if (minimumPickupCount == null) {
            return null;
        }
        if (minimumPickupCount < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("minimumPickupCount"));
        }
        return minimumPickupCount;
    }

    private EinAb requireTeacherEinAb(User teacher, UUID einAbId, boolean admin, Bezirk bezirk) {
        EinAb einAb = einAbRepository.findWithTeacherByIdAndBezirk(einAbId, bezirk)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.EINAB_NOT_FOUND));
        if (!admin && !einAb.getTeacher().getId().equals(teacher.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.ONLY_OWN_EINABS_MANAGEABLE);
        }
        return einAb;
    }

    private EinAb reloadEinAbWithTeacher(UUID einAbId, Bezirk bezirk) {
        return einAbRepository.findWithTeacherByIdAndBezirk(einAbId, bezirk)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.EINAB_NOT_FOUND));
    }

    private Slot requireTeacherBookedSlot(UUID slotId, User teacher, Bezirk bezirk) {
        Slot slot = slotRepository.findByIdAndEinAbBezirk(slotId, bezirk)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.SLOT_NOT_FOUND));
        if (!isSlotTeacher(teacher, slot)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.ONLY_OWN_EINABS_MANAGEABLE);
        }
        if (slot.getBookingUser() == null || !slot.getBookingUser().isActive()) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.BOOKING_NOT_FOUND);
        }
        return slot;
    }

    private boolean isSlotTeacher(User teacher, Slot slot) {
        User slotTeacher = slot.getTeacher() == null ? slot.getEinAb().getTeacher() : slot.getTeacher();
        return slotTeacher.getId().equals(teacher.getId()) || slot.getEinAb().getTeacher().getId().equals(teacher.getId());
    }

    private int normalizeSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }

}
