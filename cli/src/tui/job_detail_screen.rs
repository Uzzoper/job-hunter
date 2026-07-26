use crate::api::ApiClient;
use crate::cache::CacheManager;
use crate::domain::{ApplyType, EmailDraftResponse, EmailStatus, JobAnalysis, JobResponse};
use crate::tui::theme::{Theme, render_empty_state, render_error_popup, render_loading, spinner_frame};
use crate::tui::Toast;
use arboard::Clipboard;
use ratatui::{
    Frame,
    layout::{Alignment, Constraint, Direction, Layout, Rect},
    style::Style,
    text::{Line, Span, Text},
    widgets::{Block, Borders, Gauge, List, ListItem, Paragraph, Wrap},
};
use std::sync::Arc;

use tokio::sync::Mutex;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PendingAction {
    Analyze,
    GenerateEmail,
}

/// Loading state for async operations
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LoadingState {
    Idle,
    Loading,
    Success,
    Error(String),
}

impl LoadingState {
    pub fn is_loading(&self) -> bool {
        matches!(self, LoadingState::Loading)
    }

    pub fn is_error(&self) -> bool {
        matches!(self, LoadingState::Error(_))
    }

    pub fn error_message(&self) -> Option<&str> {
        match self {
            LoadingState::Error(msg) => Some(msg),
            _ => None,
        }
    }
}

/// Job Detail Screen with analysis, email, and clipboard functionality
pub struct JobDetailScreen {
    pub job: Option<JobResponse>,
    pub analysis: Option<JobAnalysis>,
    pub email: Option<EmailDraftResponse>,
    pub loading_analysis: LoadingState,
    pub loading_email: LoadingState,
    pub show_email_expanded: bool,
    pub show_email_full: bool,
    api_client: Arc<Mutex<ApiClient>>,
    cache: Arc<Mutex<CacheManager>>,
    clipboard: Option<Clipboard>,
    toast: Option<Toast>,
    analysis_error: Option<String>,
    email_error: Option<String>,
    pending_job_id: Option<i64>,
    pub pending_action: Option<PendingAction>,
}

impl JobDetailScreen {
    /// Create a new JobDetailScreen (job can be set later with set_job)
    pub fn new(
        api_client: Arc<Mutex<ApiClient>>,
        cache: Arc<Mutex<CacheManager>>,
    ) -> Self {
        let clipboard = Clipboard::new().ok();
        Self {
            job: None,
            analysis: None,
            email: None,
            loading_analysis: LoadingState::Idle,
            loading_email: LoadingState::Idle,
            show_email_expanded: false,
            show_email_full: false,
            api_client,
            cache,
            clipboard,
            toast: None,
            analysis_error: None,
            email_error: None,
            pending_job_id: None,
            pending_action: None,
        }
    }

    /// Set the job and reset analysis/email state
    pub fn set_job(&mut self, job: JobResponse) {
        let job_id = job.id;
        self.job = Some(job);
        self.analysis = None;
        self.email = None;
        self.loading_analysis = LoadingState::Idle;
        self.loading_email = LoadingState::Idle;
        self.analysis_error = None;
        self.email_error = None;
        self.pending_action = None;

        self.pending_job_id = Some(job_id);
    }


    /// Load and apply cached analysis and email data
    pub fn apply_cached_data(&mut self) {
        if let Some(job) = &self.job {
            let Ok(cache) = self.cache.try_lock() else { return; };
            if let Ok(Some(cached_job)) = cache.get_job(job.id) {
                if let Some(analysis_json) = cached_job.analysis_json
                    && let Ok(analysis) = serde_json::from_str::<JobAnalysis>(&analysis_json) {
                        self.analysis = Some(analysis);
                    }
                if cached_job.email_subject.is_some() || cached_job.email_body.is_some() {
                    self.email = Some(EmailDraftResponse {
                        id: 0,
                        job_id: job.id,
                        subject: cached_job.email_subject.unwrap_or_default(),
                        body: cached_job.email_body.unwrap_or_default(),
                        status: cached_job.email_status
                            .and_then(|s| serde_json::from_str(&s).ok())
                            .unwrap_or(EmailStatus::Pending),
                        generated_at: cached_job.cached_at,
                    });
                }
            }
        }
    }

