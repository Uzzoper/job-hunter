use crate::api::ApiClient;
use crate::config::ConfigManager;
use crate::domain::{AuthRequest, AuthResponse, LoginRequest};
use crate::tui::app::{App, AppState};
use crate::tui::theme::{Theme, render_error_popup, render_loading};
use ratatui::{
    Frame,
    layout::{Alignment, Constraint, Direction, Layout, Rect},
    style::{Modifier, Style},
    text::{Line, Span, Text},
    widgets::{Block, Borders, Paragraph},
};
use std::sync::Arc;
use tokio::sync::Mutex;

/// Authentication mode: Login or Register
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AuthMode {
    Login,
    Register,
}

impl AuthMode {
    pub fn display_name(&self) -> &'static str {
        match self {
            AuthMode::Login => "LOGIN",
            AuthMode::Register => "REGISTER",
        }
    }

    pub fn toggle(&self) -> Self {
        match self {
            AuthMode::Login => AuthMode::Register,
            AuthMode::Register => AuthMode::Login,
        }
    }
}

/// Input field focus state
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InputField {
    Name,
    Email,
    Password,
}

impl InputField {
    pub fn display_name(&self) -> &'static str {
        match self {
            InputField::Name => "Name",
            InputField::Email => "Email",
            InputField::Password => "Password",
        }
    }

    pub fn is_visible_in_mode(&self, mode: AuthMode) -> bool {
        match (self, mode) {
            (InputField::Name, AuthMode::Register) => true,
            (InputField::Name, AuthMode::Login) => false,
            (InputField::Email, _) => true,
            (InputField::Password, _) => true,
        }
    }

    pub fn next(&self, mode: AuthMode) -> Self {
        let fields = Self::visible_fields(mode);
        let idx = fields.iter().position(|f| f == self).unwrap_or(0);
        fields[(idx + 1) % fields.len()]
    }

    pub fn prev(&self, mode: AuthMode) -> Self {
        let fields = Self::visible_fields(mode);
        let idx = fields.iter().position(|f| f == self).unwrap_or(0);
        fields[(idx + fields.len() - 1) % fields.len()]
    }

    fn visible_fields(mode: AuthMode) -> Vec<Self> {
        match mode {
            AuthMode::Login => vec![InputField::Email, InputField::Password],
            AuthMode::Register => vec![InputField::Name, InputField::Email, InputField::Password],
        }
    }
}

/// Authentication screen state and logic
pub struct AuthScreen {
    pub mode: AuthMode,
    pub focused_field: InputField,
    pub name: String,
    pub email: String,
    pub password: String,
    pub error_message: Option<String>,
    pub is_loading: bool,
    api_client: Arc<Mutex<ApiClient>>,
    config_manager: Arc<Mutex<ConfigManager>>,
}

impl AuthScreen {
    pub fn new(api_client: Arc<Mutex<ApiClient>>, config_manager: Arc<Mutex<ConfigManager>>) -> Self {
        Self {
            mode: AuthMode::Login,
            focused_field: InputField::Email,
            name: String::new(),
            email: String::new(),
            password: String::new(),
            error_message: None,
            is_loading: false,
            api_client,
            config_manager,
        }
    }

    pub fn toggle_mode(&mut self) {
        self.mode = self.mode.toggle();
        self.focused_field = InputField::visible_fields(self.mode)[0];
        self.clear_error();
        self.clear_fields();
    }

    pub fn focus_next(&mut self) {
        self.focused_field = self.focused_field.next(self.mode);
    }

    pub fn focus_prev(&mut self) {
        self.focused_field = self.focused_field.prev(self.mode);
    }

    pub fn handle_char(&mut self, c: char) {
        if self.is_loading {
            return;
        }
        match self.focused_field {
            InputField::Name if self.mode == AuthMode::Register => self.name.push(c),
            InputField::Email => self.email.push(c),
            InputField::Password => self.password.push(c),
            _ => {}
        }
        self.clear_error();
    }

