use serde::{Deserialize, Serialize};
use chrono::NaiveDate;
use std::cmp::Ordering;

/// Represents a job listing returned by the API.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct JobResponse {
    pub id: i64,
    pub title: String,
    pub company: String,
    pub url: String,
    pub description: String,
    pub posted_at: NaiveDate,
    pub source: String,
    pub contact_email: Option<String>,
}

/// The source from a JobResponse was fetched.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum JobSource {
    Gupy,
    Infojobs,
    LinkedIn,
    LinkedInJsoup,
    Unknown,
}

impl std::fmt::Display for JobSource {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            JobSource::Gupy => write!(f, "gupy"),
            JobSource::Infojobs => write!(f, "infojobs"),
            JobSource::LinkedIn => write!(f, "linkedin"),
            JobSource::LinkedInJsoup => write!(f, "linkedin_jsoup"),
            JobSource::Unknown => write!(f, "unknown"),
        }
    }
}

impl From<&str> for JobSource {
    fn from(s: &str) -> Self {
        match s.to_lowercase().as_str() {
            "gupy" => JobSource::Gupy,
            "infojobs" => JobSource::Infojobs,
            "linkedin" => JobSource::LinkedIn,
            "linkedin_jsoup" => JobSource::LinkedInJsoup,
            _ => JobSource::Unknown,
        }
    }
}

impl JobSource {
    pub fn all() -> Vec<JobSource> {
        vec![
            JobSource::Gupy,
            JobSource::Infojobs,
            JobSource::LinkedIn,
            JobSource::LinkedInJsoup,
        ]
    }
}

/// Apply type inferred from the job description.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ApplyType {
    ExternalApply,
    EmailAvailable,
    Unknown,
}

impl ApplyType {
    pub fn from_description(description: &str) -> Self {
        let lower = description.to_lowercase();
        if lower.contains("candidatura externa")
            || lower.contains("external application")
            || lower.contains("apply externally")
            || lower.contains("apply at")
            || lower.contains("candidate-se no site")
        {
            ApplyType::ExternalApply
        } else if description.len() >= 20 {
            ApplyType::EmailAvailable
        } else {
            ApplyType::Unknown
        }
    }
}

/// Seniority level inferred from the job title.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SeniorityLevel {
    Junior,
    Pleno,
    Senior,
    Lead,
    Unknown,
}

impl SeniorityLevel {
    pub fn from_title(title: &str) -> Self {
        let lower = title.to_lowercase();
        if lower.contains("junior")
            || lower.contains("jr")
            || lower.contains("jr.")
            || lower.contains("entry")
            || lower.contains("estágio")
            || lower.contains("estagio")
            || lower.contains("trainee")
            || lower.contains("júnior")
        {
            SeniorityLevel::Junior
        } else if lower.contains("pleno") || lower.contains("mid-level") || lower.contains("mid ") {
            SeniorityLevel::Pleno
        } else if lower.contains("senior")
            || lower.contains("sr")
            || lower.contains("sr.")
            || lower.contains("sênior")
            || lower.contains("sénior")
        {
            SeniorityLevel::Senior
        } else if lower.contains("lead")
            || lower.contains("head")
            || lower.contains("principal")
            || lower.contains("staff")
            || lower.contains("architect")
            || lower.contains("manager")
            || lower.contains("coordinator")
        {
            SeniorityLevel::Lead
        } else {
            SeniorityLevel::Unknown
        }
    }
}

/// Determines if a role is a software development role based on the title.
pub fn is_dev_role(title: &str) -> bool {
    let lower = title.to_lowercase();
    lower.contains("developer")
        || lower.contains("dev")
        || lower.contains("engineer")
        || lower.contains("software")
        || lower.contains("programmer")
        || lower.contains("frontend")
        || lower.contains("front-end")
        || lower.contains("backend")
        || lower.contains("back-end")
        || lower.contains("fullstack")
        || lower.contains("full-stack")
        || lower.contains("web")
        || lower.contains("mobile")
        || lower.contains("ios")
        || lower.contains("android")
        || lower.contains("data scientist")
        || lower.contains("machine learning")
        || lower.contains("ml engineer")
        || lower.contains("ai")
        || lower.contains("qa")
        || lower.contains("quality assurance")
        || lower.contains("test")
        || lower.contains("devops")
        || lower.contains("sre")
        || lower.contains("infrastructure")
        || lower.contains("cloud")
        || lower.contains("security")
        || lower.contains("site reliability")
}

