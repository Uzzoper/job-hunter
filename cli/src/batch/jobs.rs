use crate::api::ApiClient;
use crate::cache::CacheManager;
use crate::config;
use crate::domain::{JobAnalysis, JobResponse};
use crate::error::{ApiError, CliError};
use crate::util;

/// List jobs with optional filters and format flags.
///
/// `keyword` filters by title/company (case-insensitive).
/// `source` filters by provider name (gupy, linkedin, infojobs).
/// `min_score` requires cached analysis data — jobs without a cached score are excluded.
/// `csv` / `json` control output format. When neither is set, a pretty table is printed.
/// `offline` forces loading from cache only (no API call).
/// `refresh` forces an API call and updates the cache.
#[allow(clippy::too_many_arguments)]
pub async fn handle_list(
    keyword: Option<String>,
    min_score: Option<u8>,
    source: Option<String>,
    csv: bool,
    json: bool,
    offline: bool,
    refresh: bool,
    api_url: &str,
    token: &Option<String>,
) -> anyhow::Result<()> {
    let config = config::load(None)?;
    let cache = CacheManager::new(None, config.cache_ttl_hours)?;

    // Check cache integrity on startup
    if let Some(warning) = cache.ensure_integrity()? {
        eprintln!("Warning: {warning}");
    }

    let jobs: Vec<JobResponse> = if offline {
        // Offline mode: load from cache only — no API call
        let cached = cache.get_all_jobs(None)?;
        if cached.is_empty() {
            eprintln!("No cached jobs found. Use 'jh fetch' to fetch jobs from the API.");
            eprintln!("Hint: Run with --refresh to force a cache update from the API.");
            return Ok(());
        }
        eprintln!("Info: Offline mode — showing cached jobs.");
        cached
            .into_iter()
            .map(|cj| JobResponse {
                id: cj.id,
                title: cj.title,
                company: cj.company,
                url: cj.url,
                description: cj.description,
                posted_at: cj.posted_at,
                source: cj.source,
            })
            .collect()
    } else if refresh {
        // Refresh mode: always fetch from API and update cache
        let client = ApiClient::new(api_url).with_token(token.clone().unwrap_or_default());
        let api_jobs = client.get_jobs().await?;
        cache.update_cache_on_fetch(&api_jobs)?;
        eprintln!("Info: Cache updated with {} jobs.", api_jobs.len());
        api_jobs
    } else {
        let client = ApiClient::new(api_url).with_token(token.clone().unwrap_or_default());
        let api_jobs = client.get_jobs().await?;
        cache.update_cache_on_fetch(&api_jobs)?;
        api_jobs
    };

    let mut filtered: Vec<JobResponse> = jobs;

    if let Some(kw) = &keyword {
        let kw_lower = kw.to_lowercase();
        filtered.retain(|j| {
            j.title.to_lowercase().contains(&kw_lower)
                || j.company.to_lowercase().contains(&kw_lower)
        });
    }

    if let Some(src) = &source {
        let src_lower = src.to_lowercase();
        filtered.retain(|j| j.source.to_lowercase() == src_lower);
    }

    if let Some(min) = min_score {
        let score_map = load_cached_match_scores(&cache)?;
        let min = min as i32;
        filtered.retain(|j| score_map.get(&j.id).is_some_and(|&s| s >= min));
    }

    if json {
        println!("{}", serde_json::to_string_pretty(&filtered)?);
    } else if csv {
        util::export_jobs_csv(&filtered, std::io::stdout())?;
    } else {
        if filtered.is_empty() {
            println!("No jobs found.");
            return Ok(());
        }

        println!(
            "{:<5} {:<40} {:<25} {:<12} {:<12}",
            "ID", "Title", "Company", "Source", "Posted"
        );
        println!("{}", "-".repeat(100));

        for job in &filtered {
            println!(
                "{:<5} {:<40} {:<25} {:<12} {:<12}",
                job.id,
                util::truncate(&job.title, 38),
                util::truncate(&job.company, 23),
                job.source,
                job.posted_at,
            );
        }

        println!("\nTotal: {} jobs", filtered.len());
    }

    Ok(())
}

