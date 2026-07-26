use crate::domain::{
    AuthRequest, AuthResponse, EmailDraftResponse, FetchResponse, JobAnalysis,
    JobResponse, LoginRequest, ProfileRequest, ProfileResponse,
};
use crate::error::{ApiError, CliError, Result};
use reqwest::{Client, Method, RequestBuilder};
use serde::de::DeserializeOwned;
use std::time::Duration;

/// HTTP client for the Job Hunter REST API.
///
/// All methods are async and return [`Result<T>`] which is
/// `Result<T, CliError>`. Network-level failures (connection refused,
/// timeout, TLS errors) map to [`CliError::Network`]. HTTP error
/// status codes map to [`ApiError`] variants.
#[derive(Debug, Clone)]
pub struct ApiClient {
    client: Client,
    base_url: String,
    token: Option<String>,
}

impl ApiClient {
    /// Create a new API client pointing at `base_url`.
    ///
    /// The client uses a connection pool with reasonable timeouts.
    pub fn new(base_url: &str) -> Self {
        let client = Client::builder()
            .timeout(Duration::from_secs(30))
            .connect_timeout(Duration::from_secs(10))
            .pool_idle_timeout(Duration::from_secs(90))
            .pool_max_idle_per_host(10)
            .build()
            .expect("reqwest::Client::build() should not fail with these settings");

        Self {
            client,
            base_url: base_url.trim_end_matches('/').to_string(),
            token: None,
        }
    }

    pub fn with_token(mut self, token: impl Into<String>) -> Self {
        self.token = Some(token.into());
        self
    }

    pub fn set_token(&mut self, token: &str) {
        self.token = Some(token.to_string());
    }

    pub fn clear_token(&mut self) {
        self.token = None;
    }

    /// Get the current base URL.
    pub fn base_url(&self) -> &str {
        &self.base_url
    }

    /// Build a [`RequestBuilder`] for the given method and path.
    ///
    /// If a token is set, the `Authorization: Bearer <token>` header
    /// is attached automatically.
    fn request(&self, method: Method, path: &str) -> RequestBuilder {
        let url = format!("{}{}", self.base_url, path);
        let mut req = self.client.request(method, url);
        if let Some(token) = &self.token {
            req = req.bearer_auth(token);
        }
        req
    }

    /// Execute a request and deserialize the response.
    ///
    /// On success (2xx), the response body is deserialized as `T`.
    /// On HTTP error (4xx, 5xx), the response body is read and
    /// mapped to the appropriate [`ApiError`] variant.
    /// Network-level errors (connection refused, timeout, TLS) are
    /// converted to [`CliError::Network`].
    async fn handle_response<T: DeserializeOwned>(&self, resp: reqwest::Response) -> Result<T> {
        let status = resp.status();
        if status.is_success() {
            let body = resp.json::<T>().await.map_err(|e| {
                CliError::Api(ApiError::DeserializeError(e.to_string()))
            })?;
            Ok(body)
        } else {
            let body = resp.text().await.unwrap_or_default();
            Err(CliError::Api(ApiError::from_status_and_body(status.as_u16(), body)))
        }
    }

    // =========================================================================
    // Auth endpoints
    // =========================================================================

    pub async fn register(&self, req: &AuthRequest) -> Result<AuthResponse> {
        let resp = self
            .request(Method::POST, "/api/auth/register")
            .json(req)
            .send()
            .await?;
        self.handle_response(resp).await
    }

    pub async fn login(&self, req: &LoginRequest) -> Result<AuthResponse> {
        let resp = self
            .request(Method::POST, "/api/auth/login")
            .json(req)
            .send()
            .await?;
        self.handle_response(resp).await
    }

    // =========================================================================
    // Jobs endpoints
    // =========================================================================

    pub async fn get_jobs(&self) -> Result<Vec<JobResponse>> {
        let resp = self.request(Method::GET, "/api/jobs").send().await?;
        self.handle_response(resp).await
    }

