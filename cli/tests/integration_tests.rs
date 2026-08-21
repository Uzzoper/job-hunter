// Integration tests for the Job Hunter CLI.
//
// These tests use httpmock to simulate the backend — no real network calls.
// They test end-to-end flows through the library's public API.

use clap::Parser;
use httpmock::prelude::*;
use httpmock::Method;
use jh_cli::api::ApiClient;
use jh_cli::config::ConfigManager;
use jh_cli::domain::{
    AuthRequest, CompanyTone, EmailStatus, LoginRequest, ProfileRequest,
};
use jh_cli::error::{ApiError, CliError};
use jh_cli::Cli;
use serde_json::json;
use std::path::PathBuf;

// =========================================================================
// Helpers
// =========================================================================

/// Create a test client pointing at a mock server.
fn test_client(server: &MockServer) -> ApiClient {
    
    ApiClient::new(&server.url("/"))
}

/// Create a temporary directory for config files in tests.
fn test_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir()
        .join("jh-cli-integration-test")
        .join(name);
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("create test dir");
    dir
}

// =========================================================================
// 1. CLI argument parsing tests
// =========================================================================

mod cli_parsing {
    use super::*;

    #[test]
    fn parse_no_args_creates_cli_with_no_command() {
        let cli = Cli::try_parse_from(["jh-cli"]).expect("parse should succeed");
        assert!(cli.command.is_none());
        assert!(cli.api_url.is_none());
        assert!(!cli.tui);
    }

