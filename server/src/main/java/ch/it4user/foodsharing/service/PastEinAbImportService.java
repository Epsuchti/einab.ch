package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.BookingComment;
import ch.it4user.foodsharing.domain.entity.EinAb;
import ch.it4user.foodsharing.domain.entity.Slot;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.EinAbCategory;
import ch.it4user.foodsharing.domain.enumtype.SlotStatus;
import ch.it4user.foodsharing.openapi.model.PastEinAbImportCounts;
import ch.it4user.foodsharing.openapi.model.PastEinAbImportNameMapping;
import ch.it4user.foodsharing.openapi.model.PastEinAbImportPreview;
import ch.it4user.foodsharing.openapi.model.PastEinAbImportReason;
import ch.it4user.foodsharing.openapi.model.PastEinAbImportRequest;
import ch.it4user.foodsharing.openapi.model.PastEinAbImportResult;
import ch.it4user.foodsharing.openapi.model.PastEinAbImportRow;
import ch.it4user.foodsharing.openapi.model.PastEinAbImportRowStatus;
import ch.it4user.foodsharing.repository.BookingCommentRepository;
import ch.it4user.foodsharing.repository.EinAbRepository;
import ch.it4user.foodsharing.repository.PastEinAbImportRepository;
import ch.it4user.foodsharing.repository.SlotRepository;
import ch.it4user.foodsharing.repository.UserRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports the past EinAbs of the Zurich EinAb protocol: the spreadsheet itself (.xlsx) or a CSV export of its EinAbs
 * sheet. The file contains personal data and free-text feedback: it is never stored and neither the feedback nor the
 * file is logged. Preview and confirm both analyse the upload from scratch, so confirming twice (or after a partial
 * earlier import) only adds what is still missing.
 * <p>
 * The teacher (EinAb-Geber*in) of a row is the user with the Foodsharing ID that the dropdown value names: either an ID
 * written in the cell itself or, in the workbook, the ID next to that name in the "EinAb-Geberinnen" list the dropdown
 * is built from. Rows without a known teacher are imported with the importing admin as teacher. A row imported earlier
 * with the importing admin as teacher gets the teacher from the file when the file names one.
 * <p>
 * EinAbs are saved directly (not via {@code TeacherService}) because creating one there notifies all notification
 * subscribers, which must not happen for hundreds of old EinAbs.
 */
@Service
public class PastEinAbImportService {

    static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
    private static final int MAX_ROWS = 5000;
    private static final int MAX_COMMENT_LENGTH = 4000;
    private static final Pattern DATE = Pattern.compile("(\\d{1,2})\\s*\\.\\s*(\\d{1,2})\\s*\\.?\\s*(\\d{2}|\\d{4})");
    /** A Foodsharing ID inside the EinAb-Geber*in cell, e.g. "Hanna (946329)" or a profile link. */
    private static final Pattern TEACHER_ID = Pattern.compile("(?<!\\d)(\\d{3,18})(?!\\d)");
    private static final Pattern EXCEL_NUMBER = Pattern.compile("\\d+(\\.\\d+)?(E\\d+)?");
    /** Day 0 of the Excel date serial numbers (1900 date system, including its leap year bug). */
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    /** Misspelled or abbreviated store names (key = normalised name) mapped to the name to use. */
    private static final Map<String, String> STORE_ALIASES = Map.of(
            "babus", "Babu's Bakery",
            "babusbakery", "Babu's Bakery",
            "aldibadnerstrasse", "Aldi Badenerstrasse",
            "tinyfishstadelhofen", "Tiny Fish Stadelhofen",
            "tinyfishlöwenstrasse", "Tiny Fish Löwenstrasse",
            "tinyfishbadenerstrasse", "Tiny Fish Badenerstrasse");

    private final BezirkService bezirkService;
    private final UserRepository userRepository;
    private final PastEinAbImportRepository importRepository;
    private final EinAbRepository einAbRepository;
    private final SlotRepository slotRepository;
    private final BookingCommentRepository bookingCommentRepository;
    private final BookingUserService bookingUserService;
    private final UserRefreshQueueService refreshQueueService;