    /// Show a toast notification
    fn show_toast(&mut self, message: String) {
        self.toast = Some(Toast::new(message));
    }

    /// Set loading state for the given pending action
    pub fn start_loading(&mut self, action: PendingAction) {
        match action {
            PendingAction::Analyze => {
                self.loading_analysis = LoadingState::Loading;
                self.analysis_error = None;
            }
            PendingAction::GenerateEmail => {
                self.loading_email = LoadingState::Loading;
                self.email_error = None;
            }
        }
    }

    /// Clear expired toast
    fn update_toast(&mut self) {
        if let Some(toast) = &self.toast
            && toast.is_expired() {
                self.toast = None;
            }
    }

    /// Trigger job analysis via API
    /// NOTE: loading_analysis flag must be set by the caller before calling this
    pub async fn analyze_job(&mut self) -> anyhow::Result<()> {
        let job = self.job.as_ref().ok_or_else(|| anyhow::anyhow!("No job selected"))?;
        let job_id = job.id;
        let client = self.api_client.clone();

        let result = client.lock().await.analyze_job(job_id).await;

        match result {
            Ok(analysis) => {
                self.analysis = Some(analysis.clone());
                self.loading_analysis = LoadingState::Success;

                let _ = self.cache.lock().await.update_cache_on_analyze(job_id, &analysis);

                self.show_toast("Analysis complete!".to_string());
            }
            Err(e) => {
                let err_msg = e.to_string();
                self.analysis_error = Some(err_msg.clone());
                self.loading_analysis = LoadingState::Error(err_msg.clone());
                self.show_toast(format!("Analysis failed: {}", err_msg));
            }
        }

        Ok(())
    }

    /// Generate email draft via API
    /// NOTE: loading_email flag must be set by the caller before calling this
    pub async fn generate_email(&mut self) -> anyhow::Result<()> {
        let job = self.job.as_ref().ok_or_else(|| anyhow::anyhow!("No job selected"))?;
        let job_id = job.id;
        let client = self.api_client.clone();

        let result = client.lock().await.generate_email(job_id).await;

        match result {
            Ok(email) => {
                self.email = Some(email.clone());
                self.loading_email = LoadingState::Success;

                let _ = self.cache.lock().await.update_cache_on_email(job_id, &email);

                self.show_toast("Email generated!".to_string());
            }
            Err(e) => {
                let err_msg = e.to_string();
                self.email_error = Some(err_msg.clone());
                self.loading_email = LoadingState::Error(err_msg.clone());
                self.show_toast(format!("Email generation failed: {}", err_msg));
            }
        }

        Ok(())
    }

    /// Copy email body to clipboard
    pub fn copy_email_to_clipboard(&mut self) -> anyhow::Result<()> {
        if let Some(email) = &self.email {
            if let Some(clipboard) = &mut self.clipboard {
                clipboard.set_text(&email.body)?;
                self.show_toast("Email copied to clipboard!".to_string());
                Ok(())
            } else {
                Err(anyhow::anyhow!("Clipboard not available"))
            }
        } else {
            Err(anyhow::anyhow!("No email to copy"))
        }
    }

    /// Toggle email panel expanded state
    pub fn toggle_email_expanded(&mut self) {
        self.show_email_expanded = !self.show_email_expanded;
    }

    /// Toggle full email view
    pub fn toggle_email_full(&mut self) {
        self.show_email_full = !self.show_email_full;
    }

    /// Get the match score display text
    #[allow(dead_code)]
    fn score_text(&self) -> String {
        if let Some(analysis) = &self.analysis {
            format!("{}%", analysis.match_score)
        } else {
            "---".to_string()
        }
    }

    /// Get company tone display text
    #[allow(dead_code)]
    fn tone_text(&self) -> String {
        if let Some(analysis) = &self.analysis {
            format!("{}", analysis.company_tone)
        } else {
            "UNKNOWN".to_string()
        }
    }

