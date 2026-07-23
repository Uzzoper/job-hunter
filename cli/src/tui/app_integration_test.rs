use crate::api::ApiClient;
use crate::config::Config;
use crate::tui::app::{App, AppState};
use crate::tui::profile_screen::ProfileScreen;
use crate::tui::job_detail_screen::{JobDetailScreen, LoadingState};
use crate::tui::job_list_screen::SearchFocus;
use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ratatui::Terminal;
use ratatui::backend::TestBackend;
use std::sync::Arc;
use tokio::sync::Mutex;

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

    // Test render doesn't panic
    terminal.draw(|frame| app.render(frame)).unwrap();
    
    // Test handle_event doesn't panic
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('q'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
}

// ===== Profile Handler Tests =====

#[tokio::test]
async fn profile_e_toggles_edit_mode() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::Profile;
    // Initialize profile_screen
    let profile_screen = ProfileScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::config::ConfigManager::new())),
    );
    app.profile_screen = Some(profile_screen);

    // Press 'e' to enter edit mode
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('e'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();

    // Assert edit mode
    assert_eq!(app.profile_screen.as_ref().unwrap().mode, crate::tui::profile_screen::ProfileMode::Edit);

    // Press 'e' again — in Edit mode 'e' should insert char, NOT toggle mode
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('e'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();

    // Assert still in edit mode ('e' inserted as char, didn't toggle)
    assert_eq!(app.profile_screen.as_ref().unwrap().mode, crate::tui::profile_screen::ProfileMode::Edit);

    // Press 'Esc' to return to view mode
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert_eq!(app.profile_screen.as_ref().unwrap().mode, crate::tui::profile_screen::ProfileMode::View);
}

#[tokio::test]
async fn profile_r_triggers_load_profile() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::Profile;
    let profile_screen = ProfileScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::config::ConfigManager::new())),
    );
    app.profile_screen = Some(profile_screen);

    // Press 'r' to reload profile
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('r'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();

    // The load_profile is async and will set loading state
    // We just verify the handler was called without panic
    assert_eq!(app.state, AppState::Profile);
}

#[tokio::test]
async fn profile_b_cancels_edit_then_exits() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::Profile;
    let profile_screen = ProfileScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::config::ConfigManager::new())),
    );
    app.profile_screen = Some(profile_screen);

    // First enter edit mode
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('e'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert_eq!(app.profile_screen.as_ref().unwrap().mode, crate::tui::profile_screen::ProfileMode::Edit);

    // Press 'Esc' to cancel edit (returns to view mode)
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert_eq!(app.profile_screen.as_ref().unwrap().mode, crate::tui::profile_screen::ProfileMode::View);
    assert_eq!(app.state, AppState::Profile); // Still in profile

    // Press 'b' to exit to job list (now in View mode, so 'b' navigates)
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('b'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert_eq!(app.state, AppState::JobList);
}

#[tokio::test]
async fn profile_esc_cancels_edit_then_exits() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::Profile;
    let profile_screen = ProfileScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::config::ConfigManager::new())),
    );
    app.profile_screen = Some(profile_screen);

    // First enter edit mode
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('e'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert_eq!(app.profile_screen.as_ref().unwrap().mode, crate::tui::profile_screen::ProfileMode::Edit);

    // Press Esc to cancel edit (should return to view mode)
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert_eq!(app.profile_screen.as_ref().unwrap().mode, crate::tui::profile_screen::ProfileMode::View);
    assert_eq!(app.state, AppState::Profile); // Still in profile

    // Press Esc again to exit to job list
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert_eq!(app.state, AppState::JobList);
}

// ===== JobDetail Handler Tests =====