/// Analysis result returned by the AI.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct JobAnalysis {
    pub job_id: i64,
    pub match_score: i32,
    pub reasoning: String,
    pub skills: Vec<String>,
}

/// Response from the AI email generation endpoint.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct EmailDraftResponse {
    pub id: i64,
    pub job_id: i64,
    pub subject: String,
    pub body: String,
    pub sent: bool,
    pub sent_at: Option<NaiveDate>,
    pub created_at: Option<NaiveDate>,
}

impl EmailDraftResponse {
    pub fn status(&self) -> EmailStatus {
        if self.sent {
            EmailStatus::Sent(self.sent_at.unwrap_or(self.created_at.unwrap_or(chrono::Local::now().date_naive())))
        } else {
            EmailStatus::Pending
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EmailStatus {
    Sent(NaiveDate),
    Pending,
}

impl std::fmt::Display for EmailStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            EmailStatus::Sent(date) => write!(f, "Sent on {}", date),
            EmailStatus::Pending => write!(f, "Pending"),
        }
    }
}

/// Authentication response from the API.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuthResponse {
    pub token: String,
    pub email: String,
    pub name: String,
}

/// Login request payload.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoginRequest {
    pub email: String,
    pub password: String,
}

/// Registration request payload.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RegisterRequest {
    pub name: String,
    pub email: String,
    pub password: String,
}

/// Profile response payload.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ProfileResponse {
    pub name: String,
    pub email: String,
    #[serde(default)]
    pub bio: Option<String>,
    #[serde(default)]
    pub target_role: Option<String>,
    #[serde(default)]
    pub years_of_experience: Option<i32>,
    #[serde(default)]
    pub tech_stack: Option<String>,
    #[serde(default)]
    pub linkedin_url: Option<String>,
    #[serde(default)]
    pub portfolio_url: Option<String>,
    #[serde(default)]
    pub preferred_location: Option<String>,
    #[serde(default)]
    pub open_to_remote: Option<bool>,
    #[serde(default)]
    pub skills: Option<Vec<String>>,
}

/// Profile update request payload.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProfileRequest {
    pub name: String,
    #[serde(default)]
    pub bio: Option<String>,
    #[serde(default)]
    pub target_role: Option<String>,
    #[serde(default)]
    pub years_of_experience: Option<i32>,
    #[serde(default)]
    pub tech_stack: Option<String>,
    #[serde(default)]
    pub linkedin_url: Option<String>,
    #[serde(default)]
    pub portfolio_url: Option<String>,
    #[serde(default)]
    pub preferred_location: Option<String>,
    #[serde(default)]
    pub open_to_remote: Option<bool>,
}

/// Company tone is a reference to the tone used by the company in their job listings.
/// This is used when generating emails to match the company's communication style.
pub type CompanyTone = String;

/// Cached job is a local representation that mirrors JobResponse plus metadata used by the cache.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CachedJob {
    pub id: i64,
    pub title: String,
    pub company: String,
    pub url: String,
    pub description: String,
    pub posted_at: NaiveDate,
    pub source: String,
    pub contact_email: Option<String>,
    pub fetched_at: NaiveDate,
}

impl From<CachedJob> for JobResponse {
    fn from(cj: CachedJob) -> Self {
        JobResponse {
            id: cj.id,
            title: cj.title,
            company: cj.company,
            url: cj.url,
            description: cj.description,
            posted_at: cj.posted_at,
            source: cj.source,
            contact_email: cj.contact_email,
        }
    }
}

impl CachedJob {
    /// Days since this job was fetched from the API.
    pub fn days_since_fetch(&self) -> i64 {
        (chrono::Local::now().date_naive() - self.fetched_at).num_days()
    }
}

/// Company tone type to choose from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum CompanyToneType {
    Formal,
    Casual,
    Technical,
    Friendly,
    Professional,
}

impl CompanyToneType {
    pub fn all() -> Vec<CompanyToneType> {
        vec![
            CompanyToneType::Formal,
            CompanyToneType::Casual,
            CompanyToneType::Technical,
            CompanyToneType::Friendly,
            CompanyToneType::Professional,
        ]
    }

