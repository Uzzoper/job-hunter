//! Shared contact-field validation rules.
//!
//! Mirrors backend limits (see `docs/specs/cli-tui-spec.md`): phone ≤ 30
//! chars; contact-email must be a valid email ≤ 255 chars; URLs ≤ 500 chars.
//! Used by both the TUI profile screen and the batch `profile edit` command.

/// Max allowed phone number length.
pub const PHONE_MAX_LEN: usize = 30;
/// Max allowed contact email length.
pub const CONTACT_EMAIL_MAX_LEN: usize = 255;
/// Max allowed URL length (portfolio, GitHub, LinkedIn).
pub const URL_MAX_LEN: usize = 500;

/// Basic email plausibility check: local@domain with no whitespace.
/// Full RFC validation is left to the backend.
pub fn is_valid_email(s: &str) -> bool {
    match s.split_once('@') {
        Some((local, domain)) => {
            !local.is_empty() && !domain.is_empty() && !s.chars().any(char::is_whitespace)
        }
        None => false,
    }
}

/// Violation messages for a phone value; empty slice means valid.
pub fn phone_errors(value: &str) -> Vec<String> {
    (value.chars().count() > PHONE_MAX_LEN)
        .then(|| format!("phone must be at most {PHONE_MAX_LEN} characters"))
        .into_iter()
        .collect()
}

/// Violation messages for a contact email value; empty slice means valid.
///
/// An empty value is allowed (field not set); format is checked before
/// length so users see the most relevant error first.
pub fn contact_email_errors(value: &str) -> Vec<String> {
    let mut errors = Vec::new();
    if !value.is_empty() && !is_valid_email(value) {
        errors.push("contactEmail must be a valid email address".to_string());
    }
    if value.chars().count() > CONTACT_EMAIL_MAX_LEN {
        errors.push(format!(
            "contactEmail must be at most {CONTACT_EMAIL_MAX_LEN} characters"
        ));
    }
    errors
}

/// Violation messages for a URL value; empty slice means valid.
pub fn url_errors(value: &str) -> Vec<String> {
    (value.chars().count() > URL_MAX_LEN)
        .then(|| format!("URL must be at most {URL_MAX_LEN} characters"))
        .into_iter()
        .collect()
}

/// Validate optional contact values, returning all violation messages.
///
/// `None` (omitted flag) and empty/whitespace values (clear-the-field
/// sentinel) are skipped, matching `apply_contact_field` semantics in the
/// batch edit path. Values are trimmed before checking, since that is what
/// actually gets sent to the backend.
pub fn validate_optional_contact_fields(
    phone: Option<&str>,
    contact_email: Option<&str>,
    portfolio_url: Option<&str>,
    github_url: Option<&str>,
    linkedin_url: Option<&str>,
) -> Vec<String> {
    let mut errors = Vec::new();

    if let Some(v) = phone.map(str::trim).filter(|v| !v.is_empty()) {
        errors.extend(phone_errors(v));
    }
    if let Some(v) = contact_email.map(str::trim).filter(|v| !v.is_empty()) {
        errors.extend(contact_email_errors(v));
    }
    for url in [portfolio_url, github_url, linkedin_url] {
        if let Some(v) = url.map(str::trim).filter(|v| !v.is_empty()) {
            errors.extend(url_errors(v));
        }
    }

    errors
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_valid_email_accepts_local_at_domain() {
        assert!(is_valid_email("juan@example.com"));
    }

    #[test]
    fn is_valid_email_rejects_missing_parts_or_whitespace() {
        assert!(!is_valid_email("not-an-email"));
        assert!(!is_valid_email("@example.com"));
        assert!(!is_valid_email("juan@"));
        assert!(!is_valid_email("ju an@example.com"));
    }

    #[test]
    fn phone_errors_only_when_over_limit() {
        assert!(phone_errors(&"1".repeat(30)).is_empty());
        assert_eq!(
            phone_errors(&"1".repeat(31)),
            vec!["phone must be at most 30 characters"]
        );
    }

    #[test]
    fn contact_email_errors_reports_format_before_length() {
        assert!(contact_email_errors("juan@example.com").is_empty());
        assert!(contact_email_errors("").is_empty());

        let too_long = format!("{}@example.com", "a".repeat(252));
        assert_eq!(
            contact_email_errors(&too_long),
            vec!["contactEmail must be at most 255 characters"]
        );

        // Invalid format only (still short enough).
        assert_eq!(
            contact_email_errors("no at sign"),
            vec!["contactEmail must be a valid email address"]
        );

        // Invalid format AND over limit: format error comes first.
        let both = "a ".repeat(200);
        assert_eq!(
            contact_email_errors(both.trim_end()),
            vec![
                "contactEmail must be a valid email address",
                "contactEmail must be at most 255 characters",
            ]
        );
    }

    #[test]
    fn url_errors_only_when_over_limit() {
        assert!(url_errors(&format!("https://example.com/{}", "a".repeat(480))).is_empty());
        assert_eq!(
            url_errors(&format!("https://example.com/{}", "a".repeat(481))),
            vec!["URL must be at most 500 characters"]
        );
    }

    #[test]
    fn validate_optional_contact_fields_skips_none_and_empty() {
        let errors = validate_optional_contact_fields(
            None,
            Some("   "),
            Some(""),
            None,
            Some("https://ok.dev"),
        );
        assert!(errors.is_empty(), "None/empty values must be skipped: {errors:?}");
    }

    #[test]
    fn validate_optional_contact_fields_collects_all_violations() {
        let errors = validate_optional_contact_fields(
            Some(&"1".repeat(31)),
            Some("nope"),
            None,
            Some(&"h".repeat(501)),
            None,
        );
        assert_eq!(errors.len(), 3, "expected one violation per bad field: {errors:?}");
    }
}