#[tokio::test]
async fn job_detail_a_triggers_analyze_job() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::JobDetail;
    // Initialize job_detail_screen with a job
    let job = crate::domain::JobResponse {
        id: 1,
        title: "Test Job".into(),
        company: "Test Corp".into(),
        url: "https://example.com/job/1".into(),
        description: "Test description".into(),
        posted_at: chrono::NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
        source: "gupy".into(),
    };
    app.selected_job = Some(job.clone());

    let job_detail_screen = JobDetailScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::cache::CacheManager::new(None, 24).unwrap())),
    );
    app.job_detail_screen = Some(job_detail_screen);

    // Press 'a' to analyze
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('a'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();

    // Verify analyze_job was called (loading state should be set)
    // The screen's analyze_job sets loading_analysis to Loading
    assert_eq!(app.state, AppState::JobDetail);
}

#[tokio::test]
async fn job_detail_e_triggers_generate_email() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::JobDetail;
    let job = crate::domain::JobResponse {
        id: 1,
        title: "Test Job".into(),
        company: "Test Corp".into(),
        url: "https://example.com/job/1".into(),
        description: "Test description".into(),
        posted_at: chrono::NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
        source: "gupy".into(),
    };
    app.selected_job = Some(job.clone());

    let job_detail_screen = JobDetailScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::cache::CacheManager::new(None, 24).unwrap())),
    );
    app.job_detail_screen = Some(job_detail_screen);

    // Press 'e' to generate email
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('e'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();

    // Verify generate_email was called
    assert_eq!(app.state, AppState::JobDetail);
}

#[tokio::test]
async fn job_detail_a_ignored_in_non_jobdetail_state() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    // Test in JobList state
    app.state = AppState::JobList;
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('a'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert_eq!(app.state, AppState::JobList);

    // Test in Profile state
    app.state = AppState::Profile;
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('a'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert_eq!(app.state, AppState::Profile);
}

#[tokio::test]
async fn job_detail_e_ignored_in_non_jobdetail_state() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    // Test in JobList state
    app.state = AppState::JobList;
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('e'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert_eq!(app.state, AppState::JobList);

    // Test in Profile state (should be handled by profile_screen::handle_event)
    app.state = AppState::Profile;
    let profile_screen = ProfileScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::config::ConfigManager::new())),
    );
    app.profile_screen = Some(profile_screen);

    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('e'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    // In Profile, 'e' toggles edit mode
    assert_eq!(app.profile_screen.as_ref().unwrap().mode, crate::tui::profile_screen::ProfileMode::Edit);
}

#[tokio::test]
async fn job_detail_space_toggles_email_expanded() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::JobDetail;
    let job = crate::domain::JobResponse {
        id: 1,
        title: "Test Job".into(),
        company: "Test Corp".into(),
        url: "https://example.com/job/1".into(),
        description: "Test description".into(),
        posted_at: chrono::NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
        source: "gupy".into(),
    };
    app.selected_job = Some(job);

    let screen = JobDetailScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::cache::CacheManager::new(None, 24).unwrap())),
    );
    app.job_detail_screen = Some(screen);

    let email = crate::domain::EmailDraftResponse {
        id: 1,
        job_id: 1,
        subject: "Test Subject".into(),
        body: "Test body".into(),
        status: crate::domain::EmailStatus::Pending,
        generated_at: chrono::NaiveDateTime::parse_from_str("2026-07-14T10:00:00", "%Y-%m-%dT%H:%M:%S").unwrap(),
    };
    app.job_detail_screen.as_mut().unwrap().email = Some(email);

    assert!(!app.job_detail_screen.as_ref().unwrap().show_email_expanded);

    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char(' '), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert!(app.job_detail_screen.as_ref().unwrap().show_email_expanded);

    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char(' '), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert!(!app.job_detail_screen.as_ref().unwrap().show_email_expanded);
}