    public PastEinAbImportService(BezirkService bezirkService,
                                  UserRepository userRepository,
                                  PastEinAbImportRepository importRepository,
                                  EinAbRepository einAbRepository,
                                  SlotRepository slotRepository,
                                  BookingCommentRepository bookingCommentRepository,
                                  BookingUserService bookingUserService,
                                  UserRefreshQueueService refreshQueueService) {
        this.bezirkService = bezirkService;
        this.userRepository = userRepository;
        this.importRepository = importRepository;
        this.einAbRepository = einAbRepository;
        this.slotRepository = slotRepository;
        this.bookingCommentRepository = bookingCommentRepository;
        this.bookingUserService = bookingUserService;
        this.refreshQueueService = refreshQueueService;
    }

    /** A parsed row together with the data the import needs (the feedback is only kept in memory). */
    private static final class Parsed {
        PastEinAbImportRow row = new PastEinAbImportRow();
        EinAbCategory category;
        String feedback = "";
        Instant start;
        boolean notCounted;
        String duplicateKey;
        /** The slot this row was imported (or entered) as before, when its teacher is to be replaced. */
        Slot teacherUpdateSlot;
    }

    private record Analysis(List<Parsed> rows, Map<String, User> existingUsers, Map<String, User> existingTeachers,
                            List<PastEinAbImportNameMapping> storeMappings,
                            List<PastEinAbImportNameMapping> categoryMappings) {
    }

    /**
     * The uploaded table: the header first, then the data rows. {@code lines} holds the row number in the file of
     * every record, {@code teacherIds} maps normalised teacher names to Foodsharing IDs (empty for CSV).
     */
    private record Source(List<List<String>> records, List<Integer> lines, Map<String, String> teacherIds) {
    }

