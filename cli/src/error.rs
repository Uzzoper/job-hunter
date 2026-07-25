use thiserror::Error;

/// Errors originating from the API client.
///
/// Each variant maps to a specific HTTP status code returned by the
/// backend, except for `HttpError` (transport-level failures) and
/// `DeserializeError` (response body parsing failures).
#[derive(Debug, Error)]
pub enum ApiError {
    /// HTTP 400 — malformed request payload.
    #[error("Bad request: {0}")]
    BadRequest(String),

    /// HTTP 401 — missing or expired token.
    #[error("Unauthorized: {0}")]
    Unauthorized(String),

    /// HTTP 404 — resource not found.
    #[error("Not found: {0}")]
    NotFound(String),

    /// HTTP 409 — resource conflict (e.g. duplicate).
    #[error("Conflict: {0}")]
    Conflict(String),

    /// HTTP 502 — upstream proxy / gateway error.
    #[error("Bad gateway: {0}")]
    BadGateway(String),

    /// HTTP 5xx (excluding 502) — internal server error.
    #[error("Internal server error: {0}")]
    ServerError(String),

    /// Transport-level HTTP failure (connection refused, timeout, TLS,
    /// or any other `reqwest::Error` that does not carry a status code
    /// or is not a decode error).
    #[error("HTTP request failed: {0}")]
    HttpError(reqwest::Error),

    /// JSON (or other) deserialization failure on the response body.
    #[error("Failed to deserialize response: {0}")]
    DeserializeError(String),
}