    /// Get apply type badge text and style based on job description
    fn apply_type_badge(&self, theme: &Theme) -> (String, Style) {
        let description = self.job.as_ref().map(|j| j.description.as_str()).unwrap_or("");
        match ApplyType::from_description(description) {
            ApplyType::ExternalApply => (" 🔗 EXTERNAL ".to_string(), theme.style_bad()),
            ApplyType::EmailAvailable => (" 📧 EMAIL ".to_string(), theme.style_good()),
            ApplyType::Unknown => (" ❓ UNKNOWN ".to_string(), theme.style_warn()),
        }
    }

    /// Open job URL in the system browser
    fn open_job_url(&mut self) {
        if let Some(job) = &self.job {
            match open::that(&job.url) {
                Ok(()) => self.show_toast("Opened in browser".to_string()),
                Err(e) => self.show_toast(format!("Failed to open URL: {}", e)),
            }
        }
    }

    fn draw_header(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let Some(job) = &self.job else {
            let empty = Paragraph::new(Text::styled(
                "No job selected",
                theme.style_dim(),
            ));
            frame.render_widget(empty, area);
            return;
        };

        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Length(1),
                Constraint::Length(1),
                Constraint::Length(1),
                Constraint::Length(1),
            ])
            .split(area);

        let title = Paragraph::new(Text::styled(
            format!(" 💼 {}", job.title),
            theme.style_title(),
        ));
        frame.render_widget(title, chunks[0]);

        let meta = Paragraph::new(Text::styled(
            format!(
                " 🏢 {}  ·  📅 {}  ·  📡 {}",
                job.company, job.posted_at, job.source
            ),
            theme.style_normal(),
        ));
        frame.render_widget(meta, chunks[1]);

        let url = Paragraph::new(Text::styled(
            format!(" 🔗 {}", job.url),
            theme.style_dim(),
        ));
        frame.render_widget(url, chunks[2]);

        let (badge_text, badge_style) = self.apply_type_badge(theme);
        let badge = Paragraph::new(Text::styled(badge_text, badge_style));
        frame.render_widget(badge, chunks[3]);
    }

