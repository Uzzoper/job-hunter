use chrono::{NaiveDate, NaiveDateTime};
use serde::{Deserialize, Serialize};

/// Request to register a new user.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthRequest {
    pub name: String,
    pub email: String,
    pub password: String,
}

/// Request to login.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LoginRequest {
    pub email: String,
    pub password: String,
}

/// Response from authentication endpoints.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthResponse {
    pub token: String,
    pub user_id: i64,
    pub name: String,
    pub email: String,
}

/// Job response from the backend (no matchScore - that's in JobAnalysis).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JobResponse {
    pub id: i64,
    pub title: String,
    pub company: String,
    pub url: String,
    pub description: String,
    pub posted_at: NaiveDate,
    pub source: String,
}

/// AI analysis of a job (returned directly by analyze endpoint).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JobAnalysis {
    pub id: i64,
    pub job_id: i64,
    pub user_id: i64,
    pub match_score: i32,
    pub matched_skills: Vec<String>,
    pub missing_skills: Vec<String>,
    pub company_tone: CompanyTone,
    pub summary: String,
}

/// Email draft response.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EmailDraftResponse {
    pub id: i64,
    pub job_id: i64,
    pub subject: String,
    pub body: String,
    pub status: EmailStatus,
    pub generated_at: NaiveDateTime,
}

/// Profile request for updating user profile.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileRequest {
    pub resume_text: String,
    pub skills: Vec<String>,
    pub tone: CompanyTone,
}

/// Profile response from the backend.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileResponse {
    pub id: Option<i64>,
    pub user_id: i64,
    pub resume_text: String,
    pub skills: Vec<String>,
    pub tone: CompanyTone,
}

/// Response from fetch endpoints.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FetchResponse {
    pub message: String,
}

/// Error response from the backend.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ErrorResponse {
    pub timestamp: String,
    pub status: u16,
    pub error: String,
    pub message: String,
}

/// Company tone enum matching backend.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CompanyTone {
    Formal,
    Casual,
    Startup,
}

impl std::fmt::Display for CompanyTone {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Formal => write!(f, "FORMAL"),
            Self::Casual => write!(f, "CASUAL"),
            Self::Startup => write!(f, "STARTUP"),
        }
    }
}

/// Email status enum matching backend.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum EmailStatus {
    Pending,
    Sent,
}

impl std::fmt::Display for EmailStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Pending => write!(f, "PENDING"),
            Self::Sent => write!(f, "SENT"),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ApplyType {
    ExternalApply,
    EmailAvailable,
    Unknown,
}

impl ApplyType {
    pub fn from_description(desc: impl AsRef<str>) -> Self {
        let desc = desc.as_ref();
        if desc.is_empty() {
            return Self::ExternalApply;
        }
        if desc.trim().len() < 20 {
            return Self::Unknown;
        }
        Self::EmailAvailable
    }
}

impl std::fmt::Display for ApplyType {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::ExternalApply => write!(f, "EXTERNAL_APPLY"),
            Self::EmailAvailable => write!(f, "EMAIL_AVAILABLE"),
            Self::Unknown => write!(f, "UNKNOWN"),
        }
    }
}