    pub async fn get_job(&self, id: i64) -> Result<JobResponse> {
        let resp = self
            .request(Method::GET, &format!("/api/jobs/{id}"))
            .send()
            .await?;
        self.handle_response(resp).await
    }

    pub async fn fetch_jobs(&self) -> Result<FetchResponse> {
        let resp = self
            .request(Method::POST, "/api/jobs/fetch")
            .send()
            .await?;
        self.handle_response(resp).await
    }

    pub async fn fetch_linkedin(&self) -> Result<FetchResponse> {
        let resp = self
            .request(Method::POST, "/api/jobs/fetch/linkedin")
            .send()
            .await?;
        self.handle_response(resp).await
    }

    // =========================================================================
    // Analysis endpoint
    // =========================================================================

    pub async fn analyze_job(&self, job_id: i64) -> Result<JobAnalysis> {
        let resp = self
            .request(Method::POST, &format!("/api/jobs/{job_id}/analyze"))
            .timeout(Duration::from_secs(180))
            .send()
            .await?;
        self.handle_response(resp).await
    }

    // =========================================================================
    // Email endpoints
    // =========================================================================

    pub async fn get_email(&self, job_id: i64) -> Result<EmailDraftResponse> {
        let resp = self
            .request(Method::GET, &format!("/api/jobs/{job_id}/email"))
            .send()
            .await?;
        self.handle_response(resp).await
    }

    pub async fn generate_email(&self, job_id: i64) -> Result<EmailDraftResponse> {
        let resp = self
            .request(Method::POST, &format!("/api/jobs/{job_id}/email"))
            .timeout(Duration::from_secs(180))
            .send()
            .await?;
        self.handle_response(resp).await
    }

    // =========================================================================
    // Profile endpoints
    // =========================================================================

    pub async fn get_profile(&self) -> Result<ProfileResponse> {
        let resp = self.request(Method::GET, "/api/profile").send().await?;
        self.handle_response(resp).await
    }

    pub async fn update_profile(&self, req: &ProfileRequest) -> Result<ProfileResponse> {
        let resp = self
            .request(Method::PUT, "/api/profile")
            .json(req)
            .send()
            .await?;
        self.handle_response(resp).await
    }
}

