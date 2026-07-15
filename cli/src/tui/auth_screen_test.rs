#[cfg(test)]
mod tests {
    use crate::api::ApiClient;
    use crate::config::Config;
    use crate::tui::app::{App, AppState};
    use crate::tui::auth_screen::{AuthMode, AuthScreen, InputField};
    use ratatui::Frame;
    use ratatui::backend::TestBackend;
    use ratatui::Terminal;

    fn create_test_app() -> App {
        let config = Config::default();
        let api_client = ApiClient::new("http://localhost:8080");
        App::new(api_client, config)
    }

    fn create_test_terminal() -> Terminal<TestBackend> {
        Terminal::new(TestBackend::new(80, 24)).unwrap()
    }

    #[test]
    fn auth_screen_new_creates_login_mode_by_default() {
        let screen = AuthScreen::new();

        assert_eq!(screen.mode, AuthMode::Login);
        assert_eq!(screen.focused_field, InputField::Email);
        assert!(screen.email.is_empty());
        assert!(screen.password.is_empty());
        assert!(screen.name.is_empty());
        assert!(screen.error_message.is_none());
        assert!(!screen.is_loading);
    }

    #[test]
    fn auth_screen_can_switch_to_register_mode() {
        let mut screen = AuthScreen::new();
        screen.toggle_mode();

        assert_eq!(screen.mode, AuthMode::Register);
        assert_eq!(screen.focused_field, InputField::Name);
        assert!(screen.name.is_empty());
    }

    #[test]
    fn auth_screen_can_switch_back_to_login_mode() {
        let mut screen = AuthScreen::new();
        screen.toggle_mode();
        screen.toggle_mode();

        assert_eq!(screen.mode, AuthMode::Login);
        assert_eq!(screen.focused_field, InputField::Email);
    }

    #[test]
    fn auth_screen_email_field_accepts_input() {
        let mut screen = AuthScreen::new();
        screen.handle_char('t');
        screen.handle_char('e');
        screen.handle_char('s');
        screen.handle_char('t');
        screen.handle_char('@');
        screen.handle_char('e');
        screen.handle_char('x');
        screen.handle_char('a');
        screen.handle_char('m');
        screen.handle_char('p');
        screen.handle_char('l');
        screen.handle_char('e');
        screen.handle_char('.');
        screen.handle_char('c');
        screen.handle_char('o');
        screen.handle_char('m');

        assert_eq!(screen.email, "test@example.com");
    }

    #[test]
    fn auth_screen_password_field_accepts_input_masked() {
        let mut screen = AuthScreen::new();
        screen.focus_next();
        screen.handle_char('s');
        screen.handle_char('e');
        screen.handle_char('c');
        screen.handle_char('r');
        screen.handle_char('e');
        screen.handle_char('t');

        assert_eq!(screen.password, "secret");
        assert_eq!(screen.masked_password(), "******");
    }

    #[test]
    fn auth_screen_name_field_only_in_register_mode() {
        let mut screen = AuthScreen::new();
        assert_eq!(screen.mode, AuthMode::Login);

        // In login mode, name field should not be focusable
        screen.handle_char('J');
        screen.handle_char('o');
        screen.handle_char('h');
        screen.handle_char('n');
        assert!(screen.name.is_empty()); // Name should not accept input in login mode

        // Switch to register mode
        screen.toggle_mode();
        assert_eq!(screen.mode, AuthMode::Register);

        // Now name field should accept input
        screen.handle_char('J');
        screen.handle_char('o');
        screen.handle_char('h');
        screen.handle_char('n');
        assert_eq!(screen.name, "John");
    }

    #[test]
    fn auth_screen_backspace_removes_last_char() {
        let mut screen = AuthScreen::new();
        screen.handle_char('t');
        screen.handle_char('e');
        screen.handle_char('s');
        screen.handle_char('t');
        assert_eq!(screen.email, "test");

        screen.handle_backspace();
        assert_eq!(screen.email, "tes");

        screen.handle_backspace();
        screen.handle_backspace();
        screen.handle_backspace();
        assert!(screen.email.is_empty());

        // Backspace on empty should not panic
        screen.handle_backspace();
        assert!(screen.email.is_empty());
    }

    #[test]
    fn auth_screen_tab_cycles_focus_in_login_mode() {
        let mut screen = AuthScreen::new();
        assert_eq!(screen.focused_field, InputField::Email);

        screen.focus_next();
        assert_eq!(screen.focused_field, InputField::Password);

        screen.focus_next();
        assert_eq!(screen.focused_field, InputField::Email); // Cycles back
    }

    #[test]
    fn auth_screen_tab_cycles_focus_in_register_mode() {
        let mut screen = AuthScreen::new();
        screen.toggle_mode(); // Register mode
        assert_eq!(screen.focused_field, InputField::Name);

        screen.focus_next();
        assert_eq!(screen.focused_field, InputField::Email);

        screen.focus_next();
        assert_eq!(screen.focused_field, InputField::Password);

        screen.focus_next();
        assert_eq!(screen.focused_field, InputField::Name); // Cycles back
    }

    #[test]
    fn auth_screen_validates_email_format() {
        let mut screen = AuthScreen::new();

        screen.email = "invalid-email".to_string();
        assert!(!screen.is_email_valid());

        screen.email = "valid@example.com".to_string();
        assert!(screen.is_email_valid());

        screen.email = "user.name@domain.org".to_string();
        assert!(screen.is_email_valid());

        screen.email = "".to_string();
        assert!(!screen.is_email_valid());
    }

    #[test]
    fn auth_screen_can_set_error_message() {
        let mut screen = AuthScreen::new();
        assert!(screen.error_message.is_none());

        screen.set_error("Invalid credentials".to_string());
        assert_eq!(screen.error_message, Some("Invalid credentials".to_string()));

        screen.clear_error();
        assert!(screen.error_message.is_none());
    }

    #[test]
    fn auth_screen_loading_state() {
        let mut screen = AuthScreen::new();
        assert!(!screen.is_loading);

        screen.set_loading(true);
        assert!(screen.is_loading);

        screen.set_loading(false);
        assert!(!screen.is_loading);
    }

    #[test]
    fn auth_screen_form_render_does_not_panic() {
        let mut screen = AuthScreen::new();
        let mut terminal = create_test_terminal();

        // This should not panic
        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn auth_screen_register_form_render_does_not_panic() {
        let mut screen = AuthScreen::new();
        screen.toggle_mode(); // Switch to register
        let mut terminal = create_test_terminal();

        // This should not panic
        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn auth_screen_error_display_render_does_not_panic() {
        let mut screen = AuthScreen::new();
        screen.set_error("Invalid credentials".to_string());
        let mut terminal = create_test_terminal();

        // This should not panic
        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn auth_screen_loading_spinner_render_does_not_panic() {
        let mut screen = AuthScreen::new();
        screen.set_loading(true);
        let mut terminal = create_test_terminal();

        // This should not panic
        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn auth_mode_display_names() {
        assert_eq!(AuthMode::Login.display_name(), "LOGIN");
        assert_eq!(AuthMode::Register.display_name(), "REGISTER");
    }

    #[test]
    fn input_field_display_names() {
        assert_eq!(InputField::Name.display_name(), "Name");
        assert_eq!(InputField::Email.display_name(), "Email");
        assert_eq!(InputField::Password.display_name(), "Password");
    }
}