fn draw_score_gauge(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let score = self.analysis.as_ref().map(|a| a.match_score).unwrap_or(0);
        let score_style = theme.style_score_color(score);

        let gauge = Gauge::default()
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(theme.style_border(false))
                    .title(Span::styled(" Match Score ", theme.style_title())),
            )
            .gauge_style(score_style)
            .percent(score as u16)
            .label(format!("{}%", score));
        frame.render_widget(gauge, area);
    }

    fn draw_skills_panel(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let mut items = Vec::new();

        if let Some(analysis) = &self.analysis {
            for skill in &analysis.matched_skills {
                items.push(ListItem::new(Line::from(vec![
                    Span::styled("✅ ", theme.style_good()),
                    Span::styled(skill, theme.style_normal()),
                ])));
            }

            for skill in &analysis.missing_skills {
                items.push(ListItem::new(Line::from(vec![
                    Span::styled("❌ ", theme.style_bad()),
                    Span::styled(skill, theme.style_normal()),
                ])));
            }

            if analysis.matched_skills.is_empty() && analysis.missing_skills.is_empty() {
                items.push(ListItem::new(Line::from(Span::styled(
                    "No skills data available",
                    theme.style_dim(),
                ))));
            }
        } else {
            items.push(ListItem::new(Line::from(Span::styled(
                "Press 'a' to analyze job",
                theme.style_dim(),
            ))));
        }

        let skills_list = List::new(items)
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(theme.style_border(false))
                    .title(Span::styled(" Skills ", theme.style_title())),
            );

        frame.render_widget(skills_list, area);
    }

    fn draw_email_panel(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let title = if self.show_email_expanded {
            " Email Draft (expanded) "
        } else {
            " Email Draft "
        };

        let block = Block::default()
            .borders(Borders::ALL)
            .border_style(theme.style_border(false))
            .title(Span::styled(title, theme.style_title()));

        let inner = block.inner(area);
        frame.render_widget(block, area);

        if self.loading_email.is_loading() {
            let spinner = spinner_frame();
            let loading = Paragraph::new(Text::styled(
                format!(" {} Generating email...", spinner),
                theme.style_warn(),
            ))
            .alignment(Alignment::Center);
            frame.render_widget(loading, inner);
            return;
        }

        if let Some(email_error) = &self.email_error {
            let error = Paragraph::new(Text::styled(
                format!("Error: {}", email_error),
                theme.style_bad(),
            ))
            .alignment(Alignment::Center)
            .wrap(Wrap { trim: false });
            frame.render_widget(error, inner);
            return;
        }

        if let Some(email) = &self.email {
            let content = if self.show_email_full {
                format!("Subject: {}\n\n{}", email.subject, email.body)
            } else {
                let preview = if email.body.len() > 200 {
                    format!("{}...", &email.body[..200])
                } else {
                    email.body.clone()
                };
                format!("Subject: {}\n\n{}", email.subject, preview)
            };

            let email_widget = Paragraph::new(Text::styled(content, theme.style_normal()))
                .wrap(Wrap { trim: false })
                .alignment(Alignment::Left);
            frame.render_widget(email_widget, inner);
        } else {
            let hint = Paragraph::new(Text::styled(
                "Press 'e' to generate email",
                theme.style_dim(),
            ))
            .alignment(Alignment::Center);
            frame.render_widget(hint, inner);
        }
    }

    fn draw_action_bar(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let mut hotkeys = vec![];

        hotkeys.push(Span::styled(" [q] Back ", theme.style_dim()));
        hotkeys.push(Span::styled(" | ", theme.style_dim()));

        if self.analysis.is_none() && !self.loading_analysis.is_loading() {
            hotkeys.push(Span::styled(" [a] Analyze ", theme.style_highlight()));
        } else if self.loading_analysis.is_loading() {
            hotkeys.push(Span::styled(" [a] Analyzing... ", theme.style_warn()));
        } else {
            hotkeys.push(Span::styled(" [a] Re-analyze ", theme.style_dim()));
        }
        hotkeys.push(Span::styled(" | ", theme.style_dim()));

        if self.email.is_none() && !self.loading_email.is_loading() {
            hotkeys.push(Span::styled(" [e] Email ", theme.style_highlight()));
        } else if self.loading_email.is_loading() {
            hotkeys.push(Span::styled(" [e] Generating... ", theme.style_warn()));
        } else {
            hotkeys.push(Span::styled(" [e] Regenerate ", theme.style_dim()));
        }
        hotkeys.push(Span::styled(" | ", theme.style_dim()));

        if self.email.is_some() {
            hotkeys.push(Span::styled(" [c] Copy ", theme.style_highlight()));
        } else {
            hotkeys.push(Span::styled(" [c] Copy ", theme.style_dim()));
        }
        hotkeys.push(Span::styled(" | ", theme.style_dim()));
        hotkeys.push(Span::styled(" [Esc] Quit ", theme.style_dim()));

        let action_bar = Paragraph::new(Line::from(hotkeys))
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(theme.style_border(false)),
            )
            .alignment(Alignment::Center);
        frame.render_widget(action_bar, area);
    }

    fn draw_toast(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        if let Some(toast) = &self.toast {
            let toast_area = Rect {
                x: area.x + (area.width.saturating_sub(50)) / 2,
                y: area.y + 2,
                width: 50.min(area.width),
                height: 3,
            };

            let toast_widget = Paragraph::new(Text::styled(
                format!(" {} ", toast.message),
                theme.style_good(),
            ))
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(theme.style_good()),
            )
            .alignment(Alignment::Center);

            frame.render_widget(toast_widget, toast_area);
        }
    }


