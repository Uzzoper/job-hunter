package com.juanperuzzo.job_hunter.infrastructure.config;

import com.juanperuzzo.job_hunter.application.port.out.KeywordResolver;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class DefaultKeywordResolver implements KeywordResolver {

    private final UserProfileRepository userProfileRepository;

    public DefaultKeywordResolver(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = Objects.requireNonNull(userProfileRepository, "userProfileRepository");
    }

    @Override
    public List<String> resolve(Long userId) {
        Objects.requireNonNull(userId, "userId");
        var profileOpt = userProfileRepository.findByUserId(userId);
        if (profileOpt.isPresent()) {
            var prefs = profileOpt.get().preferences();
            if (prefs != null && prefs.keywords() != null && !prefs.keywords().isEmpty()) {
                return prefs.keywords();
            }
        }
        return List.of();
    }
}
