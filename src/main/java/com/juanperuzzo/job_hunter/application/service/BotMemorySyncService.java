package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.out.BotMemoryPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.model.BotPreferences;
import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import com.juanperuzzo.job_hunter.domain.model.WorkModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Reads the Hermes bot memory files, parses {@code §}-delimited sections into
 * {@link BotPreferences}, merges applicable key-values into the user's profile
 * (fill-if-empty), and supports write-back of new sections to {@code MEMORY.md}.
 *
 * <p>File format: sections delimited by {@code §} on its own line. Within each
 * section, lines matching {@code key: value} are extracted as key-value pairs;
 * the full section text is preserved as a raw section.
 *
 * <p>Follows the <em>fill-if-empty merge</em> precedent from
 * {@code ResumeUploadService}: existing profile values always win.
 */
public class BotMemorySyncService {

    private static final Logger log = LoggerFactory.getLogger(BotMemorySyncService.class);

    /** Regex for the § delimiter line (the § character possibly surrounded by whitespace). */
    private static final Pattern SECTION_DELIMITER = Pattern.compile("^\\s*§\\s*$", Pattern.MULTILINE);

    /** Regex for key: value lines within a section (key ≤ 80 chars, non-empty). */
    private static final Pattern KEY_VALUE_LINE = Pattern.compile("^([^:]{1,80}):\\s*(.+)$");

    /**
     * Generic mapping from BotPreferences key (already lowercase) to a copy-function
     * that returns the profile with the field filled when it is currently blank,
     * or the same profile instance when the field is already set (fill-if-empty).
     * <p>
     * New fields are added by editing <em>only</em> this map — no switch or
     * if-chain in {@link #mergeIntoProfile}.
     */
    private static final Map<String, BiFunction<UserProfile, String, UserProfile>> FIELD_SETTERS = Map.of(
            "phone",        (p, v) -> isBlank(p.phone())        ? copyContact(p, v, p.contactEmail(),  p.portfolioUrl(), p.githubUrl(),  p.linkedinUrl()) : p,
            "contactemail", (p, v) -> isBlank(p.contactEmail())  ? copyContact(p, p.phone(),            v,                p.portfolioUrl(), p.githubUrl(), p.linkedinUrl()) : p,
            "email",        (p, v) -> isBlank(p.contactEmail())  ? copyContact(p, p.phone(),            v,                p.portfolioUrl(), p.githubUrl(), p.linkedinUrl()) : p,
            "portfolio",    (p, v) -> isBlank(p.portfolioUrl())  ? copyContact(p, p.phone(),            p.contactEmail(), v,                p.githubUrl(), p.linkedinUrl()) : p,
            "portfoliourl", (p, v) -> isBlank(p.portfolioUrl())  ? copyContact(p, p.phone(),            p.contactEmail(), v,                p.githubUrl(), p.linkedinUrl()) : p,
            "github",       (p, v) -> isBlank(p.githubUrl())    ? copyContact(p, p.phone(),            p.contactEmail(), p.portfolioUrl(), v,             p.linkedinUrl()) : p,
            "githuburl",    (p, v) -> isBlank(p.githubUrl())    ? copyContact(p, p.phone(),            p.contactEmail(), p.portfolioUrl(), v,             p.linkedinUrl()) : p,
            "linkedin",     (p, v) -> isBlank(p.linkedinUrl())  ? copyContact(p, p.phone(),            p.contactEmail(), p.portfolioUrl(), p.githubUrl(), v)               : p,
            "linkedinurl",  (p, v) -> isBlank(p.linkedinUrl())  ? copyContact(p, p.phone(),            p.contactEmail(), p.portfolioUrl(), p.githubUrl(), v)               : p
    );

    // --- Work-model token normalization map (lowercase + accent-stripped → WorkModel) ---
    private static final Map<String, WorkModel> WORK_MODEL_TOKENS = Map.of(
            "remoto",    WorkModel.REMOTE,
            "remote",    WorkModel.REMOTE,
            "hibrido",   WorkModel.HYBRID,
            "hybrid",    WorkModel.HYBRID,
            "presencial", WorkModel.ONSITE,
            "onsite",    WorkModel.ONSITE,
            "on-site",   WorkModel.ONSITE,
            "on site",   WorkModel.ONSITE
    );