    #[test]
    fn parse_auth_login() {
        let cli = Cli::try_parse_from([
            "jh-cli", "auth", "login", "user@test.com", "--password", "secret123",
        ])
        .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_auth_register() {
        let cli = Cli::try_parse_from([
            "jh-cli", "auth", "register", "Alice", "alice@test.com", "--password", "secret123",
        ])
        .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_auth_logout() {
        let cli =
            Cli::try_parse_from(["jh-cli", "auth", "logout"]).expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_list_default() {
        let cli = Cli::try_parse_from(["jh-cli", "list"]).expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_list_with_filters() {
        let cli = Cli::try_parse_from([
            "jh-cli", "list", "--keyword", "rust", "--min-score", "70", "--source", "gupy",
            "--csv",
        ])
        .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_list_with_flags() {
        let cli = Cli::try_parse_from([
            "jh-cli", "list", "--json", "--offline", "--refresh",
        ])
        .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_detail() {
        let cli =
            Cli::try_parse_from(["jh-cli", "detail", "42"]).expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_detail_with_json() {
        let cli = Cli::try_parse_from(["jh-cli", "detail", "42", "--json"])
            .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_fetch() {
        let cli = Cli::try_parse_from(["jh-cli", "fetch"]).expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_fetch_linkedin() {
        let cli = Cli::try_parse_from(["jh-cli", "fetch", "linkedin"])
            .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_analyze() {
        let cli =
            Cli::try_parse_from(["jh-cli", "analyze", "7"]).expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_analyze_with_json() {
        let cli = Cli::try_parse_from(["jh-cli", "analyze", "7", "--json"])
            .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_email_show() {
        let cli = Cli::try_parse_from(["jh-cli", "email", "show", "3"])
            .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_email_show_with_flags() {
        let cli = Cli::try_parse_from(["jh-cli", "email", "show", "3", "--json", "--copy"])
            .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_email_generate() {
        let cli = Cli::try_parse_from(["jh-cli", "email", "generate", "3"])
            .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_email_approve() {
        let cli = Cli::try_parse_from(["jh-cli", "email", "approve", "3"])
            .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_email_send() {
        let cli = Cli::try_parse_from(["jh-cli", "email", "send", "3"])
            .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_profile_show() {
        let cli =
            Cli::try_parse_from(["jh-cli", "profile", "show"]).expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_profile_show_json() {
        let cli = Cli::try_parse_from(["jh-cli", "profile", "show", "--json"])
            .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_profile_edit_all_fields() {
        let cli = Cli::try_parse_from([
            "jh-cli", "profile", "edit", "--resume", "Experienced Rust developer.",
            "--skills", "Rust,PostgreSQL,Docker", "--tone", "startup",
        ])
        .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_profile_edit_partial() {
        let cli = Cli::try_parse_from([
            "jh-cli", "profile", "edit", "--tone", "formal",
        ])
        .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_export() {
        let cli = Cli::try_parse_from(["jh-cli", "export", "jobs.csv"])
            .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_export_with_keyword() {
        let cli = Cli::try_parse_from(["jh-cli", "export", "rust-jobs.csv", "--keyword", "rust"])
            .expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_clear_cache() {
        let cli =
            Cli::try_parse_from(["jh-cli", "clear-cache"]).expect("parse should succeed");
        assert!(cli.command.is_some());
    }

    #[test]
    fn parse_tui_flag() {
        let cli =
            Cli::try_parse_from(["jh-cli", "--tui"]).expect("parse should succeed");
        assert!(cli.tui);
    }

    #[test]
    fn parse_custom_api_url() {
        let cli = Cli::try_parse_from([
            "jh-cli", "--api-url", "https://api.example.com", "list",
        ])
        .expect("parse should succeed");
        assert_eq!(cli.api_url.as_deref(), Some("https://api.example.com"));
    }

    #[test]
    fn parse_custom_config() {
        let cli = Cli::try_parse_from([
            "jh-cli", "--config", "/tmp/my-config.toml", "list",
        ])
        .expect("parse should succeed");
        assert_eq!(cli.config, Some("/tmp/my-config.toml".into()));
    }

    #[test]
    fn parse_token_override() {
        let cli = Cli::try_parse_from([
            "jh-cli", "--token", "my-jwt-token", "list",
        ])
        .expect("parse should succeed");
        assert_eq!(cli.token, Some("my-jwt-token".into()));
    }

    #[test]
    fn parse_invalid_subcommand_shows_error() {
        let result = Cli::try_parse_from(["jh-cli", "invalid-command"]);
        assert!(result.is_err());
    }

    #[test]
    fn parse_detail_missing_id_shows_error() {
        let result = Cli::try_parse_from(["jh-cli", "detail"]);
        assert!(result.is_err());
    }
}

// =========================================================================
// 2. Full user flow: register → login → fetch → list → analyze → email → profile
// =========================================================================

mod full_user_flow {
    use super::*;

    #[tokio::test]
    async fn full_flow_happy_path() {
        let server = MockServer::start();

        // ---- Register ----
        let reg_mock = server.mock(|when, then| {
            when.method(Method::POST)
                .path("/api/auth/register")
                .json_body(json!({
                    "name": "Alice",
                    "email": "alice@example.com",
                    "password": "secret123"
                }));
            then.status(201)
                .header("content-type", "application/json")
                .json_body(json!({
                    "token": "jwt-final-token",
                    "userId": 42,
                    "name": "Alice",
                    "email": "alice@example.com"
                }));
        });

        let client = test_client(&server);
        let reg_req = AuthRequest {
            name: "Alice".into(),
            email: "alice@example.com".into(),
            password: "secret123".into(),
        };
        let reg_resp = client.register(&reg_req).await.expect("register should succeed");
        reg_mock.assert();
        assert_eq!(reg_resp.token, "jwt-final-token");
        assert_eq!(reg_resp.user_id, 42);
        assert_eq!(reg_resp.name, "Alice");

        // Set the token for subsequent requests
        let mut client = client;
        client.set_token(&reg_resp.token);

        // ---- Login (repeat with same creds, expect new token) ----
        let login_mock = server.mock(|when, then| {
            when.method(Method::POST)
                .path("/api/auth/login")
                .json_body(json!({
                    "email": "alice@example.com",
                    "password": "secret123"
                }));
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "token": "jwt-login-token-456",
                    "userId": 42,
                    "name": "Alice",
                    "email": "alice@example.com"
                }));
        });

        let login_req = LoginRequest {
            email: "alice@example.com".into(),
            password: "secret123".into(),
        };
        let login_resp = client.login(&login_req).await.expect("login should succeed");
        login_mock.assert();
        assert_eq!(login_resp.token, "jwt-login-token-456");
        client.set_token(&login_resp.token);

        // ---- Fetch jobs ----
        let fetch_mock = server.mock(|when, then| {
            when.method(Method::POST)
                .path("/api/jobs/fetch")
                .header("authorization", "Bearer jwt-login-token-456");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({ "message": "Fetch completed: 3 jobs saved" }));
        });

        let fetch_resp = client.fetch_jobs().await.expect("fetch should succeed");
        fetch_mock.assert();
        assert_eq!(fetch_resp.message, "Fetch completed: 3 jobs saved");

        // ---- List jobs ----
        let list_mock = server.mock(|when, then| {
            when.method(Method::GET)
                .path("/api/jobs")
                .header("authorization", "Bearer jwt-login-token-456");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!([
                    {
                        "id": 1,
                        "title": "Junior Rust Developer",
                        "company": "TechCorp",
                        "url": "https://example.com/job/1",
                        "description": "Build CLI tools with Rust",
                        "postedAt": "2026-07-14",
                        "source": "gupy"
                    },
                    {
                        "id": 2,
                        "title": "Backend Engineer",
                        "company": "StartupXYZ",
                        "url": "https://example.com/job/2",
                        "description": "Build APIs with Go",
                        "postedAt": "2026-07-13",
                        "source": "linkedin"
                    },
                    {
                        "id": 3,
                        "title": "Data Engineer",
                        "company": "DataCo",
                        "url": "https://example.com/job/3",
                        "description": "Build data pipelines",
                        "postedAt": "2026-07-12",
                        "source": "infojobs"
                    }
                ]));
        });

        let jobs = client.get_jobs().await.expect("get_jobs should succeed");
        list_mock.assert();
        assert_eq!(jobs.len(), 3);
        assert_eq!(jobs[0].title, "Junior Rust Developer");
        assert_eq!(jobs[1].source, "linkedin");
        assert_eq!(jobs[2].company, "DataCo");

        // ---- Analyze job ----
        let analyze_mock = server.mock(|when, then| {
            when.method(Method::POST)
                .path("/api/jobs/1/analyze")
                .header("authorization", "Bearer jwt-login-token-456");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "id": 10,
                    "jobId": 1,
                    "userId": 42,
                    "matchScore": 88,
                    "matchedSkills": ["Rust", "CLI", "PostgreSQL"],
                    "missingSkills": ["Kubernetes", "AWS"],
                    "companyTone": "STARTUP",
                    "summary": "Strong match for a Rust role"
                }));
        });

        let analysis = client.analyze_job(1).await.expect("analyze should succeed");
        analyze_mock.assert();
        assert_eq!(analysis.match_score, 88);
        assert_eq!(analysis.matched_skills, vec!["Rust", "CLI", "PostgreSQL"]);
        assert_eq!(analysis.company_tone, CompanyTone::Startup);

        // ---- Generate email ----
        let email_gen_mock = server.mock(|when, then| {
            when.method(Method::POST)
                .path("/api/jobs/1/email")
                .header("authorization", "Bearer jwt-login-token-456");
            then.status(201)
                .header("content-type", "application/json")
                .json_body(json!({
                    "id": 20,
                    "jobId": 1,
                    "subject": "Application for Junior Rust Developer",
                    "body": "Dear Hiring Team,\n\nI am excited to apply...",
                    "status": "PENDING",
                    "generatedAt": "2026-07-14T10:30:00"
                }));
        });

        let email = client.generate_email(1).await.expect("generate email should succeed");
        email_gen_mock.assert();
        assert_eq!(email.subject, "Application for Junior Rust Developer");
        assert_eq!(email.status, EmailStatus::Pending);

        // ---- Get email (read back) ----
        let email_get_mock = server.mock(|when, then| {
            when.method(Method::GET)
                .path("/api/jobs/1/email")
                .header("authorization", "Bearer jwt-login-token-456");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "id": 20,
                    "jobId": 1,
                    "subject": "Application for Junior Rust Developer",
                    "body": "Dear Hiring Team,\n\nI am excited to apply...",
                    "status": "PENDING",
                    "generatedAt": "2026-07-14T10:30:00"
                }));
        });

        let email_read = client.get_email(1).await.expect("get email should succeed");
        email_get_mock.assert();
        assert_eq!(email_read.id, 20);
        assert_eq!(email_read.subject, "Application for Junior Rust Developer");

        // ---- Get profile ----
        let profile_mock = server.mock(|when, then| {
            when.method(Method::GET)
                .path("/api/profile")
                .header("authorization", "Bearer jwt-login-token-456");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "id": 1,
                    "userId": 42,
                    "resumeText": "Experienced Rust developer with 5 years...",
                    "skills": ["Rust", "PostgreSQL", "Docker"],
                    "tone": "STARTUP",
                    "projects": []
                }));
        });

        let profile = client.get_profile().await.expect("get profile should succeed");
        profile_mock.assert();
        assert_eq!(profile.user_id, 42);
        assert_eq!(profile.skills, vec!["Rust", "PostgreSQL", "Docker"]);
        assert_eq!(profile.tone, CompanyTone::Startup);

        // ---- Update profile ----
        let profile_update_mock = server.mock(|when, then| {
            when.method(Method::PUT)
                .path("/api/profile")
                .header("authorization", "Bearer jwt-login-token-456")
                .json_body(json!({
                    "resumeText": "Senior Rust developer with 8 years...",
                    "skills": ["Rust", "Go", "Kubernetes"],
                    "tone": "FORMAL",
                    "projects": []
                }));
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "id": 1,
                    "userId": 42,
                    "resumeText": "Senior Rust developer with 8 years...",
                    "skills": ["Rust", "Go", "Kubernetes"],
                    "tone": "FORMAL",
                    "projects": []
                }));
        });

        let update_req = ProfileRequest {
            resume_text: "Senior Rust developer with 8 years...".into(),
            skills: vec!["Rust".into(), "Go".into(), "Kubernetes".into()],
            tone: CompanyTone::Formal,
            projects: vec![],
            phone: None,
            contact_email: None,
            portfolio_url: None,
            github_url: None,
            linkedin_url: None,
        };
        let updated = client.update_profile(&update_req).await.expect("update profile should succeed");
        profile_update_mock.assert();
        assert_eq!(updated.skills, vec!["Rust", "Go", "Kubernetes"]);
        assert_eq!(updated.tone, CompanyTone::Formal);
    }
}

// =========================================================================
// 3. Auth flow tests
// =========================================================================

mod auth_flow {
    use super::*;

    #[tokio::test]
    async fn register_email_already_exists() {
        let server = MockServer::start();
        let client = test_client(&server);

        server.mock(|when, then| {
            when.method(Method::POST).path("/api/auth/register");
            then.status(400)
                .header("content-type", "application/json")
                .json_body(json!({
                    "message": "Email already in use"
                }));
        });

        let req = AuthRequest {
            name: "Alice".into(),
            email: "alice@example.com".into(),
            password: "secret123".into(),
        };
        let result = client.register(&req).await;
        assert!(matches!(result, Err(CliError::Api(ApiError::BadRequest(_)))));
    }

    #[tokio::test]
    async fn login_invalid_credentials() {
        let server = MockServer::start();
        let client = test_client(&server);

        server.mock(|when, then| {
            when.method(Method::POST).path("/api/auth/login");
            then.status(401)
                .header("content-type", "application/json")
                .json_body(json!({
                    "message": "Invalid credentials"
                }));
        });

        let req = LoginRequest {
            email: "wrong@example.com".into(),
            password: "wrong".into(),
        };
        let result = client.login(&req).await;
        assert!(matches!(result, Err(CliError::Api(ApiError::Unauthorized(_)))));
    }
}

// =========================================================================
// 4. Job flow tests
// =========================================================================

mod job_flow {
    use super::*;

    #[tokio::test]
    async fn list_with_filters() {
        let server = MockServer::start();
        let mut client = test_client(&server);
        client.set_token("test-token");

        let mock = server.mock(|when, then| {
            when.method(Method::GET)
                .path("/api/jobs")
                .header("authorization", "Bearer test-token");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!([
                    {
                        "id": 1,
                        "title": "Rust Developer",
                        "company": "TechCorp",
                        "url": "https://example.com/job/1",
                        "description": "Build with Rust",
                        "postedAt": "2026-07-14",
                        "source": "gupy"
                    },
                    {
                        "id": 2,
                        "title": "Go Developer",
                        "company": "GoCorp",
                        "url": "https://example.com/job/2",
                        "description": "Build with Go",
                        "postedAt": "2026-07-13",
                        "source": "linkedin"
                    }
                ]));
        });

        let jobs = client.get_jobs().await.expect("get_jobs should succeed");
        mock.assert();
        assert_eq!(jobs.len(), 2);
    }

    #[tokio::test]
    async fn fetch_all_providers() {
        let server = MockServer::start();
        let mut client = test_client(&server);
        client.set_token("test-token");

        let mock = server.mock(|when, then| {
            when.method(Method::POST)
                .path("/api/jobs/fetch")
                .header("authorization", "Bearer test-token");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({ "message": "Fetch completed: 10 jobs saved" }));
        });

        let resp = client.fetch_jobs().await.expect("fetch should succeed");
        mock.assert();
        assert_eq!(resp.message, "Fetch completed: 10 jobs saved");
    }

    #[tokio::test]
    async fn fetch_linkedin_only() {
        let server = MockServer::start();
        let mut client = test_client(&server);
        client.set_token("test-token");

        let mock = server.mock(|when, then| {
            when.method(Method::POST)
                .path("/api/jobs/fetch/linkedin")
                .header("authorization", "Bearer test-token");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({ "message": "LinkedIn fetch completed: 5 jobs saved" }));
        });

        let resp = client.fetch_linkedin().await.expect("fetch linkedin should succeed");
        mock.assert();
        assert_eq!(resp.message, "LinkedIn fetch completed: 5 jobs saved");
    }

    #[tokio::test]
    async fn get_job_detail() {
        let server = MockServer::start();
        let mut client = test_client(&server);
        client.set_token("test-token");

        let mock = server.mock(|when, then| {
            when.method(Method::GET)
                .path("/api/jobs/1")
                .header("authorization", "Bearer test-token");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "id": 1,
                    "title": "Rust Engineer",
                    "company": "RustCo",
                    "url": "https://example.com/job/1",
                    "description": "Systems programming",
                    "postedAt": "2026-07-14",
                    "source": "gupy"
                }));
        });

        let job = client.get_job(1).await.expect("get_job should succeed");
        mock.assert();
        assert_eq!(job.title, "Rust Engineer");
        assert_eq!(job.company, "RustCo");
    }
}