    pub fn handle_backspace(&mut self) {
        if self.is_loading {
            return;
        }
        match self.focused_field {
            InputField::Name if self.mode == AuthMode::Register => {
                self.name.pop();
            }
            InputField::Email => {
                self.email.pop();
            }
            InputField::Password => {
                self.password.pop();
            }
            _ => {}
        }
        self.clear_error();
    }

    pub fn set_error(&mut self, message: String) {
        self.error_message = Some(message);
    }

    pub fn clear_error(&mut self) {
        self.error_message = None;
    }

    pub fn set_loading(&mut self, loading: bool) {
        self.is_loading = loading;
    }

    pub fn clear_fields(&mut self) {
        self.name.clear();
        self.email.clear();
        self.password.clear();
    }

    pub fn is_email_valid(&self) -> bool {
        !self.email.is_empty() && self.email.contains('@') && self.email.contains('.')
    }

    pub fn is_form_valid(&self) -> bool {
        match self.mode {
            AuthMode::Login => self.is_email_valid() && !self.password.is_empty(),
            AuthMode::Register => {
                !self.name.is_empty() && self.is_email_valid() && !self.password.is_empty()
            }
        }
    }

    pub fn masked_password(&self) -> String {
        "*".repeat(self.password.len())
    }

pub async fn submit(&mut self, app: &mut App) -> anyhow::Result<()> {
        if !self.is_form_valid() {
            self.set_error(match self.mode {
                AuthMode::Login => "Please enter valid email and password".to_string(),
                AuthMode::Register => "Please fill all fields with valid data".to_string(),
            });
            return Ok(());
        }

        self.set_loading(true);
        self.clear_error();

        // Clone the data we need before locking
        let email = self.email.clone();
        let password = self.password.clone();
        let name = self.name.clone();
        let mode = self.mode;

        // Get the client and config path, then drop locks before API call
        let client = self.api_client.clone();
        let config_path = {
            let config_manager = self.config_manager.lock().await;
            config_manager.path().to_path_buf()
        };

        let result = match mode {
            AuthMode::Login => {
                let req = LoginRequest { email, password };
                client.lock().await.login(&req).await
            }
            AuthMode::Register => {
                let req = AuthRequest { name, email, password };
                client.lock().await.register(&req).await
            }
        };

        self.set_loading(false);

        match result {
            Ok(auth_response) => {
                self.handle_auth_success(app, auth_response, config_path).await;
            }
            Err(e) => {
                self.set_error(format!("{}", e));
                self.password.clear();
            }
        }

        Ok(())
    }

    async fn handle_auth_success(
        &mut self,
        app: &mut App,
        auth_response: AuthResponse,
        config_path: std::path::PathBuf,
    ) {
        app.token = Some(auth_response.token.clone());
        app.auth = Some(auth_response.clone());
        {
            let mut client = app.api_client.lock().await;
            client.set_token(&auth_response.token);
        }

        // Reload config manager to save token
        if let Ok(mut config_manager) = ConfigManager::load(Some(&config_path)) {
            config_manager.set_token(&auth_response.token);
            if let Err(e) = config_manager.save() {
                self.set_error(format!("Failed to save token: {}", e));
                return;
            }
        }

        app.state = AppState::JobList;
        self.clear_fields();
    }

