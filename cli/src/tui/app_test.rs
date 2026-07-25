#[cfg(test)]
mod tests {
    use crate::api::ApiClient;
    use crate::config::Config;
    use crate::tui::app::{App, AppState, Screen};

    fn create_test_app() -> App {
        let config = Config::default();
        let api_client = ApiClient::new("http://localhost:8080");
        App::new(api_client, config)
    }

    #[test]
    fn app_new_creates_app_with_default_state() {
        let app = create_test_app();

        assert_eq!(app.state, AppState::Auth);
        assert!(!app.should_quit);
        assert!(app.token.is_none());
        assert!(app.jobs.is_empty());
        assert!(app.selected_job.is_none());
        assert!(app.profile.is_none());
    }

    #[test]
    fn app_state_transitions_auth_to_job_list() {
        let mut app = create_test_app();
        app.token = Some("test-token".to_string());

        app.state = AppState::JobList;

        assert_eq!(app.state, AppState::JobList);
    }

    #[test]
    fn app_state_transitions_job_list_to_job_detail() {
        let mut app = create_test_app();
        app.state = AppState::JobList;

        app.selected_job = Some(crate::domain::JobResponse {
            id: 1,
            title: "Test Job".into(),
            company: "Test Corp".into(),
            url: "https://example.com/job/1".into(),
            description: "Test description".into(),
            posted_at: chrono::NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
        });
        app.state = AppState::JobDetail;

        assert_eq!(app.state, AppState::JobDetail);
        assert!(app.selected_job.is_some());
    }

    #[test]
    fn app_state_transitions_job_detail_back_to_job_list() {
        let mut app = create_test_app();
        app.state = AppState::JobDetail;

        app.state = AppState::JobList;
        app.selected_job = None;

        assert_eq!(app.state, AppState::JobList);
        assert!(app.selected_job.is_none());
    }

    #[test]
    fn app_state_transitions_job_list_to_profile() {
        let mut app = create_test_app();
        app.state = AppState::JobList;

        app.state = AppState::Profile;

        assert_eq!(app.state, AppState::Profile);
    }

    #[test]
    fn app_state_transitions_profile_back_to_job_list() {
        let mut app = create_test_app();
        app.state = AppState::Profile;

        app.state = AppState::JobList;

        assert_eq!(app.state, AppState::JobList);
    }

    #[test]
    fn app_state_transitions_any_to_quitting_on_quit() {
        let mut app = create_test_app();

        app.state = AppState::Auth;
        app.should_quit = true;
        assert!(app.should_quit);

        app.state = AppState::JobList;
        app.should_quit = true;
        assert!(app.should_quit);

        app.state = AppState::JobDetail;
        app.should_quit = true;
        assert!(app.should_quit);

        app.state = AppState::Profile;
        app.should_quit = true;
        assert!(app.should_quit);
    }

    #[test]
    fn app_should_quit_returns_false_initially() {
        let app = create_test_app();
        assert!(!app.should_quit());
    }

    #[test]
    fn app_should_quit_returns_true_when_set() {
        let mut app = create_test_app();
        app.should_quit = true;
        assert!(app.should_quit());
    }
}