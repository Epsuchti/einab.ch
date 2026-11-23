package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.openapi.model.IcalCandidate;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class IcalImportService {

    private static final DateTimeFormatter ZULU = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
    private static final Set<String> ALLOWED_HOSTS = Set.of("foodsharing.network", "foodsharing.de");
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_FEED_BYTES = 2 * 1024 * 1024;

    /**
     * The server downloads this link, so only Foodsharing calendar feeds over https are allowed. Anything else
     * (file:, internal hosts, other sites) would let a teacher make the server fetch arbitrary resources.
     */
    public static boolean isAllowedLink(String icalLink) {
        if (icalLink == null || icalLink.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(icalLink.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return ALLOWED_HOSTS.stream().anyMatch(allowed ->
                    normalizedHost.equals(allowed) || normalizedHost.endsWith("." + allowed));
        } catch (Exception ex) {
            return false;
        }
    }

    public List<IcalCandidate> loadCandidates(String icalLink) {
        if (!isAllowedLink(icalLink)) {
            return List.of();
        }

        List<IcalCandidate> candidates = new ArrayList<>();
        Instant now = Instant.now();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                download(icalLink.trim()), StandardCharsets.UTF_8))) {
            List<String> lines = unfoldLines(reader);
            Instant currentStart = null;
            String currentSummary = null;
            String currentLocation = null;
            for (String line : lines) {
                if (line.startsWith("DTSTART:")) {
                    currentStart = parseDateTime(line.substring("DTSTART:".length()));
                } else if (line.startsWith("DTSTART;")) {
                    currentStart = parseDateTime(line.substring(line.indexOf(':') + 1));
                } else if (line.startsWith("SUMMARY:")) {
                    currentSummary = unescapeText(line.substring("SUMMARY:".length())).trim();
                } else if (line.startsWith("LOCATION:")) {
                    currentLocation = unescapeText(line.substring("LOCATION:".length())).trim();
                } else if (line.startsWith("LOCATION;")) {
                    currentLocation = unescapeText(line.substring(line.indexOf(':') + 1)).trim();
                } else if (line.equals("END:VEVENT") && currentStart != null) {
                    if (!currentStart.isBefore(now)) {
                        IcalCandidate candidate = new IcalCandidate();
                        candidate.setStartDateTime(OffsetDateTime.ofInstant(currentStart, ZoneOffset.UTC));
                        candidate.setSummary(currentSummary == null || currentSummary.isBlank() ? "Imported event" : currentSummary);
                        candidate.setLocation(currentLocation == null || currentLocation.isBlank() ? null : currentLocation);
                        candidates.add(candidate);
                    }
                    currentStart = null;
                    currentSummary = null;
                    currentLocation = null;
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return candidates;
    }

    private InputStream download(String icalLink) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(icalLink).toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        // A redirect could lead off the allowed hosts.
        connection.setInstanceFollowRedirects(false);
        try (InputStream body = connection.getInputStream()) {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Unexpected iCal response " + connection.getResponseCode());
            }
            byte[] bytes = body.readNBytes(MAX_FEED_BYTES + 1);
            if (bytes.length > MAX_FEED_BYTES) {
                throw new IOException("iCal feed too large");
            }
            return new ByteArrayInputStream(bytes);
        } finally {
            connection.disconnect();
        }
    }

    private List<String> unfoldLines(BufferedReader reader) throws java.io.IOException {
        List<String> lines = new ArrayList<>();
        String current = null;
        String rawLine;
        while ((rawLine = reader.readLine()) != null) {
            if ((rawLine.startsWith(" ") || rawLine.startsWith("\t")) && current != null) {
                current += rawLine.substring(1);
                continue;
            }
            if (current != null) {
                lines.add(current);
            }
            current = rawLine;
        }
        if (current != null) {
            lines.add(current);
        }
        return lines;
    }

    private Instant parseDateTime(String rawValue) {
        if (rawValue.endsWith("Z")) {
            return OffsetDateTime.of(LocalDateTime.parse(rawValue, ZULU), ZoneOffset.UTC).toInstant();
        }
        return LocalDateTime.parse(rawValue, LOCAL).atZone(ZURICH).toInstant();
    }

    private String unescapeText(String value) {
        StringBuilder unescaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\\' || index == value.length() - 1) {
                unescaped.append(character);
                continue;
            }

            char escapedCharacter = value.charAt(++index);
            switch (escapedCharacter) {
                case 'n', 'N' -> unescaped.append('\n');
                case ',', ';', '\\' -> unescaped.append(escapedCharacter);
                default -> {
                    unescaped.append('\\');
                    unescaped.append(escapedCharacter);
                }
            }
        }
        return unescaped.toString();
    }
}
