use crate::api::ApiClient;
use crate::config::ConfigManager;
use crate::domain::{AuthRequest, LoginRequest};
use crate::error::CliError;
use std::path::Path;

/// Minimum password length required for registration and login.
const MIN_PASSWORD_LENGTH: usize = 6;

/// Handle auth subcommands.
///
/// `config_path` is optional and used only in tests to redirect config
/// file writes to a temporary directory. When `None`, the default
/// `~/.config/job-hunter/config.toml` is used.
pub async fn handle(
    action: crate::AuthAction,
    api_url: &str,
    config_path: Option<&Path>,
) -> anyhow::Result<()> {
    match action {
        crate::AuthAction::Register {
            name,
            email,
            password,
        } => handle_register(name, email, password, api_url, config_path).await,
        crate::AuthAction::Login {
            email,
            password,
        } => handle_login(email, password, api_url, config_path).await,
        crate::AuthAction::Logout => handle_logout(config_path).await,
    }
}

/// Register a new user and save the received token.
async fn handle_register(
    name: String,
    email: String,
    password: String,
    api_url: &str,
    config_path: Option<&Path>,
) -> anyhow::Result<()> {
    let name = name.trim().to_string();
    if name.is_empty() {
        eprintln!("Error: Name cannot be empty.");
        return Ok(());
    }
    if let Err(e) = validate_email(&email) {
        eprintln!("Error: {e}");
        return Ok(());
    }
    if let Err(e) = validate_password(&password) {
        eprintln!("Error: {e}");
        return Ok(());
    }

    warn_if_logged_in(config_path);

    let client = ApiClient::new(api_url);
    let req = AuthRequest {
        name: name.clone(),
        email: email.clone(),
        password,
    };

    match client.register(&req).await {
        Ok(resp) => {
            if let Err(e) = persist_token(&resp.token, config_path) {
                eprintln!("Warning: Failed to save token: {e}");
            }
            println!("Registered and logged in as {} <{}>", resp.name, resp.email);
        }
        Err(err) => handle_api_error(err, "register"),
    }
    Ok(())
}

/// Log in and save the received token.
async fn handle_login(
    email: String,
    password: String,
    api_url: &str,
    config_path: Option<&Path>,
) -> anyhow::Result<()> {
    if let Err(e) = validate_email(&email) {
        eprintln!("Error: {e}");
        return Ok(());
    }
    if let Err(e) = validate_password(&password) {
        eprintln!("Error: {e}");
        return Ok(());
    }

    warn_if_logged_in(config_path);

    let client = ApiClient::new(api_url);
    let req = LoginRequest {
        email: email.clone(),
        password,
    };

    match client.login(&req).await {
        Ok(resp) => {
            if let Err(e) = persist_token(&resp.token, config_path) {
                eprintln!("Warning: Failed to save token: {e}");
            }
            println!("Logged in as {} <{}>", resp.name, resp.email);
        }
        Err(err) => handle_api_error(err, "login"),
    }
    Ok(())
}

/// Log out by clearing the stored token.
async fn handle_logout(config_path: Option<&Path>) -> anyhow::Result<()> {
    match ConfigManager::load(config_path) {
        Ok(mut mgr) => {
            if mgr.get_token().is_none() {
                println!("Not currently logged in.");
                return Ok(());
            }
            mgr.clear_token();
            if let Err(e) = mgr.save() {
                eprintln!("Warning: Failed to save config: {e}");
            }
            println!("Logged out successfully.");
        }
        Err(e) => {
            eprintln!("Warning: Could not load config: {e}");
        }
    }
    Ok(())
}

/// Validate email format (must contain @ and a domain).
fn validate_email(email: &str) -> anyhow::Result<()> {
    let email = email.trim();
    if email.is_empty() {
        anyhow::bail!("Email cannot be empty.");
    }
    if !email.contains('@') || !email.contains('.') {
        anyhow::bail!("Invalid email format.");
    }
    Ok(())
}

/// Validate password length.
fn validate_password(password: &str) -> anyhow::Result<()> {
    if password.len() < MIN_PASSWORD_LENGTH {
        anyhow::bail!(
            "Password must be at least {MIN_PASSWORD_LENGTH} characters."
        );
    }
    Ok(())
}

