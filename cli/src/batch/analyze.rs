use crate::api::ApiClient;
use crate::cache::CacheManager;
use crate::config;
use crate::domain::JobAnalysis;
use crate::error::{ApiError, CliError};
use crate::util;

/// Analyze a job with AI.
///
/// When `use_json` is `true`, the full `JobAnalysis` is printed as
/// pretty-printed JSON. Otherwise a human-readable summary with
/// color-coded score and skill lists is shown.
///
/// If the job is not found (404), a friendly message with a hint to
/// run `jh fetch` is printed to stderr. If analysis already exists
/// (409), a hint to use `jh detail` is printed.
pub async fn handle_analyze(
    job_id: String,
    use_json: bool,
    api_url: &str,
    token: &Option<String>,
) -> anyhow::Result<()> {
    let id = match util::parse_job_id(&job_id) {
        Ok(id) => id,
        Err(e) => {
            eprintln!("Error: {e}");
            return Ok(());
        }
    };
    let client = ApiClient::new(api_url).with_token(token.clone().unwrap_or_default());

    let config = config::load(None)?;
    let cache = CacheManager::new(None, config.cache_ttl_hours)?;

    match client.analyze_job(id).await {
        Ok(analysis) => {
            cache.update_cache_on_analyze(id, &analysis)?;
            if use_json {
                println!("{}", serde_json::to_string_pretty(&analysis)?);
            } else {
                print_analysis(&analysis);
            }
        }
        Err(CliError::Api(ApiError::Conflict(_))) => {
            eprintln!("Error: Analysis already exists for job {id}.");
            eprintln!("Hint: Use 'jh detail {id}' to view the existing analysis.");
        }
        Err(CliError::Api(ApiError::NotFound(_))) => {
            eprintln!("Error: Job {id} not found.");
            eprintln!("Hint: Run 'jh fetch' to load jobs from providers, then try again.");
        }
        Err(e) => return Err(e.into()),
    }

    Ok(())
}

/// Handle email subcommands.
///
/// Dispatches to the appropriate handler based on the action variant.
pub async fn handle_email(
    action: crate::EmailAction,
    api_url: &str,
    token: &Option<String>,
) -> anyhow::Result<()> {
    let client = ApiClient::new(api_url).with_token(token.clone().unwrap_or_default());

    match action {
        crate::EmailAction::Show { job_id, json, copy } => {
            handle_email_show(job_id, json, copy, &client).await
        }
        crate::EmailAction::Generate { job_id } => {
            handle_email_generate(job_id, &client).await
        }
    }
}

/// Show the current email draft for a job.
///
/// When `use_json` is `true`, the full `EmailDraftResponse` is printed
/// as pretty-printed JSON. When `copy` is `true`, the email body is
/// copied to the system clipboard.
///
/// If no draft exists (404), a hint to run `jh email generate` is shown.
async fn handle_email_show(
    job_id: String,
    use_json: bool,
    copy: bool,
    client: &ApiClient,
) -> anyhow::Result<()> {
    let id = match util::parse_job_id(&job_id) {
        Ok(id) => id,
        Err(e) => {
            eprintln!("Error: {e}");
            return Ok(());
        }
    };

    match client.get_email(id).await {
        Ok(draft) => {
            if copy {
                match util::copy_to_clipboard(&draft.body) {
                    Ok(()) => println!("Email body copied to clipboard."),
                    Err(e) => eprintln!("Warning: Could not copy to clipboard: {e}"),
                }
            }

            if use_json {
                println!("{}", serde_json::to_string_pretty(&draft)?);
            } else {
                println!("Subject: {}", draft.subject);
                println!("---");
                println!("{}", draft.body);
            }
        }
        Err(CliError::Api(ApiError::NotFound(_))) => {
            eprintln!("Error: No email draft found for job {id}.");
            eprintln!("Hint: Use 'jh email generate {id}' to create a new draft.");
        }
        Err(e) => return Err(e.into()),
    }

    Ok(())
}

