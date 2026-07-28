use crate::domain::{CachedJob, EmailDraftResponse, JobAnalysis, JobResponse};
use chrono::{NaiveDate, NaiveDateTime, Utc};
use rusqlite::{params, Connection};
use std::collections::HashMap;
use std::path::PathBuf;

use crate::error::Result;

/// Manage a local SQLite cache for job listings, analyses, and emails.
///
/// The database is stored at `~/.config/job-hunter/cache.db` and uses
/// WAL mode for safe concurrent reads. Schema is auto-created on first
/// use.
pub struct CacheManager {
    conn: Connection,
    path: Option<PathBuf>,
    ttl_hours: u64,
}

/// Default cache database path: `~/.config/job-hunter/cache.db`
pub fn default_cache_path() -> Option<PathBuf> {
    dirs_next::config_dir().map(|d| d.join("job-hunter").join("cache.db"))
}

const CREATE_TABLE_SQL: &str = "CREATE TABLE IF NOT EXISTS cached_jobs (
    id INTEGER PRIMARY KEY,
    title TEXT NOT NULL,
    company TEXT NOT NULL,
    url TEXT NOT NULL UNIQUE,
    description TEXT NOT NULL,
    posted_at TEXT NOT NULL,
    source TEXT NOT NULL,
    match_score INTEGER,
    analysis_json TEXT,
    email_subject TEXT,
    email_body TEXT,
    email_status TEXT,
    cached_at TEXT NOT NULL DEFAULT (datetime('now'))
);";

impl CacheManager {
    /// Open (or create) the cache database at the given path.
    ///
    /// If `path` is `None`, uses the default location
    /// (`~/.config/job-hunter/cache.db`). Creates the directory and
    /// the schema tables if they do not exist. Enables WAL mode for
    /// concurrent access safety.
    pub fn new(path: Option<PathBuf>, ttl_hours: u64) -> Result<Self> {
        let db_path = match path {
            Some(p) => p,
            None => default_cache_path().ok_or_else(|| {
                crate::error::CliError::Cache("cannot determine cache directory".into())
            })?,
        };

        if let Some(parent) = db_path.parent() {
            std::fs::create_dir_all(parent)?;
        }

        let conn = Connection::open(&db_path)?;
        conn.execute_batch("PRAGMA journal_mode=WAL;")?;
        conn.execute_batch(CREATE_TABLE_SQL)?;

        Ok(Self {
            conn,
            path: Some(db_path),
            ttl_hours,
        })
    }

    /// Open an in-memory database (for testing).
    pub fn new_in_memory(ttl_hours: u64) -> Result<Self> {
        let conn = Connection::open_in_memory()?;
        // WAL pragma is silently ignored for in-memory databases
        conn.execute_batch("PRAGMA journal_mode=WAL;")?;
        conn.execute_batch(CREATE_TABLE_SQL)?;
        Ok(Self {
            conn,
            path: None,
            ttl_hours,
        })
    }

    /// Upsert all jobs into the cache.
    ///
    /// Uses a single transaction for atomicity. Duplicates are
    /// resolved by URL (the `UNIQUE` constraint).
    pub fn save_jobs(&self, jobs: &[JobResponse]) -> Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        {
            let mut stmt = tx.prepare(
                "INSERT OR REPLACE INTO cached_jobs (id, title, company, url, description, posted_at, source)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            )?;
            for job in jobs {
                stmt.execute(params![
                    job.id,
                    &job.title,
                    &job.company,
                    &job.url,
                    &job.description,
                    job.posted_at.format("%Y-%m-%d").to_string(),
                    &job.source,
                ])?;
            }
        }
        tx.commit()?;
        Ok(())
    }

    /// Load all cached jobs, optionally filtered by keyword.
    ///
    /// When `keyword` is `Some`, filters by title, company, or
    /// description (case-insensitive LIKE match).
    pub fn get_all_jobs(&self, keyword: Option<&str>) -> Result<Vec<CachedJob>> {
        let mut sql = String::from(
            "SELECT id, title, company, url, description, posted_at, source,
                    match_score, analysis_json, email_subject, email_body, email_status, cached_at
             FROM cached_jobs",
        );
        let mut param_values: Vec<String> = Vec::new();

        if let Some(kw) = keyword {
            sql.push_str(" WHERE title LIKE ?1 OR company LIKE ?1 OR description LIKE ?1");
            param_values.push(format!("%{}%", kw));
        }

        sql.push_str(" ORDER BY id");

        let mut stmt = self.conn.prepare(&sql)?;
        let params_refs: Vec<&dyn rusqlite::types::ToSql> =
            param_values.iter().map(|s| s as &dyn rusqlite::types::ToSql).collect();

        let rows = stmt.query_map(params_refs.as_slice(), Self::row_to_cached_job)?;
        let mut jobs = Vec::new();
        for row in rows {
            jobs.push(row?);
        }
        Ok(jobs)
    }