impl ApiError {
    /// Create an [`ApiError`] from an HTTP status code and response body.
    pub fn from_status_and_body(status: u16, body: String) -> Self {
        match status {
            400 => Self::BadRequest(body),
            401 => Self::Unauthorized(body),
            404 => Self::NotFound(body),
            409 => Self::Conflict(body),
            502 => Self::BadGateway(body),
            s if (500..=599).contains(&s) => Self::ServerError(body),
            _ => Self::ServerError(format!("HTTP {status}: {body}")),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::{
        AuthRequest, AuthResponse, CompanyTone, EmailStatus, LoginRequest, ProfileRequest,
    };
    use httpmock::{Method, MockServer};
    use reqwest::Client;
    use serde_json::json;

    // Helper to create a test client pointing at a mock server
    fn test_client(server: &MockServer) -> ApiClient {
        let mut client = ApiClient::new(&server.url("/"));
        client.client = Client::new(); // Use default client for tests
        client
    }

    // =========================================================================
    // Auth tests
    // =========================================================================

    #[tokio::test]
    async fn register_success_returns_auth_response() {
        let server = MockServer::start();
        let client = test_client(&server);

        let expected = AuthResponse {
            token: "jwt-token-123".into(),
            user_id: 42,
            name: "Alice".into(),
            email: "alice@example.com".into(),
        };

        let mock = server.mock(|when, then| {
            when.method(Method::POST).path("/api/auth/register");
            then.status(201)
                .header("content-type", "application/json")
                .json_body(json!({
                    "token": "jwt-token-123",
                    "userId": 42,
                    "name": "Alice",
                    "email": "alice@example.com"
                }));
        });

        let req = AuthRequest {
            name: "Alice".into(),
            email: "alice@example.com".into(),
            password: "secret123".into(),
        };

        let result = client.register(&req).await;
        mock.assert();
        assert!(result.is_ok());
        let resp = result.unwrap();
        assert_eq!(resp.token, expected.token);
        assert_eq!(resp.user_id, expected.user_id);
        assert_eq!(resp.name, expected.name);
        assert_eq!(resp.email, expected.email);
    }

    #[tokio::test]
    async fn register_400_returns_bad_request() {
        let server = MockServer::start();
        let client = test_client(&server);

        server.mock(|when, then| {
            when.method(Method::POST).path("/api/auth/register");
            then.status(400)
                .header("content-type", "application/json")
                .json_body(json!({
                    "timestamp": "2026-07-14T12:00:00.000",
                    "status": 400,
                    "error": "Bad Request",
                    "message": "email already exists"
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
    async fn login_success_returns_auth_response() {
        let server = MockServer::start();
        let client = test_client(&server);

        let mock = server.mock(|when, then| {
            when.method(Method::POST).path("/api/auth/login");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "token": "jwt-login-token",
                    "userId": 1,
                    "name": "Bob",
                    "email": "bob@example.com"
                }));
        });

        let req = LoginRequest {
            email: "bob@example.com".into(),
            password: "password123".into(),
        };

        let result = client.login(&req).await;
        mock.assert();
        assert!(result.is_ok());
        let resp = result.unwrap();
        assert_eq!(resp.token, "jwt-login-token");
        assert_eq!(resp.user_id, 1);
    }

    #[tokio::test]
    async fn login_401_returns_unauthorized() {
        let server = MockServer::start();
        let client = test_client(&server);

        server.mock(|when, then| {
            when.method(Method::POST).path("/api/auth/login");
            then.status(401)
                .header("content-type", "application/json")
                .json_body(json!({
                    "timestamp": "2026-07-14T12:00:00.000",
                    "status": 401,
                    "error": "Unauthorized",
                    "message": "Invalid credentials"
                }));
        });

        let req = LoginRequest {
            email: "bob@example.com".into(),
            password: "wrong".into(),
        };

        let result = client.login(&req).await;
        assert!(matches!(result, Err(CliError::Api(ApiError::Unauthorized(_)))));
    }

    // =========================================================================
    // Jobs tests
    // =========================================================================

    #[tokio::test]
    async fn get_jobs_success_returns_list() {
        let server = MockServer::start();
        let client = test_client(&server);

        let mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!([
                    {
                        "id": 1,
                        "title": "Junior Rust Developer",
                        "company": "TechCorp",
                        "url": "https://example.com/job/1",
                        "description": "Build CLI tools",
                        "postedAt": "2026-07-14",
                        "source": "gupy"
                    },
                    {
                        "id": 2,
                        "title": "Backend Engineer",
                        "company": "StartupXYZ",
                        "url": "https://example.com/job/2",
                        "description": "Build APIs",
                        "postedAt": "2026-07-13",
                        "source": "linkedin"
                    }
                ]));
        });

        let result = client.get_jobs().await;
        mock.assert();
        assert!(result.is_ok());
        let jobs = result.unwrap();
        assert_eq!(jobs.len(), 2);
        assert_eq!(jobs[0].id, 1);
        assert_eq!(jobs[0].title, "Junior Rust Developer");
        assert_eq!(jobs[1].source, "linkedin");
    }

    #[tokio::test]
    async fn get_jobs_401_returns_unauthorized() {
        let server = MockServer::start();
        let client = test_client(&server);

        server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs");
            then.status(401)
                .header("content-type", "application/json")
                .json_body(json!({
                    "timestamp": "2026-07-14T12:00:00.000",
                    "status": 401,
                    "error": "Unauthorized",
                    "message": "Token expired"
                }));
        });

        let result = client.get_jobs().await;
        assert!(matches!(result, Err(CliError::Api(ApiError::Unauthorized(_)))));
    }