pub fn draw(&mut self, frame: &mut Frame, area: Rect) {

        let theme = Theme::detect();
        self.update_toast();

        if self.loading_analysis.is_loading() || self.loading_email.is_loading() {
            let msg = if self.loading_analysis.is_loading() {
                "Analyzing job with AI..."
            } else {
                "Generating email draft..."
            };
            render_loading(frame, area, &theme, msg);
            return;
        }

        if let Some(err) = &self.analysis_error {
            render_error_popup(frame, area, &theme, err, "[Enter] Dismiss  [a] Retry");
            return;
        }
        if let Some(err) = &self.email_error {
            render_error_popup(frame, area, &theme, err, "[Enter] Dismiss  [e] Retry");
            return;
        }

        let Some(_job) = &self.job else {
            render_empty_state(frame, area, &theme, "No job selected.\nPress Q to go back.");
            return;
        };

        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Length(5),
                Constraint::Min(0),
                Constraint::Length(3),
            ])
            .split(area);

        self.draw_header(frame, chunks[0], &theme);

        let content_chunks = Layout::default()
            .direction(Direction::Horizontal)
            .constraints([Constraint::Percentage(50), Constraint::Percentage(50)])
            .split(chunks[1]);

        let left_chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([Constraint::Length(8), Constraint::Min(0)])
            .split(content_chunks[0]);

        self.draw_score_gauge(frame, left_chunks[0], &theme);
        self.draw_skills_panel(frame, left_chunks[1], &theme);

        self.draw_email_panel(frame, content_chunks[1], &theme);

        self.draw_action_bar(frame, chunks[2], &theme);

        self.draw_toast(frame, area, &theme);
    }
}

/// Handle keyboard events for the job detail screen
pub async fn handle_event(
    event: crossterm::event::Event,
    app: &mut crate::tui::app::App,
    screen: &mut JobDetailScreen,
) -> anyhow::Result<()> {
    use crossterm::event::{KeyCode, KeyEventKind};

    if let crossterm::event::Event::Key(key) = event
        && key.kind == KeyEventKind::Press {
            // If error popup is showing, only handle dismiss/retry/nav keys
            if screen.analysis_error.is_some() || screen.email_error.is_some() {
                match key.code {
                    KeyCode::Enter => {
                        screen.analysis_error = None;
                        screen.email_error = None;
                        screen.loading_analysis = LoadingState::Idle;
                        screen.loading_email = LoadingState::Idle;
                    }
                    KeyCode::Char('a') | KeyCode::Char('A') => {
                        if !screen.loading_analysis.is_loading() {
                            screen.pending_action = Some(PendingAction::Analyze);
                        }
                    }
                    KeyCode::Char('e') | KeyCode::Char('E') => {
                        if !screen.loading_email.is_loading() {
                            screen.pending_action = Some(PendingAction::GenerateEmail);
                        }
                    }
                    KeyCode::Esc => {
                        app.should_quit = true;
                        app.state = crate::tui::app::AppState::Quitting;
                    }
                    KeyCode::Char('q') | KeyCode::Char('Q') => {
                        app.state = crate::tui::app::AppState::JobList;
                        app.selected_job = None;
                    }
                    _ => {}
                }
                return Ok(());
            }
            match key.code {
                KeyCode::Esc => {
                    app.should_quit = true;
                    app.state = crate::tui::app::AppState::Quitting;
                }
                KeyCode::Char('q') | KeyCode::Char('Q') => {
                    app.state = crate::tui::app::AppState::JobList;
                    app.selected_job = None;
                }
                KeyCode::Char('a') | KeyCode::Char('A') => {
                    if !screen.loading_analysis.is_loading() {
                        screen.pending_action = Some(PendingAction::Analyze);
                    }
                }
                KeyCode::Char('e') | KeyCode::Char('E') => {
                    if !screen.loading_email.is_loading() {
                        screen.pending_action = Some(PendingAction::GenerateEmail);
                    }
                }
                KeyCode::Char('c') | KeyCode::Char('C') => {
                    if screen.email.is_some() {
                        let _ = screen.copy_email_to_clipboard();
                    }
                }
                KeyCode::Char(' ') | KeyCode::Enter => {
                    screen.toggle_email_expanded();
                }
                KeyCode::Char('f') | KeyCode::Char('F') => {
                    screen.toggle_email_full();
                }
                KeyCode::Char('o') | KeyCode::Char('O') => {
                    screen.open_job_url();
                }
                _ => {}
            }
        }
    Ok(())
}

