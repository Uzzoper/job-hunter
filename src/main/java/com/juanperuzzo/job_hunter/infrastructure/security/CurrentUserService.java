package com.juanperuzzo.job_hunter.infrastructure.security;

import com.juanperuzzo.job_hunter.application.port.in.CurrentUserProvider;
import com.juanperuzzo.job_hunter.domain.model.User;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserService implements CurrentUserProvider {

    @Override
    public Long getCurrentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof User user)) {
            throw new IllegalStateException("User not authenticated");
        }
        return user.id();
    }
}