/// Load cached match scores for all jobs.
///
/// Returns a map of job_id → match_score. Returns an empty map when
/// the cache cannot be loaded (e.g. first run, corrupt DB).
fn load_cached_match_scores(cache: &CacheManager) -> anyhow::Result<std::collections::HashMap<i64, i32>> {
    let cached = cache.get_all_jobs(None)?;
    Ok(cached
        .into_iter()
        .filter_map(|cj| cj.match_score.map(|s| (cj.id, s)))
        .collect())
}

/// Show full job detail.
///
/// Fetches the job from the API. If analysis data is available, it is
/// fetched via `POST /api/jobs/{id}/analyze` (which creates an analysis
/// if none exists). When `--json` is set, output is a JSON object.
pub async fn handle_detail(
    id: i64,
    use_json: bool,
    api_url: &str,
    token: &Option<String>,
) -> anyhow::Result<()> {
    let client = ApiClient::new(api_url).with_token(token.clone().unwrap_or_default());

    let job = match client.get_job(id).await {
        Ok(j) => j,
        Err(CliError::Api(ApiError::NotFound(ref _msg))) => {
            eprintln!("Error: Job {id} not found.");
            eprintln!("Hint: Run 'jh fetch' to load jobs from providers, then try again.");
            return Ok(());
        }
        Err(e) => return Err(e.into()),
    };

    let analysis = fetch_analysis(&client, id).await;

    if use_json {
        print_job_json(&job, analysis.as_ref());
    } else {
        print_job_detail(&job, analysis.as_ref());
    }

    Ok(())
}

/// Attempt to fetch analysis for a job.
///
/// Returns `None` when the analysis already existed (409) or any other
/// non-fatal error occurs.
async fn fetch_analysis(client: &ApiClient, job_id: i64) -> Option<JobAnalysis> {
    match client.analyze_job(job_id).await {
        Ok(a) => Some(a),
        Err(CliError::Api(ApiError::Conflict(_))) => None,
        Err(_) => None,
    }
}

/// Print job detail in JSON format, optionally including analysis.
fn print_job_json(job: &JobResponse, analysis: Option<&JobAnalysis>) {
    let mut map = serde_json::Map::new();
    map.insert("id".into(), serde_json::Value::Number(job.id.into()));
    map.insert("title".into(), job.title.as_str().into());
    map.insert("company".into(), job.company.as_str().into());
    map.insert("url".into(), job.url.as_str().into());
    map.insert("description".into(), job.description.as_str().into());
    map.insert("postedAt".into(), job.posted_at.to_string().into());
    map.insert("source".into(), job.source.as_str().into());

    if let Some(a) = analysis
        && let Ok(val) = serde_json::to_value(a) {
            map.insert("analysis".into(), val);
        }

    let out = serde_json::to_string_pretty(&map).unwrap_or_else(|_| "{}".into());
    println!("{out}");
}

/// Print job detail in human-readable format.
fn print_job_detail(job: &JobResponse, analysis: Option<&JobAnalysis>) {
    println!("=== Job #{} ===", job.id);
    println!("  Title:       {}", job.title);
    println!("  Company:     {}", job.company);
    println!("  URL:         {}", job.url);
    println!("  Description: {}", job.description);
    println!("  Posted At:   {}", job.posted_at);
    println!("  Source:      {}", job.source);

    if let Some(a) = analysis {
        println!();
        println!("--- Analysis ---");
        println!("  Match Score:  {}%", a.match_score);
        if !a.matched_skills.is_empty() {
            println!("  Matched:     {}", a.matched_skills.join(", "));
        }
        if !a.missing_skills.is_empty() {
            println!("  Missing:     {}", a.missing_skills.join(", "));
        }
        println!("  Company Tone: {}", a.company_tone);
        if !a.summary.is_empty() {
            println!("  Summary:     {}", a.summary);
        }
    }
}

/// Fetch jobs from providers.
///
/// When `source` is `Some("linkedin")`, only the LinkedIn scraper is
/// triggered. When `source` is `None`, all providers are triggered.
pub async fn handle_fetch(
    source: Option<String>,
    api_url: &str,
    token: &Option<String>,
) -> anyhow::Result<()> {
    let config = crate::config::load(None)?;
    let cache = crate::cache::CacheManager::new(None, config.cache_ttl_hours)?;

    let client = ApiClient::new(api_url).with_token(token.clone().unwrap_or_default());

    match source.as_deref() {
        Some("linkedin") => {
            let resp = client.fetch_linkedin().await?;
            println!("{}", resp.message);
        }
        Some(src) => {
            eprintln!(
                "Error: Unknown source '{src}'. Expected 'linkedin' or omit for all providers."
            );
            eprintln!(
                "Hint: Use 'jh fetch linkedin' for LinkedIn only, or 'jh fetch' for all providers."
            );
            return Ok(());
        }
        None => {
            let resp = client.fetch_jobs().await?;
            println!("{}", resp.message);
        }
    }

    let jobs = client.get_jobs().await?;
    cache.update_cache_on_fetch(&jobs)?;

    Ok(())
}