    pub fn draw(&mut self, frame: &mut Frame, area: Rect) {
        let theme = Theme::detect();

        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Length(9),
                Constraint::Length(3),
                Constraint::Min(0),
                Constraint::Length(3),
            ])
            .split(area);

        self.draw_logo(frame, chunks[0], &theme);
        self.draw_mode_tabs(frame, chunks[1], &theme);
        self.draw_form(frame, chunks[2], &theme);
        self.draw_status(frame, chunks[3], &theme);
    }

    fn draw_logo(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let logo_lines = [
            "      _  ___  ____        _   _ _   _ _   _ _____ _____ ____  ",
            "     | |/ _ \\| __ )      | | | | | | | \\ | |_   _| ____|  _ \\ ",
            "  _  | | | | |  _ \\ _____| |_| | | | |  \\| | | | |  _| | |_) |",
            " | |_| | |_| | |_) |_____|  _  | |_| | |\\  | | | | |___|  _ < ",
            "  \\___/ \\___/|____/      |_| |_|\\___/|_| \\_| |_| |_____|_| \\_\\",
        ];

        let subtitle = "  CYBERPUNK JOB HUNTER  ";

        let mut lines = Vec::new();
        for (i, line) in logo_lines.iter().enumerate() {
            let style = if i < 3 {
                theme.style_title()
            } else {
                theme.style_highlight()
            };
            lines.push(Line::from(Span::styled(*line, style)));
        }

        let subtitle_style = theme.style_dim();
        lines.push(Line::from(Span::styled(subtitle, subtitle_style)));

        let logo_width = 62u16;
        let logo_area = Rect {
            x: area.x + (area.width.saturating_sub(logo_width)) / 2,
            y: area.y,
            width: logo_width.min(area.width),
            height: (logo_lines.len() + 1) as u16,
        };

        frame.render_widget(Paragraph::new(lines), logo_area);
    }

    fn draw_mode_tabs(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let login_style = if self.mode == AuthMode::Login {
            theme.style_selected()
        } else {
            theme.style_dim()
        };
        let register_style = if self.mode == AuthMode::Register {
            theme.style_selected()
        } else {
            theme.style_dim()
        };

        let tabs = Paragraph::new(vec![Line::from(vec![
            Span::styled(format!("  {}  ", AuthMode::Login.display_name()), login_style),
            Span::styled("  ", theme.style_dim()),
            Span::styled(format!("  {}  ", AuthMode::Register.display_name()), register_style),
        ])])
        .alignment(Alignment::Center)
        .block(
            Block::default()
                .borders(Borders::BOTTOM)
                .border_style(theme.style_border(false)),
        );

        frame.render_widget(tabs, area);
    }

    fn draw_form(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let fields = InputField::visible_fields(self.mode);
        let field_height = 3;
        let total_height = fields.len() as u16 * field_height;

        let form_area = if area.height > total_height {
            let y_offset = (area.height - total_height) / 2;
            Rect {
                x: area.x + 4,
                y: area.y + y_offset,
                width: area.width.saturating_sub(8),
                height: total_height,
            }
        } else {
            Rect {
                x: area.x + 4,
                y: area.y,
                width: area.width.saturating_sub(8),
                height: area.height,
            }
        };

        let field_chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints(vec![Constraint::Length(field_height); fields.len()])
            .split(form_area);

        for (i, field) in fields.iter().enumerate() {
            let is_focused = self.focused_field == *field;
            self.draw_field(frame, field_chunks[i], field, is_focused, theme);
        }
    }

    fn draw_field(&self, frame: &mut Frame, area: Rect, field: &InputField, focused: bool, theme: &Theme) {
        let masked = self.masked_password();
        let (label, value, is_password) = match field {
            InputField::Name => ("Name", self.name.as_str(), false),
            InputField::Email => ("Email", self.email.as_str(), false),
            InputField::Password => ("Password", masked.as_str(), true),
        };

        let label_style = if focused {
            Style::default().fg(theme.primary).add_modifier(Modifier::BOLD)
        } else {
            theme.style_dim()
        };

        let value_style = Style::default().fg(theme.text).bg(theme.surface);

        let border_style = if focused {
            theme.style_border(true)
        } else {
            theme.style_border(false)
        };

        let display_value = if is_password && value.is_empty() && !focused {
            "••••••"
        } else {
            value
        };

        let cursor_pos = if focused {
            Some((area.x + 1 + display_value.len() as u16, area.y + 1))
        } else {
            None
        };

        let field_block = Block::default()
            .borders(Borders::ALL)
            .border_style(border_style)
            .title(Span::styled(format!(" {} ", label), label_style));

        let field_text = if focused || label != "Password" {
            display_value.to_string()
        } else {
            "•".repeat(display_value.chars().count())
        };

        let paragraph = Paragraph::new(field_text)
            .style(value_style)
            .block(field_block);

        frame.render_widget(paragraph, area);

        if let Some((cx, cy)) = cursor_pos {
            frame.set_cursor_position((cx.min(area.x + area.width - 2), cy));
        }
    }

    fn draw_status(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        if self.is_loading {
            render_loading(frame, area, theme, "Authenticating...");
        } else if let Some(error) = &self.error_message {
            let hint = if !error.is_empty() {
                "[Enter] Dismiss  [r] Retry"
            } else {
                "[Enter] Dismiss"
            };
            render_error_popup(frame, area, theme, error, hint);
        } else {
            let shortcuts = match self.mode {
                AuthMode::Login => " [Tab] Switch  [Enter] Login  [q] Quit ",
                AuthMode::Register => " [Tab] Switch  [Enter] Register  [q] Quit ",
            };
            let para = Paragraph::new(Text::styled(shortcuts, theme.style_dim()))
                .alignment(Alignment::Center)
                .block(Block::default().borders(Borders::ALL).border_style(theme.style_dim()));
            frame.render_widget(para, area);
        }
    }
}

