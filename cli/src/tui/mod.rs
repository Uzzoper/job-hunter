mod app;
mod theme;
mod auth_screen;
mod job_list_screen;
mod job_detail_screen;
mod profile_screen;

#[cfg(test)]
mod theme_test;

pub use app::{App, AppState};
pub use theme::Theme;

use crate::api::ApiClient;
use crate::config::Config;

/// Entry point for TUI mode.
pub async fn run(
    api_client: ApiClient,
    config: Config,
) -> anyhow::Result<()> {
    let mut terminal = ratatui::init();
    let result = app::App::new(api_client, config)
        .run(&mut terminal)
        .await;
    ratatui::restore();
    result
}