    pub fn description(&self) -> &'static str {
        match self {
            CompanyToneType::Formal => "Formal and professional",
            CompanyToneType::Casual => "Casual and friendly",
            CompanyToneType::Technical => "Technical and detailed",
            CompanyToneType::Friendly => "Warm and approachable",
            CompanyToneType::Professional => "Standard professional",
        }
    }
}

/// Commands that can be run in batch mode.
#[derive(Debug, Clone)]
pub enum BatchCommand {
    Fetch,
    Analyze {
        keyword: Option<String>,
        min_score: Option<i32>,
        min_days_old: Option<i64>,
        offline: bool,
        limit: Option<usize>,
    },
    Email {
        keyword: Option<String>,
        min_score: Option<i32>,
        min_days_old: Option<i64>,
        offline: bool,
        limit: Option<usize>,
        confirm: bool,
    },
    Export {
        keyword: Option<String>,
        min_score: Option<i32>,
        min_days_old: Option<i64>,
        offline: bool,
        limit: Option<usize>,
        format: Option<String>,
        output: Option<String>,
    },
    Stats,
    List,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn apply_type_from_external_keywords() {
        assert_eq!(ApplyType::from_description("Candidatura externa via portal"), ApplyType::ExternalApply);
        assert_eq!(ApplyType::from_description("External application on company site"), ApplyType::ExternalApply);
        assert_eq!(ApplyType::from_description("Candidate-se no site da empresa"), ApplyType::ExternalApply);
    }

    #[test]
    fn apply_type_description_long_implies_email() {
        assert_eq!(ApplyType::from_description("This description is at least twenty characters long!"), ApplyType::EmailAvailable);
    }

    #[test]
    fn apply_type_short_description_is_unknown() {
        assert_eq!(ApplyType::from_description(""), ApplyType::Unknown);
        assert_eq!(ApplyType::from_description("short"), ApplyType::Unknown);
    }

    #[test]
    fn seniority_junior_detection() {
        assert_eq!(SeniorityLevel::from_title("Junior Software Developer"), SeniorityLevel::Junior);
        assert_eq!(SeniorityLevel::from_title("Estágio em Desenvolvimento"), SeniorityLevel::Junior);
        assert_eq!(SeniorityLevel::from_title("Trainee Developer"), SeniorityLevel::Junior);
        assert_eq!(SeniorityLevel::from_title("Jr. Developer"), SeniorityLevel::Junior);
    }

    #[test]
    fn seniority_pleno_detection() {
        assert_eq!(SeniorityLevel::from_title("Pleno Developer"), SeniorityLevel::Pleno);
        assert_eq!(SeniorityLevel::from_title("Mid-level Engineer"), SeniorityLevel::Pleno);
    }

    #[test]
    fn seniority_senior_detection() {
        assert_eq!(SeniorityLevel::from_title("Senior Software Engineer"), SeniorityLevel::Senior);
        assert_eq!(SeniorityLevel::from_title("Sr. Developer"), SeniorityLevel::Senior);
        assert_eq!(SeniorityLevel::from_title("Sênior Developer"), SeniorityLevel::Senior);
    }

    #[test]
    fn seniority_lead_detection() {
        assert_eq!(SeniorityLevel::from_title("Lead Engineer"), SeniorityLevel::Lead);
        assert_eq!(SeniorityLevel::from_title("Head of Engineering"), SeniorityLevel::Lead);
        assert_eq!(SeniorityLevel::from_title("Principal Architect"), SeniorityLevel::Lead);
        assert_eq!(SeniorityLevel::from_title("Staff Engineer"), SeniorityLevel::Lead);
        assert_eq!(SeniorityLevel::from_title("Engineering Manager"), SeniorityLevel::Lead);
    }

    #[test]
    fn seniority_unknown_detection() {
        assert_eq!(SeniorityLevel::from_title("Software Developer"), SeniorityLevel::Unknown);
        assert_eq!(SeniorityLevel::from_title("Developer"), SeniorityLevel::Unknown);
    }

