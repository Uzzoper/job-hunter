use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

/// Default base URL for the Job Hunter API.
const DEFAULT_BASE_URL: &str = "http://localhost:8080";
/// Default cache TTL in hours.
const DEFAULT_CACHE_TTL_HOURS: u64 = 24;

/// Application configuration persisted as TOML.
///
/// Stored at `~/.config/job-hunter/config.toml`.
#[derive(Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    /// Base URL for the Job Hunter REST API.
    pub base_url: String,
    /// How long to cache job listings before refreshing (in hours).
    pub cache_ttl_hours: u64,
    /// API authentication token (JWT).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            base_url: DEFAULT_BASE_URL.to_string(),
            cache_ttl_hours: DEFAULT_CACHE_TTL_HOURS,
            token: None,
        }
    }
}

/// Custom Debug that redacts the token to avoid leaking secrets in logs.
impl std::fmt::Debug for Config {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Config")
            .field("base_url", &self.base_url)
            .field("cache_ttl_hours", &self.cache_ttl_hours)
            .field("token", &self.token.as_ref().map(|_| "***"))
            .finish()
    }
}

/// Default config path: `~/.config/job-hunter/config.toml`.
pub fn default_config_path() -> Option<PathBuf> {
    dirs_next::config_dir().map(|d| d.join("job-hunter").join("config.toml"))
}

/// Manages loading, saving, and modifying application configuration.
///
/// # Security
///
/// The config file is saved with Unix permissions `0o600`
/// (user-read-write only) to protect the stored token.
#[derive(Debug)]
pub struct ConfigManager {
    path: PathBuf,
    config: Config,
}

impl ConfigManager {
    /// Create a new ConfigManager with default configuration.
    ///
    /// This is useful for testing or when you don't need to persist to disk.
    pub fn new() -> Self {
        Self {
            path: PathBuf::new(),
            config: Config::default(),
        }
    }

    /// Load configuration from an optional path.
    ///
    /// If `path` is `None`, uses the default location
    /// (`~/.config/job-hunter/config.toml`). If the file does not
    /// exist, returns a `Config` with default values.
    ///
    /// # Errors
    ///
    /// Returns an error if the config directory cannot be determined,
    /// the file cannot be read, or its contents are not valid TOML.
    pub fn load(path: Option<&Path>) -> Result<Self> {
        let config_path = match path {
            Some(p) => p.to_path_buf(),
            None => default_config_path().context("cannot determine config directory")?,
        };

        let config = if config_path.exists() {
            let content = fs::read_to_string(&config_path)
                .with_context(|| format!("reading config from {:?}", config_path))?;
            toml::from_str(&content)
                .with_context(|| format!("parsing config from {:?}", config_path))?
        } else {
            Config::default()
        };

        Ok(Self {
            path: config_path,
            config,
        })
    }

    /// Save the current configuration to disk.
    ///
    /// Creates the parent directory if it does not exist. Writes to a
    /// temporary file first, then atomically renames to the target
    /// path. On Unix, sets file permissions to `0o600`.
    ///
    /// # Errors
    ///
    /// Returns an error if the directory cannot be created, the file
    /// cannot be written, or permissions cannot be set.
    pub fn save(&self) -> Result<()> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent).context("creating config directory")?;
        }

        let content =
            toml::to_string_pretty(&self.config).context("serializing config")?;

        let tmp_path = self.path.with_extension("toml.tmp");
        {
            let mut file =
                fs::File::create(&tmp_path).context("creating temp config file")?;
            file.write_all(content.as_bytes())
                .context("writing config")?;
            file.flush().context("flushing config")?;
        }

        #[cfg(unix)]
        {
            let mut perms =
                fs::metadata(&tmp_path).context("reading temp file metadata")?.permissions();
            std::os::unix::fs::PermissionsExt::set_mode(&mut perms, 0o600);
            fs::set_permissions(&tmp_path, perms)
                .context("setting config file permissions")?;
        }

        fs::rename(&tmp_path, &self.path).context("renaming temp config file")?;

        Ok(())
    }

    /// Store the API authentication token.
    ///
    /// The token is persisted to disk on the next `save()` call.
    pub fn set_token(&mut self, token: &str) {
        self.config.token = Some(token.to_string());
    }

    /// Remove the stored API authentication token.
    pub fn clear_token(&mut self) {
        self.config.token = None;
    }

    /// Get a reference to the stored API token, if any.
    pub fn get_token(&self) -> Option<&str> {
        self.config.token.as_deref()
    }

    /// Change the Job Hunter API base URL.
    pub fn set_base_url(&mut self, url: &str) {
        self.config.base_url = url.to_string();
    }

    /// Get a reference to the managed `Config`.
    pub fn config(&self) -> &Config {
        &self.config
    }

    /// Get a mutable reference to the managed `Config`.
    pub fn config_mut(&mut self) -> &mut Config {
        &mut self.config
    }

    /// Get the path to the config file.
    pub fn path(&self) -> &Path {
        &self.path
    }
}