/// Print a warning if there is already a stored token.
fn warn_if_logged_in(config_path: Option<&Path>) {
    if let Ok(mgr) = ConfigManager::load(config_path)
        && mgr.get_token().is_some() {
            eprintln!(
                "Warning: You are already logged in. \
                 This will overwrite your existing session."
            );
        }
}

/// Persist an auth token to the config file.
fn persist_token(token: &str, config_path: Option<&Path>) -> anyhow::Result<()> {
    let mut mgr = ConfigManager::load(config_path)?;
    mgr.set_token(token);
    mgr.save()?;
    Ok(())
}

/// Display a user-friendly error message for API errors.
fn handle_api_error(err: CliError, action: &str) {
    match &err {
        CliError::Api(api_err) => match api_err {
            crate::error::ApiError::Unauthorized(_) => {
                eprintln!("Error: Invalid email or password.");
                eprintln!("Hint: Check your credentials and try 'jh login' again.");
            }
            crate::error::ApiError::BadRequest(msg) => {
                // Show the server-side error message directly
                eprintln!("Error: {msg}");
            }
            crate::error::ApiError::Conflict(_)
                if action == "register" => {
                    eprintln!("Error: An account with this email already exists.");
                    eprintln!("Hint: Try 'jh login' instead.");
                }
            _ => {
                eprintln!("Error: {err}");
            }
        },
        CliError::Network(_) => {
            eprintln!("Error: Could not connect to the server.");
            eprintln!(
                "Make sure the backend is running at the configured URL \
                 (default: http://localhost:8080)."
            );
        }
        _ => {
            eprintln!("Error: {err}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use httpmock::prelude::*;
    use httpmock::Method;
    use serde_json::json;
    use std::fs;
    use std::path::PathBuf;

    /// Create a temporary directory for config files in tests.
    fn test_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir()
            .join("jh-cli-auth-test")
            .join(name);
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("create test dir");
        dir
    }

    fn config_path(dir: &Path) -> PathBuf {
        dir.join("config.toml")
    }

    // =====================================================================
    // Validation tests
    // =====================================================================

    #[test]
    fn validate_email_rejects_empty() {
        let result = validate_email("");
        assert!(result.is_err());
        assert!(
            result.unwrap_err().to_string().contains("empty"),
            "should mention empty"
        );
    }

    #[test]
    fn validate_email_rejects_missing_at() {
        let result = validate_email("noatsign");
        assert!(result.is_err());
    }

    #[test]
    fn validate_email_rejects_missing_domain() {
        let result = validate_email("user@");
        assert!(result.is_err());
    }

    #[test]
    fn validate_email_accepts_valid() {
        let result = validate_email("alice@example.com");
        assert!(result.is_ok());
    }

    #[test]
    fn validate_password_rejects_short() {
        let result = validate_password("12345");
        assert!(result.is_err());
        let msg = result.unwrap_err().to_string();
        assert!(msg.contains("6"), "should mention minimum length: {msg}");
    }

    #[test]
    fn validate_password_accepts_minimum() {
        let result = validate_password("123456");
        assert!(result.is_ok());
    }

    #[test]
    fn validate_password_accepts_long() {
        let result = validate_password("a-very-long-password-with-special-chars!@#");
        assert!(result.is_ok());
    }

    // =====================================================================
    // Register flow
    // =====================================================================

    #[tokio::test]
    async fn register_success_saves_token() {
        let server = MockServer::start();
        let dir = test_dir("register_success_saves_token");
        let cfg_path = config_path(&dir);

        let mock = server.mock(|when, then| {
            when.method(Method::POST)
                .path("/api/auth/register")
                .json_body(json!({
                    "name": "Alice",
                    "email": "alice@example.com",
                    "password": "secret123"
                }));
            then.status(201)
                .header("content-type", "application/json")
                .json_body(json!({
                    "token": "jwt-register-token",
                    "userId": 42,
                    "name": "Alice",
                    "email": "alice@example.com"
                }));
        });

        let action = crate::AuthAction::Register {
            name: "Alice".into(),
            email: "alice@example.com".into(),
            password: "secret123".into(),
        };

        let result = handle(action, &server.url(""), Some(&cfg_path)).await;
        assert!(result.is_ok(), "handler should return Ok");
        mock.assert();

        // Verify token was persisted
        let mgr = ConfigManager::load(Some(&cfg_path)).expect("load config");
        assert_eq!(
            mgr.get_token(),
            Some("jwt-register-token"),
            "token should be saved to config"
        );
    }

    #[tokio::test]
    async fn register_with_empty_name_prints_error() {
        let server = MockServer::start();
        let dir = test_dir("register_with_empty_name_prints_error");
        let cfg_path = config_path(&dir);

        let action = crate::AuthAction::Register {
            name: "   ".into(),
            email: "alice@example.com".into(),
            password: "secret123".into(),
        };

        let result = handle(action, &server.url(""), Some(&cfg_path)).await;
        assert!(result.is_ok(), "handler should not panic");

        // Token should NOT be saved
        let mgr = ConfigManager::load(Some(&cfg_path)).expect("load config");
        assert!(mgr.get_token().is_none(), "token should not be saved");
    }

    #[tokio::test]
    async fn register_with_invalid_email_prints_error() {
        let server = MockServer::start();
        let dir = test_dir("register_with_invalid_email_prints_error");
        let cfg_path = config_path(&dir);

        let action = crate::AuthAction::Register {
            name: "Alice".into(),
            email: "not-an-email".into(),
            password: "secret123".into(),
        };

        let result = handle(action, &server.url(""), Some(&cfg_path)).await;
        assert!(result.is_ok());
        let mgr = ConfigManager::load(Some(&cfg_path)).expect("load config");
        assert!(mgr.get_token().is_none(), "token should not be saved");
    }

    #[tokio::test]
    async fn register_with_short_password_prints_error() {
        let server = MockServer::start();
        let dir = test_dir("register_with_short_password_prints_error");
        let cfg_path = config_path(&dir);

        let action = crate::AuthAction::Register {
            name: "Alice".into(),
            email: "alice@example.com".into(),
            password: "12345".into(),
        };

        let result = handle(action, &server.url(""), Some(&cfg_path)).await;
        assert!(result.is_ok());
        let mgr = ConfigManager::load(Some(&cfg_path)).expect("load config");
        assert!(mgr.get_token().is_none());
    }

    #[tokio::test]
    async fn register_400_prints_friendly_message() {
        let server = MockServer::start();
        let dir = test_dir("register_400_prints_friendly_message");
        let cfg_path = config_path(&dir);

        let mock = server.mock(|when, then| {
            when.method(Method::POST).path("/api/auth/register");
            then.status(400)
                .header("content-type", "application/json")
                .json_body(json!({
                    "timestamp": "2026-07-14T12:00:00.000",
                    "status": 400,
                    "error": "Bad Request",
                    "message": "Email already in use"
                }));
        });

        let action = crate::AuthAction::Register {
            name: "Alice".into(),
            email: "alice@example.com".into(),
            password: "secret123".into(),
        };

        let result = handle(action, &server.url(""), Some(&cfg_path)).await;
        assert!(result.is_ok());
        mock.assert();

        // Token should NOT be saved on failure
        let mgr = ConfigManager::load(Some(&cfg_path)).expect("load config");
        assert!(mgr.get_token().is_none(), "token should not be saved on error");
    }

    // =====================================================================
    // Login flow
    // =====================================================================

    #[tokio::test]
    async fn login_success_saves_token() {
        let server = MockServer::start();
        let dir = test_dir("login_success_saves_token");
        let cfg_path = config_path(&dir);

        let mock = server.mock(|when, then| {
            when.method(Method::POST)
                .path("/api/auth/login")
                .json_body(json!({
                    "email": "bob@example.com",
                    "password": "password123"
                }));
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "token": "jwt-login-token",
                    "userId": 1,
                    "name": "Bob",
                    "email": "bob@example.com"
                }));
        });

        let action = crate::AuthAction::Login {
            email: "bob@example.com".into(),
            password: "password123".into(),
        };

        let result = handle(action, &server.url(""), Some(&cfg_path)).await;
        assert!(result.is_ok());
        mock.assert();

        let mgr = ConfigManager::load(Some(&cfg_path)).expect("load config");
        assert_eq!(
            mgr.get_token(),
            Some("jwt-login-token"),
            "token should be saved to config"
        );
    }

    #[tokio::test]
    async fn login_with_invalid_email_prints_error() {
        let server = MockServer::start();
        let dir = test_dir("login_with_invalid_email_prints_error");
        let cfg_path = config_path(&dir);

        let action = crate::AuthAction::Login {
            email: "bad".into(),
            password: "password123".into(),
        };

        let result = handle(action, &server.url(""), Some(&cfg_path)).await;
        assert!(result.is_ok());
        let mgr = ConfigManager::load(Some(&cfg_path)).expect("load config");
        assert!(mgr.get_token().is_none());
    }

    #[tokio::test]
    async fn login_401_prints_friendly_message() {
        let server = MockServer::start();
        let dir = test_dir("login_401_prints_friendly_message");
        let cfg_path = config_path(&dir);

        let mock = server.mock(|when, then| {
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

        let action = crate::AuthAction::Login {
            email: "bob@example.com".into(),
            password: "wrong-password".into(),
        };

        let result = handle(action, &server.url(""), Some(&cfg_path)).await;
        assert!(result.is_ok());
        mock.assert();

        // Token should NOT be saved
        let mgr = ConfigManager::load(Some(&cfg_path)).expect("load config");
        assert!(mgr.get_token().is_none());
    }

    // =====================================================================
    // Logout flow
    // =====================================================================

    #[tokio::test]
    async fn logout_clears_token() {
        let server = MockServer::start();
        let dir = test_dir("logout_clears_token");
        let cfg_path = config_path(&dir);

        // First save a token
        let mut mgr = ConfigManager::load(Some(&cfg_path)).expect("load");
        mgr.set_token("existing-token");
        mgr.save().expect("save");

        let action = crate::AuthAction::Logout;
        let result = handle(action, &server.url(""), Some(&cfg_path)).await;
        assert!(result.is_ok());

        let mgr = ConfigManager::load(Some(&cfg_path)).expect("reload");
        assert!(mgr.get_token().is_none(), "token should be cleared");
    }

    #[tokio::test]
    async fn logout_when_not_logged_in_prints_message() {
        let server = MockServer::start();
        let dir = test_dir("logout_when_not_logged_in_prints_message");
        let cfg_path = config_path(&dir);

        let action = crate::AuthAction::Logout;
        let result = handle(action, &server.url(""), Some(&cfg_path)).await;
        assert!(result.is_ok());

        // Config should still be empty
        let mgr = ConfigManager::load(Some(&cfg_path)).expect("load");
        assert!(mgr.get_token().is_none());
    }

    // =====================================================================
    // Warning when already logged in
    // =====================================================================

    #[tokio::test]
    async fn login_warns_when_already_logged_in() {
        let server = MockServer::start();
        let dir = test_dir("login_warns_when_already_logged_in");
        let cfg_path = config_path(&dir);

        // Pre-populate config with an existing token
        let mut mgr = ConfigManager::load(Some(&cfg_path)).expect("load");
        mgr.set_token("old-token");
        mgr.save().expect("save");

        let mock = server.mock(|when, then| {
            when.method(Method::POST).path("/api/auth/login");
            then.status(200)
                .header("content-type", "application/json")
                .json_body(json!({
                    "token": "new-token",
                    "userId": 1,
                    "name": "Bob",
                    "email": "bob@example.com"
                }));
        });

        let action = crate::AuthAction::Login {
            email: "bob@example.com".into(),
            password: "password123".into(),
        };

        let result = handle(action, &server.url(""), Some(&cfg_path)).await;
        assert!(result.is_ok());
        mock.assert();

        // Token should be updated to the new one
        let mgr = ConfigManager::load(Some(&cfg_path)).expect("reload");
        assert_eq!(mgr.get_token(), Some("new-token"));
    }
}
