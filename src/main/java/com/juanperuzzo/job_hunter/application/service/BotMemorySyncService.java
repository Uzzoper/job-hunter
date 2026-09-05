package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.out.BotMemoryPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.model.BotPreferences;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
            "phone",        (p, v) -> isBlank(p.phone())        ? copy(p, v, p.contactEmail(),  p.portfolioUrl(), p.githubUrl(),  p.linkedinUrl()) : p,
            "contactemail", (p, v) -> isBlank(p.contactEmail())  ? copy(p, p.phone(),            v,                p.portfolioUrl(), p.githubUrl(), p.linkedinUrl()) : p,
            "email",        (p, v) -> isBlank(p.contactEmail())  ? copy(p, p.phone(),            v,                p.portfolioUrl(), p.githubUrl(), p.linkedinUrl()) : p,
            "portfolio",    (p, v) -> isBlank(p.portfolioUrl())  ? copy(p, p.phone(),            p.contactEmail(), v,                p.githubUrl(), p.linkedinUrl()) : p,
            "portfoliourl", (p, v) -> isBlank(p.portfolioUrl())  ? copy(p, p.phone(),            p.contactEmail(), v,                p.githubUrl(), p.linkedinUrl()) : p,
            "github",       (p, v) -> isBlank(p.githubUrl())    ? copy(p, p.phone(),            p.contactEmail(), p.portfolioUrl(), v,             p.linkedinUrl()) : p,
            "githuburl",    (p, v) -> isBlank(p.githubUrl())    ? copy(p, p.phone(),            p.contactEmail(), p.portfolioUrl(), v,             p.linkedinUrl()) : p,
            "linkedin",     (p, v) -> isBlank(p.linkedinUrl())  ? copy(p, p.phone(),            p.contactEmail(), p.portfolioUrl(), p.githubUrl(), v)               : p,
            "linkedinurl",  (p, v) -> isBlank(p.linkedinUrl())  ? copy(p, p.phone(),            p.contactEmail(), p.portfolioUrl(), p.githubUrl(), v)               : p
    );

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
     * silently skipped. Change detection uses object identity: unchanged fields leave
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

        for (var entry : prefs.keyValues().entrySet()) {
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            var setter = FIELD_SETTERS.get(entry.getKey().toLowerCase());
            if (setter != null) {
                updated = setter.apply(updated, value);
            }
        }

        if (updated != profile) {
            userProfileRepository.save(updated);
            log.info("Merged bot memory preferences into profile for user {}", userId);
        }
    }
}

// ── Private helpers ───────────────────────────────────────────────

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Creates a copy of {@code p} with the five contact fields replaced.
     * Used as the inner body of every {@link #FIELD_SETTERS} lambda so that
     * adding a new field means adding only one map entry.
     */
    private static UserProfile copy(UserProfile p,
                                    String phone, String contactEmail,
                                    String portfolioUrl, String githubUrl,
                                    String linkedinUrl) {
        return new UserProfile(
                p.id(), p.userId(), p.resumeText(),
                p.skills(), p.tone(), p.projects(),
                phone, contactEmail, portfolioUrl, githubUrl, linkedinUrl);
    }