/// Convenience function to load configuration from an optional path.
///
/// This is the primary entry point used by `main.rs`. Returns a `Config`
/// with default values if the file does not exist.
///
/// # Errors
///
/// Returns an error if the config directory cannot be determined, the
/// file cannot be read, or its contents are not valid TOML.
pub fn load(path: Option<&str>) -> Result<Config> {
    let path = path.map(Path::new);
    let manager = ConfigManager::load(path)?;
    Ok(manager.config)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn test_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir()
            .join("jh-cli-config-test")
            .join(name);
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("create test dir");
        dir
    }

    #[test]
    fn load_returns_default_when_file_not_found() {
        let dir = test_dir("load_returns_default_when_file_not_found");
        let path = dir.join("nonexistent.toml");

        let manager = ConfigManager::load(Some(&path)).expect("load should succeed");
        assert_eq!(manager.config.base_url, DEFAULT_BASE_URL);
        assert_eq!(manager.config.cache_ttl_hours, DEFAULT_CACHE_TTL_HOURS);
        assert!(manager.config.token.is_none());
    }

    #[test]
    fn save_and_load_roundtrip() {
        let dir = test_dir("save_and_load_roundtrip");
        let path = dir.join("config.toml");

        let mut manager = ConfigManager::load(Some(&path)).expect("load");
        manager.set_base_url("http://custom:9090");
        manager.set_token("test-token-123");
        manager.save().expect("save");

        let loaded = ConfigManager::load(Some(&path)).expect("reload");
        assert_eq!(loaded.config.base_url, "http://custom:9090");
        assert_eq!(loaded.config.cache_ttl_hours, DEFAULT_CACHE_TTL_HOURS);
        assert_eq!(loaded.config.token.as_deref(), Some("test-token-123"));
    }

    #[test]
    fn set_token_persists_after_save() {
        let dir = test_dir("set_token_persists_after_save");
        let path = dir.join("config.toml");

        let mut manager = ConfigManager::load(Some(&path)).expect("load");
        assert!(manager.get_token().is_none());

        manager.set_token("jwt-secret");
        assert_eq!(manager.get_token(), Some("jwt-secret"));

        manager.save().expect("save");

        let loaded = ConfigManager::load(Some(&path)).expect("reload");
        assert_eq!(loaded.get_token(), Some("jwt-secret"));
    }

    #[test]
    fn clear_token_removes_token() {
        let dir = test_dir("clear_token_removes_token");
        let path = dir.join("config.toml");

        let mut manager = ConfigManager::load(Some(&path)).expect("load");
        manager.set_token("temp-token");
        manager.save().expect("save");

        let loaded = ConfigManager::load(Some(&path)).expect("reload");
        assert_eq!(loaded.get_token(), Some("temp-token"));

        let mut after_clear = ConfigManager::load(Some(&path)).expect("reload");
        after_clear.clear_token();
        after_clear.save().expect("save");

        let final_load = ConfigManager::load(Some(&path)).expect("reload");
        assert!(final_load.get_token().is_none());
    }

    #[test]
    fn corrupt_toml_returns_error() {
        let dir = test_dir("corrupt_toml_returns_error");
        let path = dir.join("config.toml");

        fs::write(&path, "this is not valid toml [[[").expect("write corrupt file");

        let result = ConfigManager::load(Some(&path));
        assert!(result.is_err(), "corrupt TOML should return an error");
        let err = result.unwrap_err().to_string();
        assert!(
            err.contains("parsing config") || err.contains("toml"),
            "error should mention parsing or TOML: {err}"
        );
    }

    #[test]
    fn missing_fields_apply_defaults() {
        let dir = test_dir("missing_fields_apply_defaults");
        let path = dir.join("config.toml");

        fs::write(&path, "token = \"some-token\"\n").expect("write partial config");

        let manager = ConfigManager::load(Some(&path)).expect("load should succeed");
        assert_eq!(manager.config.base_url, DEFAULT_BASE_URL);
        assert_eq!(manager.config.cache_ttl_hours, DEFAULT_CACHE_TTL_HOURS);
        assert_eq!(manager.config.token.as_deref(), Some("some-token"));
    }

    #[test]
    fn base_url_customization() {
        let dir = test_dir("base_url_customization");
        let path = dir.join("config.toml");

        let mut manager = ConfigManager::load(Some(&path)).expect("load");
        manager.set_base_url("https://api.job-hunter.example.com");
        manager.save().expect("save");

        let loaded = ConfigManager::load(Some(&path)).expect("reload");
        assert_eq!(loaded.config.base_url, "https://api.job-hunter.example.com");
    }

    #[test]
    fn load_convenience_function_returns_config() {
        let dir = test_dir("load_convenience_function_returns_config");
        let path = dir.join("config.toml");

        let mut mgr = ConfigManager::load(Some(&path)).expect("load");
        mgr.set_token("convenience-test");
        mgr.save().expect("save");

        let config = load(Some(path.to_str().unwrap())).expect("load convenience");
        assert_eq!(config.token.as_deref(), Some("convenience-test"));
        assert_eq!(config.base_url, DEFAULT_BASE_URL);
    }

    #[test]
    fn config_debug_redacts_token() {
        let config = Config {
            base_url: "http://test:8080".into(),
            cache_ttl_hours: 12,
            token: Some("super-secret".into()),
        };
        let debug = format!("{config:?}");
        assert!(!debug.contains("super-secret"), "token leaked in debug output");
        assert!(debug.contains("***"), "token should be redacted");
    }

    #[test]
    #[cfg(unix)]
    fn save_sets_600_permissions() {
        use std::os::unix::fs::PermissionsExt;

        let dir = test_dir("save_sets_600_permissions");
        let path = dir.join("config.toml");

        let manager = ConfigManager::load(Some(&path)).expect("load");
        manager.save().expect("save");

        let metadata = fs::metadata(&path).expect("read metadata");
        let mode = metadata.permissions().mode() & 0o777;
        assert_eq!(
            mode, 0o600,
            "config file should have 600 permissions, got {mode:o}"
        );
    }
}