pub fn draw(frame: &mut Frame, area: Rect, app: &mut App) {
    if app.auth_screen.is_none() {
        let api_client = app.api_client.clone();
        let config_manager = Arc::new(Mutex::new(ConfigManager::load(None).unwrap_or_default()));
        app.auth_screen = Some(AuthScreen::new(api_client, config_manager));
    }

    if let Some(screen) = &mut app.auth_screen {
        screen.draw(frame, area);
    }
}

pub async fn handle_event(event: crossterm::event::Event, app: &mut App) -> anyhow::Result<()> {
    if let crossterm::event::Event::Key(key) = event {
        if app.error_message.is_some() {
            return handle_error_popup(key, app).await;
        }

        let is_loading = app.auth_screen.as_ref().map(|s| s.is_loading).unwrap_or(false);
        if is_loading {
            return Ok(());
        }

        match key.code {
            crossterm::event::KeyCode::Char('q') | crossterm::event::KeyCode::Char('Q') => {
                app.should_quit = true;
                app.state = AppState::Quitting;
            }
            crossterm::event::KeyCode::Esc => {
                app.should_quit = true;
                app.state = AppState::Quitting;
            }
            crossterm::event::KeyCode::Tab => {
                if let Some(screen) = &mut app.auth_screen {
                    screen.toggle_mode();
                }
            }
            crossterm::event::KeyCode::Enter => {
                // Extract the screen to avoid double mutable borrow
                if let Some(mut screen) = app.auth_screen.take() {
                    let result = screen.submit(app).await;
                    app.auth_screen = Some(screen);
                    result?;
                }
            }
            crossterm::event::KeyCode::Backspace => {
                if let Some(screen) = &mut app.auth_screen {
                    screen.handle_backspace();
                }
            }
            crossterm::event::KeyCode::Char(c) => {
                if let Some(screen) = &mut app.auth_screen {
                    screen.handle_char(c);
                }
            }
            crossterm::event::KeyCode::Up => {
                if let Some(screen) = &mut app.auth_screen {
                    screen.focus_prev();
                }
            }
            crossterm::event::KeyCode::Down => {
                if let Some(screen) = &mut app.auth_screen {
                    screen.focus_next();
                }
            }
            _ => {}
        }
    }
    Ok(())
}

async fn handle_error_popup(key: crossterm::event::KeyEvent, app: &mut App) -> anyhow::Result<()> {
    match key.code {
        crossterm::event::KeyCode::Enter => {
            app.clear_error();
        }
        crossterm::event::KeyCode::Char('r') | crossterm::event::KeyCode::Char('R') => {
            app.clear_error();
        }
        _ => {}
    }
    Ok(())
}