    @Transactional(readOnly = true)
    public PastEinAbImportPreview preview(String bezirkSlug, User admin, PastEinAbImportRequest request) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        Analysis analysis = analyse(bezirk, admin, request);
        PastEinAbImportCounts counts = new PastEinAbImportCounts();
        Set<String> newUsers = new HashSet<>();
        Set<String> existingUsers = new HashSet<>();
        Set<String> newTeachers = new HashSet<>();
        int teacherUpdates = 0;
        int create = 0;
        int skip = 0;
        int attention = 0;
        for (Parsed parsed : analysis.rows()) {
            PastEinAbImportRow row = parsed.row;
            switch (row.getStatus()) {
                case CREATE -> create++;
                case SKIP_DUPLICATE -> skip++;
                case NEEDS_ATTENTION -> attention++;
            }
            if (row.getStatus() == PastEinAbImportRowStatus.CREATE && row.getFoodsharingId() != null) {
                (row.getNewUser() ? newUsers : existingUsers).add(row.getFoodsharingId());
            }
            if (parsed.teacherUpdateSlot != null) {
                teacherUpdates++;
            }
            if ((row.getStatus() == PastEinAbImportRowStatus.CREATE || parsed.teacherUpdateSlot != null)
                    && row.getNewTeacher()) {
                newTeachers.add(row.getTeacherFoodsharingId());
            }
        }
        counts.setTotalRows(analysis.rows().size());
        counts.setWillCreate(create);
        counts.setWillSkip(skip);
        counts.setNeedsAttention(attention);
        counts.setNewUsers(newUsers.size());
        counts.setExistingUsers(existingUsers.size());
        counts.setNewTeachers(newTeachers.size());
        counts.setTeacherUpdates(teacherUpdates);
        PastEinAbImportPreview preview = new PastEinAbImportPreview();
        preview.setCounts(counts);
        preview.setRows(analysis.rows().stream().map(parsed -> parsed.row).toList());
        preview.setStoreMappings(analysis.storeMappings());
        preview.setCategoryMappings(analysis.categoryMappings());
        return preview;
    }

    @Transactional
    public PastEinAbImportResult confirm(String bezirkSlug, User admin, PastEinAbImportRequest request) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        Analysis analysis = analyse(bezirk, admin, request);
        Map<String, User> users = new HashMap<>(analysis.existingUsers());
        Map<String, User> teachers = new HashMap<>(analysis.existingTeachers());
        Set<UUID> createdUserIds = new HashSet<>();
        Map<UUID, User> touchedUsers = new LinkedHashMap<>();
        int created = 0;
        int skipped = 0;
        int attention = 0;
        int teachersUpdated = 0;
        for (Parsed parsed : analysis.rows()) {
            PastEinAbImportRow row = parsed.row;
            switch (row.getStatus()) {
                case SKIP_DUPLICATE -> skipped++;
                case NEEDS_ATTENTION -> attention++;
                case CREATE -> { }
            }
            if (parsed.teacherUpdateSlot != null) {
                User teacher = teacherFor(row, bezirk, admin, users, teachers, createdUserIds, touchedUsers);
                updateTeacher(parsed.teacherUpdateSlot, admin, teacher);
                teachersUpdated++;
            }
            if (row.getStatus() != PastEinAbImportRowStatus.CREATE) {
                continue;
            }
            String foodsharingId = row.getFoodsharingId();
            User user = users.get(foodsharingId);
            if (user == null) {
                user = bookingUserService.getOrCreateWithoutLookup(foodsharingId, bezirk);
                users.put(foodsharingId, user);
                createdUserIds.add(user.getId());
            }
            bookingUserService.assignToBezirk(user, bezirk);
            touchedUsers.put(user.getId(), user);
            User teacher = teacherFor(row, bezirk, admin, users, teachers, createdUserIds, touchedUsers);
            createPastEinAb(bezirk, teacher, user, parsed);
            created++;
        }
        PastEinAbImportResult result = new PastEinAbImportResult();
        result.setCreated(created);
        result.setSkipped(skipped);
        result.setNeedsAttention(attention);
        result.setUsersCreated(createdUserIds.size());
        result.setTeachersUpdated(teachersUpdated);
        result.setRefreshQueued(refreshQueueService.enqueue(bezirk, touchedUsers.values()));
        return result;
    }

    /**
     * The teacher user of a row: the user with the teacher's Foodsharing ID, created (like a booking user, without asking
     * Foodsharing) when it does not exist yet; the importing admin when the row names no known teacher.
     */
    private User teacherFor(PastEinAbImportRow row, Bezirk bezirk, User admin, Map<String, User> users,
                            Map<String, User> teachers, Set<UUID> createdUserIds, Map<UUID, User> touchedUsers) {
        String teacherId = row.getTeacherFoodsharingId();
        if (teacherId == null) {
            return admin;
        }
        User teacher = teachers.get(teacherId);
        if (teacher == null) {
            teacher = users.get(teacherId);
        }
        if (teacher == null) {
            teacher = bookingUserService.getOrCreateWithoutLookup(teacherId, bezirk);
            createdUserIds.add(teacher.getId());
            touchedUsers.put(teacher.getId(), teacher);
        }
        teachers.put(teacherId, teacher);
        return teacher;
    }

    /** Moves an EinAb imported (or entered) earlier by the importing admin to the teacher named in the file. */
    private void updateTeacher(Slot slot, User admin, User teacher) {
        EinAb einAb = slot.getEinAb();
        einAb.setTeacher(teacher);
        slot.setTeacher(teacher);
        for (BookingComment comment : bookingCommentRepository.findAllBySlotAndBookingUserOrderByCreatedAtAsc(
                slot, slot.getBookingUser())) {
            if (comment.getTeacher() != null && comment.getTeacher().getId().equals(admin.getId())) {
                comment.setTeacher(teacher);
            }
        }
    }

    private void createPastEinAb(Bezirk bezirk, User admin, User user, Parsed parsed) {
        EinAb einAb = new EinAb();
        einAb.setBezirk(bezirk);
        einAb.setTeacher(admin);
        einAb.setCategory(parsed.category);
        einAb.setStartDateTime(parsed.start);
        einAb.setBusinessName(parsed.row.getStore());
        einAb.setVisitFairteiler(parsed.row.getVisitFairteiler() && parsed.category != EinAbCategory.ONLINE);
        einAb.setSlotCount(1);
        EinAb saved = einAbRepository.save(einAb);
        Slot slot = new Slot();
        slot.setEinAb(saved);
        slot.setTeacher(admin);
        slot.setBookingUser(user);
        // Same state the admin "do not count" action produces: CANCELLED keeps the user and the comment.
        slot.setStatus(parsed.notCounted ? SlotStatus.CANCELLED : SlotStatus.DONE);
        slot.setBookedAt(parsed.start);
        slot.setDoneAt(parsed.notCounted ? null : parsed.start);
        Slot savedSlot = slotRepository.save(slot);
        if (!parsed.feedback.isEmpty()) {
            BookingComment comment = new BookingComment();
            comment.setBookingUser(user);
            comment.setSlot(savedSlot);
            comment.setTeacher(admin);
            comment.setComment(truncate(parsed.feedback));
            bookingCommentRepository.save(comment);
        }
    }

    private Analysis analyse(Bezirk bezirk, User admin, PastEinAbImportRequest request) {
        Source source = readSource(request);
        List<List<String>> records = source.records();
        if (records.size() < 2 || records.size() > MAX_ROWS + 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    List.of(isXlsx(request) ? "xlsx" : "csv"));
        }
        Columns columns = Columns.of(records.get(0));
        LocalDate today = LocalDate.now(ZURICH);
        List<Parsed> rows = new ArrayList<>();
        for (int index = 1; index < records.size(); index++) {
            List<String> cells = records.get(index);
            if (cells.stream().allMatch(cell -> cell.isBlank())) {
                continue;
            }
            Parsed parsed = parseRow(source.lines().get(index), cells, columns, today);
            applyTeacher(parsed.row, columns.get(cells, Columns.GIVER), source.teacherIds());
            rows.add(parsed);
        }

        applyStoreNames(bezirk, rows);

        Set<String> ids = new HashSet<>();
        for (Parsed parsed : rows) {
            if (parsed.row.getFoodsharingId() != null) {
                ids.add(parsed.row.getFoodsharingId());
            }
        }
        Map<String, User> existingUsers = new HashMap<>();
        for (String id : ids) {
            userRepository.findByFoodsharingIdIgnoreCase(id).ifPresent(user -> existingUsers.put(id, user));
        }
        Map<String, User> existingTeachers = new HashMap<>();
        for (Parsed parsed : rows) {
            String teacherId = parsed.row.getTeacherFoodsharingId();
            if (teacherId != null && !existingTeachers.containsKey(teacherId)) {
                User teacher = existingUsers.get(teacherId);
                if (teacher == null) {
                    teacher = userRepository.findByFoodsharingIdIgnoreCase(teacherId).orElse(null);
                }
                if (teacher != null) {
                    existingTeachers.put(teacherId, teacher);
                }
            }
        }
        for (Parsed parsed : rows) {
            String teacherId = parsed.row.getTeacherFoodsharingId();
            parsed.row.setNewTeacher(teacherId != null && !existingTeachers.containsKey(teacherId));
        }

        // Key: user + day + store. Slots of existing users cover earlier imports and hand-entered past EinAbs.
        Map<String, Slot> known = new HashMap<>();
        Set<UUID> userIds = new HashSet<>();
        existingUsers.values().forEach(user -> userIds.add(user.getId()));
        Instant from = rows.stream().map(parsed -> parsed.start).filter(java.util.Objects::nonNull)
                .min(Instant::compareTo).orElse(null);
        Instant to = rows.stream().map(parsed -> parsed.start).filter(java.util.Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
        if (!userIds.isEmpty() && from != null) {
            for (Slot slot : importRepository.findBookedSlots(
                    userIds, bezirk, from.minusSeconds(86_400), to.plusSeconds(86_400))) {
                known.putIfAbsent(duplicateKey(slot.getBookingUser().getId(), slot.getEinAb().getStartDateTime()
                        .atZone(ZURICH).toLocalDate(), slot.getEinAb().getBusinessName()), slot);
            }
        }

        Set<String> seenInFile = new HashSet<>();
        for (Parsed parsed : rows) {
            PastEinAbImportRow row = parsed.row;
            User user = row.getFoodsharingId() == null ? null : existingUsers.get(row.getFoodsharingId());
            row.setNewUser(row.getFoodsharingId() != null && user == null);
            if (user != null && user.getBezirk() != null && !user.getBezirk().getId().equals(bezirk.getId())) {
                row.addReasonsItem(PastEinAbImportReason.USER_OTHER_BEZIRK);
            }
            decideStatus(parsed, user, known, seenInFile);
            markTeacherUpdate(parsed, admin, existingTeachers);
        }
        return new Analysis(rows, existingUsers, existingTeachers, mappingSummary(rows, true),
                mappingSummary(rows, false));
    }

    /**
     * An already imported row whose EinAb still has the importing admin as teacher (an import made before teachers were
     * read from the file) gets the teacher the file names.
     */
    private static void markTeacherUpdate(Parsed parsed, User admin, Map<String, User> existingTeachers) {
        PastEinAbImportRow row = parsed.row;
        if (parsed.teacherUpdateSlot == null) {
            return;
        }
        Slot slot = parsed.teacherUpdateSlot;
        parsed.teacherUpdateSlot = null;
        String teacherId = row.getTeacherFoodsharingId();
        if (teacherId == null || admin == null || slot.getEinAb().getTeacher() == null
                || !slot.getEinAb().getTeacher().getId().equals(admin.getId())) {
            return;
        }
        User teacher = existingTeachers.get(teacherId);
        if (teacher != null && teacher.getId().equals(admin.getId())) {
            return;
        }
        parsed.teacherUpdateSlot = slot;
        row.addReasonsItem(PastEinAbImportReason.TEACHER_UPDATED);
    }

    private void decideStatus(Parsed parsed, User user, Map<String, Slot> known, Set<String> seenInFile) {
        PastEinAbImportRow row = parsed.row;
        List<PastEinAbImportReason> reasons = row.getReasons();
        boolean blocked = reasons.contains(PastEinAbImportReason.MISSING_ID)
                || reasons.contains(PastEinAbImportReason.INVALID_ID)
                || reasons.contains(PastEinAbImportReason.INVALID_DATE)
                || reasons.contains(PastEinAbImportReason.FUTURE_DATE)
                || reasons.contains(PastEinAbImportReason.MISSING_STORE)
                || reasons.contains(PastEinAbImportReason.UNKNOWN_CATEGORY)
                || reasons.contains(PastEinAbImportReason.USER_OTHER_BEZIRK);
        if (blocked) {
            row.setStatus(PastEinAbImportRowStatus.NEEDS_ATTENTION);
            return;
        }
        String key = duplicateKey(user == null ? null : user.getId(), row.getDate(), row.getStore());
        if (user != null && known.containsKey(key)) {
            row.setStatus(PastEinAbImportRowStatus.SKIP_DUPLICATE);
            reasons.add(PastEinAbImportReason.ALREADY_IMPORTED);
            parsed.teacherUpdateSlot = known.get(key);
        } else if (!seenInFile.add(row.getFoodsharingId() + "|" + key.substring(key.indexOf('|') + 1))) {
            row.setStatus(PastEinAbImportRowStatus.SKIP_DUPLICATE);
            reasons.add(PastEinAbImportReason.DUPLICATE_IN_FILE);
        } else {
            row.setStatus(PastEinAbImportRowStatus.CREATE);
        }
    }

    private static String duplicateKey(UUID userId, LocalDate date, String store) {
        return userId + "|" + date + "|" + (store == null ? "" : store.toLowerCase(Locale.ROOT).trim());
    }

    private static boolean isXlsx(PastEinAbImportRequest request) {
        return request.getXlsx() != null && !request.getXlsx().isBlank();
    }

    private Source readSource(PastEinAbImportRequest request) {
        if (!isXlsx(request)) {
            if (request.getCsv() == null || request.getCsv().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("csv"));
            }
            List<List<String>> records = PastEinAbCsvParser.parse(request.getCsv());
            List<Integer> lines = new ArrayList<>();
            for (int index = 0; index < records.size(); index++) {
                lines.add(index + 1);
            }
            return new Source(records, lines, Map.of());
        }
        List<PastEinAbXlsxReader.Sheet> sheets;
        try {
            sheets = PastEinAbXlsxReader.read(Base64.getMimeDecoder().decode(request.getXlsx()));
        } catch (IOException | IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("xlsx"));
        }
        for (PastEinAbXlsxReader.Sheet sheet : sheets) {
            List<List<String>> rows = sheet.rows();
            for (int header = 0; header < Math.min(rows.size(), 20); header++) {
                if (isProtocolHeader(rows.get(header))) {
                    List<List<String>> records = new ArrayList<>(rows.subList(header, rows.size()));
                    List<Integer> lines = new ArrayList<>(sheet.rowNumbers().subList(header, rows.size()));
                    convertXlsxCells(records);
                    return new Source(records, lines, teacherIds(sheets));
                }
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("xlsx"));
    }

    /** The header row of the EinAbs sheet: has an "ID", a "Datum" and a "Betrieb" column. */
    private static boolean isProtocolHeader(List<String> row) {
        boolean id = false;
        boolean date = false;
        boolean store = false;
        for (String cell : row) {
            String name = headerKey(cell);
            id |= name.equals("id");
            date |= name.startsWith("datum");
            store |= name.startsWith("betrieb");
        }
        return id && date && store;
    }

    private static String headerKey(String cell) {
        return cell == null ? "" : cell.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /** Workbook cells hold numbers: dates as Excel serial days, IDs and "No." possibly with a decimal part. */
    private static void convertXlsxCells(List<List<String>> records) {
        Columns columns = Columns.of(records.get(0));
        for (int index = 1; index < records.size(); index++) {
            List<String> cells = records.get(index);
            int dateColumn = columns.index()[Columns.DATE];
            if (dateColumn < cells.size() && EXCEL_NUMBER.matcher(cells.get(dateColumn).trim()).matches()) {
                long days = new BigDecimal(cells.get(dateColumn).trim()).longValue();
                LocalDate date = days > 0 && days < 2_958_466 ? EXCEL_EPOCH.plusDays(days) : null;
                cells.set(dateColumn, date == null ? cells.get(dateColumn)
                        : date.getDayOfMonth() + "." + date.getMonthValue() + "." + date.getYear());
            }
            for (int field : new int[] {Columns.ID, Columns.NUMBER}) {
                int column = columns.index()[field];
                if (column < cells.size() && EXCEL_NUMBER.matcher(cells.get(column).trim()).matches()) {
                    cells.set(column, wholeNumber(cells.get(column).trim()));
                }
            }
        }
    }

    private static String wholeNumber(String number) {
        try {
            BigDecimal value = new BigDecimal(number).stripTrailingZeros();
            return value.scale() <= 0 ? value.toPlainString() : number;
        } catch (NumberFormatException ex) {
            return number;
        }
    }

    /**
     * The list the EinAb-Geber*in dropdown is built from: a sheet whose first row has an "EinAb-Geber*innen" column and an
     * "ID" column. Keys are normalised names. (The overview sheet's "EinAb-Geber" count column does not match.)
     */
    private static Map<String, String> teacherIds(List<PastEinAbXlsxReader.Sheet> sheets) {
        Map<String, String> ids = new HashMap<>();
        for (PastEinAbXlsxReader.Sheet sheet : sheets) {
            if (sheet.rows().isEmpty()) {
                continue;
            }
            List<String> header = sheet.rows().get(0);
            int nameColumn = -1;
            int idColumn = -1;
            for (int column = 0; column < header.size(); column++) {
                String name = headerKey(header.get(column));
                if (nameColumn < 0 && name.matches("einab-geber[*:_]?innen.*")) {
                    nameColumn = column;
                } else if (idColumn < 0 && name.equals("id")) {
                    idColumn = column;
                }
            }
            if (nameColumn < 0 || idColumn < 0) {
                continue;
            }
            for (List<String> row : sheet.rows().subList(1, sheet.rows().size())) {
                if (nameColumn >= row.size() || idColumn >= row.size()) {
                    continue;
                }
                String name = teacherKey(row.get(nameColumn));
                String id = wholeNumber(row.get(idColumn).trim());
                if (!name.isEmpty() && !name.matches("[\\d.]+") && id.matches("\\d{1,18}")) {
                    ids.putIfAbsent(name, id);
                }
            }
        }
        return ids;
    }

    private static String teacherKey(String name) {
        return name == null ? "" : name.replace('\u00a0', ' ').trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static void applyTeacher(PastEinAbImportRow row, String giver, Map<String, String> teacherIds) {
        row.setTeacherName(giver);
        Matcher matcher = TEACHER_ID.matcher(giver);
        String id = matcher.find() ? matcher.group(1) : teacherIds.get(teacherKey(giver));
        row.setTeacherFoodsharingId(id);
        if (id == null && !teacherIds.isEmpty()) {
            row.addReasonsItem(PastEinAbImportReason.TEACHER_UNKNOWN);
        }
    }

    private Parsed parseRow(int line, List<String> cells, Columns columns, LocalDate today) {
        Parsed parsed = new Parsed();
        PastEinAbImportRow row = parsed.row;
        row.setLine(line);
        row.setReasons(new ArrayList<>());
        row.setNewUser(false);
        row.setNewTeacher(false);

        String id = columns.get(cells, Columns.ID).replaceAll("\\s", "");
        if (id.isEmpty()) {
            row.addReasonsItem(PastEinAbImportReason.MISSING_ID);
        } else if (!id.matches("\\d{1,18}")) {
            row.addReasonsItem(PastEinAbImportReason.INVALID_ID);
        } else {
            row.setFoodsharingId(id);
        }

        String rawDate = columns.get(cells, Columns.DATE);
        row.setRawDate(rawDate);
        LocalDate date = parseDate(rawDate);
        if (date == null) {
            row.addReasonsItem(PastEinAbImportReason.INVALID_DATE);
        } else {
            row.setDate(date);
            Instant noon = ZonedDateTime.of(date, LocalTime.NOON, ZURICH).toInstant();
            Instant now = Instant.now();
            // Only past EinAbs: a later date is flagged, and "today at noon" must not lie in the future.
            parsed.start = noon.isAfter(now) ? now : noon;
            if (date.isAfter(today)) {
                row.addReasonsItem(PastEinAbImportReason.FUTURE_DATE);
            }
        }

        // "x" in the No. column: the EinAb is kept, but it does not count (cancelled, no-show or failed). Any other
        // content, including an empty cell, imports as a normal EinAb.
        if (columns.get(cells, Columns.NUMBER).equalsIgnoreCase("x")) {
            parsed.notCounted = true;
            row.addReasonsItem(PastEinAbImportReason.NOT_COUNTED);
        }

        String rawStore = columns.get(cells, Columns.STORE);
        row.setRawStore(rawStore);
        row.setStore(rawStore);
        if (rawStore.isEmpty()) {
            row.addReasonsItem(PastEinAbImportReason.MISSING_STORE);
        }

        String rawCategory = columns.get(cells, Columns.CATEGORY);
        row.setRawCategory(rawCategory);
        parsed.category = mapCategory(rawCategory, row);
        if (parsed.category != null) {
            row.setCategory(ch.it4user.foodsharing.openapi.model.EinAbCategory.fromValue(parsed.category.name()));
        } else {
            row.addReasonsItem(PastEinAbImportReason.UNKNOWN_CATEGORY);
        }

        String visit = columns.get(cells, Columns.FAIRTEILER).toLowerCase(Locale.ROOT);
        row.setVisitFairteiler(visit.equals("ja") || visit.equals("yes"));
        if (!visit.isEmpty() && !visit.equals("ja") && !visit.equals("nein") && !visit.equals("yes")
                && !visit.equals("no")) {
            row.addReasonsItem(PastEinAbImportReason.FAIRTEILER_UNKNOWN);
        }

        parsed.feedback = columns.get(cells, Columns.FEEDBACK);
        row.setHasComment(!parsed.feedback.isEmpty());
        if (parsed.feedback.isEmpty()) {
            row.addReasonsItem(PastEinAbImportReason.NO_FEEDBACK);
        }
        return parsed;
    }

    static LocalDate parseDate(String raw) {
        Matcher matcher = DATE.matcher(raw == null ? "" : raw.trim());
        if (!matcher.matches()) {
            return null;
        }
        int year = Integer.parseInt(matcher.group(3));
        if (year < 100) {
            year += 2000;
        }
        try {
            return LocalDate.of(year, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(1)));
        } catch (java.time.DateTimeException ex) {
            return null;
        }
    }

    private EinAbCategory mapCategory(String raw, PastEinAbImportRow row) {
        String key = raw.toLowerCase(Locale.ROOT).trim();
        switch (key) {
            case "lebensmittelladen", "supermarkt", "laden":
                return EinAbCategory.SUPERMARKET;
            case "restaurant":
                return EinAbCategory.RESTAURANT;
            case "bäckerei", "baeckerei":
                return EinAbCategory.BAKERY;
            case "takeout", "take-out", "takeaway":
                return EinAbCategory.TAKEOUT;
            case "markt":
                return EinAbCategory.MARKET;
            case "messe":
                return EinAbCategory.MESSE;
            case "messe/grossabholung", "grossabholung":
                // Both categories exist; this combined label means a large pick-up at a fair.
                return EinAbCategory.GROSSABHOLUNG;
            case "ft-besuch", "ft-reinigung", "fairteiler":
                row.addReasonsItem(PastEinAbImportReason.CATEGORY_MAPPED);
                return EinAbCategory.FAIRTEILER_CLEANING;
            case "online":
                return EinAbCategory.ONLINE;
            default:
                return null;
        }
    }

    /**
     * Spellings that differ only in case, spaces or punctuation are one store. The name used is, in this order: a known
     * alias, a store name that already exists in this Bezirk, the most frequent spelling in the file.
     */
    private void applyStoreNames(Bezirk bezirk, List<Parsed> rows) {
        Map<String, String> existing = new HashMap<>();
        for (String name : importRepository.findBusinessNames(bezirk)) {
            existing.putIfAbsent(storeKey(name), name);
        }
        Map<String, Map<String, Integer>> spellings = new HashMap<>();
        for (Parsed parsed : rows) {
            String raw = parsed.row.getRawStore();
            if (!raw.isEmpty()) {
                spellings.computeIfAbsent(storeKey(raw), key -> new HashMap<>()).merge(raw, 1, Integer::sum);
            }
        }
        for (Parsed parsed : rows) {
            String raw = parsed.row.getRawStore();
            if (raw.isEmpty()) {
                continue;
            }
            String key = storeKey(raw);
            String target = STORE_ALIASES.get(key);
            if (target == null) {
                target = existing.get(key);
            }
            if (target == null) {
                target = spellings.get(key).entrySet().stream()
                        .max(Map.Entry.<String, Integer>comparingByValue()
                                .thenComparing(entry -> Character.isUpperCase(entry.getKey().charAt(0))))
                        .map(Map.Entry::getKey)
                        .orElse(raw);
            }
            parsed.row.setStore(target);
            if (!target.equals(raw)) {
                parsed.row.addReasonsItem(PastEinAbImportReason.STORE_NAME_CORRECTED);
            }
        }
    }

    private static String storeKey(String name) {
        StringBuilder key = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                key.append(c);
            }
        }
        return key.toString();
    }

    private static List<PastEinAbImportNameMapping> mappingSummary(List<Parsed> rows, boolean stores) {
        Map<String, PastEinAbImportNameMapping> mappings = new LinkedHashMap<>();
        for (Parsed parsed : rows) {
            PastEinAbImportRow row = parsed.row;
            String from = stores ? row.getRawStore() : row.getRawCategory();
            String to = stores ? row.getStore() : (row.getCategory() == null ? null : row.getCategory().getValue());
            if (from.isEmpty() || to == null || (stores && from.equals(to))) {
                continue;
            }
            PastEinAbImportNameMapping mapping = mappings.computeIfAbsent(from + "\u0000" + to, key -> {
                PastEinAbImportNameMapping created = new PastEinAbImportNameMapping();
                created.setFrom(from);
                created.setTo(to);
                created.setCount(0);
                return created;
            });
            mapping.setCount(mapping.getCount() + 1);
        }
        return new ArrayList<>(mappings.values());
    }

    private static String truncate(String text) {
        return text.length() > MAX_COMMENT_LENGTH ? text.substring(0, MAX_COMMENT_LENGTH) : text;
    }

    /** Finds the columns by header name (the header cells contain line breaks) and falls back to the usual order. */
    private record Columns(int[] index) {
        static final int ID = 0;
        static final int DATE = 1;
        static final int NUMBER = 2;
        static final int STORE = 3;
        static final int CATEGORY = 4;
        static final int GIVER = 5;
        static final int FAIRTEILER = 6;
        static final int FEEDBACK = 7;
        private static final String[] PREFIXES = {"id", "datum", "no", "betrieb", "kategorie", "einab", "ft", "feedback"};

        static Columns of(List<String> header) {
            int[] index = {0, 1, 2, 3, 4, 5, 6, 7};
            for (int field = 0; field < PREFIXES.length; field++) {
                for (int column = 0; column < header.size(); column++) {
                    String name = header.get(column).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
                    if (name.startsWith(PREFIXES[field])) {
                        index[field] = column;
                        break;
                    }
                }
            }
            return new Columns(index);
        }

        String get(List<String> cells, int field) {
            int column = index[field];
            return column < cells.size() ? cells.get(column).replace(' ', ' ').trim() : "";
        }
    }
}