    /// Get a single cached job by its backend ID.
    pub fn get_job(&self, id: i64) -> Result<Option<CachedJob>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, title, company, url, description, posted_at, source,
                    match_score, analysis_json, email_subject, email_body, email_status, cached_at
             FROM cached_jobs WHERE id = ?1",
        )?;

        let mut rows = stmt.query_map(params![id], Self::row_to_cached_job)?;
        match rows.next() {
            Some(Ok(job)) => Ok(Some(job)),
            Some(Err(e)) => Err(e.into()),
            None => Ok(None),
        }
    }

    /// Get match scores for all cached jobs.
    ///
    /// Returns a map of job ID → match score. Scores that are `NULL`
    /// in the database (not yet analyzed) are returned as `None`.
    pub fn get_all_scores(&self) -> Result<HashMap<i64, Option<i32>>> {
        let mut stmt = self
            .conn
            .prepare("SELECT id, match_score FROM cached_jobs")?;

        let rows = stmt.query_map([], |row| {
            let id: i64 = row.get(0)?;
            let score: Option<i32> = row.get(1)?;
            Ok((id, score))
        })?;

        let mut scores = HashMap::new();
        for row in rows {
            let (id, score) = row?;
            scores.insert(id, score);
        }
        Ok(scores)
    }

    /// Save analysis results for a cached job.
    ///
    /// Updates the `match_score` and `analysis_json` columns for the
    /// given job. The analysis JSON is a serialized `JobAnalysis`.
    pub fn save_analysis(&self, job_id: i64, analysis: &JobAnalysis) -> Result<()> {
        let analysis_json = serde_json::to_string(analysis)
            .map_err(|e| crate::error::CliError::Cache(format!("serialize analysis: {e}")))?;

        self.conn.execute(
            "UPDATE cached_jobs SET match_score = ?1, analysis_json = ?2 WHERE id = ?3",
            params![analysis.match_score, analysis_json, job_id],
        )?;
        Ok(())
    }

    /// Save email draft data for a cached job.
    ///
    /// Updates the `email_subject`, `email_body`, and `email_status`
    /// columns for the given job.
    pub fn save_email(&self, job_id: i64, email: &EmailDraftResponse) -> Result<()> {
        let status_str = serde_json::to_string(&email.status)
            .map_err(|e| crate::error::CliError::Cache(format!("serialize email status: {e}")))?;
        let status_str = status_str.trim_matches('"').to_string();

        self.conn.execute(
            "UPDATE cached_jobs SET email_subject = ?1, email_body = ?2, email_status = ?3 WHERE id = ?4",
            params![email.subject, email.body, status_str, job_id],
        )?;
        Ok(())
    }

    /// Check whether the cache is stale (older than the TTL).
    ///
    /// Returns `true` if there are no cached jobs, or if the most
    /// recent `cached_at` timestamp is older than `ttl_hours`.
    pub fn is_stale(&self) -> Result<bool> {
        let mut stmt = self
            .conn
            .prepare("SELECT MAX(cached_at) FROM cached_jobs")?;

        let max_cached_at: Option<String> = stmt.query_row([], |row| row.get(0))?;

        match max_cached_at {
            None => Ok(true),
            Some(ts) => {
                let cached_time = NaiveDateTime::parse_from_str(&ts, "%Y-%m-%d %H:%M:%S")
                    .map_err(|e| {
                        crate::error::CliError::Cache(format!("parse cached_at: {e}"))
                    })?;
                let now = Utc::now().naive_utc();
                let elapsed = now - cached_time;
                let elapsed_hours = elapsed.num_hours() as u64;
                Ok(elapsed_hours >= self.ttl_hours)
            }
        }
    }

    pub fn clear(&self) -> Result<()> {
        self.conn.execute_batch("DELETE FROM cached_jobs")?;
        Ok(())
    }

    /// Run `PRAGMA integrity_check` and return whether the database is
    /// intact.
    ///
    /// Returns `true` if the integrity check passes (returns "ok").
    /// Returns `false` if corruption is detected.
    pub fn integrity_check(&self) -> Result<bool> {
        let mut stmt = self.conn.prepare("PRAGMA integrity_check")?;
        let result: String = stmt.query_row([], |row| row.get(0))?;
        Ok(result == "ok")
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    fn row_to_cached_job(row: &rusqlite::Row) -> rusqlite::Result<CachedJob> {
        let posted_at_str: String = row.get("posted_at")?;
        let cached_at_str: String = row.get("cached_at")?;

        let posted_at = NaiveDate::parse_from_str(&posted_at_str, "%Y-%m-%d").map_err(|e| {
            rusqlite::Error::ToSqlConversionFailure(Box::new(e))
        })?;

        let cached_at =
            NaiveDateTime::parse_from_str(&cached_at_str, "%Y-%m-%d %H:%M:%S").map_err(|e| {
                rusqlite::Error::ToSqlConversionFailure(Box::new(e))
            })?;

        Ok(CachedJob {
            id: row.get("id")?,
            title: row.get("title")?,
            company: row.get("company")?,
            url: row.get("url")?,
            description: row.get("description")?,
            posted_at,
            source: row.get("source")?,
            match_score: row.get("match_score")?,
            analysis_json: row.get("analysis_json")?,
            email_subject: row.get("email_subject")?,
            email_body: row.get("email_body")?,
            email_status: row.get("email_status")?,
            cached_at,
        })
    }

    /// Get the path to the cache database file, if available.
    pub fn path(&self) -> Option<&PathBuf> {
        self.path.as_ref()
    }

    /// Update cache after fetching jobs from the API.
    ///
    /// Uses `INSERT ... ON CONFLICT` to preserve existing analysis and
    /// email data. When a job with the same `id` already exists, only
    /// the fetched fields (title, company, url, description, source)
    /// are updated. The `match_score`, `analysis_json`, and email
    /// columns are left intact.
    ///
    /// When a job with the same `url` but a different `id` already
    /// exists (URL dedup), the conflict on `url` is resolved by
    /// replacing the old row entirely (new fetch → new analysis).
    pub fn update_cache_on_fetch(&self, jobs: &[JobResponse]) -> Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        {
            // Step 1: Delete rows that conflict on URL (different ID)
            // This handles URL-based dedup: a re-fetched job with the same URL
            // but different ID replaces the old entry completely.
            {
                let mut del_stmt = tx.prepare(
                    "DELETE FROM cached_jobs WHERE url = ?1 AND id != ?2",
                )?;
                for job in jobs {
                    del_stmt.execute(params![&job.url, job.id])?;
                }
            }

            // Step 2: Upsert on ID — preserve analysis/email columns
            let mut stmt = tx.prepare(
                "INSERT INTO cached_jobs (id, title, company, url, description, posted_at, source, cached_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, datetime('now'))
                 ON CONFLICT(id) DO UPDATE SET
                    title = excluded.title,
                    company = excluded.company,
                    url = excluded.url,
                    description = excluded.description,
                    posted_at = excluded.posted_at,
                    source = excluded.source,
                    cached_at = datetime('now')",
            )?;
            for job in jobs {
                stmt.execute(params![
                    job.id,
                    &job.title,
                    &job.company,
                    &job.url,
                    &job.description,
                    job.posted_at.format("%Y-%m-%d").to_string(),
                    &job.source,
                ])?;
            }
        }
        tx.commit()?;
        Ok(())
    }

    /// Update cache after analyzing a job.
    ///
    /// Updates the `match_score` and `analysis_json` columns for the given job.
    /// Does NOT update `cached_at` — analysis data is preserved across cache refreshes.
    pub fn update_cache_on_analyze(&self, job_id: i64, analysis: &JobAnalysis) -> Result<()> {
        let analysis_json = serde_json::to_string(analysis)
            .map_err(|e| crate::error::CliError::Cache(format!("serialize analysis: {e}")))?;

        self.conn.execute(
            "UPDATE cached_jobs SET match_score = ?1, analysis_json = ?2 WHERE id = ?3",
            params![analysis.match_score, analysis_json, job_id],
        )?;
        Ok(())
    }

    /// Update cache after generating an email draft.
    ///
    /// Updates the `email_subject`, `email_body`, and `email_status` columns.
    /// Does NOT update `cached_at` — email data is preserved across cache refreshes.
    pub fn update_cache_on_email(&self, job_id: i64, email: &EmailDraftResponse) -> Result<()> {
        let status_str = serde_json::to_string(&email.status)
            .map_err(|e| crate::error::CliError::Cache(format!("serialize email status: {e}")))?;
        let status_str = status_str.trim_matches('"').to_string();

        self.conn.execute(
            "UPDATE cached_jobs SET email_subject = ?1, email_body = ?2, email_status = ?3 WHERE id = ?4",
            params![email.subject, email.body, status_str, job_id],
        )?;
        Ok(())
    }

    /// Check cache integrity and auto-rebuild if corrupted.
    ///
    /// Runs `PRAGMA integrity_check`. If corruption is detected, clears
    /// the database and recreates the schema. Returns a warning message
    /// if rebuild occurred, `None` if cache was healthy.
    pub fn ensure_integrity(&self) -> Result<Option<String>> {
        if self.integrity_check()? {
            return Ok(None);
        }

        // Corruption detected — rebuild
        eprintln!("Warning: Cache database corruption detected. Rebuilding cache...");
        self.clear()?;
        self.conn.execute_batch(CREATE_TABLE_SQL)?;
        Ok(Some("Cache was corrupted and has been rebuilt".to_string()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::{CompanyTone, EmailStatus};
    use chrono::NaiveDate;

    fn create_manager() -> CacheManager {
        CacheManager::new_in_memory(24).expect("create in-memory cache")
    }

    fn sample_jobs() -> Vec<JobResponse> {
        vec![
            JobResponse {
                id: 1,
                title: "Junior Rust Developer".into(),
                company: "Tech Corp".into(),
                url: "https://example.com/job/1".into(),
                description: "Build CLI tools in Rust".into(),
                posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
                source: "gupy".into(),
            },
            JobResponse {
                id: 2,
                title: "Java Backend Engineer".into(),
                company: "IBM".into(),
                url: "https://example.com/job/2".into(),
                description: "Enterprise Java development with Spring".into(),
                posted_at: NaiveDate::from_ymd_opt(2026, 7, 13).unwrap(),
                source: "linkedin".into(),
            },
        ]
    }

    fn sample_analysis() -> JobAnalysis {
        JobAnalysis {
            id: 10,
            job_id: 1,
            user_id: 1,
            match_score: 85,
            matched_skills: vec!["Rust".into(), "CLI".into()],
            missing_skills: vec!["Kubernetes".into()],
            company_tone: CompanyTone::Startup,
            summary: "Strong match for a Rust role".into(),
        }
    }

    fn sample_email() -> EmailDraftResponse {
        EmailDraftResponse {
            id: 100,
            job_id: 1,
            subject: "Application for Junior Rust Developer".into(),
            body: "Dear Tech Corp team...".into(),
            status: EmailStatus::Pending,
            generated_at: NaiveDateTime::parse_from_str("2026-07-14T10:00:00", "%Y-%m-%dT%H:%M:%S")
                .unwrap(),
            sent_at: None,
        }
    }

    // ------------------------------------------------------------------
    // Schema & initialization
    // ------------------------------------------------------------------

    #[test]
    fn new_creates_database_and_schema() {
        let manager = create_manager();
        let jobs = manager.get_all_jobs(None).expect("get all jobs");
        assert!(jobs.is_empty(), "new cache should have no jobs");
    }

    // ------------------------------------------------------------------
    // save_jobs / get_all_jobs / get_job
    // ------------------------------------------------------------------

    #[test]
    fn save_jobs_and_get_all_roundtrip() {
        let manager = create_manager();
        manager.save_jobs(&sample_jobs()).expect("save jobs");
        let jobs = manager.get_all_jobs(None).expect("get all jobs");
        assert_eq!(jobs.len(), 2);
        assert_eq!(jobs[0].title, "Junior Rust Developer");
        assert_eq!(jobs[0].company, "Tech Corp");
        assert_eq!(jobs[0].source, "gupy");
        assert_eq!(jobs[1].title, "Java Backend Engineer");
        assert_eq!(jobs[1].company, "IBM");
    }

    #[test]
    fn save_jobs_empty_list_does_not_fail() {
        let manager = create_manager();
        manager.save_jobs(&[]).expect("save empty jobs");
        let jobs = manager.get_all_jobs(None).expect("get all jobs");
        assert!(jobs.is_empty());
    }

    #[test]
    fn save_jobs_deduplicates_by_url() {
        let manager = create_manager();
        let mut jobs = sample_jobs();
        // Same URL as job 1, different id/title
        jobs.push(JobResponse {
            id: 99,
            title: "Duplicate Rust Dev".into(),
            company: "Tech Corp".into(),
            url: "https://example.com/job/1".into(),
            description: "Same URL".into(),
            posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
        });
        manager.save_jobs(&jobs).expect("save jobs with duplicate");
        let all = manager.get_all_jobs(None).expect("get all jobs");
        assert_eq!(all.len(), 2, "duplicate URL should be replaced, not appended");
        // The duplicate should have replaced the original — id should be 99 now
        let job1 = all.iter().find(|j| j.id == 99);
        assert!(job1.is_some(), "duplicate should have replaced original");
    }

    #[test]
    fn get_job_by_id_returns_correct_job() {
        let manager = create_manager();
        manager.save_jobs(&sample_jobs()).expect("save jobs");
        let job = manager.get_job(2).expect("get job 2");
        assert!(job.is_some());
        assert_eq!(job.unwrap().title, "Java Backend Engineer");
    }

    #[test]
    fn get_job_not_found_returns_none() {
        let manager = create_manager();
        let job = manager.get_job(999).expect("get nonexistent job");
        assert!(job.is_none());
    }

    // ------------------------------------------------------------------
    // get_all_scores
    // ------------------------------------------------------------------

    #[test]
    fn get_all_scores_returns_map_of_all_jobs() {
        let manager = create_manager();
        manager.save_jobs(&sample_jobs()).expect("save jobs");
        // Save analysis to set match_score on job 1
        manager.save_analysis(1, &sample_analysis()).expect("save analysis");

        let scores = manager.get_all_scores().expect("get all scores");
        assert_eq!(scores.len(), 2);
        // Job 1 has a match_score of 85
        assert_eq!(scores.get(&1), Some(&Some(85)));
        // Job 2 has no analysis — score is NULL
        assert_eq!(scores.get(&2), Some(&None));
    }

    #[test]
    fn get_all_scores_empty_when_no_jobs() {
        let manager = create_manager();
        let scores = manager.get_all_scores().expect("get all scores");
        assert!(scores.is_empty(), "expected empty map");
    }

    #[test]
    fn get_all_scores_handles_null_scores() {
        let manager = create_manager();
        // Save a job with NULL match_score (no analysis yet)
        let jobs = vec![JobResponse {
            id: 1,
            title: "Null Score Job".into(),
            company: "Acme".into(),
            url: "https://example.com/null-score".into(),
            description: "Not analyzed yet".into(),
            posted_at: NaiveDate::from_ymd_opt(2026, 7, 15).unwrap(),
            source: "gupy".into(),
        }];
        manager.save_jobs(&jobs).expect("save job");

        let scores = manager.get_all_scores().expect("get all scores");
        assert_eq!(scores.len(), 1);
        assert_eq!(scores.get(&1), Some(&None), "unanalyzed job should have None score");
    }

    // ------------------------------------------------------------------
    // Keyword filter
    // ------------------------------------------------------------------

    #[test]
    fn get_all_jobs_with_keyword_filter() {
        let manager = create_manager();
        manager.save_jobs(&sample_jobs()).expect("save jobs");
        let jobs = manager.get_all_jobs(Some("Rust")).expect("filter by keyword");
        assert_eq!(jobs.len(), 1);
        assert_eq!(jobs[0].title, "Junior Rust Developer");
    }

    #[test]
    fn get_all_jobs_keyword_matches_company() {
        let manager = create_manager();
        manager.save_jobs(&sample_jobs()).expect("save jobs");
        let jobs = manager.get_all_jobs(Some("IBM")).expect("filter by company");
        assert_eq!(jobs.len(), 1);
        assert_eq!(jobs[0].company, "IBM");
    }

    #[test]
    fn get_all_jobs_keyword_no_match_returns_empty() {
        let manager = create_manager();
        manager.save_jobs(&sample_jobs()).expect("save jobs");
        let jobs = manager.get_all_jobs(Some("NonExistent")).expect("filter no match");
        assert!(jobs.is_empty());
    }

    // ------------------------------------------------------------------
    // save_analysis
    // ------------------------------------------------------------------

    #[test]
    fn save_analysis_updates_match_score_and_json() {
        let manager = create_manager();
        manager.save_jobs(&sample_jobs()).expect("save jobs");
        manager.save_analysis(1, &sample_analysis()).expect("save analysis");

        let job = manager.get_job(1).expect("get job").unwrap();
        assert_eq!(job.match_score, Some(85));
        assert!(job.analysis_json.is_some());
        let analysis_json = job.analysis_json.unwrap();
        assert!(analysis_json.contains("\"matchScore\":85"), "analysis JSON should contain score: {analysis_json}");
    }

    #[test]
    fn save_analysis_nonexistent_job_does_not_error() {
        let manager = create_manager();
        // Trying to update a job that doesn't exist — should succeed (0 rows affected)
        let result = manager.save_analysis(999, &sample_analysis());
        assert!(result.is_ok());
    }

    // ------------------------------------------------------------------
    // save_email
    // ------------------------------------------------------------------

    #[test]
    fn save_email_updates_subject_body_and_status() {
        let manager = create_manager();
        manager.save_jobs(&sample_jobs()).expect("save jobs");
        manager.save_email(1, &sample_email()).expect("save email");

        let job = manager.get_job(1).expect("get job").unwrap();
        assert_eq!(job.email_subject, Some("Application for Junior Rust Developer".into()));
        assert_eq!(job.email_body, Some("Dear Tech Corp team...".into()));
        assert_eq!(job.email_status, Some("PENDING".into()));
    }

    // ------------------------------------------------------------------
    // is_stale
    // ------------------------------------------------------------------

    #[test]
    fn is_stale_returns_true_when_no_data() {
        let manager = create_manager();
        assert!(manager.is_stale().expect("is_stale on empty cache"));
    }

    #[test]
    fn is_stale_returns_false_for_fresh_data() {
        let manager = create_manager();
        manager.save_jobs(&sample_jobs()).expect("save jobs");
        // Data was just inserted — should not be stale
        assert!(!manager.is_stale().expect("is_stale with fresh data"));
    }

    #[test]
    fn is_stale_respects_ttl() {
        // TTL of 0 hours — any data is immediately stale
        let manager = CacheManager::new_in_memory(0).expect("create cache with 0 TTL");
        manager.save_jobs(&sample_jobs()).expect("save jobs");
        assert!(manager.is_stale().expect("is_stale with 0 TTL"));
    }

    // ------------------------------------------------------------------
    // clear
    // ------------------------------------------------------------------

    #[test]
    fn clear_removes_all_data() {
        let manager = create_manager();
        manager.save_jobs(&sample_jobs()).expect("save jobs");
        manager.clear().expect("clear cache");
        let jobs = manager.get_all_jobs(None).expect("get all jobs after clear");
        assert!(jobs.is_empty());
        // is_stale should return true after clear
        assert!(manager.is_stale().expect("is_stale after clear"));
    }

    // ------------------------------------------------------------------
    // integrity_check
    // ------------------------------------------------------------------

    #[test]
    fn integrity_check_returns_true_for_clean_db() {
        let manager = create_manager();
        assert!(manager.integrity_check().expect("integrity check"));
    }

    // ------------------------------------------------------------------
    // File-based cache (default path)
    // ------------------------------------------------------------------

    #[test]
    fn new_creates_database_file() {
        let dir = std::env::temp_dir()
            .join("jh-cli-cache-test")
            .join("new_creates_database_file");
        let _ = std::fs::remove_dir_all(&dir);
        let db_path = dir.join("cache.db");

        let manager = CacheManager::new(Some(db_path.clone()), 24).expect("create file cache");
        assert!(db_path.exists(), "database file should exist");
        assert!(manager.path().is_some());

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn save_jobs_and_get_all_roundtrip_file() {
        let dir = std::env::temp_dir()
            .join("jh-cli-cache-test")
            .join("save_jobs_file_roundtrip");
        let _ = std::fs::remove_dir_all(&dir);
        let db_path = dir.join("cache.db");

        let manager = CacheManager::new(Some(db_path.clone()), 24).expect("create file cache");
        manager.save_jobs(&sample_jobs()).expect("save jobs");

        let manager2 = CacheManager::new(Some(db_path), 24).expect("reopen cache");
        let jobs = manager2.get_all_jobs(None).expect("get all jobs");
        assert_eq!(jobs.len(), 2);

        let _ = std::fs::remove_dir_all(&dir);
    }
}
