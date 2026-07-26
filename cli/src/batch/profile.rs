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
        } => handle_edit(resume, skills, tone, projects, &client).await,
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
        };
        let result = handle(action, &server.url(""), &token).await;

        assert!(result.is_ok(), "401 should return Ok with hint: {result:?}");
        get_mock.assert();
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
}