    private static final String SALARY_NON_DIGITS = "[^0-9]";

    private final BotMemoryPort botMemoryPort;
    private final UserProfileRepository userProfileRepository;
    private final Path memoryDir;
    private final String memoryFileName;
    private final String userFileName;

    public BotMemorySyncService(
            BotMemoryPort botMemoryPort,
            UserProfileRepository userProfileRepository,
            Path memoryDir,
            String memoryFileName,
            String userFileName) {
        this.botMemoryPort = botMemoryPort;
        this.userProfileRepository = userProfileRepository;
        this.memoryDir = memoryDir;
        this.memoryFileName = memoryFileName;
        this.userFileName = userFileName;
    }

    /**
     * Reads MEMORY.md and USER.md from the configured directory, parses them,
     * and merges applicable key-values into the user's profile (fill-if-empty).
     * <p>
     * Missing files or directories are handled gracefully (WARN log, no error).
     *
     * @param userId the user whose profile to update
     */
    public void syncFromBotMemory(Long userId) {
        var memoryFile = memoryDir.resolve(memoryFileName);
        var userFile = memoryDir.resolve(userFileName);

        var memoryPrefs = readAndParse(memoryFile);
        var userPrefs = readAndParse(userFile);

        // Merge both files: USER.md values take precedence on duplicate keys
        var mergedKeyValues = new LinkedHashMap<>(memoryPrefs.keyValues());
        mergedKeyValues.putAll(userPrefs.keyValues());

        var merged = new BotPreferences(mergedKeyValues,
                List.copyOf(memoryPrefs.rawSections()));

        if (merged.keyValues().isEmpty()) {
            log.debug("No key-value preferences found in bot memory for user {}", userId);
            return;
        }

        mergeIntoProfile(userId, merged);
    }

    /**
     * Parses raw file content into {@link BotPreferences}.
     *
     * @param content the file content (may be null or blank)
     * @return parsed preferences; empty if content is null/blank
     */
    public BotPreferences parseMemoryContent(String content) {
        if (content == null || content.isBlank()) {
            return BotPreferences.empty();
        }

        String[] sections = SECTION_DELIMITER.split(content);
        var keyValues = new LinkedHashMap<String, String>();
        var rawSections = new ArrayList<String>();

        for (String section : sections) {
            String trimmed = section.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            rawSections.add(trimmed);

            // Extract key: value pairs from lines within the section
            for (String line : trimmed.split("\\n")) {
                var matcher = KEY_VALUE_LINE.matcher(line.strip());
                if (matcher.matches()) {
                    String key = matcher.group(1).strip();
                    String value = matcher.group(2).strip();
                    keyValues.put(key, value);
                }
            }
        }

        return new BotPreferences(keyValues, rawSections);
    }

    /**
     * Appends a new section to MEMORY.md. The section is delimited by {@code §}.
     *
     * @param userId the user whose memory file to update
     * @param text   the free-text content of the new section
     */
    public void writeMemoryEntry(Long userId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        var memoryFile = memoryDir.resolve(memoryFileName);
        String safeText = text.replace("\u00A7", "\u00B7");
        String section = "\n\u00A7\n" + safeText.strip() + "\n";
        botMemoryPort.appendSection(memoryFile, section);
        log.info("Wrote memory entry for user {} to {}", userId, memoryFile);
    }

    // ── Private helpers ───────────────────────────────────────────────

    private BotPreferences readAndParse(Path file) {
        try {
            var content = botMemoryPort.readFile(file);
            return content.map(this::parseMemoryContent).orElseGet(() -> {
                log.warn("Bot memory file not found (skipping): {}", file);
                return BotPreferences.empty();
            });
        } catch (Exception e) {
            log.error("Failed to read bot memory file {}: {}", file, e.getMessage(), e);
            return BotPreferences.empty();
        }
    }

