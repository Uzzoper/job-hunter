package com.juanperuzzo.job_hunter.integration;

import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.web.dto.AuthRequest;
import com.juanperuzzo.job_hunter.web.dto.AuthResponse;
import com.juanperuzzo.job_hunter.web.dto.ProfileRequest;
import com.juanperuzzo.job_hunter.web.dto.ProfileResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Auth full flow integration test")
class AuthIntegrationTest {

    private static final String TEST_EMAIL = "test-integration@example.com";
    private static final String TEST_PASSWORD = "secret123";
    private static final String TEST_NAME = "TestUser";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RestClient restClient;
    private String authToken;
    private Long userId;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_USER", () -> "peruzzo");
        registry.add("DB_PASSWORD", () -> "jobhunter123");
        registry.add("jwt.secret", () -> "test-secret-key-min-32-chars-long-for-hmac!!123");
        registry.add("OPENROUTER_API_KEY", () -> "sk-test-dummy-key");
    }

    @BeforeAll
    void setUp() {
        restClient = RestClient.create();
        cleanupTestData();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @Order(1)
    @DisplayName("should register a new user and return 201 with token")
    void register_whenValidData_shouldReturn201AndToken() {
        var request = new AuthRequest(TEST_NAME, TEST_EMAIL, TEST_PASSWORD);
        var response = restClient.post()
                .uri(url("/api/auth/register"))
                .body(request)
                .retrieve()
                .toEntity(AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().token()).isNotEmpty();
        assertThat(response.getBody().userId()).isPositive();
        assertThat(response.getBody().name()).isEqualTo(TEST_NAME);
        assertThat(response.getBody().email()).isEqualTo(TEST_EMAIL);
        this.authToken = response.getBody().token();
        this.userId = response.getBody().userId();
    }

    @Test
    @Order(2)
    @DisplayName("should login with valid credentials and return 200 with token")
    void login_whenValidCredentials_shouldReturn200AndToken() {
        var request = new AuthRequest(null, TEST_EMAIL, TEST_PASSWORD);
        var response = restClient.post()
                .uri(url("/api/auth/login"))
                .body(request)
                .retrieve()
                .toEntity(AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().token()).isNotEmpty();
        assertThat(response.getBody().userId()).isEqualTo(userId);
        this.authToken = response.getBody().token();
    }

    @Test
    @Order(3)
    @DisplayName("should return profile when authenticated")
    void getProfile_whenAuthenticated_shouldReturn200() {
        var response = restClient.get()
                .uri(url("/api/profile"))
                .header("Authorization", "Bearer " + authToken)
                .retrieve()
                .toEntity(ProfileResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().userId()).isEqualTo(userId);
    }

    @Test
    @Order(4)
    @DisplayName("should return 401 when no auth token is provided")
    void getProfile_whenNoAuth_shouldReturn401() {
        var response = restClient.get()
                .uri(url("/api/profile"))
                .retrieve()
                .onStatus(status -> status.value() == 401, (request, response1) -> {})
                .toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(5)
    @DisplayName("should return 401 when invalid token is provided")
    void getProfile_whenInvalidToken_shouldReturn401() {
        var response = restClient.get()
                .uri(url("/api/profile"))
                .header("Authorization", "Bearer invalid-token-123")
                .retrieve()
                .onStatus(status -> status.value() == 401, (request, response1) -> {})
                .toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(6)
    @DisplayName("should save profile when authenticated")
    void saveProfile_whenAuthenticated_shouldReturn200() {
        var resumeText = "Java developer with Spring Boot experience building REST APIs and microservices.";
        var profileRequest = new ProfileRequest(
                resumeText,
                List.of("Java", "Spring Boot", "PostgreSQL"),
                CompanyTone.FORMAL);
        var response = restClient.put()
                .uri(url("/api/profile"))
                .header("Authorization", "Bearer " + authToken)
                .body(profileRequest)
                .retrieve()
                .toEntity(ProfileResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().resumeText()).isEqualTo(resumeText);
        assertThat(response.getBody().skills()).containsExactly("Java", "Spring Boot", "PostgreSQL");
        assertThat(response.getBody().tone()).isEqualTo(CompanyTone.FORMAL);
        assertThat(response.getBody().userId()).isEqualTo(userId);
    }

    @Test
    @Order(7)
    @DisplayName("should return 409 when registering with duplicate email")
    void register_whenDuplicateEmail_shouldReturn409() {
        var request = new AuthRequest("AnotherUser", TEST_EMAIL, "otherpass123");
        var response = restClient.post()
                .uri(url("/api/auth/register"))
                .body(request)
                .retrieve()
                .onStatus(status -> status.value() == 409, (request1, response1) -> {})
                .toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @Order(8)
    @DisplayName("should return 401 when login with wrong password")
    void login_whenWrongPassword_shouldReturn401() {
        var request = new AuthRequest(null, TEST_EMAIL, "wrongpassword");
        var response = restClient.post()
                .uri(url("/api/auth/login"))
                .body(request)
                .retrieve()
                .onStatus(status -> status.value() == 401, (request1, response1) -> {})
                .toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @AfterAll
    void cleanup() {
        cleanupTestData();
    }

    private void cleanupTestData() {
        jdbcTemplate.update("DELETE FROM email_drafts WHERE user_id = (SELECT id FROM users WHERE email = ?)", TEST_EMAIL);
        jdbcTemplate.update("DELETE FROM job_analyses WHERE user_id = (SELECT id FROM users WHERE email = ?)", TEST_EMAIL);
        jdbcTemplate.update("DELETE FROM user_profiles WHERE user_id = (SELECT id FROM users WHERE email = ?)", TEST_EMAIL);
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", TEST_EMAIL);
    }
}