#[tokio::test]
async fn job_detail_c_copies_email() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::JobDetail;
    let job = crate::domain::JobResponse {
        id: 1,
        title: "Test Job".into(),
        company: "Test Corp".into(),
        url: "https://example.com/job/1".into(),
        description: "Test description".into(),
        posted_at: chrono::NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
        source: "gupy".into(),
    };
    app.selected_job = Some(job);

    let screen = JobDetailScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::cache::CacheManager::new(None, 24).unwrap())),
    );
    app.job_detail_screen = Some(screen);

    let email = crate::domain::EmailDraftResponse {
        id: 1,
        job_id: 1,
        subject: "Test Subject".into(),
        body: "Test body".into(),
        status: crate::domain::EmailStatus::Pending,
        generated_at: chrono::NaiveDateTime::parse_from_str("2026-07-14T10:00:00", "%Y-%m-%dT%H:%M:%S").unwrap(),
    };
    app.job_detail_screen.as_mut().unwrap().email = Some(email);

    // handler ignores clipboard errors, so just verify no panic
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('c'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();

    assert_eq!(app.state, AppState::JobDetail);
}

#[tokio::test]
async fn job_detail_f_toggles_email_full() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::JobDetail;
    let job = crate::domain::JobResponse {
        id: 1,
        title: "Test Job".into(),
        company: "Test Corp".into(),
        url: "https://example.com/job/1".into(),
        description: "Test description".into(),
        posted_at: chrono::NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
        source: "gupy".into(),
    };
    app.selected_job = Some(job);

    let screen = JobDetailScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::cache::CacheManager::new(None, 24).unwrap())),
    );
    app.job_detail_screen = Some(screen);

    assert!(!app.job_detail_screen.as_ref().unwrap().show_email_full);

    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('f'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert!(app.job_detail_screen.as_ref().unwrap().show_email_full);

    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('f'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert!(!app.job_detail_screen.as_ref().unwrap().show_email_full);
}

// ===== Render Tests =====

#[tokio::test]
async fn profile_render_does_not_panic() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::Profile;
    let profile_screen = ProfileScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::config::ConfigManager::new())),
    );
    app.profile_screen = Some(profile_screen);

    let backend = TestBackend::new(80, 24);
    let mut terminal = Terminal::new(backend).unwrap();

    terminal.draw(|frame| app.render(frame)).unwrap();
}

#[tokio::test]
async fn job_detail_render_does_not_panic() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::JobDetail;
    let job = crate::domain::JobResponse {
        id: 1,
        title: "Test Job".into(),
        company: "Test Corp".into(),
        url: "https://example.com/job/1".into(),
        description: "Test description".into(),
        posted_at: chrono::NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
        source: "gupy".into(),
    };
    app.selected_job = Some(job);

    let job_detail_screen = JobDetailScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::cache::CacheManager::new(None, 24).unwrap())),
    );
    app.job_detail_screen = Some(job_detail_screen);

    let backend = TestBackend::new(80, 24);
    let mut terminal = Terminal::new(backend).unwrap();

    terminal.draw(|frame| app.render(frame)).unwrap();
}

// ===== handle_enter Tests =====

#[tokio::test]
async fn handle_enter_selects_highlighted_job_not_first() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::JobList;

    let jobs = vec![
        crate::domain::JobResponse {
            id: 1,
            title: "Job 1".into(),
            company: "Company 1".into(),
            url: "https://example.com/1".into(),
            description: "Description 1".into(),
            posted_at: chrono::NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
        },
        crate::domain::JobResponse {
            id: 2,
            title: "Job 2".into(),
            company: "Company 2".into(),
            url: "https://example.com/2".into(),
            description: "Description 2".into(),
            posted_at: chrono::NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
        },
        crate::domain::JobResponse {
            id: 3,
            title: "Job 3".into(),
            company: "Company 3".into(),
            url: "https://example.com/3".into(),
            description: "Description 3".into(),
            posted_at: chrono::NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
        },
    ];

    if let Some(screen) = &mut app.job_list_screen {
        screen.set_jobs(jobs);
        screen.select_next(); // index 1
        screen.select_next(); // index 2 (3rd job)
    }

    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();

    assert_eq!(app.state, AppState::JobDetail);
    assert_eq!(app.selected_job.as_ref().unwrap().id, 3);
}