    /**
     * Merges parsed preferences into the user's profile using fill-if-empty rules.
     * Only keys present in {@link #FIELD_SETTERS} are considered; unmapped keys are
     * silently skipped. Preferences-specific keys (workModel, salary, locations,
     * excludedCompanies) go through the parse-lenient / validate-strict firewall.
     * Change detection uses object identity: unchanged fields leave
     * the profile instance unmodified, so a final {@code !=} check avoids an
     * unnecessary save.
     */
    private void mergeIntoProfile(Long userId, BotPreferences prefs) {
        var existing = userProfileRepository.findByUserId(userId);
        if (existing.isEmpty()) {
            log.warn("No profile found for user {} — skipping bot memory merge", userId);
            return;
        }

        var profile = existing.get();
        var updated = profile;

        // Standard contact-field merge (existing pattern)
        for (var entry : prefs.keyValues().entrySet()) {
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            var setter = FIELD_SETTERS.get(entry.getKey().toLowerCase(Locale.ROOT));
            if (setter != null) {
                updated = setter.apply(updated, value);
            }
        }

        // Preferences merge (parse-lenient / validate-strict firewall)
        updated = mergePreferences(updated, prefs, userId);

        if (updated != profile) {
            userProfileRepository.save(updated);
            log.info("Merged bot memory values into profile for user {} (contact fields and/or preferences)", userId);
        }
    }

    /**
     * Merge bot-sourced preferences into the profile. Each preference field goes
     * through normalization (lowercase, strip accents) then exact-match validation
     * before being set. Invalid candidates are dropped with a WARN log.
     * <p>
     * Fill-if-empty: human-set values (non-null) always win.
     */
    private UserProfile mergePreferences(UserProfile profile, BotPreferences prefs, Long userId) {
        var existingPrefs = profile.preferences();
        WorkModel workModel = existingPrefs != null ? existingPrefs.workModel() : null;
        Integer salaryFloor = existingPrefs != null ? existingPrefs.salaryFloor() : null;
        List<String> locations = existingPrefs != null && !existingPrefs.locations().isEmpty()
                ? existingPrefs.locations() : null;
        List<String> excludedCompanies = existingPrefs != null && !existingPrefs.excludedCompanies().isEmpty()
                ? existingPrefs.excludedCompanies() : null;

        String rawWorkModel = findKeyValueIgnoreCase(prefs, "workmodel");
        String rawSalary = findKeyValueIgnoreCase(prefs, "salary");
        String rawLocations = findKeyValueIgnoreCase(prefs, "locations");
        String rawExcluded = findKeyValueIgnoreCase(prefs, "excludedcompanies");

        boolean changed = false;

        // workModel: fill-if-empty + normalize
        if (workModel == null && rawWorkModel != null && !rawWorkModel.isBlank()) {
            WorkModel parsed = parseWorkModel(rawWorkModel);
            if (parsed != null) {
                log.info("Merged preferences for user {}: workModel=null→{} (source: '{}')",
                        userId, parsed, truncate(rawWorkModel, 80));
                workModel = parsed;
                changed = true;
            } else {
                log.warn("Dropped invalid workModel token '{}' for user {} — not a recognized work-model value", rawWorkModel, userId);
            }
        }

        // salaryFloor: fill-if-empty + normalize
        if (salaryFloor == null && rawSalary != null && !rawSalary.isBlank()) {
            Integer parsed = parseSalaryFloor(rawSalary);
            if (parsed != null) {
                log.info("Merged preferences for user {}: salaryFloor=null→{} (source: '{}')",
                        userId, parsed, truncate(rawSalary, 80));
                salaryFloor = parsed;
                changed = true;
            } else {
                log.warn("Dropped invalid salary value '{}' for user {} — not a valid positive integer", rawSalary, userId);
            }
        }

        // locations: fill-if-empty + normalize
        if (locations == null && rawLocations != null && !rawLocations.isBlank()) {
            List<String> parsed = parseLocations(rawLocations);
            if (!parsed.isEmpty()) {
                log.info("Merged preferences for user {}: locations=null→{} (source: '{}')",
                        userId, parsed, truncate(rawLocations, 80));
                locations = parsed;
                changed = true;
            } else {
                log.warn("Dropped invalid locations value '{}' for user {} — no valid locations parsed", rawLocations, userId);
            }
        }

        // excludedCompanies: fill-if-empty + normalize
        if (excludedCompanies == null && rawExcluded != null && !rawExcluded.isBlank()) {
            List<String> parsed = parseExcludedCompanies(rawExcluded);
            if (!parsed.isEmpty()) {
                log.info("Merged preferences for user {}: excludedCompanies=null→{} (source: '{}')",
                        userId, parsed, truncate(rawExcluded, 80));
                excludedCompanies = parsed;
                changed = true;
            } else {
                log.warn("Dropped invalid excludedCompanies value '{}' for user {} — no valid companies parsed", rawExcluded, userId);
            }
        }

        if (!changed) {
            return profile;
        }

        return new UserProfile(
                profile.id(), profile.userId(), profile.resumeText(),
                profile.skills(), profile.tone(), profile.projects(),
                profile.phone(), profile.contactEmail(),
                profile.portfolioUrl(), profile.githubUrl(), profile.linkedinUrl(),
                new UserPreferences(workModel, salaryFloor,
                        locations != null ? locations : List.of(),
                        excludedCompanies != null ? excludedCompanies : List.of()));
    }