    #[test]
    fn is_dev_role_positive() {
        assert!(is_dev_role("Software Developer"));
        assert!(is_dev_role("Backend Engineer"));
        assert!(is_dev_role("Front-end Developer"));
        assert!(is_dev_role("Full Stack Developer"));
        assert!(is_dev_role("Data Scientist"));
        assert!(is_dev_role("QA Engineer"));
        assert!(is_dev_role("DevOps Engineer"));
        assert!(is_dev_role("Machine Learning Engineer"));
    }

    #[test]
    fn is_dev_role_negative() {
        assert!(!is_dev_role("Marketing Specialist"));
        assert!(!is_dev_role("Sales Representative"));
        assert!(!is_dev_role("Designer"));
        assert!(!is_dev_role("Product Manager"));
    }

    #[test]
    fn job_response_deserialize_contact_email() {
        let json = r#"{
            "id": 1,
            "title": "Software Engineer",
            "company": "TechCo",
            "url": "https://example.com/job/1",
            "description": "Full stack developer needed",
            "posted_at": "2026-07-15",
            "source": "linkedin",
            "contact_email": "jobs@techco.com"
        }"#;
        let job: JobResponse = serde_json::from_str(json).unwrap();
        assert_eq!(job.contact_email, Some("jobs@techco.com".to_string()));
    }

    #[test]
    fn job_response_deserialize_missing_contact_email() {
        let json = r#"{
            "id": 2,
            "title": "Backend Developer",
            "company": "StartupX",
            "url": "https://example.com/job/2",
            "description": "Backend role",
            "posted_at": "2026-07-10",
            "source": "gupy"
        }"#;
        let job: JobResponse = serde_json::from_str(json).unwrap();
        assert_eq!(job.contact_email, None);
    }

    #[test]
    fn email_draft_status_pending() {
        let draft = EmailDraftResponse {
            id: 1,
            job_id: 1,
            subject: "Application".into(),
            body: "Body".into(),
            sent: false,
            sent_at: None,
            created_at: Some(NaiveDate::from_ymd_opt(2026, 7, 15).unwrap()),
        };
        assert_eq!(draft.status(), EmailStatus::Pending);
    }

    #[test]
    fn email_draft_status_sent() {
        let draft = EmailDraftResponse {
            id: 2,
            job_id: 1,
            subject: "Application".into(),
            body: "Body".into(),
            sent: true,
            sent_at: Some(NaiveDate::from_ymd_opt(2026, 7, 20).unwrap()),
            created_at: Some(NaiveDate::from_ymd_opt(2026, 7, 15).unwrap()),
        };
        assert_eq!(draft.status(), EmailStatus::Sent(NaiveDate::from_ymd_opt(2026, 7, 20).unwrap()));
    }

    #[test]
    fn job_source_from_str() {
        assert_eq!(JobSource::from("gupy"), JobSource::Gupy);
        assert_eq!(JobSource::from("infojobs"), JobSource::Infojobs);
        assert_eq!(JobSource::from("linkedin"), JobSource::LinkedIn);
        assert_eq!(JobSource::from("linkedin_jsoup"), JobSource::LinkedInJsoup);
        assert_eq!(JobSource::from("unknown"), JobSource::Unknown);
    }

    #[test]
    fn job_source_display() {
        assert_eq!(JobSource::Gupy.to_string(), "gupy");
        assert_eq!(JobSource::Infojobs.to_string(), "infojobs");
        assert_eq!(JobSource::LinkedIn.to_string(), "linkedin");
    }

    #[test]
    fn cached_job_from_job_response() {
        let response = JobResponse {
            id: 1,
            title: "Dev".into(),
            company: "Co".into(),
            url: "url".into(),
            description: "desc".into(),
            posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
            contact_email: Some("hr@co.com".into()),
        };
        let cached: CachedJob = CachedJob {
            id: response.id,
            title: response.title.clone(),
            company: response.company.clone(),
            url: response.url.clone(),
            description: response.description.clone(),
            posted_at: response.posted_at,
            source: response.source.clone(),
            contact_email: response.contact_email.clone(),
            fetched_at: NaiveDate::from_ymd_opt(2026, 7, 28).unwrap(),
        };
        let back: JobResponse = cached.into();
        assert_eq!(back.contact_email, Some("hr@co.com".into()));
    }
}