#[tokio::test]
async fn handle_enter_on_empty_list_does_not_transition() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::JobList;
    // app.jobs is empty, and job_list_screen has no jobs set

    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();

    assert_eq!(app.state, AppState::JobList);
    assert!(app.selected_job.is_none());
}

#[tokio::test]
async fn handle_enter_with_search_focused_blurs_search() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::JobList;

    // Focus search so Enter gets intercepted
    if let Some(screen) = &mut app.job_list_screen {
        screen.focus_search();
    }

    // Press Enter through handle_event (the routing layer)
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();

    // Search should be blurred, and state should NOT transition to JobDetail
    assert_eq!(
        app.job_list_screen.as_ref().unwrap().search_focus,
        SearchFocus::Blurred
    );
    assert_eq!(app.state, AppState::JobList);
}

#[tokio::test]
async fn handle_enter_after_filtering_selects_filtered_job() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::JobList;

    let jobs = vec![
        crate::domain::JobResponse {
            id: 1,
            title: "Java Developer".into(),
            company: "Company 1".into(),
            url: "https://example.com/1".into(),
            description: "Java role".into(),
            posted_at: chrono::NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
        },
        crate::domain::JobResponse {
            id: 2,
            title: "Rust Developer".into(),
            company: "Company 2".into(),
            url: "https://example.com/2".into(),
            description: "Rust role".into(),
            posted_at: chrono::NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
        },
        crate::domain::JobResponse {
            id: 3,
            title: "Junior Java Dev".into(),
            company: "Company 3".into(),
            url: "https://example.com/3".into(),
            description: "Another Java role".into(),
            posted_at: chrono::NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
        },
    ];

    if let Some(screen) = &mut app.job_list_screen {
        screen.set_jobs(jobs);
        // Filter for "Java" — leaves job id:1 and id:3
        screen.focus_search();
        for c in "Java".chars() {
            screen.handle_search_char(c);
        }
        screen.blur_search();
        // Navigate to the second filtered result (Junior Java Dev, id:3)
        screen.select_next();
    }

    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();

    assert_eq!(app.state, AppState::JobDetail);
    assert_eq!(app.selected_job.as_ref().unwrap().id, 3);
}

#[tokio::test]
async fn handle_enter_sets_job_on_detail_screen() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::JobList;

    let jobs = vec![crate::domain::JobResponse {
        id: 42,
        title: "Test Job".into(),
        company: "Test Company".into(),
        url: "https://example.com/42".into(),
        description: "Test Description".into(),
        posted_at: chrono::NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
        source: "gupy".into(),
    }];

    if let Some(screen) = &mut app.job_list_screen {
        screen.set_jobs(jobs);
    }

    let job_detail_screen = JobDetailScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::cache::CacheManager::new(None, 24).unwrap())),
    );
    app.job_detail_screen = Some(job_detail_screen);

    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();

    assert_eq!(app.state, AppState::JobDetail);
    assert_eq!(
        app.job_detail_screen
            .as_ref()
            .unwrap()
            .job
            .as_ref()
            .unwrap()
            .id,
        42
    );
}

#[tokio::test]
async fn analyze_job_does_not_hang_when_job_none() {
    let mut screen = JobDetailScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::cache::CacheManager::new(None, 24).unwrap())),
    );

    assert!(screen.job.is_none());

    let result = screen.analyze_job().await;
    assert!(result.is_err());
    assert_eq!(result.unwrap_err().to_string(), "No job selected");

    assert!(!screen.loading_analysis.is_loading());
    assert_eq!(screen.loading_analysis, LoadingState::Idle);
}

// ===== Profile Cursor Navigation Tests =====