/// Generate a new email draft for a job.
///
/// On success, the generated subject and body are printed to stdout.
/// If the job is not found (404), a friendly hint is displayed.
async fn handle_email_generate(
    job_id: String,
    client: &ApiClient,
) -> anyhow::Result<()> {
    let id = match util::parse_job_id(&job_id) {
        Ok(id) => id,
        Err(e) => {
            eprintln!("Error: {e}");
            return Ok(());
        }
    };

    match client.generate_email(id).await {
        Ok(draft) => {
            println!("Subject: {}", draft.subject);
            println!("---");
            println!("{}", draft.body);

            let config = crate::config::load(None)?;
            let cache = crate::cache::CacheManager::new(None, config.cache_ttl_hours)?;
            let _ = cache.update_cache_on_email(id, &draft);
        }
        Err(CliError::Api(ApiError::NotFound(_))) => {
            eprintln!("Error: Job {id} not found.");
            eprintln!("Hint: Run 'jh fetch' to load jobs from providers, then try again.");
        }
        Err(e) => return Err(e.into()),
    }

    Ok(())
}

/// Print analysis in human-readable format with color-coded score and skills.
///
/// - Match score: green (>=80), yellow (50-79), red (<50)
/// - Matched skills: green
/// - Missing skills: red
fn print_analysis(analysis: &JobAnalysis) {
    let green = "\x1b[32m";
    let yellow = "\x1b[33m";
    let red = "\x1b[31m";
    let reset = "\x1b[0m";

    let score_color = if analysis.match_score >= 80 {
        green
    } else if analysis.match_score >= 50 {
        yellow
    } else {
        red
    };

    println!("{score_color}Match Score: {}%{reset}", analysis.match_score);

    if !analysis.matched_skills.is_empty() {
        println!(
            "Matched Skills: {green}{}{reset}",
            analysis.matched_skills.join(", ")
        );
    }
    if !analysis.missing_skills.is_empty() {
        println!(
            "Missing Skills: {red}{}{reset}",
            analysis.missing_skills.join(", ")
        );
    }
    println!("Company Tone: {}", analysis.company_tone);
    if !analysis.summary.is_empty() {
        println!("Summary: {}", analysis.summary);
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

    fn sample_analysis(job_id: i64, score: i32) -> serde_json::Value {
        json!({
            "id": 1,
            "jobId": job_id,
            "userId": 1,
            "matchScore": score,
            "matchedSkills": ["Rust", "PostgreSQL"],
            "missingSkills": ["Kubernetes"],
            "companyTone": "STARTUP",
            "summary": "Good match for backend role"
        })
    }

    fn sample_email(job_id: i64) -> serde_json::Value {
        json!({
            "id": 10,
            "jobId": job_id,
            "subject": "Application for Rust Developer",
            "body": "Dear Hiring Team,\n\nI am writing to apply...",
            "status": "PENDING",
            "generatedAt": "2026-07-14T10:30:00"
        })
    }

    // =====================================================================
    // handle_analyze tests
    // =====================================================================

    #[tokio::test]
    async fn analyze_success_shows_analysis() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::POST)
                .path("/api/jobs/7/analyze")
                .header("authorization", "Bearer test-token");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_analysis(7, 88));
        });

        let result = handle_analyze("7".into(), false, &server.url(""), &token).await;
        assert!(result.is_ok(), "analyze should succeed: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn analyze_with_json_flag_outputs_json() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::POST).path("/api/jobs/7/analyze");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_analysis(7, 88));
        });

        let result = handle_analyze("7".into(), true, &server.url(""), &token).await;
        assert!(result.is_ok(), "analyze --json should succeed: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn analyze_already_exists_shows_hint() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::POST).path("/api/jobs/7/analyze");
            then.status(409)
                .header("content-type", "application/json")
                .json_body(json!({
                    "message": "Analysis already exists for job 7"
                }));
        });

        let result = handle_analyze("7".into(), false, &server.url(""), &token).await;
        assert!(result.is_ok(), "409 should return Ok with hint: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn analyze_job_not_found_shows_hint() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::POST).path("/api/jobs/999/analyze");
            then.status(404)
                .header("content-type", "application/json")
                .json_body(json!({
                    "message": "Job 999 not found"
                }));
        });

        let result = handle_analyze("999".into(), false, &server.url(""), &token).await;
        assert!(result.is_ok(), "404 should return Ok with hint: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn analyze_invalid_id_prints_error() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let result = handle_analyze("not-a-number".into(), false, &server.url(""), &token).await;
        assert!(result.is_ok(), "invalid ID should return Ok with error message");
    }

    // =====================================================================
    // handle_email tests
    // =====================================================================

    #[tokio::test]
    async fn email_show_success_shows_email() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::GET)
                .path("/api/jobs/3/email")
                .header("authorization", "Bearer test-token");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_email(3));
        });

        let action = crate::EmailAction::Show {
            job_id: "3".into(),
            json: false,
            copy: false,
        };

        let result = handle_email(action, &server.url(""), &token).await;
        assert!(result.is_ok(), "email show should succeed: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn email_show_with_json_flag() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs/3/email");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_email(3));
        });

        let action = crate::EmailAction::Show {
            job_id: "3".into(),
            json: true,
            copy: false,
        };

        let result = handle_email(action, &server.url(""), &token).await;
        assert!(result.is_ok(), "email show --json should succeed: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn email_show_not_found_suggests_generate() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs/999/email");
            then.status(404)
                .header("content-type", "application/json")
                .json_body(json!({
                    "message": "Email draft not found for job 999"
                }));
        });

        let action = crate::EmailAction::Show {
            job_id: "999".into(),
            json: false,
            copy: false,
        };

        let result = handle_email(action, &server.url(""), &token).await;
        assert!(result.is_ok(), "404 should return Ok with hint: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn email_show_invalid_id_prints_error() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let action = crate::EmailAction::Show {
            job_id: "bad-id".into(),
            json: false,
            copy: false,
        };

        let result = handle_email(action, &server.url(""), &token).await;
        assert!(result.is_ok(), "invalid ID should return Ok with error message");
    }

    #[tokio::test]
    async fn email_generate_success_shows_email() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::POST)
                .path("/api/jobs/3/email")
                .header("authorization", "Bearer test-token");
            then.status(201)
                .header("content-type", "application/json")
                .json_body(sample_email(3));
        });

        let action = crate::EmailAction::Generate {
            job_id: "3".into(),
        };

        let result = handle_email(action, &server.url(""), &token).await;
        assert!(result.is_ok(), "email generate should succeed: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn email_generate_job_not_found_shows_hint() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::POST).path("/api/jobs/999/email");
            then.status(404)
                .header("content-type", "application/json")
                .json_body(json!({
                    "message": "Job 999 not found"
                }));
        });

        let action = crate::EmailAction::Generate {
            job_id: "999".into(),
        };

        let result = handle_email(action, &server.url(""), &token).await;
        assert!(result.is_ok(), "404 should return Ok with hint: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn email_generate_invalid_id_prints_error() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let action = crate::EmailAction::Generate {
            job_id: "not-a-number".into(),
        };

        let result = handle_email(action, &server.url(""), &token).await;
        assert!(result.is_ok(), "invalid ID should return Ok with error message");
    }

    // =====================================================================
    // print_analysis tests
    // =====================================================================

    #[test]
    fn print_analysis_high_score_uses_green() {
        let analysis = JobAnalysis {
            id: 1,
            job_id: 1,
            user_id: 1,
            match_score: 85,
            matched_skills: vec!["Rust".into()],
            missing_skills: vec![],
            company_tone: crate::domain::CompanyTone::Startup,
            summary: "Great".into(),
        };
        print_analysis(&analysis);
    }

    #[test]
    fn print_analysis_medium_score_uses_yellow() {
        let analysis = JobAnalysis {
            id: 2,
            job_id: 2,
            user_id: 1,
            match_score: 60,
            matched_skills: vec![],
            missing_skills: vec!["Kubernetes".into()],
            company_tone: crate::domain::CompanyTone::Formal,
            summary: "OK".into(),
        };
        print_analysis(&analysis);
    }

    #[test]
    fn print_analysis_low_score_uses_red() {
        let analysis = JobAnalysis {
            id: 3,
            job_id: 3,
            user_id: 1,
            match_score: 30,
            matched_skills: vec![],
            missing_skills: vec![],
            company_tone: crate::domain::CompanyTone::Casual,
            summary: String::new(),
        };
        print_analysis(&analysis);
    }
}