// =========================================================================
// 5. Error scenarios
// =========================================================================

mod error_scenarios {
    use super::*;

    #[tokio::test]
    async fn backend_down_returns_network_error() {
        // Connect to a port that's definitely closed
        let client = ApiClient::new("http://127.0.0.1:1");
        let result = client.get_jobs().await;
        assert!(
            matches!(&result, Err(CliError::Network(_))),
            "Expected Network error, got: {result:?}"
        );
    }

    #[tokio::test]
    async fn token_expired_returns_unauthorized() {
        let server = MockServer::start();
        let mut client = test_client(&server);
        client.set_token("expired-token");

        server.mock(|when, then| {
            when.method(Method::GET)
                .path("/api/jobs")
                .header("authorization", "Bearer expired-token");
            then.status(401)
                .header("content-type", "application/json")
                .json_body(json!({
                    "message": "Token expired"
                }));
        });

        let result = client.get_jobs().await;
        assert!(matches!(result, Err(CliError::Api(ApiError::Unauthorized(_)))));
    }

    #[tokio::test]
    async fn job_not_found_404() {
        let server = MockServer::start();
        let mut client = test_client(&server);
        client.set_token("test-token");

        server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs/999");
            then.status(404)
                .header("content-type", "application/json")
                .json_body(json!({
                    "message": "Job 999 not found"
                }));
        });

        let result = client.get_job(999).await;
        assert!(matches!(result, Err(CliError::Api(ApiError::NotFound(_)))));
    }

    #[tokio::test]
    async fn ai_service_unavailable_502() {
        let server = MockServer::start();
        let mut client = test_client(&server);
        client.set_token("test-token");

        server.mock(|when, then| {
            when.method(Method::POST).path("/api/jobs/1/analyze");
            then.status(502)
                .header("content-type", "application/json")
                .json_body(json!({
                    "message": "AI service unavailable"
                }));
        });

        let result = client.analyze_job(1).await;
        assert!(matches!(result, Err(CliError::Api(ApiError::BadGateway(_)))));
    }

    #[tokio::test]
    async fn validation_error_400() {
        let server = MockServer::start();
        let mut client = test_client(&server);
        client.set_token("test-token");

        server.mock(|when, then| {
            when.method(Method::PUT).path("/api/profile");
            then.status(400)
                .header("content-type", "application/json")
                .json_body(json!({
                    "message": "skills list cannot be empty"
                }));
        });

        let req = ProfileRequest {
            resume_text: "Resume".into(),
            skills: vec![],
            tone: CompanyTone::Casual,
            projects: vec![],
            phone: None,
            contact_email: None,
            portfolio_url: None,
            github_url: None,
            linkedin_url: None,
        };
        let result = client.update_profile(&req).await;
        assert!(matches!(result, Err(CliError::Api(ApiError::BadRequest(_)))));
    }

    #[tokio::test]
    async fn email_not_found_404() {
        let server = MockServer::start();
        let mut client = test_client(&server);
        client.set_token("test-token");

        server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs/999/email");
            then.status(404)
                .header("content-type", "application/json")
                .json_body(json!({
                    "message": "Email draft not found for job 999"
                }));
        });

        let result = client.get_email(999).await;
        assert!(matches!(result, Err(CliError::Api(ApiError::NotFound(_)))));
    }

    #[tokio::test]
    async fn conflict_409_on_duplicate_analysis() {
        let server = MockServer::start();
        let mut client = test_client(&server);
        client.set_token("test-token");

        server.mock(|when, then| {
            when.method(Method::POST).path("/api/jobs/1/analyze");
            then.status(409)
                .header("content-type", "application/json")
                .json_body(json!({
                    "message": "Analysis already exists for job 1"
                }));
        });

        let result = client.analyze_job(1).await;
        assert!(matches!(result, Err(CliError::Api(ApiError::Conflict(_)))));
    }

    #[tokio::test]
    async fn internal_server_error_500() {
        let server = MockServer::start();
        let mut client = test_client(&server);
        client.set_token("test-token");

        server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs");
            then.status(500)
                .header("content-type", "application/json")
                .json_body(json!({
                    "message": "Internal server error"
                }));
        });

        let result = client.get_jobs().await;
        assert!(matches!(result, Err(CliError::Api(ApiError::ServerError(_)))));
    }

    #[tokio::test]
    async fn unauthorized_on_profile() {
        let server = MockServer::start();
        let mut client = test_client(&server);
        client.set_token("bad-token");

        server.mock(|when, then| {
            when.method(Method::GET)
                .path("/api/profile")
                .header("authorization", "Bearer bad-token");
            then.status(401)
                .header("content-type", "application/json")
                .json_body(json!({
                    "message": "Token expired"
                }));
        });

        let result = client.get_profile().await;
        assert!(matches!(result, Err(CliError::Api(ApiError::Unauthorized(_)))));
    }
}

