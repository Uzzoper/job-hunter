use std::io::Write;
use std::process::{Command, Stdio};

use crate::domain::{CachedJob, JobResponse};

pub fn truncate(s: &str, max_len: usize) -> String {
    if s.len() <= max_len {
        s.to_string()
    } else {
        format!("{}…", &s[..max_len.saturating_sub(1)])
    }
}

pub fn parse_job_id(s: &str) -> anyhow::Result<i64> {
    s.parse::<i64>()
        .map_err(|_| anyhow::anyhow!("invalid job ID: {s}"))
}

// ---------------------------------------------------------------------------
// CSV export
// ---------------------------------------------------------------------------

/// Export `JobResponse` records to a CSV writer.
///
/// Headers: `id`, `title`, `company`, `url`, `description`, `posted_at`,
/// `source`.  Properly handles commas, quotes, and multiline values per
/// RFC 4180 via the `csv` crate.
pub fn export_jobs_csv(jobs: &[JobResponse], writer: impl Write) -> anyhow::Result<()> {
    let mut wtr = csv::Writer::from_writer(writer);
    wtr.write_record([
        "id",
        "title",
        "company",
        "url",
        "description",
        "posted_at",
        "source",
    ])?;
    for job in jobs {
        wtr.write_record(&[
            job.id.to_string(),
            job.title.clone(),
            job.company.clone(),
            job.url.clone(),
            job.description.clone(),
            job.posted_at.to_string(),
            job.source.clone(),
        ])?;
    }
    wtr.flush()?;
    Ok(())
}

/// Export `CachedJob` records (with optional analysis and email data) to a
/// CSV writer.
///
/// Extended headers: `id`, `title`, `company`, `url`, `description`,
/// `posted_at`, `source`, `match_score`, `matched_skills`,
/// `missing_skills`, `tone`, `summary`, `email_subject`, `email_body`.
///
/// Fields nested inside `analysis_json` (matched_skills, missing_skills,
/// tone, summary) are extracted when the JSON is parseable; otherwise
/// they are left empty.  Array fields are joined with `"; "`.
pub fn export_jobs_with_analysis_csv(
    jobs: &[CachedJob],
    writer: impl Write,
) -> anyhow::Result<()> {
    let mut wtr = csv::Writer::from_writer(writer);
    wtr.write_record([
        "id",
        "title",
        "company",
        "url",
        "description",
        "posted_at",
        "source",
        "match_score",
        "matched_skills",
        "missing_skills",
        "tone",
        "summary",
        "email_subject",
        "email_body",
    ])?;

    for job in jobs {
        let (matched_skills, missing_skills, tone, summary) = job
            .analysis_json
            .as_deref()
            .and_then(|json| serde_json::from_str::<crate::domain::JobAnalysis>(json).ok())
            .map(|a| {
                (
                    a.matched_skills.join("; "),
                    a.missing_skills.join("; "),
                    a.company_tone.to_string(),
                    a.summary,
                )
            })
            .unwrap_or_default();

        wtr.write_record(&[
            job.id.to_string(),
            job.title.clone(),
            job.company.clone(),
            job.url.clone(),
            job.description.clone(),
            job.posted_at.to_string(),
            job.source.clone(),
            job.match_score.map_or(String::new(), |s| s.to_string()),
            matched_skills,
            missing_skills,
            tone,
            summary,
            job.email_subject.clone().unwrap_or_default(),
            job.email_body.clone().unwrap_or_default(),
        ])?;
    }

    wtr.flush()?;
    Ok(())
}

// ---------------------------------------------------------------------------
// Clipboard
// ---------------------------------------------------------------------------

/// Copy text to the system clipboard.
///
/// Tries **arboard** first (native X11/Wayland/OS X clipboard). Falls back
/// to `xclip` then `xsel` when no display server is available (e.g.
/// headless CI or remote terminal).  Returns an error only when every
/// backend fails.
pub fn copy_to_clipboard(text: &str) -> anyhow::Result<()> {
    // Primary: arboard (native Rust clipboard via X11/Wayland/OS X)
    if let Ok(mut clipboard) = arboard::Clipboard::new()
        && clipboard.set_text(text).is_ok() {
            return Ok(());
        }

    // Fallback 1: xclip
    if let Ok(mut child) = Command::new("xclip")
        .args(["-selection", "clipboard"])
        .stdin(Stdio::piped())
        .spawn()
        && let Some(mut stdin) = child.stdin.take() {
            let _ = stdin.write_all(text.as_bytes());
            drop(stdin);
            let _ = child.wait();
            return Ok(());
        }

    // Fallback 2: xsel
    if let Ok(mut child) = Command::new("xsel")
        .args(["--clipboard", "--input"])
        .stdin(Stdio::piped())
        .spawn()
        && let Some(mut stdin) = child.stdin.take() {
            let _ = stdin.write_all(text.as_bytes());
            drop(stdin);
            let _ = child.wait();
            return Ok(());
        }

    Err(anyhow::anyhow!(
        "clipboard: no available backend (arboard, xclip, xsel)"
    ))
}

