use crate::api::ApiClient;
use crate::domain::{CompanyTone, ProfileRequest, ProfileResponse, ProjectRequest};
use crate::error::{ApiError, CliError};
use crate::util;

/// Handle profile subcommands.
///
/// Dispatches to the appropriate handler based on the action variant.
pub async fn handle(
    action: crate::ProfileAction,
    api_url: &str,
    token: &Option<String>,
) -> anyhow::Result<()> {
    let client = ApiClient::new(api_url).with_token(token.clone().unwrap_or_default());

    match action {
        crate::ProfileAction::Show { json } => handle_show(json, &client).await,
        crate::ProfileAction::Edit {
            resume,
            skills,
            tone,
            projects,
            phone,
            contact_email,
            portfolio_url,
            github_url,
            linkedin_url,
        } => {
            handle_edit(
                resume,
                skills,
                tone,
                projects,
                phone,
                contact_email,
                portfolio_url,
                github_url,
                linkedin_url,
                &client,
            )
            .await
        }
        crate::ProfileAction::UploadResume { path } => handle_upload_resume(path, &client).await,
    }
}

/// Show the current user profile.
///
/// When `use_json` is `true`, the full `ProfileResponse` is printed as
/// pretty-printed JSON. Otherwise a human-readable summary is shown
/// with resume preview (truncated), skills as a numbered list, and
/// tone badge.
async fn handle_show(use_json: bool, client: &ApiClient) -> anyhow::Result<()> {
    match client.get_profile().await {
        Ok(profile) => {
            if use_json {
                println!("{}", serde_json::to_string_pretty(&profile)?);
            } else {
                print_profile(&profile);
            }
        }
        Err(CliError::Api(ApiError::Unauthorized(_))) => {
            eprintln!("Error: Not authenticated.");
            eprintln!("Hint: Run 'jh auth login' to authenticate.");
        }
        Err(e) => return Err(e.into()),
    }
    Ok(())
}

