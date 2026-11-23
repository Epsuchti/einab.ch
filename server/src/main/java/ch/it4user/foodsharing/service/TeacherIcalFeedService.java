package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.EinAb;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.SlotStatus;
import ch.it4user.foodsharing.repository.EinAbRepository;
import ch.it4user.foodsharing.repository.UserRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherIcalFeedService {

    private static final String FEED_PATH = "/api/teacher/ical/";
    private static final Set<SlotStatus> BOOKED_STATUSES = Set.of(
            SlotStatus.BOOKED,
            SlotStatus.DONE);
    private static final DateTimeFormatter ICAL_DATE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private final UserRepository userRepository;
    private final EinAbRepository einAbRepository;
    private final TokenService tokenService;
    private final AppProperties appProperties;

    public TeacherIcalFeedService(UserRepository userRepository,
                                  EinAbRepository einAbRepository,
                                  TokenService tokenService,
                                  AppProperties appProperties) {
        this.userRepository = userRepository;
        this.einAbRepository = einAbRepository;
        this.tokenService = tokenService;
        this.appProperties = appProperties;
    }

    @Transactional
    public String getFeedLink(User teacher) {
        User managedTeacher = userRepository.findByIdForUpdate(teacher.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.TEACHER_NOT_FOUND));
        String token = managedTeacher.getIcalFeedToken();
        if (token == null || token.isBlank()) {
            do {
                token = tokenService.generateToken();
            } while (userRepository.existsByIcalFeedToken(token));
            managedTeacher.setIcalFeedToken(token);
        }
        String baseUrl = appProperties.getFrontend().getBaseUrl();
        if (baseUrl == null) {
            baseUrl = "";
        }
        return baseUrl.replaceAll("/+$", "") + FEED_PATH + token + ".ics";
    }

    @Transactional(readOnly = true)
    public String renderFeed(String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND);
        }
        User teacher = userRepository.findByIcalFeedToken(token)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND));
        return render(einAbRepository.findAllByTeacherWithBookings(teacher, BOOKED_STATUSES));
    }

    @Transactional(readOnly = true)
    public String renderEinAb(EinAb einAb) {
        return render(java.util.List.of(einAb));
    }

    private String render(Collection<EinAb> einAbs) {
        Instant now = Instant.now();
        StringBuilder feed = new StringBuilder()
                .append("BEGIN:VCALENDAR\r\n")
                .append("VERSION:2.0\r\n")
                .append("PRODID:-//foodsharing.network//EinAb//DE\r\n")
                .append("CALSCALE:GREGORIAN\r\n")
                .append("METHOD:PUBLISH\r\n");
        for (EinAb einAb : einAbs) {
            feed.append("BEGIN:VEVENT\r\n")
                    .append("UID:").append(einAb.getId()).append("@foodsharing-einab-tabelle\r\n")
                    .append("DTSTAMP:").append(ICAL_DATE_TIME.format(now)).append("\r\n")
                    .append("DTSTART:").append(ICAL_DATE_TIME.format(einAb.getStartDateTime())).append("\r\n");
            appendProperty(feed, "SUMMARY", summary(einAb));
            appendProperty(feed, "LOCATION", location(einAb));
            appendProperty(feed, "DESCRIPTION", description(einAb));
            appendProperty(feed, "URL", einAb.getOnlineCallLink());
            feed.append("STATUS:CONFIRMED\r\n")
                    .append("END:VEVENT\r\n");
        }
        return feed.append("END:VCALENDAR\r\n").toString();
    }

    private void appendProperty(StringBuilder feed, String name, String value) {
        if (value != null && !value.isBlank()) {
            feed.append(name).append(':').append(escape(value)).append("\r\n");
        }
    }

    private String summary(EinAb einAb) {
        String businessName = trimToNull(einAb.getBusinessName());
        String category = einAb.getCategory() == null ? "EinAb" : einAb.getCategory().name();
        return businessName == null ? category : businessName + " (" + category + ")";
    }

    private String location(EinAb einAb) {
        if (trimToNull(einAb.getOnlineCallLink()) != null) {
            return einAb.getOnlineCallLink();
        }
        String privateAddress = trimToNull(einAb.getPrivateAddress());
        return privateAddress != null ? privateAddress : einAb.getLocation();
    }

    private String description(EinAb einAb) {
        String privateInfo = trimToNull(einAb.getPrivateInfo());
        String publicInfo = trimToNull(einAb.getPublicInfo());
        if (privateInfo == null) {
            return publicInfo;
        }
        return publicInfo == null ? privateInfo : privateInfo + "\n\n" + publicInfo;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n")
                .replace(";", "\\;")
                .replace(",", "\\,");
    }
}