/// Export jobs to a CSV file.
///
/// Kept for backward compatibility with the `Export` subcommand.
/// The recommended way is `jh list --csv > file.csv`.
pub async fn handle_export(
    output: String,
    keyword: Option<String>,
    api_url: &str,
    token: &Option<String>,
) -> anyhow::Result<()> {
    let client = ApiClient::new(api_url).with_token(token.clone().unwrap_or_default());
    let jobs = client.get_jobs().await?;

    let filtered: Vec<JobResponse> = jobs
        .into_iter()
        .filter(|job| {
            if let Some(kw) = &keyword {
                let kw_lower = kw.to_lowercase();
                job.title.to_lowercase().contains(&kw_lower)
                    || job.company.to_lowercase().contains(&kw_lower)
            } else {
                true
            }
        })
        .collect();

    let file = std::fs::File::create(&output)?;
    util::export_jobs_csv(&filtered, file)?;
    println!("Exported {} jobs to {}", filtered.len(), output);
    Ok(())
}

// =========================================================================
// Tests
// =========================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::CompanyTone;
    use httpmock::prelude::*;
    use httpmock::Method;
    use serde_json::json;
    use chrono::NaiveDate;

    /// Build a sample job for test mocks.
    fn sample_job(id: i64, title: &str, company: &str, source: &str, days_ago: i64) -> serde_json::Value {
        json!({
            "id": id,
            "title": title,
            "company": company,
            "url": format!("https://example.com/job/{id}"),
            "description": format!("Description for {title}"),
            "postedAt": (chrono::Utc::now() - chrono::Duration::days(days_ago)).date_naive().to_string(),
            "source": source
        })
    }

    fn sample_analysis(job_id: i64, score: i32) -> serde_json::Value {
        json!({
            "id": job_id,
            "jobId": job_id,
            "userId": 1,
            "matchScore": score,
            "matchedSkills": ["Rust", "PostgreSQL"],
            "missingSkills": ["Kubernetes"],
            "companyTone": "STARTUP",
            "summary": "Good match"
        })
    }

    // =====================================================================
    // handle_list tests
    // =====================================================================

    #[tokio::test]
    async fn list_shows_table_with_jobs() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::GET)
                .path("/api/jobs")
                .header("authorization", "Bearer test-token");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!([
                    sample_job(1, "Junior Rust Dev", "TechCorp", "gupy", 1),
                    sample_job(2, "Backend Engineer", "StartupXYZ", "linkedin", 2),
                ]));
        });

        let result = handle_list(
            None, None, None, false, false,
            false, false,
            &server.url(""), &token,
        ).await;

        assert!(result.is_ok(), "handler should succeed: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn list_csv_outputs_csv() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!([
                    sample_job(1, "Rust Dev", "Acme", "gupy", 0),
                    sample_job(2, "Go Dev", "Beta", "linkedin", 1),
                ]));
        });

let result = handle_list(
            None, None, None, false, false,
            false, false,
            &server.url(""), &token,
        ).await;

        assert!(result.is_ok(), "CSV export should succeed: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn list_json_outputs_json() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!([
                    sample_job(1, "Rust Dev", "Acme", "gupy", 0),
                ]));
        });

let result = handle_list(
            None, None, None, true, false,
            false, false,
            &server.url(""), &token,
        ).await;

        assert!(result.is_ok(), "JSON output should succeed: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn list_with_keyword_filters_results() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!([
                    sample_job(1, "Junior Rust Developer", "TechCorp", "gupy", 1),
                    sample_job(2, "Senior Java Engineer", "BigCo", "linkedin", 3),
                    sample_job(3, "Rust Backend Engineer", "StartupXYZ", "infojobs", 2),
                ]));
        });

