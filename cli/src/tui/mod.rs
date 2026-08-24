mod app;
mod theme;
mod auth_screen;
mod job_list_screen;
mod job_detail_screen;
mod profile_screen;
mod toast;

#[cfg(test)]
mod theme_test;

#[cfg(test)]
mod app_integration_test;

pub use app::{App, AppState};
pub use theme::Theme;
pub use toast::Toast;

use crate::api::ApiClient;
use crate::config::Config;
use crossterm::event::{DisableBracketedPaste, EnableBracketedPaste};
use crossterm::execute;
use scopeguard::defer;
use std::path::Path;

/// Entry point for TUI mode. `config_path` is the `-c/--config` value so all
/// TUI-internal config reads/writes honor it
/// (docs/specs/cli-auth-ux-fixes.md, Rule 3).
pub async fn run(
    api_client: ApiClient,
    config: Config,
    config_path: Option<&str>,
) -> anyhow::Result<()> {
    let mut terminal = ratatui::init();
    execute!(std::io::stdout(), EnableBracketedPaste)?;
    defer! {
        let _ = execute!(std::io::stdout(), DisableBracketedPaste);
        ratatui::restore();
    }
    app::App::with_config_path(api_client, config, config_path.map(Path::new))
        .run(&mut terminal)
        .await
}