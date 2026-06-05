package com.juanperuzzo.job_hunter.infrastructure.security;

import com.juanperuzzo.job_hunter.application.port.out.TokenProvider;
import com.juanperuzzo.job_hunter.domain.model.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class JwtTokenService implements TokenProvider {

    private final String secret;
    private final int expirationHours;

    public JwtTokenService(String secret, int expirationHours) {
        this.secret = secret;
        this.expirationHours = expirationHours;
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String issue(User user) {
        return Jwts.builder()
                .subject(user.id().toString())
                .claim("userId", user.id())
                .claim("email", user.email())
                .claim("name", user.name())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationHours * 3600 * 1000L))
                .signWith(getSigningKey())
                .compact();
    }

    @Override
    public User validate(String token) {
        var claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Long userId = claims.get("userId", Long.class);
        String email = claims.get("email", String.class);
        String name = claims.get("name", String.class);

        return new User(userId, email, name, "");
    }
}