impl Default for ConfigManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::ApiClient;
    use ratatui::backend::TestBackend;
    use ratatui::Terminal;

    fn create_test_screen() -> AuthScreen {
        let api_client = Arc::new(Mutex::new(ApiClient::new("http://localhost:8080")));
        let config_manager = Arc::new(Mutex::new(ConfigManager::new()));
        AuthScreen::new(api_client, config_manager)
    }

    fn create_test_terminal() -> Terminal<TestBackend> {
        Terminal::new(TestBackend::new(80, 24)).unwrap()
    }

    #[test]
    fn auth_screen_new_creates_login_mode_by_default() {
        let screen = create_test_screen();

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
        let mut screen = create_test_screen();
        screen.toggle_mode();

        assert_eq!(screen.mode, AuthMode::Register);
        assert_eq!(screen.focused_field, InputField::Name);
        assert!(screen.name.is_empty());
    }

    #[test]
    fn auth_screen_can_switch_back_to_login_mode() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.toggle_mode();

        assert_eq!(screen.mode, AuthMode::Login);
        assert_eq!(screen.focused_field, InputField::Email);
    }

    #[test]
    fn auth_screen_email_field_accepts_input() {
        let mut screen = create_test_screen();
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
        let mut screen = create_test_screen();
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
        let mut screen = create_test_screen();
        assert_eq!(screen.mode, AuthMode::Login);

        screen.handle_char('J');
        screen.handle_char('o');
        screen.handle_char('h');
        screen.handle_char('n');
        assert!(screen.name.is_empty());

        screen.toggle_mode();
        assert_eq!(screen.mode, AuthMode::Register);

        screen.handle_char('J');
        screen.handle_char('o');
        screen.handle_char('h');
        screen.handle_char('n');
        assert_eq!(screen.name, "John");
    }

    #[test]
    fn auth_screen_backspace_removes_last_char() {
        let mut screen = create_test_screen();
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

        screen.handle_backspace();
        assert!(screen.email.is_empty());
    }

    #[test]
    fn auth_screen_tab_cycles_focus_in_login_mode() {
        let mut screen = create_test_screen();
        assert_eq!(screen.focused_field, InputField::Email);

        screen.focus_next();
        assert_eq!(screen.focused_field, InputField::Password);

        screen.focus_next();
        assert_eq!(screen.focused_field, InputField::Email);
    }

    #[test]
    fn auth_screen_tab_cycles_focus_in_register_mode() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        assert_eq!(screen.focused_field, InputField::Name);

        screen.focus_next();
        assert_eq!(screen.focused_field, InputField::Email);

        screen.focus_next();
        assert_eq!(screen.focused_field, InputField::Password);

        screen.focus_next();
        assert_eq!(screen.focused_field, InputField::Name);
    }

    #[test]
    fn auth_screen_validates_email_format() {
        let mut screen = create_test_screen();

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
        let mut screen = create_test_screen();
        assert!(screen.error_message.is_none());

        screen.set_error("Invalid credentials".to_string());
        assert_eq!(screen.error_message, Some("Invalid credentials".to_string()));

        screen.clear_error();
        assert!(screen.error_message.is_none());
    }

    #[test]
    fn auth_screen_loading_state() {
        let mut screen = create_test_screen();
        assert!(!screen.is_loading);

        screen.set_loading(true);
        assert!(screen.is_loading);

        screen.set_loading(false);
        assert!(!screen.is_loading);
    }

    #[test]
    fn auth_screen_form_render_does_not_panic() {
        let mut screen = create_test_screen();
        let mut terminal = create_test_terminal();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn auth_screen_register_form_render_does_not_panic() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        let mut terminal = create_test_terminal();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn auth_screen_error_display_render_does_not_panic() {
        let mut screen = create_test_screen();
        screen.set_error("Invalid credentials".to_string());
        let mut terminal = create_test_terminal();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn auth_screen_loading_spinner_render_does_not_panic() {
        let mut screen = create_test_screen();
        screen.set_loading(true);
        let mut terminal = create_test_terminal();

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