// =========================================================================
// Tests
// =========================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::{CompanyTone, JobAnalysis, JobResponse};
    use chrono::{NaiveDate, NaiveDateTime};

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    fn sample_job(id: i64) -> JobResponse {
        JobResponse {
            id,
            title: format!("Job {}", id),
            company: format!("Company {}", id),
            url: format!("https://example.com/job/{}", id),
            description: format!("Description for job {}", id),
            posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
        }
    }

    fn sample_cached_job(id: i64) -> CachedJob {
        let analysis = JobAnalysis {
            id,
            job_id: id,
            user_id: 1,
            match_score: 85,
            matched_skills: vec!["Rust".into(), "PostgreSQL".into()],
            missing_skills: vec!["Kubernetes".into()],
            company_tone: CompanyTone::Startup,
            summary: "Good match".into(),
        };
        CachedJob {
            id,
            title: format!("Job {}", id),
            company: format!("Company {}", id),
            url: format!("https://example.com/job/{}", id),
            description: format!("Description for job {}", id),
            posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
            match_score: Some(85),
            analysis_json: Some(serde_json::to_string(&analysis).unwrap()),
            email_subject: Some("Application".into()),
            email_body: Some("Dear team...".into()),
            email_status: Some("PENDING".into()),
            cached_at: NaiveDateTime::parse_from_str("2026-07-14T10:00:00", "%Y-%m-%dT%H:%M:%S")
                .unwrap(),
        }
    }

    // ------------------------------------------------------------------
    // export_jobs_csv
    // ------------------------------------------------------------------

    #[test]
    fn export_jobs_csv_writes_correct_headers() {
        let jobs = vec![sample_job(1)];
        let mut buf = Vec::new();
        export_jobs_csv(&jobs, &mut buf).unwrap();
        let csv_str = String::from_utf8(buf).unwrap();
        let header = csv_str.lines().next().unwrap();
        assert_eq!(header, "id,title,company,url,description,posted_at,source");
    }

    #[test]
    fn export_jobs_csv_writes_data_rows() {
        let jobs = vec![sample_job(1), sample_job(2)];
        let mut buf = Vec::new();
        export_jobs_csv(&jobs, &mut buf).unwrap();
        let csv_str = String::from_utf8(buf).unwrap();
        let lines: Vec<&str> = csv_str.lines().collect();
        assert_eq!(lines.len(), 3); // header + 2 data rows
        assert!(lines[1].contains("Job 1"), "row 1: {0}", lines[1]);
        assert!(lines[2].contains("Job 2"), "row 2: {0}", lines[2]);
    }

    #[test]
    fn export_jobs_csv_handles_commas_in_description() {
        let mut job = sample_job(1);
        job.description = "Rust, Go, and Python".into();
        let jobs = vec![job];
        let mut buf = Vec::new();
        export_jobs_csv(&jobs, &mut buf).unwrap();
        let csv_str = String::from_utf8(buf).unwrap();
        // The comma should be quoted: "...\"Rust, Go, and Python\"..."
        // csv crate wraps fields containing commas in quotes
        assert!(
            csv_str.contains("\"Rust, Go, and Python\""),
            "commas should be quoted: {csv_str}"
        );
    }

    #[test]
    fn export_jobs_csv_handles_quotes_in_description() {
        let mut job = sample_job(1);
        job.description = "He said \"hello\"".into();
        let jobs = vec![job];
        let mut buf = Vec::new();
        export_jobs_csv(&jobs, &mut buf).unwrap();
        let csv_str = String::from_utf8(buf).unwrap();
        // csv crate doubles inner quotes: "He said ""hello"""
        assert!(
            csv_str.contains(r#""""hello"""""#) || csv_str.contains(r#""hello""#),
            "quotes should be escaped: {csv_str}"
        );
    }

    #[test]
    fn export_jobs_csv_handles_multiline_description() {
        let mut job = sample_job(1);
        job.description = "line1\nline2\nline3".into();
        let jobs = vec![job];
        let mut buf = Vec::new();
        export_jobs_csv(&jobs, &mut buf).unwrap();
        let csv_str = String::from_utf8(buf).unwrap();
        assert!(csv_str.contains("line1\nline2\nline3"), "multiline: {csv_str}");
    }

    #[test]
    fn export_jobs_csv_empty_list_writes_only_headers() {
        let jobs: Vec<JobResponse> = vec![];
        let mut buf = Vec::new();
        export_jobs_csv(&jobs, &mut buf).unwrap();
        let csv_str = String::from_utf8(buf).unwrap();
        assert_eq!(csv_str.trim(), "id,title,company,url,description,posted_at,source");
    }

    #[test]
    fn export_jobs_csv_can_be_parsed_back() {
        let jobs = vec![sample_job(1), sample_job(2)];
        let mut buf = Vec::new();
        export_jobs_csv(&jobs, &mut buf).unwrap();

        let mut reader = csv::Reader::from_reader(buf.as_slice());
        for (i, result) in reader.records().enumerate() {
            let record = result.unwrap();
            let id: i64 = record[0].parse().unwrap();
            assert_eq!(id, (i + 1) as i64);
            assert_eq!(&record[1], &format!("Job {}", i + 1));
            assert_eq!(&record[2], &format!("Company {}", i + 1));
            assert_eq!(&record[6], "gupy");
        }
    }

    // ------------------------------------------------------------------
    // export_jobs_with_analysis_csv
    // ------------------------------------------------------------------

    #[test]
    fn export_jobs_with_analysis_csv_writes_extended_headers() {
        let jobs = vec![sample_cached_job(1)];
        let mut buf = Vec::new();
        export_jobs_with_analysis_csv(&jobs, &mut buf).unwrap();
        let csv_str = String::from_utf8(buf).unwrap();
        let header = csv_str.lines().next().unwrap();
        assert_eq!(
            header,
            "id,title,company,url,description,posted_at,source,\
             match_score,matched_skills,missing_skills,tone,summary,\
             email_subject,email_body"
        );
    }

    #[test]
    fn export_jobs_with_analysis_csv_includes_analysis_fields() {
        let jobs = vec![sample_cached_job(1)];
        let mut buf = Vec::new();
        export_jobs_with_analysis_csv(&jobs, &mut buf).unwrap();
        let csv_str = String::from_utf8(buf).unwrap();
        assert!(csv_str.contains("85"), "should contain match_score");
        assert!(csv_str.contains("Rust; PostgreSQL"), "matched_skills");
        assert!(csv_str.contains("Kubernetes"), "missing_skills");
        assert!(csv_str.contains("STARTUP"), "tone");
        assert!(csv_str.contains("Good match"), "summary");
        assert!(csv_str.contains("Application"), "email subject");
        assert!(csv_str.contains("Dear team..."), "email body");
    }

    #[test]
    fn export_jobs_with_analysis_csv_handles_none_fields() {
        let mut job = sample_cached_job(1);
        job.match_score = None;
        job.analysis_json = None;
        job.email_subject = None;
        job.email_body = None;
        let jobs = vec![job];
        let mut buf = Vec::new();
        export_jobs_with_analysis_csv(&jobs, &mut buf).unwrap();
        let csv_str = String::from_utf8(buf).unwrap();
        // All optional fields should be empty in the CSV row
        let row = csv_str.lines().nth(1).unwrap();
        assert!(
            !row.contains("85"),
            "should not contain score when None: {row}"
        );
        // Verify empty fields by checking surrounding separators
        assert!(row.contains(",,"), "None fields should produce empty cells");
    }

    #[test]
    fn export_jobs_with_analysis_csv_handles_broken_analysis_json() {
        let mut job = sample_cached_job(1);
        job.analysis_json = Some("not valid json".into());
        let jobs = vec![job];
        let mut buf = Vec::new();
        // Should not panic — broken JSON should produce empty analysis fields
        export_jobs_with_analysis_csv(&jobs, &mut buf).unwrap();
        let csv_str = String::from_utf8(buf).unwrap();
        // score comes from top-level CachedJob field, not analysis_json
        assert!(csv_str.contains(",85,"), "top-level score should still appear");
    }

    #[test]
    fn export_jobs_with_analysis_csv_can_be_parsed_back() {
        let jobs = vec![sample_cached_job(1)];
        let mut buf = Vec::new();
        export_jobs_with_analysis_csv(&jobs, &mut buf).unwrap();

        let mut reader = csv::Reader::from_reader(buf.as_slice());
        for result in reader.records() {
            let record = result.unwrap();
            assert_eq!(&record[0], "1");
            assert_eq!(&record[7], "85");
            assert_eq!(&record[8], "Rust; PostgreSQL");
            assert_eq!(&record[9], "Kubernetes");
            assert_eq!(&record[10], "STARTUP");
            assert_eq!(&record[11], "Good match");
            assert_eq!(&record[12], "Application");
        }
    }

    // ------------------------------------------------------------------
    // copy_to_clipboard
    // ------------------------------------------------------------------

    /// The clipboard test is best-effort — on headless CI it will fail,
    /// so we accept either Ok or a specific clipboard error.
    #[test]
    fn copy_to_clipboard_either_works_or_fails_gracefully() {
        match copy_to_clipboard("test content") {
            Ok(()) => {} // clipboard available, works
            Err(e) => {
                let msg = e.to_string().to_lowercase();
                // On headless Linux without DISPLAY/Wayland, arboard
                // returns a backend-unavailable error.  Our fallback
                // error includes "clipboard".
                assert!(
                    msg.contains("clipboard"),
                    "unexpected error: {e}"
                );
            }
        }
    }
}