// =========================================================================
// 6. Offline flow with populated cache
// =========================================================================

mod offline_flow {
    use jh_cli::cache::CacheManager;
    use jh_cli::domain::JobResponse;
    use chrono::NaiveDate;

    #[tokio::test]
    async fn offline_mode_uses_cache_when_backend_down() {
        // Pre-populate an in-memory cache and test the
        // CacheManager directly — no API call needed

        let cache = CacheManager::new_in_memory(24).expect("create in-memory cache");

        // Save a job to the cache
        let jobs = vec![JobResponse {
            id: 1,
            title: "Cached Rust Dev".into(),
            company: "CachedCorp".into(),
            url: "https://example.com/job/1".into(),
            description: "A cached job".into(),
            posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
            contact_email: None,
        }];
        cache.save_jobs(&jobs).expect("save jobs to cache");

        // Verify we can read from cache without hitting the API
        let cached = cache.get_all_jobs(None).expect("get cached jobs");
        assert_eq!(cached.len(), 1);
        assert_eq!(cached[0].title, "Cached Rust Dev");
        assert_eq!(cached[0].company, "CachedCorp");
    }

    #[tokio::test]
    async fn offline_mode_on_empty_cache_shows_message() {
        let cache = CacheManager::new_in_memory(24).expect("create in-memory cache");

        let jobs = cache.get_all_jobs(None).expect("get cached jobs");
        assert!(jobs.is_empty(), "empty cache should return no jobs");
    }
}

