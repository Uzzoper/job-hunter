use crate::api::ApiClient;
use crate::config::Config;
use crate::tui::app::{App, AppState};
use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ratatui::Terminal;
use ratatui::backend::TestBackend;

#[tokio::test]
async fn tui_app_new_creates_app_with_default_state() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let app = App::new(api_client, config);

    assert_eq!(app.state, AppState::Auth);
    assert!(!app.should_quit());
    assert!(app.token.is_none());
    assert!(app.jobs.is_empty());
    assert!(app.selected_job.is_none());
    assert!(app.profile.is_none());
    assert!(app.error_message.is_none());
}

#[tokio::test]
async fn tui_app_should_quit_returns_false_initially() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let app = App::new(api_client, config);

    assert!(!app.should_quit());
}

#[tokio::test]
async fn tui_app_should_quit_returns_true_when_set() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.should_quit = true;
    assert!(app.should_quit());
}

#[tokio::test]
async fn tui_app_should_quit_returns_true_when_state_is_quitting() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::Quitting;
    assert!(app.should_quit());
}

#[tokio::test]
async fn tui_app_transition_to_changes_state() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.transition_to(AppState::JobList);
    assert_eq!(app.state, AppState::JobList);

    app.transition_to(AppState::JobDetail);
    assert_eq!(app.state, AppState::JobDetail);

    app.transition_to(AppState::Profile);
    assert_eq!(app.state, AppState::Profile);

    app.transition_to(AppState::Quitting);
    assert_eq!(app.state, AppState::Quitting);
}

#[tokio::test]
async fn tui_app_set_error_and_clear_error() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    assert!(app.error_message.is_none());

    app.set_error("Test error".to_string());
    assert_eq!(app.error_message, Some("Test error".to_string()));

    app.clear_error();
    assert!(app.error_message.is_none());
}

#[tokio::test]
async fn tui_app_render_does_not_panic() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    let backend = TestBackend::new(80, 24);
    let mut terminal = Terminal::new(backend).unwrap();

    terminal.draw(|frame| app.render(frame)).unwrap();
}

#[tokio::test]
async fn tui_app_run_exits_cleanly_on_quit() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    let backend = TestBackend::new(80, 24);
    let mut terminal = Terminal::new(backend).unwrap();

    app.should_quit = true;

    let result = app.run(&mut terminal).await;
    assert!(result.is_ok());
}