    #[tokio::test]
    async fn get_job_success_returns_job() {
        let server = MockServer::start();
        let client = test_client(&server);

        let mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs/42");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "id": 42,
                    "title": "Senior Rust Engineer",
                    "company": "RustCorp",
                    "url": "https://example.com/job/42",
                    "description": "Build high-performance systems",
                    "postedAt": "2026-07-10",
                    "source": "infojobs"
                }));
        });

        let result = client.get_job(42).await;
        mock.assert();
        assert!(result.is_ok());
        let job = result.unwrap();
        assert_eq!(job.id, 42);
        assert_eq!(job.company, "RustCorp");
        assert_eq!(job.source, "infojobs");
    }

    #[tokio::test]
    async fn get_job_404_returns_not_found() {
        let server = MockServer::start();
        let client = test_client(&server);

        server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs/999");
            then.status(404)
                .header("content-type", "application/json")
                .json_body(json!({
                    "timestamp": "2026-07-14T12:00:00.000",
                    "status": 404,
                    "error": "Not Found",
                    "message": "Job 999 not found"
                }));
        });

        let result = client.get_job(999).await;
        assert!(matches!(result, Err(CliError::Api(ApiError::NotFound(_)))));
    }

    #[tokio::test]
    async fn fetch_jobs_success_returns_fetch_response() {
        let server = MockServer::start();
        let client = test_client(&server);

        let mock = server.mock(|when, then| {
            when.method(Method::POST).path("/api/jobs/fetch");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({ "message": "Fetch completed: 15 jobs saved" }));
        });

        let result = client.fetch_jobs().await;
        mock.assert();
        assert!(result.is_ok());
        let resp = result.unwrap();
        assert_eq!(resp.message, "Fetch completed: 15 jobs saved");
    }

    #[tokio::test]
    async fn fetch_linkedin_success_returns_fetch_response() {
        let server = MockServer::start();
        let client = test_client(&server);

        let mock = server.mock(|when, then| {
            when.method(Method::POST).path("/api/jobs/fetch/linkedin");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({ "message": "LinkedIn fetch completed: 5 jobs saved" }));
        });

        let result = client.fetch_linkedin().await;
        mock.assert();
        assert!(result.is_ok());
        let resp = result.unwrap();
        assert_eq!(resp.message, "LinkedIn fetch completed: 5 jobs saved");
    }

    // =========================================================================
    // Analysis tests
    // =========================================================================

    #[tokio::test]
    async fn analyze_job_success_returns_analysis() {
        let server = MockServer::start();
        let client = test_client(&server);

        let mock = server.mock(|when, then| {
            when.method(Method::POST).path("/api/jobs/7/analyze");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "id": 1,
                    "jobId": 7,
                    "userId": 1,
                    "matchScore": 88,
                    "matchedSkills": ["Rust", "PostgreSQL", "CLI"],
                    "missingSkills": ["Kubernetes", "AWS"],
                    "companyTone": "STARTUP",
                    "summary": "Strong match for backend role"
                }));
        });

        let result = client.analyze_job(7).await;
        mock.assert();
        assert!(result.is_ok());
        let analysis = result.unwrap();
        assert_eq!(analysis.id, 1);
        assert_eq!(analysis.job_id, 7);
        assert_eq!(analysis.match_score, 88);
        assert_eq!(analysis.matched_skills, vec!["Rust", "PostgreSQL", "CLI"]);
        assert_eq!(analysis.missing_skills, vec!["Kubernetes", "AWS"]);
        assert_eq!(analysis.company_tone, CompanyTone::Startup);
    }

    #[tokio::test]
    async fn analyze_job_409_returns_conflict() {
        let server = MockServer::start();
        let client = test_client(&server);

        server.mock(|when, then| {
            when.method(Method::POST).path("/api/jobs/5/analyze");
            then.status(409)
                .header("content-type", "application/json")
                .json_body(json!({
                    "timestamp": "2026-07-14T12:00:00.000",
                    "status": 409,
                    "error": "Conflict",
                    "message": "Analysis already exists for job 5"
                }));
        });

        let result = client.analyze_job(5).await;
        assert!(matches!(result, Err(CliError::Api(ApiError::Conflict(_)))));
    }

    // =========================================================================
    // Email tests
    // =========================================================================

    #[tokio::test]
    async fn get_email_success_returns_draft() {
        let server = MockServer::start();
        let client = test_client(&server);

        let mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs/3/email");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "id": 10,
                    "jobId": 3,
                    "subject": "Application for Rust Developer",
                    "body": "Dear Hiring Team,\n\nI am writing to apply...",
                    "status": "PENDING",
                    "generatedAt": "2026-07-14T10:30:00"
                }));
        });

        let result = client.get_email(3).await;
        mock.assert();
        assert!(result.is_ok());
        let draft = result.unwrap();
        assert_eq!(draft.id, 10);
        assert_eq!(draft.job_id, 3);
        assert_eq!(draft.subject, "Application for Rust Developer");
        assert_eq!(draft.status, EmailStatus::Pending);
    }

    #[tokio::test]
    async fn generate_email_success_returns_draft() {
        let server = MockServer::start();
        let client = test_client(&server);

        let mock = server.mock(|when, then| {
            when.method(Method::POST).path("/api/jobs/3/email");
            then.status(201)
                .header("content-type", "application/json")
                .json_body(json!({
                    "id": 11,
                    "jobId": 3,
                    "subject": "New Application for Rust Developer",
                    "body": "Dear Team,\n\nI am excited to apply...",
                    "status": "PENDING",
                    "generatedAt": "2026-07-14T11:00:00"
                }));
        });

        let result = client.generate_email(3).await;
        mock.assert();
        assert!(result.is_ok());
        let draft = result.unwrap();
        assert_eq!(draft.id, 11);
        assert_eq!(draft.status, EmailStatus::Pending);
    }

    #[tokio::test]
    async fn get_email_404_returns_not_found() {
        let server = MockServer::start();
        let client = test_client(&server);

        server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs/999/email");
            then.status(404)
                .header("content-type", "application/json")
                .json_body(json!({
                    "timestamp": "2026-07-14T12:00:00.000",
                    "status": 404,
                    "error": "Not Found",
                    "message": "Email draft not found for job 999"
                }));
        });

        let result = client.get_email(999).await;
        assert!(matches!(result, Err(CliError::Api(ApiError::NotFound(_)))));
    }

    // =========================================================================
    // Profile tests
    // =========================================================================

    #[tokio::test]
    async fn get_profile_success_returns_profile() {
        let server = MockServer::start();
        let client = test_client(&server);

        let mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/profile");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "id": 1,
                    "userId": 1,
                    "resumeText": "Experienced Rust developer...",
                    "skills": ["Rust", "PostgreSQL", "Docker"],
                    "tone": "STARTUP",
                    "projects": []
                }));
        });

        let result = client.get_profile().await;
        mock.assert();
        assert!(result.is_ok());
        let profile = result.unwrap();
        assert_eq!(profile.id, Some(1));
        assert_eq!(profile.user_id, 1);
        assert_eq!(profile.skills, vec!["Rust", "PostgreSQL", "Docker"]);
        assert_eq!(profile.tone, CompanyTone::Startup);
    }

    #[tokio::test]
    async fn update_profile_success_returns_updated_profile() {
        let server = MockServer::start();
        let client = test_client(&server);

        let mock = server.mock(|when, then| {
            when.method(Method::PUT).path("/api/profile");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "id": 1,
                    "userId": 1,
                    "resumeText": "Updated resume...",
                    "skills": ["Rust", "Go", "Kubernetes"],
                    "tone": "FORMAL",
                    "projects": []
                }));
        });

        let req = ProfileRequest {
            resume_text: "Updated resume...".into(),
            skills: vec!["Rust".into(), "Go".into(), "Kubernetes".into()],
            tone: CompanyTone::Formal,
            projects: vec![],
        };

        let result = client.update_profile(&req).await;
        mock.assert();
        assert!(result.is_ok());
        let profile = result.unwrap();
        assert_eq!(profile.skills, vec!["Rust", "Go", "Kubernetes"]);
        assert_eq!(profile.tone, CompanyTone::Formal);
    }

    #[tokio::test]
    async fn update_profile_400_returns_bad_request() {
        let server = MockServer::start();
        let client = test_client(&server);

        server.mock(|when, then| {
            when.method(Method::PUT).path("/api/profile");
            then.status(400)
                .header("content-type", "application/json")
                .json_body(json!({
                    "timestamp": "2026-07-14T12:00:00.000",
                    "status": 400,
                    "error": "Bad Request",
                    "message": "skills list cannot be empty"
                }));
        });

        let req = ProfileRequest {
            resume_text: "Resume".into(),
            skills: vec![],
            tone: CompanyTone::Casual,
            projects: vec![],
        };

        let result = client.update_profile(&req).await;
        assert!(matches!(result, Err(CliError::Api(ApiError::BadRequest(_)))));
    }

    // =========================================================================
    // Auth header tests
    // =========================================================================

    #[tokio::test]
    async fn request_includes_bearer_token_when_set() {
        let server = MockServer::start();
        let mut client = test_client(&server);
        client.set_token("test-jwt-token");

        let mock = server.mock(|when, then| {
            when.method(Method::GET)
                .path("/api/jobs")
                .header("authorization", "Bearer test-jwt-token");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!([]));
        });

        let _ = client.get_jobs().await;
        mock.assert();
    }

    #[tokio::test]
    async fn request_no_auth_header_when_token_cleared() {
        let server = MockServer::start();
        let mut client = test_client(&server);
        client.set_token("test-jwt-token");
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

    // =========================================================================
    // Network error tests
    // =========================================================================

    #[tokio::test]
    async fn connection_refused_returns_network_error() {
        // Use a port that's guaranteed to be closed
        let client = ApiClient::new("http://127.0.0.1:1");

        let result = client.get_jobs().await;
        assert!(matches!(result, Err(CliError::Network(_))));
    }

    #[tokio::test]
    async fn timeout_returns_network_error() {
        // Use a non-routable IP that will timeout
        let client = ApiClient::new("http://10.255.255.1:80");

        let result = client.get_jobs().await;
        assert!(matches!(result, Err(CliError::Network(_))));
    }

    // =========================================================================
    // Server error tests
    // =========================================================================

    #[tokio::test]
    async fn server_error_500_returns_server_error() {
        let server = MockServer::start();
        let client = test_client(&server);

        server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs");
            then.status(500)
                .header("content-type", "application/json")
                .json_body(json!({
                    "timestamp": "2026-07-14T12:00:00.000",
                    "status": 500,
                    "error": "Internal Server Error",
                    "message": "Database connection failed"
                }));
        });

        let result = client.get_jobs().await;
        assert!(matches!(result, Err(CliError::Api(ApiError::ServerError(_)))));
    }

    #[tokio::test]
    async fn bad_gateway_502_returns_bad_gateway() {
        let server = MockServer::start();
        let client = test_client(&server);

        server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs");
            then.status(502)
                .header("content-type", "application/json")
                .json_body(json!({
                    "timestamp": "2026-07-14T12:00:00.000",
                    "status": 502,
                    "error": "Bad Gateway",
                    "message": "Upstream timeout"
                }));
        });

        let result = client.get_jobs().await;
        assert!(matches!(result, Err(CliError::Api(ApiError::BadGateway(_)))));
    }

    // =========================================================================
    // Deserialization error tests
    // =========================================================================

    #[tokio::test]
    async fn invalid_json_returns_deserialize_error() {
        let server = MockServer::start();
        let client = test_client(&server);

        server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs");
            then.status(200)
                .header("content-type", "application/json")
                .body("not valid json {{{");
        });

        let result = client.get_jobs().await;
        assert!(matches!(result, Err(CliError::Api(ApiError::DeserializeError(_)))));
    }
}