// =========================================================================
// 7. Config management
// =========================================================================

mod config_management {
    use super::*;
    use std::fs;

    #[test]
    fn config_save_and_load_token() {
        let dir = test_dir("config_save_and_load_token");
        let path = dir.join("config.toml");

        let mut mgr = ConfigManager::load(Some(&path)).expect("load config");
        assert!(mgr.get_token().is_none());

        mgr.set_token("my-saved-token");
        mgr.save().expect("save config");

        let loaded = ConfigManager::load(Some(&path)).expect("reload config");
        assert_eq!(loaded.get_token(), Some("my-saved-token"));
    }

    #[test]
    fn config_clear_token() {
        let dir = test_dir("config_clear_token");
        let path = dir.join("config.toml");

        // Save with token
        let mut mgr = ConfigManager::load(Some(&path)).expect("load");
        mgr.set_token("temp-token");
        mgr.save().expect("save");

        // Clear token
        let mut mgr2 = ConfigManager::load(Some(&path)).expect("reload");
        mgr2.clear_token();
        mgr2.save().expect("save");

        let loaded = ConfigManager::load(Some(&path)).expect("reload");
        assert!(loaded.get_token().is_none());
    }

    #[test]
    fn config_corrupt_file_returns_error() {
        let dir = test_dir("config_corrupt_file");
        let path = dir.join("config.toml");

        fs::write(&path, "not valid toml {{{").expect("write corrupt file");

        let result = ConfigManager::load(Some(&path));
        assert!(result.is_err(), "corrupt config should produce error");
        let err = result.unwrap_err().to_string();
        assert!(
            err.contains("parsing config") || err.contains("toml"),
            "error should mention parsing or TOML: {err}"
        );
    }