/// Cached job with optional analysis and email data.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CachedJob {
    pub id: i64,
    pub title: String,
    pub company: String,
    pub url: String,
    pub description: String,
    pub posted_at: NaiveDate,
    pub source: String,
    pub match_score: Option<i32>,
    pub analysis_json: Option<String>,
    pub email_subject: Option<String>,
    pub email_body: Option<String>,
    pub email_status: Option<String>,
    pub cached_at: NaiveDateTime,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn auth_request_serde_roundtrip() {
        let req = AuthRequest {
            name: "Alice".into(),
            email: "alice@example.com".into(),
            password: "secret123".into(),
        };
        let json = serde_json::to_string(&req).expect("serialize");
        let deserialized: AuthRequest = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(req.name, deserialized.name);
        assert_eq!(req.email, deserialized.email);
    }

    #[test]
    fn login_request_serde_roundtrip() {
        let req = LoginRequest {
            email: "alice@example.com".into(),
            password: "secret123".into(),
        };
        let json = serde_json::to_string(&req).expect("serialize");
        let deserialized: LoginRequest = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(req.email, deserialized.email);
    }

    #[test]
    fn auth_response_serde_roundtrip() {
        let resp = AuthResponse {
            token: "jwt...".into(),
            user_id: 42,
            name: "Alice".into(),
            email: "alice@example.com".into(),
        };
        let json = serde_json::to_string(&resp).expect("serialize");
        let deserialized: AuthResponse = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(resp.token, deserialized.token);
        assert_eq!(resp.user_id, deserialized.user_id);
    }

    #[test]
    fn job_response_serde_roundtrip() {
        let job = JobResponse {
            id: 1,
            title: "Junior Rust Developer".into(),
            company: "Tech Corp".into(),
            url: "https://example.com/job/1".into(),
            description: "Build CLI tools".into(),
            posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
        };
        let json = serde_json::to_string(&job).expect("serialize");
        let deserialized: JobResponse = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(job.id, deserialized.id);
        assert_eq!(job.title, deserialized.title);
        assert_eq!(job.url, deserialized.url);
        // Ensure no match_score field
        assert!(!json.contains("matchScore"));
        assert!(!json.contains("match_score"));
    }

    #[test]
    fn job_analysis_serde_roundtrip() {
        let analysis = JobAnalysis {
            id: 1,
            job_id: 1,
            user_id: 1,
            match_score: 85,
            matched_skills: vec!["Rust".into(), "CLI".into()],
            missing_skills: vec!["Kubernetes".into()],
            company_tone: CompanyTone::Formal,
            summary: "Great match".into(),
        };
        let json = serde_json::to_string(&analysis).expect("serialize");
        let deserialized: JobAnalysis = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(analysis.match_score, deserialized.match_score);
        assert_eq!(analysis.matched_skills, deserialized.matched_skills);
        assert_eq!(analysis.company_tone, deserialized.company_tone);
    }

    #[test]
    fn email_draft_response_serde_roundtrip() {
        let draft = EmailDraftResponse {
            id: 1,
            job_id: 1,
            subject: "Application for Rust Dev".into(),
            body: "Dear team...".into(),
            status: EmailStatus::Pending,
            generated_at: NaiveDateTime::parse_from_str("2026-07-14T10:00:00", "%Y-%m-%dT%H:%M:%S").unwrap(),
        };
        let json = serde_json::to_string(&draft).expect("serialize");
        let deserialized: EmailDraftResponse = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(draft.id, deserialized.id);
        assert_eq!(draft.subject, deserialized.subject);
        assert_eq!(draft.status, deserialized.status);
    }

    #[test]
    fn profile_request_serde_roundtrip() {
        let req = ProfileRequest {
            resume_text: "Experienced developer...".into(),
            skills: vec!["Rust".into(), "Java".into()],
            tone: CompanyTone::Startup,
        };
        let json = serde_json::to_string(&req).expect("serialize");
        let deserialized: ProfileRequest = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(req.resume_text, deserialized.resume_text);
        assert_eq!(req.skills, deserialized.skills);
        assert_eq!(req.tone, deserialized.tone);
    }

    #[test]
    fn profile_response_serde_roundtrip() {
        let resp = ProfileResponse {
            id: Some(1),
            user_id: 1,
            resume_text: "Experienced developer...".into(),
            skills: vec!["Rust".into(), "Java".into()],
            tone: CompanyTone::Casual,
        };
        let json = serde_json::to_string(&resp).expect("serialize");
        let deserialized: ProfileResponse = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(resp.id, deserialized.id);
        assert_eq!(resp.tone, deserialized.tone);
    }

    #[test]
    fn fetch_response_serde_roundtrip() {
        let resp = FetchResponse {
            message: "Fetch completed successfully".into(),
        };
        let json = serde_json::to_string(&resp).expect("serialize");
        let deserialized: FetchResponse = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(resp.message, deserialized.message);
    }

    #[test]
    fn error_response_serde_roundtrip() {
        let err = ErrorResponse {
            timestamp: "2026-07-14T12:00:00.123456789".into(),
            status: 401,
            error: "Unauthorized".into(),
            message: "Invalid credentials".into(),
        };
        let json = serde_json::to_string(&err).expect("serialize");
        let deserialized: ErrorResponse = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(err.status, deserialized.status);
        assert_eq!(err.error, deserialized.error);
    }

    #[test]
    fn company_tone_serialization() {
        assert_eq!(serde_json::to_string(&CompanyTone::Formal).unwrap(), "\"FORMAL\"");
        assert_eq!(serde_json::to_string(&CompanyTone::Casual).unwrap(), "\"CASUAL\"");
        assert_eq!(serde_json::to_string(&CompanyTone::Startup).unwrap(), "\"STARTUP\"");
    }

    #[test]
    fn email_status_serialization() {
        assert_eq!(serde_json::to_string(&EmailStatus::Pending).unwrap(), "\"PENDING\"");
        assert_eq!(serde_json::to_string(&EmailStatus::Sent).unwrap(), "\"SENT\"");
    }

    #[test]
    fn cached_job_serde_roundtrip() {
        let job = CachedJob {
            id: 1,
            title: "Rust Dev".into(),
            company: "Acme".into(),
            url: "https://example.com".into(),
            description: "Desc".into(),
            posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
            match_score: Some(85),
            analysis_json: Some("{}".into()),
            email_subject: Some("Subject".into()),
            email_body: Some("Body".into()),
            email_status: Some("PENDING".into()),
            cached_at: NaiveDateTime::parse_from_str("2026-07-14T10:00:00", "%Y-%m-%dT%H:%M:%S").unwrap(),
        };
        let json = serde_json::to_string(&job).expect("serialize");
        let deserialized: CachedJob = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(job.id, deserialized.id);
        assert_eq!(job.match_score, deserialized.match_score);
    }

    #[test]
    fn apply_type_from_description_empty() {
        assert_eq!(ApplyType::from_description(""), ApplyType::ExternalApply);
    }

    #[test]
    fn apply_type_from_description_non_empty() {
        assert_eq!(ApplyType::from_description("Job description here"), ApplyType::EmailAvailable);
    }

    #[test]
    fn apply_type_from_description_whitespace() {
        assert_eq!(ApplyType::from_description("   "), ApplyType::Unknown);
        assert_eq!(ApplyType::from_description("\t\n"), ApplyType::Unknown);
    }

    #[test]
    fn apply_type_from_description_short() {
        assert_eq!(ApplyType::from_description("Short"), ApplyType::Unknown);
        assert_eq!(ApplyType::from_description("A".repeat(19)), ApplyType::Unknown);
    }

    #[test]
    fn apply_type_from_description_boundary() {
        assert_eq!(ApplyType::from_description("A".repeat(20)), ApplyType::EmailAvailable);
    }
}
