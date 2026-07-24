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

/// Entry point for TUI mode.
pub async fn run(
    api_client: ApiClient,
    config: Config,
) -> anyhow::Result<()> {
    let mut terminal = ratatui::init();
    execute!(std::io::stdout(), EnableBracketedPaste)?;
    defer! {
        let _ = execute!(std::io::stdout(), DisableBracketedPaste);
        ratatui::restore();
    }
    app::App::new(api_client, config)
        .run(&mut terminal)
        .await
}