async fn handle_edit(
    resume: Option<String>,
    skills: Option<String>,
    tone: Option<String>,
    projects: Option<String>,
    phone: Option<String>,
    contact_email: Option<String>,
    portfolio_url: Option<String>,
    github_url: Option<String>,
    linkedin_url: Option<String>,
    client: &ApiClient,
) -> anyhow::Result<()> {
    let current = match client.get_profile().await {
        Ok(p) => p,
        Err(CliError::Api(ApiError::Unauthorized(_))) => {
            eprintln!("Error: Not authenticated.");
            eprintln!("Hint: Run 'jh auth login' to authenticate.");
            return Ok(());
        }
        Err(e) => return Err(e.into()),
    };

    let skills = skills.map(|s| {
        s.split(',')
            .map(|part| part.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect::<Vec<_>>()
    });

    let parsed_tone = if let Some(ref t) = tone {
        match t.to_lowercase().as_str() {
            "formal" => Some(CompanyTone::Formal),
            "casual" => Some(CompanyTone::Casual),
            "startup" => Some(CompanyTone::Startup),
            _ => {
                eprintln!("Error: Invalid tone '{t}'.");
                eprintln!("Valid options: formal, casual, startup");
                return Ok(());
            }
        }
    } else {
        None
    };

    let parsed_projects = if let Some(ref p) = projects {
        match serde_json::from_str::<Vec<ProjectRequest>>(p) {
            Ok(list) => Some(list),
            Err(e) => {
                eprintln!("Error: Invalid projects JSON: {e}");
                eprintln!("Hint: Provide projects as a JSON array, e.g. --projects '[{{\"name\":\"...\",\"description\":\"...\",\"techStack\":[\"Rust\"]}}]'");
                return Ok(());
            }
        }
    } else {
        None
    };

    let req = ProfileRequest {
        resume_text: resume.unwrap_or(current.resume_text),
        skills: skills.unwrap_or(current.skills),
        tone: parsed_tone.unwrap_or(current.tone),
        projects: parsed_projects.unwrap_or(current.projects.into_iter().map(|p| ProjectRequest {
            name: p.name,
            description: p.description,
            tech_stack: p.tech_stack,
        }).collect()),
        phone: apply_contact_field(current.phone, phone),
        contact_email: apply_contact_field(current.contact_email, contact_email),
        portfolio_url: apply_contact_field(current.portfolio_url, portfolio_url),
        github_url: apply_contact_field(current.github_url, github_url),
        linkedin_url: apply_contact_field(current.linkedin_url, linkedin_url),
    };

    match client.update_profile(&req).await {
        Ok(saved) => {
            println!("Profile updated successfully.");
            println!("  Resume: {} words", saved.resume_text.split_whitespace().count());
            println!("  Skills ({}):", saved.skills.len());
            for (i, skill) in saved.skills.iter().enumerate() {
                println!("    {}. {}", i + 1, skill);
            }
            println!("  Tone: {}", tone_badge(&saved.tone));
            print_contact_summary(&saved);
            println!("  Projects ({}):", saved.projects.len());
            for (i, p) in saved.projects.iter().enumerate() {
                println!("    {}. {} ({})", i + 1, p.name, p.tech_stack.join(", "));
            }
        }
        Err(CliError::Api(ApiError::BadRequest(msg))) => {
            eprintln!("Error: {msg}");
        }
        Err(e) => return Err(e.into()),
    }
    Ok(())
}

/// Merge a contact field flag with the stored value.
///
/// Omitted flag (`None`) keeps the stored value; an empty/whitespace
/// string clears the field ("not set").
fn apply_contact_field(current: Option<String>, new: Option<String>) -> Option<String> {
    match new {
        Some(value) => {
            let trimmed = value.trim().to_string();
            if trimmed.is_empty() {
                None
            } else {
                Some(trimmed)
            }
        }
        None => current,
    }
}

/// Print the contact section of a profile summary.
fn print_contact_summary(profile: &ProfileResponse) {
    let contacts = [
        ("Phone", &profile.phone),
        ("Email", &profile.contact_email),
        ("Portfolio", &profile.portfolio_url),
        ("GitHub", &profile.github_url),
        ("LinkedIn", &profile.linkedin_url),
    ];

    println!("  Contact:");
    if contacts.iter().all(|(_, v)| v.is_none()) {
        println!("    (not set)");
    } else {
        for (label, value) in contacts {
            if let Some(v) = value {
                println!("    {}: {}", label, v);
            }
        }
    }
}

/// Print profile in human-readable format.
///
/// Shows resume truncated to 200 chars, skills as a numbered list,
/// and a tone badge.
fn print_profile(profile: &ProfileResponse) {
    println!("=== Profile ===");

    let word_count = profile.resume_text.split_whitespace().count();
    println!("Resume: {} words", word_count);
    if !profile.resume_text.is_empty() {
        println!("  {}", util::truncate(&profile.resume_text, 200));
    }

    println!();
    println!("Skills ({}):", profile.skills.len());
    if profile.skills.is_empty() {
        println!("  (none)");
    } else {
        for (i, skill) in profile.skills.iter().enumerate() {
            println!("  {}. {}", i + 1, skill);
        }
    }

    println!();
    println!("Tone: {}", tone_badge(&profile.tone));

    println!();
    println!("Contact:");
    let contacts = [
        ("Phone", &profile.phone),
        ("Email", &profile.contact_email),
        ("Portfolio", &profile.portfolio_url),
        ("GitHub", &profile.github_url),
        ("LinkedIn", &profile.linkedin_url),
    ];
    if contacts.iter().all(|(_, v)| v.is_none()) {
        println!("  (not set)");
    } else {
        for (label, value) in contacts {
            if let Some(v) = value {
                println!("  {}: {}", label, v);
            }
        }
    }

    println!();
    println!("Projects ({}):", profile.projects.len());
    if profile.projects.is_empty() {
        println!("  (none)");
    } else {
        for (i, p) in profile.projects.iter().enumerate() {
            println!("  {}. {} — {} [{}]", i + 1, p.name, p.description, p.tech_stack.join(", "));
        }
    }
}

fn tone_badge(tone: &CompanyTone) -> &'static str {
    match tone {
        CompanyTone::Formal => "[FORMAL]",
        CompanyTone::Casual => "[CASUAL]",
        CompanyTone::Startup => "[STARTUP]",
    }
}

