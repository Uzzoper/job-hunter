package com.juanperuzzo.job_hunter.application.port.out;

import com.juanperuzzo.job_hunter.domain.model.User;

public interface TokenProvider {
    String issue(User user);
    User validate(String token);
}