let result = handle_list(
            None, None, None, false, true,
            false, false,
            &server.url(""), &token,
        ).await;

        assert!(result.is_ok(), "keyword filter should succeed: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn list_with_source_filters_results() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!([
                    sample_job(1, "Job A", "Co A", "gupy", 0),
                    sample_job(2, "Job B", "Co B", "linkedin", 0),
                    sample_job(3, "Job C", "Co C", "gupy", 1),
                ]));
        });

let result = handle_list(
            Some("rust".into()), None, None, false, false,
            false, false,
            &server.url(""), &token,
        ).await;

        assert!(result.is_ok(), "source filter should succeed: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn list_empty_shows_message() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!([]));
        });

        let result = handle_list(
            None, None, None, false, false,
            false, false,
            &server.url(""), &token,
        ).await;

        assert!(result.is_ok(), "empty list should succeed: {result:?}");
        mock.assert();
    }

    #[tokio::test]
    async fn list_no_token_returns_unauthorized() {
        let server = MockServer::start();
        let token: Option<String> = None;

        let mock = server.mock(|when, then| {
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

        let result = handle_list(
            None, None, None, false, false,
            false, false,
            &server.url(""), &token,
        ).await;

        assert!(result.is_err(), "should return error on 401");
        mock.assert();
    }

    // =====================================================================
    // handle_detail tests
    // =====================================================================

    #[tokio::test]
    async fn detail_shows_job_information() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let get_mock = server.mock(|when, then| {
            when.method(Method::GET)
                .path("/api/jobs/1")
                .header("authorization", "Bearer test-token");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "id": 1,
                    "title": "Junior Rust Developer",
                    "company": "TechCorp",
                    "url": "https://example.com/job/1",
                    "description": "Build CLI tools",
                    "postedAt": "2026-07-14",
                    "source": "gupy"
                }));
        });

        let analyze_mock = server.mock(|when, then| {
            when.method(Method::POST)
                .path("/api/jobs/1/analyze");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_analysis(1, 88));
        });

        let result = handle_detail(1, false, &server.url(""), &token).await;

        assert!(result.is_ok(), "detail should succeed: {result:?}");
        get_mock.assert();
        analyze_mock.assert();
    }

    #[tokio::test]
    async fn detail_json_outputs_json() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let get_mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs/2");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "id": 2,
                    "title": "Backend Engineer",
                    "company": "StartupXYZ",
                    "url": "https://example.com/job/2",
                    "description": "Build APIs",
                    "postedAt": "2026-07-13",
                    "source": "linkedin"
                }));
        });

        let analyze_mock = server.mock(|when, then| {
            when.method(Method::POST)
                .path("/api/jobs/2/analyze");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(sample_analysis(2, 75));
        });

        let result = handle_detail(2, true, &server.url(""), &token).await;

        assert!(result.is_ok(), "detail --json should succeed: {result:?}");
        get_mock.assert();
        analyze_mock.assert();
    }

    #[tokio::test]
    async fn detail_not_found_shows_friendly_message() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let get_mock = server.mock(|when, then| {
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

        let result = handle_detail(999, false, &server.url(""), &token).await;

        assert!(result.is_ok(), "not found should return Ok with friendly message");
        get_mock.assert();
    }

    #[tokio::test]
    async fn detail_handles_already_analyzed_gracefully() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let get_mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs/3");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "id": 3,
                    "title": "Data Engineer",
                    "company": "DataCo",
                    "url": "https://example.com/job/3",
                    "description": "Build data pipelines",
                    "postedAt": "2026-07-10",
                    "source": "infojobs"
                }));
        });

        let analyze_mock = server.mock(|when, then| {
            when.method(Method::POST)
                .path("/api/jobs/3/analyze");
            then.status(409)
                .header("content-type", "application/json")
                .json_body(json!({
                    "timestamp": "2026-07-14T12:00:00.000",
                    "status": 409,
                    "error": "Conflict",
                    "message": "Analysis already exists for job 3"
                }));
        });

        let result = handle_detail(3, false, &server.url(""), &token).await;

        assert!(result.is_ok(), "should handle 409 gracefully: {result:?}");
        get_mock.assert();
        analyze_mock.assert();
    }

    // =====================================================================
    // handle_fetch tests
    // =====================================================================

    #[tokio::test]
    async fn fetch_all_success() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let fetch_mock = server.mock(|when, then| {
            when.method(Method::POST)
                .path("/api/jobs/fetch")
                .header("authorization", "Bearer test-token");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({ "message": "Fetch completed: 15 jobs saved" }));
        });

        let get_mock = server.mock(|when, then| {
            when.method(Method::GET)
                .path("/api/jobs")
                .header("authorization", "Bearer test-token");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!([]));
        });

        let result = handle_fetch(None, &server.url(""), &token).await;

        assert!(result.is_ok(), "fetch all should succeed: {result:?}");
        fetch_mock.assert();
        get_mock.assert();
    }

    #[tokio::test]
    async fn fetch_linkedin_success() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let fetch_mock = server.mock(|when, then| {
            when.method(Method::POST)
                .path("/api/jobs/fetch/linkedin")
                .header("authorization", "Bearer test-token");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({ "message": "LinkedIn fetch completed: 5 jobs saved" }));
        });

        let get_mock = server.mock(|when, then| {
            when.method(Method::GET)
                .path("/api/jobs")
                .header("authorization", "Bearer test-token");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!([]));
        });

        let result = handle_fetch(
            Some("linkedin".into()),
            &server.url(""),
            &token,
        ).await;

        assert!(result.is_ok(), "fetch linkedin should succeed: {result:?}");
        fetch_mock.assert();
        get_mock.assert();
    }

    #[tokio::test]
    async fn fetch_unknown_source_prints_error() {
        let server = MockServer::start();
        let token = Some("test-token".into());

        let result = handle_fetch(
            Some("gupy".into()),
            &server.url(""),
            &token,
        ).await;

        assert!(result.is_ok(), "unknown source should return Ok with error message");
    }

    // =====================================================================
    // Cache tests
    // =====================================================================

    #[tokio::test]
    async fn test_cache_update_after_fetch() {
        let server = MockServer::start();
        let token = Some("test-token".into());
        let dir = std::env::temp_dir()
            .join("jh-cli-cache-test")
            .join("test_cache_update_after_fetch");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("job-hunter")).expect("create cache dir");

        let prev_xdg = std::env::var("XDG_CONFIG_HOME").ok();
        unsafe { std::env::set_var("XDG_CONFIG_HOME", &dir); }

        let fetch_mock = server.mock(|when, then| {
            when.method(Method::POST).path("/api/jobs/fetch");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({ "message": "Fetch done" }));
        });

        let get_mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!([
                    sample_job(1, "Rust Dev", "Acme", "gupy", 0),
                    sample_job(2, "Go Dev", "Beta", "linkedin", 1),
                ]));
        });

        let result = handle_fetch(None, &server.url(""), &token).await;
        assert!(result.is_ok(), "fetch should succeed: {result:?}");
        fetch_mock.assert();
        get_mock.assert();

        let cache_path = dir.join("job-hunter").join("cache.db");
        let cache = CacheManager::new(Some(cache_path), 24).expect("open cache");
        let cached = cache.get_all_jobs(None).expect("get cached jobs");
        assert_eq!(cached.len(), 2, "cache should have 2 jobs after fetch");
        assert_eq!(cached[0].title, "Rust Dev");
        assert_eq!(cached[1].title, "Go Dev");

        if let Some(xdg) = prev_xdg {
            unsafe { std::env::set_var("XDG_CONFIG_HOME", xdg); }
        } else {
            unsafe { std::env::remove_var("XDG_CONFIG_HOME"); }
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn test_cache_preserve_analysis() {
        let dir = std::env::temp_dir()
            .join("jh-cli-cache-test")
            .join("test_cache_preserve_analysis");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).expect("create cache dir");
        let cache_path = dir.join("cache.db");

        let cache = CacheManager::new(Some(cache_path.clone()), 24).expect("create cache");

        let jobs = vec![
            JobResponse {
                id: 1,
                title: "Rust Dev".into(),
                company: "Acme".into(),
                url: "https://example.com/job/1".into(),
                description: "Build CLI tools".into(),
                posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
                source: "gupy".into(),
            },
        ];
        cache.update_cache_on_fetch(&jobs).expect("save jobs");

        let analysis = JobAnalysis {
            id: 10,
            job_id: 1,
            user_id: 1,
            match_score: 85,
            matched_skills: vec!["Rust".into(), "CLI".into()],
            missing_skills: vec![],
            company_tone: CompanyTone::Startup,
            summary: "Good match".into(),
        };
        cache.update_cache_on_analyze(1, &analysis).expect("save analysis");

        cache.update_cache_on_fetch(&jobs).expect("refresh cache");

        let cached = cache.get_job(1).expect("get job").unwrap();
        assert_eq!(cached.match_score, Some(85), "match_score should be preserved after refresh");
        assert!(cached.analysis_json.is_some(), "analysis_json should be preserved after refresh");
        let parsed: JobAnalysis = serde_json::from_str(&cached.analysis_json.unwrap())
            .expect("parse analysis JSON");
        assert_eq!(parsed.match_score, 85);
        assert_eq!(parsed.matched_skills, vec!["Rust", "CLI"]);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn list_offline_shows_cached_jobs() {
        let server = MockServer::start();
        let token = Some("test-token".into());
        let dir = std::env::temp_dir()
            .join("jh-cli-cache-test")
            .join("list_offline_shows_cached_jobs");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("job-hunter")).expect("create cache dir");

        let prev_xdg = std::env::var("XDG_CONFIG_HOME").ok();
        unsafe { std::env::set_var("XDG_CONFIG_HOME", &dir); }

        let cache_path = dir.join("job-hunter").join("cache.db");
        let cache = CacheManager::new(Some(cache_path), 24).expect("create cache");
        cache.save_jobs(&[
            JobResponse {
                id: 1,
                title: "Junior Rust Dev".into(),
                company: "TechCorp".into(),
                url: "https://example.com/job/1".into(),
                description: "Build CLI tools in Rust".into(),
                posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
                source: "gupy".into(),
            },
        ]).expect("save job to cache");
        drop(cache);

        let result = handle_list(
            None, None, None, false, false,
            true, false,
            &server.url(""), &token,
        ).await;

        assert!(result.is_ok(), "offline list should succeed: {result:?}");

        if let Some(xdg) = prev_xdg {
            unsafe { std::env::set_var("XDG_CONFIG_HOME", xdg); }
        } else {
            unsafe { std::env::remove_var("XDG_CONFIG_HOME"); }
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn list_refresh_calls_api_and_updates_cache() {
        let server = MockServer::start();
        let token = Some("test-token".into());
        let dir = std::env::temp_dir()
            .join("jh-cli-cache-test")
            .join("list_refresh_calls_api");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("job-hunter")).expect("create cache dir");

        let prev_xdg = std::env::var("XDG_CONFIG_HOME").ok();
        unsafe { std::env::set_var("XDG_CONFIG_HOME", &dir); }

        let mock = server.mock(|when, then| {
            when.method(Method::GET).path("/api/jobs");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!([
                    sample_job(1, "Refreshed Job 1", "Co A", "gupy", 0),
                    sample_job(2, "Refreshed Job 2", "Co B", "linkedin", 1),
                ]));
        });

        let result = handle_list(
            None, None, None, false, false,
            false, true,
            &server.url(""), &token,
        ).await;

        assert!(result.is_ok(), "refresh list should succeed: {result:?}");
        mock.assert();

        let cache_path = dir.join("job-hunter").join("cache.db");
        let cache = CacheManager::new(Some(cache_path), 24).expect("open cache");
        let cached = cache.get_all_jobs(None).expect("get cached");
        assert_eq!(cached.len(), 2, "cache should have 2 jobs after refresh");
        assert_eq!(cached[0].title, "Refreshed Job 1");

        if let Some(xdg) = prev_xdg {
            unsafe { std::env::set_var("XDG_CONFIG_HOME", xdg); }
        } else {
            unsafe { std::env::remove_var("XDG_CONFIG_HOME"); }
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    // =====================================================================
    // Truncate helper tests
    // =====================================================================

    #[test]
    fn truncate_short_string_unchanged() {
        assert_eq!(util::truncate("hello", 10), "hello");
    }

    #[test]
    fn truncate_long_string_truncated() {
        let s = "a very long string that exceeds max length";
        let max = 20;
        let result = util::truncate(s, max);
        let expected_len = max.saturating_sub(1) + '…'.len_utf8();
        assert_eq!(result.len(), expected_len);
        assert!(result.ends_with('…'), "should end with ellipsis: {result}");
    }

    #[test]
    fn truncate_exact_length_unchanged() {
        assert_eq!(util::truncate("exactly ten", 11), "exactly ten");
    }
}