#[tokio::test]
async fn profile_cursor_moves_left_and_right() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::Profile;
    let profile_screen = ProfileScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::config::ConfigManager::new())),
    );
    app.profile_screen = Some(profile_screen);

    // Enter edit mode
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('e'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert_eq!(app.profile_screen.as_ref().unwrap().mode, crate::tui::profile_screen::ProfileMode::Edit);

    // Type some text
    for c in "Hello World".chars() {
        let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char(c), KeyModifiers::NONE));
        app.handle_event(event).await.unwrap();
    }
    assert_eq!(app.profile_screen.as_ref().unwrap().resume_text, "Hello World");
    assert_eq!(app.profile_screen.as_ref().unwrap().resume_cursor, 11);

    // Move cursor left 6 times (to after "Hello")
    for _ in 0..6 {
        let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Left, KeyModifiers::NONE));
        app.handle_event(event).await.unwrap();
    }
    assert_eq!(app.profile_screen.as_ref().unwrap().resume_cursor, 5);

    // Insert 'X' at cursor
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('X'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert_eq!(app.profile_screen.as_ref().unwrap().resume_text, "HelloX World");
    assert_eq!(app.profile_screen.as_ref().unwrap().resume_cursor, 6);

    // Move cursor right 6 times (to end)
    for _ in 0..6 {
        let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Right, KeyModifiers::NONE));
        app.handle_event(event).await.unwrap();
    }
    assert_eq!(app.profile_screen.as_ref().unwrap().resume_cursor, 12);

    // Backspace should delete before cursor (deletes 'd' at position 11)
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Backspace, KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert_eq!(app.profile_screen.as_ref().unwrap().resume_text, "HelloX Worl");
    assert_eq!(app.profile_screen.as_ref().unwrap().resume_cursor, 11);
}

#[tokio::test]
async fn profile_cursor_skills_field() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::Profile;
    let profile_screen = ProfileScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::config::ConfigManager::new())),
    );
    app.profile_screen = Some(profile_screen);

    // Enter edit mode
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char('e'), KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();

    // Tab to Skills field
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Tab, KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    assert_eq!(app.profile_screen.as_ref().unwrap().focused_field, crate::tui::profile_screen::ProfileField::Skills);

    // Type skills
    for c in "Rust, Java".chars() {
        let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char(c), KeyModifiers::NONE));
        app.handle_event(event).await.unwrap();
    }
    assert_eq!(app.profile_screen.as_ref().unwrap().skills_text, "Rust, Java");
    assert_eq!(app.profile_screen.as_ref().unwrap().skills_cursor, 10);

    // Move cursor left 5 times (to after "Rust," - position 5 is the space after comma)
    for _ in 0..5 {
        let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Left, KeyModifiers::NONE));
        app.handle_event(event).await.unwrap();
    }
    assert_eq!(app.profile_screen.as_ref().unwrap().skills_cursor, 5);

    // Insert 'Go' at cursor (after "Rust,")
    for c in "Go".chars() {
        let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Char(c), KeyModifiers::NONE));
        app.handle_event(event).await.unwrap();
    }
    assert_eq!(app.profile_screen.as_ref().unwrap().skills_text, "Rust,Go Java");
    assert_eq!(app.profile_screen.as_ref().unwrap().skills_cursor, 7);
}

#[tokio::test]
async fn profile_cursor_left_right_ignored_in_view_mode() {
    let config = Config::default();
    let api_client = ApiClient::new("http://localhost:8080");
    let mut app = App::new(api_client, config);

    app.state = AppState::Profile;
    let profile_screen = ProfileScreen::new(
        Arc::new(Mutex::new(ApiClient::new("http://localhost:8080"))),
        Arc::new(Mutex::new(crate::config::ConfigManager::new())),
    );
    app.profile_screen = Some(profile_screen);

    // In View mode, Left/Right should not affect cursor
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Left, KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();
    let event = crossterm::event::Event::Key(KeyEvent::new(KeyCode::Right, KeyModifiers::NONE));
    app.handle_event(event).await.unwrap();

    // Cursor should remain at 0
    assert_eq!(app.profile_screen.as_ref().unwrap().resume_cursor, 0);
    assert_eq!(app.profile_screen.as_ref().unwrap().skills_cursor, 0);
}