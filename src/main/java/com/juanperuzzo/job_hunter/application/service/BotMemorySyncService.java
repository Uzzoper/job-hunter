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
     * Generic mapping from BotPreferences key (lowercase) to UserProfile field name.
     * Keys not in this map are ignored during merge (raw data stays in BotPreferences).
     */
    private static final Map<String, String> KEY_TO_PROFILE_FIELD = Map.of(
            "contactemail", "contactEmail",
            "email", "contactEmail",
            "phone", "phone",
            "portfolio", "portfolioUrl",
            "portfoliourl", "portfolioUrl",
            "github", "githubUrl",
            "githuburl", "githubUrl",
            "linkedin", "linkedinUrl",
            "linkedinurl", "linkedinUrl"
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
        String section = "\n§\n" + text.strip() + "\n";
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
     * Only keys present in {@link #KEY_TO_PROFILE_FIELD} are considered; unmapped
     * keys are silently skipped.
     */
    private void mergeIntoProfile(Long userId, BotPreferences prefs) {
        var existing = userProfileRepository.findByUserId(userId);
        if (existing.isEmpty()) {
            log.warn("No profile found for user {} — skipping bot memory merge", userId);
            return;
        }

        var profile = existing.get();
        String phone = profile.phone();
        String contactEmail = profile.contactEmail();
        String portfolioUrl = profile.portfolioUrl();
        String githubUrl = profile.githubUrl();
        String linkedinUrl = profile.linkedinUrl();

        boolean changed = false;

        for (var entry : prefs.keyValues().entrySet()) {
            String field = KEY_TO_PROFILE_FIELD.get(entry.getKey().toLowerCase());
            if (field == null) {
                continue;
            }
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }

            switch (field) {
                case "phone" -> {
                    if (phone == null || phone.isBlank()) {
                        phone = value;
                        changed = true;
                    }
                }
                case "contactEmail" -> {
                    if (contactEmail == null || contactEmail.isBlank()) {
                        contactEmail = value;
                        changed = true;
                    }
                }
                case "portfolioUrl" -> {
                    if (portfolioUrl == null || portfolioUrl.isBlank()) {
                        portfolioUrl = value;
                        changed = true;
                    }
                }
                case "githubUrl" -> {
                    if (githubUrl == null || githubUrl.isBlank()) {
                        githubUrl = value;
                        changed = true;
                    }
                }
                case "linkedinUrl" -> {
                    if (linkedinUrl == null || linkedinUrl.isBlank()) {
                        linkedinUrl = value;
                        changed = true;
                    }
                }
            }
        }

        if (changed) {
            var updated = new UserProfile(
                    profile.id(), profile.userId(), profile.resumeText(),
                    profile.skills(), profile.tone(), profile.projects(),
                    phone, contactEmail, portfolioUrl, githubUrl, linkedinUrl);
            userProfileRepository.save(updated);
            log.info("Merged bot memory preferences into profile for user {}", userId);
        }
    }
}