/// Unified CLI error type that wraps all domain-specific errors.
///
/// Every error that can surface in the CLI — API failures, config
/// loading, cache operations, network issues, clipboard access, I/O,
/// and unexpected internal errors — is represented here.
#[derive(Debug, Error)]
pub enum CliError {
    /// An error returned by the Job Hunter REST API.
    #[error("{0}")]
    Api(#[from] ApiError),

    /// Configuration file read, parse, or write failure.
    #[error("Configuration error: {0}")]
    Config(String),

    /// Local SQLite cache operation failure.
    #[error("Cache error: {0}")]
    Cache(String),

    /// Network connectivity issue (connection refused, timeout, DNS).
    #[error("Network error: {0}")]
    Network(String),

    /// System clipboard access failure.
    #[error("Clipboard error: {0}")]
    Clipboard(String),

    /// General I/O error (file read/write, etc.).
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    /// Any unexpected internal error without a more specific variant.
    #[error("Internal error: {0}")]
    Internal(String),
}

/// Convenience alias for CLI operations that return [`CliError`].
pub type Result<T> = std::result::Result<T, CliError>;

impl ApiError {
    /// Create an [`ApiError`] from an HTTP response whose status code
    /// indicates failure (non-2xx).
    ///
    /// The response body is read and stored in the returned variant so
    /// that the caller can inspect or display the server-side error
    /// message.
    pub async fn from_response(resp: reqwest::Response) -> Self {
        let status = resp.status().as_u16();
        let body = resp.text().await.unwrap_or_default();
        match status {
            400 => Self::BadRequest(body),
            401 => Self::Unauthorized(body),
            404 => Self::NotFound(body),
            409 => Self::Conflict(body),
            502 => Self::BadGateway(body),
            _ if (500..=599).contains(&status) => Self::ServerError(body),
            _ => Self::ServerError(format!("HTTP {status}: {body}")),
        }
    }
}

// ---------------------------------------------------------------------------
// From impls
// ---------------------------------------------------------------------------

impl From<reqwest::Error> for ApiError {
    fn from(err: reqwest::Error) -> Self {
        // JSON / body deserialization failure
        if err.is_decode() {
            return Self::DeserializeError(err.to_string());
        }
        // Status-code errors (see `Response::error_for_status`)
        if let Some(status) = err.status() {
            match status.as_u16() {
                400 => Self::BadRequest(err.to_string()),
                401 => Self::Unauthorized(err.to_string()),
                404 => Self::NotFound(err.to_string()),
                409 => Self::Conflict(err.to_string()),
                502 => Self::BadGateway(err.to_string()),
                _ if status.is_server_error() => Self::ServerError(err.to_string()),
                _ => Self::HttpError(err),
            }
        } else {
            // Transport-level errors (connect, timeout, TLS, etc.)
            Self::HttpError(err)
        }
    }
}

impl From<reqwest::Error> for CliError {
    fn from(err: reqwest::Error) -> Self {
        if err.is_connect() || err.is_timeout() {
            Self::Network(err.to_string())
        } else {
            Self::Api(ApiError::from(err))
        }
    }
}

impl From<rusqlite::Error> for CliError {
    fn from(err: rusqlite::Error) -> Self {
        Self::Cache(err.to_string())
    }
}

impl From<toml::de::Error> for CliError {
    fn from(err: toml::de::Error) -> Self {
        Self::Config(err.to_string())
    }
}

impl From<toml::ser::Error> for CliError {
    fn from(err: toml::ser::Error) -> Self {
        Self::Config(err.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ------------------------------------------------------------------
    // ApiError Display formatting
    // ------------------------------------------------------------------

    #[test]
    fn api_error_display_bad_request() {
        let err = ApiError::BadRequest("invalid email".into());
        assert_eq!(err.to_string(), "Bad request: invalid email");
    }

    #[test]
    fn api_error_display_unauthorized() {
        let err = ApiError::Unauthorized("token expired".into());
        assert_eq!(err.to_string(), "Unauthorized: token expired");
    }

    #[test]
    fn api_error_display_not_found() {
        let err = ApiError::NotFound("job 42 not found".into());
        assert_eq!(err.to_string(), "Not found: job 42 not found");
    }

    #[test]
    fn api_error_display_conflict() {
        let err = ApiError::Conflict("already exists".into());
        assert_eq!(err.to_string(), "Conflict: already exists");
    }

    #[test]
    fn api_error_display_bad_gateway() {
        let err = ApiError::BadGateway("upstream timeout".into());
        assert_eq!(err.to_string(), "Bad gateway: upstream timeout");
    }

    #[test]
    fn api_error_display_server_error() {
        let err = ApiError::ServerError("internal failure".into());
        assert_eq!(err.to_string(), "Internal server error: internal failure");
    }

    #[test]
    fn api_error_display_deserialize_error() {
        let err = ApiError::DeserializeError("expected map at line 1".into());
        assert_eq!(
            err.to_string(),
            "Failed to deserialize response: expected map at line 1"
        );
    }

    // ------------------------------------------------------------------
    // CliError Display formatting
    // ------------------------------------------------------------------

    #[test]
    fn cli_error_display_api() {
        let err = CliError::Api(ApiError::NotFound("x".into()));
        assert_eq!(err.to_string(), "Not found: x");
    }

    #[test]
    fn cli_error_display_config() {
        let err = CliError::Config("cannot find config dir".into());
        assert_eq!(err.to_string(), "Configuration error: cannot find config dir");
    }

    #[test]
    fn cli_error_display_cache() {
        let err = CliError::Cache("database locked".into());
        assert_eq!(err.to_string(), "Cache error: database locked");
    }

    #[test]
    fn cli_error_display_network() {
        let err = CliError::Network("connection refused".into());
        assert_eq!(err.to_string(), "Network error: connection refused");
    }

    #[test]
    fn cli_error_display_clipboard() {
        let err = CliError::Clipboard("no display".into());
        assert_eq!(err.to_string(), "Clipboard error: no display");
    }

    #[test]
    fn cli_error_display_internal() {
        let err = CliError::Internal("unexpected null".into());
        assert_eq!(err.to_string(), "Internal error: unexpected null");
    }

    #[test]
    fn cli_error_display_io() {
        let err = CliError::Io(std::io::Error::new(std::io::ErrorKind::NotFound, "file missing"));
        assert_eq!(err.to_string(), "I/O error: file missing");
    }

    // ------------------------------------------------------------------
    // ApiError::from_response — maps HTTP status codes to variants
    // ------------------------------------------------------------------

    mod from_response {
        use super::*;
        use httpmock::prelude::*;
        use reqwest::Client;

        async fn response_with_status(status: u16, body: &str) -> reqwest::Response {
            let server = MockServer::start();
            server.mock(|when, then| {
                when.method(GET).path("/test");
                then.status(status).body(body);
            });
            Client::new()
                .get(server.url("/test"))
                .send()
                .await
                .expect("request should succeed at transport level")
        }

        #[tokio::test]
        async fn maps_400_to_bad_request() {
            let resp = response_with_status(400, "invalid input").await;
            let err = ApiError::from_response(resp).await;
            assert!(matches!(err, ApiError::BadRequest(_)));
            assert_eq!(err.to_string(), "Bad request: invalid input");
        }

        #[tokio::test]
        async fn maps_401_to_unauthorized() {
            let resp = response_with_status(401, "bad credentials").await;
            let err = ApiError::from_response(resp).await;
            assert!(matches!(err, ApiError::Unauthorized(_)));
        }

        #[tokio::test]
        async fn maps_404_to_not_found() {
            let resp = response_with_status(404, "missing").await;
            let err = ApiError::from_response(resp).await;
            assert!(matches!(err, ApiError::NotFound(_)));
        }

        #[tokio::test]
        async fn maps_409_to_conflict() {
            let resp = response_with_status(409, "duplicate").await;
            let err = ApiError::from_response(resp).await;
            assert!(matches!(err, ApiError::Conflict(_)));
            assert_eq!(err.to_string(), "Conflict: duplicate");
        }

        #[tokio::test]
        async fn maps_502_to_bad_gateway() {
            let resp = response_with_status(502, "upstream error").await;
            let err = ApiError::from_response(resp).await;
            assert!(matches!(err, ApiError::BadGateway(_)));
        }

        #[tokio::test]
        async fn maps_500_to_server_error() {
            let resp = response_with_status(500, "crash").await;
            let err = ApiError::from_response(resp).await;
            assert!(matches!(err, ApiError::ServerError(_)));
            assert_eq!(err.to_string(), "Internal server error: crash");
        }

        #[tokio::test]
        async fn maps_503_to_server_error() {
            let resp = response_with_status(503, "unavailable").await;
            let err = ApiError::from_response(resp).await;
            assert!(matches!(err, ApiError::ServerError(_)));
        }

        #[tokio::test]
        async fn maps_unknown_status_to_server_error() {
            let resp = response_with_status(418, "teapot").await;
            let err = ApiError::from_response(resp).await;
            assert!(matches!(err, ApiError::ServerError(_)));
            let msg = err.to_string();
            assert!(msg.contains("418"), "should mention status code: {msg}");
        }
    }

    // ------------------------------------------------------------------
    // From<reqwest::Error> impls
    // ------------------------------------------------------------------

    mod from_reqwest_error {
        use super::*;
        use httpmock::prelude::*;
        use reqwest::Client;

        /// Helper: make a request to a mock server that returns the
        /// given status, then convert via `error_for_status()` so that
        /// the 4xx/5xx response becomes a `reqwest::Error` with the
        /// status attached.
        async fn reqwest_error_with_status(status: u16) -> reqwest::Error {
            let server = MockServer::start();
            server.mock(|when, then| {
                when.method(GET).path("/err");
                then.status(status);
            });
            let resp = Client::new()
                .get(server.url("/err"))
                .send()
                .await
                .expect("transport should succeed");
            resp.error_for_status().unwrap_err()
        }

        #[tokio::test]
        async fn maps_400_to_bad_request() {
            let err = reqwest_error_with_status(400).await;
            let api_err = ApiError::from(err);
            assert!(matches!(api_err, ApiError::BadRequest(_)));
        }

        #[tokio::test]
        async fn maps_401_to_unauthorized() {
            let err = reqwest_error_with_status(401).await;
            let api_err = ApiError::from(err);
            assert!(matches!(api_err, ApiError::Unauthorized(_)));
        }

        #[tokio::test]
        async fn maps_404_to_not_found() {
            let err = reqwest_error_with_status(404).await;
            let api_err = ApiError::from(err);
            assert!(matches!(api_err, ApiError::NotFound(_)));
        }

        #[tokio::test]
        async fn maps_409_to_conflict() {
            let err = reqwest_error_with_status(409).await;
            let api_err = ApiError::from(err);
            assert!(matches!(api_err, ApiError::Conflict(_)));
        }

        #[tokio::test]
        async fn maps_502_to_bad_gateway() {
            let err = reqwest_error_with_status(502).await;
            let api_err = ApiError::from(err);
            assert!(matches!(api_err, ApiError::BadGateway(_)));
        }

        #[tokio::test]
        async fn maps_500_to_server_error() {
            let err = reqwest_error_with_status(500).await;
            let api_err = ApiError::from(err);
            assert!(matches!(api_err, ApiError::ServerError(_)));
        }

        #[tokio::test]
        async fn maps_501_to_server_error() {
            // 501 Not Implemented is not explicitly listed but is a
            // server error (5xx), so it maps to ServerError.
            let err = reqwest_error_with_status(501).await;
            let api_err = ApiError::from(err);
            assert!(matches!(api_err, ApiError::ServerError(_)));
        }

        /// A connection error (no server listening) should produce
        /// `ApiError::HttpError`, not a status-code variant.
        #[tokio::test]
        async fn connection_error_maps_to_http_error() {
            // Port 0 is guaranteed invalid for connecting
            let result = Client::new()
                .get("http://127.0.0.1:1/")
                .send()
                .await;
            let err = result.unwrap_err();
            let api_err = ApiError::from(err);
            assert!(matches!(api_err, ApiError::HttpError(_)));
        }

        #[tokio::test]
        async fn cli_error_from_reqwest_connection_maps_to_network() {
            let result = Client::new()
                .get("http://127.0.0.1:1/")
                .send()
                .await;
            let err = result.unwrap_err();
            let cli_err = CliError::from(err);
            assert!(matches!(cli_err, CliError::Network(_)));
        }

        #[tokio::test]
        async fn cli_error_from_reqwest_status_maps_to_api() {
            let err = reqwest_error_with_status(404).await;
            let cli_err = CliError::from(err);
            // 404 is not a connection error, so it should be Api, not Network
            assert!(matches!(cli_err, CliError::Api(ApiError::NotFound(_))));
        }
    }

    // ------------------------------------------------------------------
    // From<toml::de::Error> and From<toml::ser::Error> for CliError
    // ------------------------------------------------------------------

    #[test]
    fn from_toml_de_error_maps_to_config() {
        let input = "key = broken [[[";
        let toml_err = toml::from_str::<toml::Value>(input).unwrap_err();
        let cli_err = CliError::from(toml_err);
        assert!(matches!(cli_err, CliError::Config(_)));
        let msg = cli_err.to_string();
        assert!(
            msg.contains("Configuration error"),
            "should start with Configuration error: {msg}"
        );
    }

    #[test]
    fn from_toml_ser_error_maps_to_config() {
        // Serialize a map with non-string keys to provoke a TOML
        // serialization error, then verify it maps to CliError::Config.
        use std::collections::BTreeMap;
        let mut map = BTreeMap::new();
        map.insert(42, "value");
        let result = toml::to_string_pretty(&map);
        let toml_err = result.unwrap_err();
        let cli_err = CliError::from(toml_err);
        assert!(matches!(cli_err, CliError::Config(_)));
        let msg = cli_err.to_string();
        assert!(
            msg.starts_with("Configuration error"),
            "should start with Configuration error: {msg}"
        );
    }

    // ------------------------------------------------------------------
    // Debug formatting (smoke tests — ensure no panics)
    // ------------------------------------------------------------------

    #[test]
    fn api_error_debug_output() {
        let err = ApiError::BadRequest("x".into());
        let debug = format!("{err:?}");
        assert!(!debug.is_empty());
    }

    #[test]
    fn cli_error_debug_output() {
        let err = CliError::Cache("x".into());
        let debug = format!("{err:?}");
        assert!(!debug.is_empty());
    }

    // ------------------------------------------------------------------
    // From<rusqlite::Error> for CliError
    // ------------------------------------------------------------------

    #[test]
    fn from_rusqlite_error_maps_to_cache() {
        // rusqlite::Error::InvalidParameterName is a safe variant to construct
        let sqlite_err = rusqlite::Error::InvalidParameterName("test_param".into());
        let cli_err = CliError::from(sqlite_err);
        assert!(matches!(cli_err, CliError::Cache(_)));
        let msg = cli_err.to_string();
        assert!(
            msg.starts_with("Cache error"),
            "should start with Cache error: {msg}"
        );
    }
}
