use clap::Parser;
use jh_cli::api::ApiClient;
use jh_cli::config;
use jh_cli::batch;
use jh_cli::Cli;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();

    let config = config::load(cli.config.as_deref())?;
    let token: Option<String> = cli.token.or_else(|| config.token.clone());
    let api_url = cli
        .api_url
        .or_else(|| Some(config.base_url.clone()))
        .unwrap_or_else(|| config::DEFAULT_BASE_URL.to_string());

    let mut api_client = ApiClient::new(&api_url);
    if let Some(ref token) = token {
        api_client.set_token(token);
    }

    if cli.tui || cli.command.is_none() {
        jh_cli::tui::run(api_client, config).await?;
        return Ok(());
    }

    let command = cli.command.unwrap();
    batch::run(command, api_url, token, config).await?;

    Ok(())
}