    // ── Parse-lenient / validate-strict firewall ────────────────────

    /**
     * Parse a raw work-model string: normalize (lowercase, strip accents) then
     * exact-match against known tokens. Returns null if unrecognizable.
     */
    public static WorkModel parseWorkModel(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = stripAccents(raw.trim().toLowerCase(Locale.ROOT));
        return WORK_MODEL_TOKENS.get(normalized);
    }

    /**
     * Parse a raw salary string: extract digits, parse as integer, sanity-cap.
     * Returns null if unparseable, negative, or out of bounds.
     * Rejects any raw string containing a leading minus sign to prevent
     * stripping of the negative sign by digit extraction.
     */
    public static Integer parseSalaryFloor(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.strip();
        // Reject negative numbers explicitly before stripping non-digits
        if (trimmed.startsWith("-")) return null;
        String digits = trimmed.replaceAll(SALARY_NON_DIGITS, "");
        if (digits.isEmpty()) return null;
        try {
            int value = Integer.parseInt(digits);
            if (value <= 0 || value > UserPreferences.MAX_SALARY_FLOOR) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parse a comma-delimited locations string: split, trim, filter blanks and
     * length-exceeding items, cap at MAX_LOCATIONS.
     */
    public static List<String> parseLocations(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String[] parts = raw.split(",");
        var result = new ArrayList<String>();
        for (String part : parts) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) continue;
            if (trimmed.length() > UserPreferences.MAX_LOCATION_LENGTH) {
                log.warn("Dropping location exceeding {} chars: '{}'", UserPreferences.MAX_LOCATION_LENGTH, truncate(trimmed, 100));
                continue;
            }
            result.add(trimmed);
            if (result.size() >= UserPreferences.MAX_LOCATIONS) break;
        }
        return List.copyOf(result);
    }

    /**
     * Parse a comma-delimited excluded-companies string: split, trim, filter
     * blanks and length-exceeding items, cap at MAX_EXCLUDED_COMPANIES.
     */
    public static List<String> parseExcludedCompanies(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String[] parts = raw.split(",");
        var result = new ArrayList<String>();
        for (String part : parts) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) continue;
            if (trimmed.length() > UserPreferences.MAX_COMPANY_NAME_LENGTH) {
                log.warn("Dropping company name exceeding {} chars: '{}'", UserPreferences.MAX_COMPANY_NAME_LENGTH, truncate(trimmed, 100));
                continue;
            }
            result.add(trimmed);
            if (result.size() >= UserPreferences.MAX_EXCLUDED_COMPANIES) break;
        }
        return List.copyOf(result);
    }

    // ── Utility ──────────────────────────────────────────────────────

    /**
     * Case-insensitive key lookup in the bot preferences keyValues map.
     * Matches the existing FIELD_SETTERS pattern where keys are stored as-is
     * but looked up via {@code toLowerCase()}.
     */
    private static String findKeyValueIgnoreCase(BotPreferences prefs, String targetKey) {
        for (var entry : prefs.keyValues().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(targetKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String stripAccents(String input) {
        return Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Creates a copy of {@code p} with the five contact fields replaced.
     * Used as the inner body of every {@link #FIELD_SETTERS} lambda so that
     * adding a new field means adding only one map entry.
     */
    private static UserProfile copyContact(UserProfile p,
                                    String phone, String contactEmail,
                                    String portfolioUrl, String githubUrl,
                                    String linkedinUrl) {
        return new UserProfile(
                p.id(), p.userId(), p.resumeText(),
                p.skills(), p.tone(), p.projects(),
                phone, contactEmail, portfolioUrl, githubUrl, linkedinUrl,
                p.preferences());
    }
}