/// Entry point for drawing the job detail screen from the app
pub fn draw(frame: &mut Frame, area: Rect, app: &mut crate::tui::app::App) {
    if app.job_detail_screen.is_none()
        && let Some(job) = &app.selected_job {
            let api_client = app.api_client.clone();
            let cache = app.cache.clone();
            let mut screen = JobDetailScreen::new(api_client, cache);
            screen.set_job(job.clone());
            screen.apply_cached_data();
            app.job_detail_screen = Some(screen);
        }

    if let Some(screen) = &mut app.job_detail_screen {
        screen.draw(frame, area);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::ApiClient;
    use crate::cache::CacheManager;
    use crate::domain::{CompanyTone, EmailStatus};
    use chrono::NaiveDate;
    use std::sync::Arc;
    use tokio::sync::Mutex;

    fn create_test_screen() -> JobDetailScreen {
        let job = JobResponse {
            id: 1,
            title: "Senior Rust Developer".into(),
            company: "Acme Corp".into(),
            url: "https://example.com/job/1".into(),
            description: "Build high-performance systems in Rust".into(),
            posted_at: NaiveDate::from_ymd_opt(2026, 7, 13).unwrap(),
            source: "gupy".into(),
        };
        let api_client = Arc::new(Mutex::new(ApiClient::new("http://localhost:8080")));
        let cache = Arc::new(Mutex::new(CacheManager::new_in_memory(24).expect("cache")));
        let mut screen = JobDetailScreen::new(api_client, cache);
        screen.set_job(job);
        screen
    }

    fn sample_analysis() -> JobAnalysis {
        JobAnalysis {
            id: 1,
            job_id: 1,
            user_id: 1,
            match_score: 85,
            matched_skills: vec!["Rust".into(), "Git".into(), "SQL".into()],
            missing_skills: vec!["Kubernetes".into(), "AWS".into()],
            company_tone: CompanyTone::Formal,
            summary: "Strong backend match with Rust expertise".into(),
        }
    }

    fn sample_email() -> EmailDraftResponse {
        EmailDraftResponse {
            id: 1,
            job_id: 1,
            subject: "Candidatura para vaga Senior Rust Developer".into(),
            body: "Prezados,\n\nMe candidato à vaga de Senior Rust Developer na Acme Corp. Tenho experiência...".into(),
            status: EmailStatus::Pending,
            generated_at: chrono::NaiveDateTime::parse_from_str("2026-07-14T10:00:00", "%Y-%m-%dT%H:%M:%S").unwrap(),
        }
    }

    #[test]
    fn job_detail_screen_new_creates_with_job() {
        let screen = create_test_screen();

        assert_eq!(screen.job.as_ref().unwrap().id, 1);
        assert_eq!(screen.job.as_ref().unwrap().title, "Senior Rust Developer");
        assert_eq!(screen.job.as_ref().unwrap().company, "Acme Corp");
        assert!(screen.analysis.is_none());
        assert!(screen.email.is_none());
        assert_eq!(screen.loading_analysis, LoadingState::Idle);
        assert_eq!(screen.loading_email, LoadingState::Idle);
        assert!(!screen.show_email_expanded);
        assert!(!screen.show_email_full);
        assert!(screen.analysis_error.is_none());
        assert!(screen.email_error.is_none());
    }

    #[test]
    fn job_detail_screen_set_job_resets_state() {
        let mut screen = create_test_screen();
        screen.analysis = Some(sample_analysis());
        screen.email = Some(sample_email());
        screen.loading_analysis = LoadingState::Success;
        screen.loading_email = LoadingState::Success;
        screen.analysis_error = Some("error".into());
        screen.email_error = Some("error".into());

        let new_job = JobResponse {
            id: 2,
            title: "Java Developer".into(),
            company: "IBM".into(),
            url: "https://example.com/job/2".into(),
            description: "Java backend".into(),
            posted_at: NaiveDate::from_ymd_opt(2026, 7, 12).unwrap(),
            source: "linkedin".into(),
        };

        screen.set_job(new_job.clone());

        assert_eq!(screen.job.as_ref().unwrap().id, 2);
        assert_eq!(screen.job.as_ref().unwrap().title, "Java Developer");
        assert!(screen.analysis.is_none());
        assert!(screen.email.is_none());
        assert_eq!(screen.loading_analysis, LoadingState::Idle);
        assert_eq!(screen.loading_email, LoadingState::Idle);
        assert!(screen.analysis_error.is_none());
        assert!(screen.email_error.is_none());
    }

    #[test]
    fn loading_state_methods() {
        assert!(!LoadingState::Idle.is_loading());
        assert!(!LoadingState::Success.is_loading());
        assert!(LoadingState::Loading.is_loading());
        assert!(!LoadingState::Error("err".into()).is_loading());

        assert!(!LoadingState::Idle.is_error());
        assert!(!LoadingState::Success.is_error());
        assert!(!LoadingState::Loading.is_error());
        assert!(LoadingState::Error("err".into()).is_error());

        assert_eq!(LoadingState::Idle.error_message(), None);
        assert_eq!(LoadingState::Success.error_message(), None);
        assert_eq!(LoadingState::Loading.error_message(), None);
        assert_eq!(LoadingState::Error("test error".into()).error_message(), Some("test error"));
    }

    #[test]
    fn score_text_with_analysis() {
        let mut screen = create_test_screen();
        assert_eq!(screen.score_text(), "---");

        screen.analysis = Some(sample_analysis());
        assert_eq!(screen.score_text(), "85%");
    }

    #[test]
    fn score_text_without_analysis() {
        let screen = create_test_screen();
        assert_eq!(screen.score_text(), "---");
    }

    #[test]
    fn tone_text_with_analysis() {
        let mut screen = create_test_screen();
        assert_eq!(screen.tone_text(), "UNKNOWN");

        screen.analysis = Some(sample_analysis());
        assert_eq!(screen.tone_text(), "FORMAL");
    }

    #[test]
    fn tone_text_without_analysis() {
        let screen = create_test_screen();
        assert_eq!(screen.tone_text(), "UNKNOWN");
    }

    #[test]
    fn toggle_email_expanded() {
        let mut screen = create_test_screen();
        assert!(!screen.show_email_expanded);

        screen.toggle_email_expanded();
        assert!(screen.show_email_expanded);

        screen.toggle_email_expanded();
        assert!(!screen.show_email_expanded);
    }

    #[test]
    fn toggle_email_full() {
        let mut screen = create_test_screen();
        assert!(!screen.show_email_full);

        screen.toggle_email_full();
        assert!(screen.show_email_full);

        screen.toggle_email_full();
        assert!(!screen.show_email_full);
    }

    #[test]
    fn show_toast_sets_message() {
        let mut screen = create_test_screen();
        assert!(screen.toast.is_none());

        screen.show_toast("Test message".to_string());
        assert!(screen.toast.is_some());
        assert_eq!(screen.toast.as_ref().unwrap().message, "Test message");
    }

    #[test]
    fn company_tone_display() {
        assert_eq!(format!("{}", CompanyTone::Formal), "FORMAL");
        assert_eq!(format!("{}", CompanyTone::Casual), "CASUAL");
        assert_eq!(format!("{}", CompanyTone::Startup), "STARTUP");
    }

    #[test]
    fn email_status_display() {
        assert_eq!(format!("{}", EmailStatus::Pending), "PENDING");
        assert_eq!(format!("{}", EmailStatus::Sent), "SENT");
    }

    #[test]
    fn job_detail_screen_render_does_not_panic_empty() {
        let mut screen = create_test_screen();
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn job_detail_screen_render_does_not_panic_with_analysis() {
        let mut screen = create_test_screen();
        screen.analysis = Some(sample_analysis());
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn job_detail_screen_render_does_not_panic_with_email() {
        let mut screen = create_test_screen();
        screen.email = Some(sample_email());
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn job_detail_screen_render_does_not_panic_loading_analysis() {
        let mut screen = create_test_screen();
        screen.loading_analysis = LoadingState::Loading;
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn job_detail_screen_render_does_not_panic_loading_email() {
        let mut screen = create_test_screen();
        screen.loading_email = LoadingState::Loading;
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn job_detail_screen_render_does_not_panic_analysis_error() {
        let mut screen = create_test_screen();
        screen.analysis_error = Some("Network error".into());
        screen.loading_analysis = LoadingState::Error("Network error".into());
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn job_detail_screen_render_does_not_panic_email_error() {
        let mut screen = create_test_screen();
        screen.email_error = Some("AI service unavailable".into());
        screen.loading_email = LoadingState::Error("AI service unavailable".into());
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn job_detail_screen_render_does_not_panic_email_expanded() {
        let mut screen = create_test_screen();
        screen.email = Some(sample_email());
        screen.show_email_expanded = true;
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn job_detail_screen_render_does_not_panic_email_full() {
        let mut screen = create_test_screen();
        screen.email = Some(sample_email());
        screen.show_email_full = true;
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn job_detail_screen_render_does_not_panic_with_toast() {
        let mut screen = create_test_screen();
        screen.show_toast("Test toast".to_string());
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn job_detail_screen_handles_unicode_in_job_title() {
        let mut screen = create_test_screen();
        screen.job = Some(JobResponse {
            id: 1,
            title: "Senior Rust Developer 🦀".into(),
            company: "Empresa Brasileira 🇧🇷".into(),
            url: "https://example.com/job/1".into(),
            description: "Descrição com acentos: áéíóú ñ".into(),
            posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
        });
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn job_detail_screen_handles_very_long_email_body() {
        let mut screen = create_test_screen();
        let long_body = "Lorem ipsum ".repeat(200);
        screen.email = Some(EmailDraftResponse {
            id: 1,
            job_id: 1,
            subject: "Test Subject".into(),
            body: long_body,
            status: EmailStatus::Pending,
            generated_at: chrono::NaiveDateTime::parse_from_str("2026-07-14T10:00:00", "%Y-%m-%dT%H:%M:%S").unwrap(),
        });
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn job_detail_screen_handles_analysis_error() {
        let mut screen = create_test_screen();
        screen.analysis_error = Some("AI service unavailable".into());
        screen.loading_analysis = LoadingState::Error("AI service unavailable".into());
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn job_detail_screen_handles_email_error() {
        let mut screen = create_test_screen();
        screen.email_error = Some("Connection refused".into());
        screen.loading_email = LoadingState::Error("Connection refused".into());
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn job_detail_screen_handles_no_job_selected() {
        let mut screen = create_test_screen();
        screen.job = None;
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    fn create_test_screen_with_description(description: &str) -> JobDetailScreen {
        let job = JobResponse {
            id: 1,
            title: "Senior Rust Developer".into(),
            company: "Acme Corp".into(),
            url: "https://example.com/job/1".into(),
            description: description.into(),
            posted_at: NaiveDate::from_ymd_opt(2026, 7, 13).unwrap(),
            source: "gupy".into(),
        };
        let api_client = Arc::new(Mutex::new(ApiClient::new("http://localhost:8080")));
        let cache = Arc::new(Mutex::new(CacheManager::new_in_memory(24).expect("cache")));
        let mut screen = JobDetailScreen::new(api_client, cache);
        screen.set_job(job);
        screen
    }

    #[test]
    fn badge_shows_external_when_description_empty() {
        let screen = create_test_screen_with_description("");
        let theme = Theme::detect();
        let (text, _) = screen.apply_type_badge(&theme);
        assert_eq!(text, " 🔗 EXTERNAL ");
    }

    #[test]
    fn badge_shows_email_when_description_present() {
        let screen = create_test_screen_with_description(
            "Build high-performance distributed systems in Rust with 20+ characters",
        );
        let theme = Theme::detect();
        let (text, _) = screen.apply_type_badge(&theme);
        assert_eq!(text, " 📧 EMAIL ");
    }

    #[test]
    fn badge_shows_unknown_when_description_whitespace() {
        let screen = create_test_screen_with_description("   ");
        let theme = Theme::detect();
        let (text, _) = screen.apply_type_badge(&theme);
        assert_eq!(text, " ❓ UNKNOWN ");
    }

    #[test]
    fn open_url_keybinding_triggers_open() {
        let mut screen = create_test_screen();
        screen.show_toast("Opened in browser".to_string());
        assert!(screen.toast.is_some());
    }
}