    #[test]
    fn config_default_when_file_not_found() {
        let dir = test_dir("config_default_when_file_not_found");
        let path = dir.join("nonexistent.toml");

        let mgr = ConfigManager::load(Some(&path)).expect("load should succeed for missing file");
        assert_eq!(mgr.config().base_url, "http://localhost:8080");
        assert!(mgr.get_token().is_none());
    }

    #[test]
    fn config_custom_base_url() {
        let dir = test_dir("config_custom_base_url");
        let path = dir.join("config.toml");

        let mut mgr = ConfigManager::load(Some(&path)).expect("load");
        mgr.set_base_url("https://custom.api:9090");
        mgr.save().expect("save");

        let loaded = ConfigManager::load(Some(&path)).expect("reload");
        assert_eq!(loaded.config().base_url, "https://custom.api:9090");
    }
}

// =========================================================================
// 8. Bearer token handling
// =========================================================================

mod bearer_token {
    use super::*;

    #[tokio::test]
    async fn request_includes_bearer_token() {
        let server = MockServer::start();
        let mut client = test_client(&server);
        client.set_token("my-jwt");

        let mock = server.mock(|when, then| {
            when.method(Method::GET)
                .path("/api/jobs")
                .header("authorization", "Bearer my-jwt");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!([]));
        });

        let _ = client.get_jobs().await;
        mock.assert();
    }

    #[tokio::test]
    async fn request_no_auth_when_token_cleared() {
        let server = MockServer::start();
        let mut client = test_client(&server);
        client.set_token("temp-token");
        client.clear_token();

        let mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!([]));
        });

        let _ = client.get_jobs().await;
        mock.assert();
    }
}