async fn handle_upload_resume(path: String, client: &ApiClient) -> anyhow::Result<()> {
    println!("Uploading {} ...", path);
    match client.upload_resume(&path).await {
        Ok(profile) => {
            println!("Resume uploaded and profile extracted successfully.\n");
            print_profile(&profile);
        }
        Err(CliError::Api(ApiError::BadRequest(msg))) => {
            eprintln!("Error: {}", msg);
        }
        Err(CliError::Api(ApiError::BadGateway(msg))) => {
            eprintln!("Error: AI extraction failed: {}", msg);
        }
        Err(CliError::Api(ApiError::Unauthorized(_))) => {
            eprintln!("Error: Not authenticated.");
            eprintln!("Hint: Run 'jh auth login' to authenticate.");
        }
        Err(e) => return Err(e.into()),
    }
    Ok(())
}

// =========================================================================
// Tests
// =========================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use httpmock::prelude::*;
    use httpmock::Method;
    use serde_json::json;

    /// Build a sample profile for test mocks.
    fn sample_profile() -> serde_json::Value {
        json!({
            "id": 1,
            "userId": 1,
            "resumeText": "Experienced Rust developer with 5 years in systems programming.",
            "skills": ["Rust", "PostgreSQL", "Docker"],
            "tone": "STARTUP",
            "projects": []
        })
    }

    fn sample_updated_profile() -> serde_json::Value {
        json!({
            "id": 1,
            "userId": 1,
            "resumeText": "Senior Go developer with 8 years of backend experience.",
            "skills": ["Go", "Kubernetes", "Redis"],
            "tone": "FORMAL",
            "projects": []
        })
    }

    /// Profile fixture with all contact fields set.
    fn sample_profile_with_contacts() -> serde_json::Value {
        json!({
            "id": 1,
            "userId": 1,
            "resumeText": "Experienced Rust developer with 5 years in systems programming.",
            "skills": ["Rust", "PostgreSQL", "Docker"],
            "tone": "STARTUP",
            "projects": [],
            "phone": "+55 42 99833-1363",
            "contactEmail": "juan@example.com",
            "portfolioUrl": "https://juanperuzzo.dev",
            "githubUrl": "https://github.com/juanperuzzo",
            "linkedinUrl": "https://linkedin.com/in/juanperuzzo"
        })
    }

    // =====================================================================
    // handle_show tests
    // =====================================================================

    #[tokio::test]
    async fn show_success_displays_profile() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::GET)
                .path("/api/profile")
                .header("authorization", "Bearer test-token");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_profile());
        });

        let action = crate::ProfileAction::Show { json: false };
        let result = handle(action, &server.url(""), &token).await;

        assert!(result.is_ok(), "show should succeed: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn show_json_outputs_json() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/profile");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_profile());
        });

        let action = crate::ProfileAction::Show { json: true };
        let result = handle(action, &server.url(""), &token).await;

        assert!(result.is_ok(), "show --json should succeed: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn show_unauthorized_prints_hint() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/profile");
            then.status(401)
                .header("content-type", "application/json")
                .json_body(json!({
                    "timestamp": "2026-07-14T12:00:00.000",
                    "status": 401,
                    "error": "Unauthorized",
                    "message": "Token expired"
                }));
        });

        let action = crate::ProfileAction::Show { json: false };
        let result = handle(action, &server.url(""), &token).await;

        assert!(result.is_ok(), "401 should return Ok with hint: {result:?}");
        mock.assert();
    }

    // =====================================================================
    // handle_edit tests
    // =====================================================================

    #[tokio::test]
    async fn edit_all_fields_success() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        // First: GET current profile
        let get_mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/profile");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_profile());
        });

        // Then: PUT updated profile
        let put_mock = server.mock(|when, then| {
            when.method(Method::PUT)
                .path("/api/profile")
                .header("authorization", "Bearer test-token")
                .json_body(json!({
                    "resumeText": "Senior Go developer with 8 years of backend experience.",
                    "skills": ["Go", "Kubernetes", "Redis"],
                    "tone": "FORMAL",
                    "projects": []
                }));
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_updated_profile());
        });

        let action = crate::ProfileAction::Edit {
            resume: Some("Senior Go developer with 8 years of backend experience.".into()),
            skills: Some("Go, Kubernetes, Redis".into()),
            tone: Some("formal".into()),
            projects: None,
            phone: None,
            contact_email: None,
            portfolio_url: None,
            github_url: None,
            linkedin_url: None,
        };
        let result = handle(action, &server.url(""), &token).await;

        assert!(result.is_ok(), "edit all fields should succeed: {result:?}");
        get_mock.assert();
        put_mock.assert();
    }

    #[tokio::test]
    async fn edit_partial_fields_merges_with_current() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        // GET returns profile with resume & skills & tone
        let get_mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/profile");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_profile());
        });

        // PUT should merge: new tone, keep old resume + skills
        let put_mock = server.mock(|when, then| {
            when.method(Method::PUT)
                .path("/api/profile")
                .json_body(json!({
                    "resumeText": "Experienced Rust developer with 5 years in systems programming.",
                    "skills": ["Rust", "PostgreSQL", "Docker"],
                    "tone": "CASUAL",
                    "projects": []
                }));
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "id": 1,
                    "userId": 1,
                    "resumeText": "Experienced Rust developer with 5 years in systems programming.",
                    "skills": ["Rust", "PostgreSQL", "Docker"],
                    "tone": "CASUAL",
                    "projects": []
                }));
        });

        let action = crate::ProfileAction::Edit {
            resume: None,
            skills: None,
            tone: Some("casual".into()),
            projects: None,
            phone: None,
            contact_email: None,
            portfolio_url: None,
            github_url: None,
            linkedin_url: None,
        };
        let result = handle(action, &server.url(""), &token).await;

        assert!(result.is_ok(), "edit partial should succeed: {result:?}");
        get_mock.assert();
        put_mock.assert();
    }

    #[tokio::test]
    async fn edit_invalid_tone_prints_error() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        // GET should still be called (to check auth before validating)
        let get_mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/profile");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_profile());
        });

        let action = crate::ProfileAction::Edit {
            resume: None,
            skills: None,
            tone: Some("invalid-tone".into()),
            projects: None,
            phone: None,
            contact_email: None,
            portfolio_url: None,
            github_url: None,
            linkedin_url: None,
        };
        let result = handle(action, &server.url(""), &token).await;

        assert!(result.is_ok(), "invalid tone should return Ok with error message");
        get_mock.assert();
        // PUT should NOT be called
    }

    #[tokio::test]
    async fn edit_validation_error_shows_backend_message() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        // GET current profile
        let get_mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/profile");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_profile());
        });

        // PUT returns 400 with server-side validation message
        let put_mock = server.mock(|when, then| {
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

        let action = crate::ProfileAction::Edit {
            resume: Some("New resume".into()),
            skills: Some("".into()),
            tone: None,
            projects: None,
            phone: None,
            contact_email: None,
            portfolio_url: None,
            github_url: None,
            linkedin_url: None,
        };
        let result = handle(action, &server.url(""), &token).await;

        assert!(result.is_ok(), "400 should return Ok with backend message: {result:?}");
        get_mock.assert();
        put_mock.assert();
    }

    #[tokio::test]
    async fn edit_unauthorized_shows_hint() {
        let server = MockServer::start();
        let token = Some("expired-token".into());

        let get_mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/profile");
            then.status(401)
                .header("content-type", "application/json")
                .json_body(json!({
                    "message": "Token expired"
                }));
        });

        let action = crate::ProfileAction::Edit {
            resume: Some("New resume".into()),
            skills: None,
            tone: None,
            projects: None,
            phone: None,
            contact_email: None,
            portfolio_url: None,
            github_url: None,
            linkedin_url: None,
        };
        let result = handle(action, &server.url(""), &token).await;

        assert!(result.is_ok(), "401 should return Ok with hint: {result:?}");
        get_mock.assert();
    }

    #[tokio::test]
    async fn edit_contact_fields_sent_in_request() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let get_mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/profile");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_profile());
        });

        let put_mock = server.mock(|when, then| {
            when.method(Method::PUT)
                .path("/api/profile")
                .header("authorization", "Bearer test-token")
                .body_contains("\"phone\":\"+55 42 99833-1363\"")
                .body_contains("\"contactEmail\":\"juan@example.com\"")
                .body_contains("\"githubUrl\":\"https://github.com/juanperuzzo\"");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_updated_profile());
        });

        let action = crate::ProfileAction::Edit {
            resume: None,
            skills: None,
            tone: None,
            projects: None,
            phone: Some("+55 42 99833-1363".into()),
            contact_email: Some("juan@example.com".into()),
            portfolio_url: None,
            github_url: Some("https://github.com/juanperuzzo".into()),
            linkedin_url: None,
        };
        let result = handle(action, &server.url(""), &token).await;

        assert!(result.is_ok(), "edit with contact fields should succeed: {result:?}");
        get_mock.assert();
        put_mock.assert();
    }

    #[tokio::test]
    async fn edit_empty_contact_flag_clears_field() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        // Stored profile has a phone number set
        let get_mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/profile");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_profile_with_contacts());
        });

        // Empty --phone should clear it: body must NOT contain "phone"
        let put_with_phone = server.mock(|when, then| {
            when.method(Method::PUT)
                .path("/api/profile")
                .body_matches(regex::Regex::new(".*\"phone\".*").unwrap());
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_updated_profile());
        });

        // Cleared phone is omitted from the body entirely; this catch-all
        // only catches bodies WITHOUT "phone" (the mock above runs first).
        let put_cleared = server.mock(|when, then| {
            when.method(Method::PUT).path("/api/profile");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_updated_profile());
        });

        let action = crate::ProfileAction::Edit {
            resume: None,
            skills: None,
            tone: None,
            projects: None,
            phone: Some(String::new()),
            contact_email: None,
            portfolio_url: None,
            github_url: None,
            linkedin_url: None,
        };
        let result = handle(action, &server.url(""), &token).await;

        assert!(result.is_ok(), "clearing phone should succeed: {result:?}");
        get_mock.assert();
        put_cleared.assert();
        assert_eq!(put_with_phone.hits(), 0, "PUT body must not contain phone");
    }

    #[tokio::test]
    async fn edit_omitted_contact_fields_preserve_current() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        // Stored profile has contact fields set
        let get_mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/profile");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_profile_with_contacts());
        });

        // Omitted flags must carry current values into the PUT body
        let put_mock = server.mock(|when, then| {
            when.method(Method::PUT)
                .path("/api/profile")
                .body_contains("\"phone\":\"+55 42 99833-1363\"")
                .body_contains("\"contactEmail\":\"juan@example.com\"");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_updated_profile());
        });

        let action = crate::ProfileAction::Edit {
            resume: None,
            skills: None,
            tone: Some("casual".into()),
            projects: None,
            phone: None,
            contact_email: None,
            portfolio_url: None,
            github_url: None,
            linkedin_url: None,
        };
        let result = handle(action, &server.url(""), &token).await;

        assert!(result.is_ok(), "omitted contact flags should keep values: {result:?}");
        get_mock.assert();
        put_mock.assert();
    }

    // =====================================================================
    // tone_badge tests
    // =====================================================================

    #[test]
    fn tone_badge_formal() {
        assert_eq!(tone_badge(&CompanyTone::Formal), "[FORMAL]");
    }

    #[test]
    fn tone_badge_casual() {
        assert_eq!(tone_badge(&CompanyTone::Casual), "[CASUAL]");
    }

    #[test]
    fn tone_badge_startup() {
        assert_eq!(tone_badge(&CompanyTone::Startup), "[STARTUP]");
    }

    // =====================================================================
    // apply_contact_field tests
    // =====================================================================

    #[test]
    fn apply_contact_field_omitted_keeps_current() {
        let current = Some("+55 42 99833-1363".to_string());
        assert_eq!(apply_contact_field(current.clone(), None), current);
        assert_eq!(apply_contact_field(None, None), None);
    }

    #[test]
    fn apply_contact_field_value_replaces_current() {
        let result = apply_contact_field(Some("old".to_string()), Some("new".to_string()));
        assert_eq!(result, Some("new".to_string()));
    }

    #[test]
    fn apply_contact_field_empty_string_clears() {
        let result = apply_contact_field(Some("+55 42 99833-1363".to_string()), Some(String::new()));
        assert_eq!(result, None);
    }

    #[test]
    fn apply_contact_field_whitespace_only_clears_and_trims_values() {
        let cleared = apply_contact_field(Some("x".to_string()), Some("   ".to_string()));
        assert_eq!(cleared, None);

        let trimmed = apply_contact_field(None, Some("  https://dev.io  ".to_string()));
        assert_eq!(trimmed, Some("https://dev.io".to_string()));
    }
}
