mod auth;
mod analyze;
mod jobs;
mod profile;

use crate::config::Config;

pub async fn run(
    command: crate::Command,
    api_url: String,
    token: Option<String>,
    _config: Config,
) -> anyhow::Result<()> {
    use crate::Command::*;
    match command {
        Auth { action } => auth::handle(action, &api_url, None).await?,
        List { keyword, min_score, source, csv, json, offline, refresh } => {
            jobs::handle_list(keyword, min_score, source, csv, json, offline, refresh, &api_url, &token, None).await?
        }
        Detail { id, json } => jobs::handle_detail(id, json, &api_url, &token).await?,
        Fetch { source } => jobs::handle_fetch(source, &api_url, &token, None).await?,
        Analyze { job_id, json } => analyze::handle_analyze(job_id, json, &api_url, &token).await?,
        Email { action } => analyze::handle_email(action, &api_url, &token).await?,
        Profile { action } => profile::handle(action, &api_url, &token).await?,
        Export { output, keyword } => jobs::handle_export(output, keyword, &api_url, &token).await?,
        ClearCache => {
            let config = crate::config::load(None)?;
            let cache = crate::cache::CacheManager::new(None, config.cache_ttl_hours)?;
            cache.clear()?;
            println!("Cache cleared.");
        }
    }
    Ok(())
}
