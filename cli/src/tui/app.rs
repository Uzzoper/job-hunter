use crate::api::ApiClient;
use crate::cache::CacheManager;
use crate::config::Config;
use crate::config::ConfigManager;
use crate::domain::{AuthResponse, JobResponse, ProfileResponse};
use crate::tui::{auth_screen, job_detail_screen, job_list_screen, profile_screen};
use crate::tui::job_detail_screen::JobDetailScreen;
use crate::tui::job_list_screen::{JobListScreen, LoadingState, SearchFocus};
use crate::tui::profile_screen::ProfileScreen;
use ratatui::Terminal;
use std::io;
use std::sync::Arc;
use tokio::sync::Mutex;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AppState {
    Auth,
    JobList,
    JobDetail,
    Profile,
    Quitting,
}

pub struct App {
    pub api_client: Arc<Mutex<ApiClient>>,
    pub config: Config,
    pub cache: Arc<Mutex<CacheManager>>,
    pub state: AppState,
    pub token: Option<String>,
    pub auth: Option<AuthResponse>,
    pub jobs: Vec<JobResponse>,
    pub selected_job: Option<JobResponse>,
    pub profile: Option<ProfileResponse>,
    pub error_message: Option<String>,
    pub should_quit: bool,
    pub auth_screen: Option<auth_screen::AuthScreen>,
    pub job_list_screen: Option<JobListScreen>,
    pub job_detail_screen: Option<job_detail_screen::JobDetailScreen>,
    pub profile_screen: Option<ProfileScreen>,
}

impl App {
    pub fn new(api_client: ApiClient, config: Config) -> Self {
        let cache = Arc::new(Mutex::new(CacheManager::new(None, config.cache_ttl_hours).expect("cache init")));
        let api_client_arc = Arc::new(Mutex::new(api_client));
        let cache_arc = cache.clone();
        let config_manager = Arc::new(Mutex::new(ConfigManager::load(None).unwrap_or_default()));
        let job_list_screen = Some(JobListScreen::new(api_client_arc.clone(), cache_arc.clone()));
        let job_detail_screen = Some(JobDetailScreen::new(api_client_arc.clone(), cache_arc));
        let profile_screen = Some(ProfileScreen::new(
            api_client_arc.clone(),
            config_manager,
        ));
        Self {
            api_client: api_client_arc,
            config,
            cache,
            state: AppState::Auth,
            token: None,
            auth: None,
            jobs: Vec::new(),
            selected_job: None,
            profile: None,
            error_message: None,
            should_quit: false,
            auth_screen: None,
            job_list_screen,
            job_detail_screen,
            profile_screen,
        }
    }

    pub fn should_quit(&self) -> bool {
        self.should_quit || self.state == AppState::Quitting
    }

    pub fn set_error(&mut self, message: String) {
        self.error_message = Some(message);
    }

    pub fn clear_error(&mut self) {
        self.error_message = None;
    }

    pub fn transition_to(&mut self, new_state: AppState) {
        self.state = new_state;
        if new_state == AppState::JobList {
            self.load_jobs_on_startup();
        }
    }

    fn load_jobs_on_startup(&mut self) {
        if let Some(screen) = &mut self.job_list_screen {
            let api_client = self.api_client.clone();
            let cache = self.cache.clone();
            let rt = tokio::runtime::Handle::current();

            // Phase 1: cache-first for instant display
            rt.block_on(async {
                let cache_lock = cache.lock().await;
                if let Ok(cached_jobs) = cache_lock.get_all_jobs(None)
                    && !cached_jobs.is_empty() {
                        let jobs: Vec<JobResponse> = cached_jobs
                            .into_iter()
                                .map(|cj| JobResponse {
                                    id: cj.id,
                                    title: cj.title,
                                    company: cj.company,
                                    url: cj.url,
                                    description: cj.description,
                                    posted_at: cj.posted_at,
                                    source: cj.source,
                                    contact_email: cj.contact_email.clone(),
                                })
                            .collect();
                        screen.from_cache = true;
                        screen.cache_stale = cache_lock.is_stale().unwrap_or(true);
                        screen.set_jobs(jobs);
                        screen.status = LoadingState::Loaded;
                        return;
                    }
                screen.set_loading();
            });

            // Phase 2: API for fresh data
            rt.block_on(async {
                let client = api_client.lock().await;
                match client.get_jobs().await {
                    Ok(jobs) => {
                        let _ = cache.lock().await.update_cache_on_fetch(&jobs);
                        screen.from_cache = false;
                        screen.cache_stale = false;
                        if jobs.is_empty() {
                            screen.set_empty();
                        } else {
                            screen.set_jobs(jobs);
                            screen.status = LoadingState::Loaded;
                        }
                    }
                    Err(e) => {
                        let already_loaded = screen.status == LoadingState::Loaded && screen.from_cache;
                        if !already_loaded {
                            let cache_lock = cache.lock().await;
                            match cache_lock.get_all_jobs(None) {
                                Ok(cached_jobs) => {
                                    let jobs: Vec<JobResponse> = cached_jobs
                                        .into_iter()
                                        .map(|cj| JobResponse {
                                            id: cj.id,
                                            title: cj.title,
                                            company: cj.company,
                                            url: cj.url,
                                            description: cj.description,
                                            posted_at: cj.posted_at,
                                            source: cj.source,
                                            contact_email: cj.contact_email.clone(),
                                        })
                                        .collect();
                                    screen.from_cache = true;
                                    screen.cache_stale = true;
                                    if jobs.is_empty() {
                                        screen.set_empty();
                                    } else {
                                        screen.set_jobs(jobs);
                                        screen.status = LoadingState::Loaded;
                                    }
                                }
                                Err(_) => {
                                    screen.set_error(e.to_string());
                                }
                            }
                        }
                    }
                }
            });
        }
    }

    async fn handle_refresh(&mut self) {
        if let Some(screen) = &mut self.job_list_screen {
            let api_client = self.api_client.clone();
            let cache = self.cache.clone();

            screen.set_loading();

            let client = api_client.lock().await;
            match client.get_jobs().await {
                Ok(jobs) => {
                    let _ = cache.lock().await.update_cache_on_fetch(&jobs);
                    screen.from_cache = false;
                    screen.cache_stale = false;
                    if jobs.is_empty() {
                        screen.set_empty();
                    } else {
                        screen.set_jobs(jobs);
                    }
                }
                Err(e) => {
                    screen.set_error(e.to_string());
                }
            }
        }
    }

    pub async fn run(&mut self, terminal: &mut Terminal<ratatui::prelude::CrosstermBackend<io::Stdout>>) -> anyhow::Result<()> {
        use crossterm::event::{Event, KeyCode, KeyModifiers};

        while !self.should_quit() {
            terminal.draw(|frame| self.render(frame))?;

            // Execute pending long-running actions
            if self.state == AppState::JobDetail {
                let pending = self.job_detail_screen.as_mut()
                    .and_then(|s| s.pending_action.take());
                if let Some(action) = pending {
                    if let Some(s) = self.job_detail_screen.as_mut() {
                        s.start_loading(action);
                    }
                    terminal.draw(|frame| self.render(frame))?;
                    match action {
                        job_detail_screen::PendingAction::Analyze => {
                            if let Some(s) = self.job_detail_screen.as_mut() {
                                let _ = s.analyze_job().await;
                            }
                        }
                        job_detail_screen::PendingAction::GenerateEmail => {
                            if let Some(s) = self.job_detail_screen.as_mut() {
                                let _ = s.generate_email().await;
                            }
                        }
                        job_detail_screen::PendingAction::ApproveEmail => {
                            if let Some(s) = self.job_detail_screen.as_mut() {
                                let _ = s.approve_email().await;
                            }
                        }
                        job_detail_screen::PendingAction::SendEmail => {
                            if let Some(s) = self.job_detail_screen.as_mut() {
                                let _ = s.send_email().await;
                            }
                        }
                    }
                    continue;
                }
            }

            // Execute pending profile upload
            if let Some(path) = self.profile_screen.as_mut()
                .and_then(|s| s.pending_upload.take())
            {
                terminal.draw(|frame| self.render(frame))?;
                if let Some(s) = self.profile_screen.as_mut() {
                    s.finish_upload(&path).await;
                }
                continue;
            }

            // Handle events with timeout to allow for resize handling
            if crossterm::event::poll(std::time::Duration::from_millis(100))? {
                let event = crossterm::event::read()?;
                
                if let Event::Resize(_, _) = event {
                    // Terminal was resized, just redraw on next iteration
                    continue;
                }
                
                // Handle Ctrl+C globally
                if let Event::Key(key) = event
                    && key.code == KeyCode::Char('c') && key.modifiers.contains(KeyModifiers::CONTROL) {
                        self.should_quit = true;
                        self.state = AppState::Quitting;
                        continue;
                    }
                
                // Handle bracketed paste events
                if let Event::Paste(_) = event {
                    self.handle_event(event).await?;
                    continue;
                }
                
                self.handle_event(event).await?;
            }
        }
        Ok(())
    }

    pub(crate) fn render(&mut self, frame: &mut ratatui::Frame) {
        let area = frame.area();
        let chunks = ratatui::layout::Layout::default()
            .direction(ratatui::layout::Direction::Vertical)
            .constraints([
                ratatui::layout::Constraint::Length(3),
                ratatui::layout::Constraint::Min(0),
                ratatui::layout::Constraint::Length(3),
            ])
            .split(area);

        self.render_header(frame, chunks[0]);
        self.render_body(frame, chunks[1]);
        self.render_footer(frame, chunks[2]);

        if let Some(error) = &self.error_message {
            self.render_error_popup(frame, area, error);
        }
    }

    fn render_header(&self, frame: &mut ratatui::Frame, area: ratatui::layout::Rect) {
        use crate::tui::theme::Theme;
        use ratatui::widgets::{Block, Borders, Paragraph};
        use ratatui::text::Text;

        let title = match self.state {
            AppState::Auth => " Job Hunter — Authentication ",
            AppState::JobList => " Job Hunter — Job Listings ",
            AppState::JobDetail => " Job Hunter — Job Detail ",
            AppState::Profile => " Job Hunter — Profile ",
            AppState::Quitting => " Job Hunter — Quitting ",
        };

        let status = if self.token.is_some() {
            " ● Connected "
        } else {
            " ○ Disconnected "
        };

        let header_text = format!("{}{}", title, status);
        let header = Paragraph::new(Text::styled(header_text, Theme::title()))
            .block(Block::default().borders(Borders::ALL).border_style(Theme::dim()));
        frame.render_widget(header, area);
    }

    fn render_body(&mut self, frame: &mut ratatui::Frame, area: ratatui::layout::Rect) {
        match self.state {
            AppState::Auth => auth_screen::draw(frame, area, self),
            AppState::JobList => job_list_screen::draw(frame, area, self),
            AppState::JobDetail => job_detail_screen::draw(frame, area, self),
            AppState::Profile => profile_screen::draw(frame, area, self),
            AppState::Quitting => {
                use crate::tui::theme::Theme;
                use ratatui::widgets::{Block, Borders, Paragraph};
                use ratatui::text::Text;
                let para = Paragraph::new(Text::styled("Goodbye!", Theme::good()))
                    .block(Block::default().borders(Borders::ALL).border_style(Theme::dim()))
                    .centered();
                frame.render_widget(para, area);
            }
        }
    }

    fn render_footer(&self, frame: &mut ratatui::Frame, area: ratatui::layout::Rect) {
        use crate::tui::theme::Theme;
        use ratatui::widgets::{Block, Borders, Paragraph};
        use ratatui::text::Text;

        let shortcuts = match self.state {
            AppState::Auth => " [Enter] Continue  [Esc] Quit ",
            AppState::JobList => " ",
            AppState::JobDetail => " ",
            AppState::Profile => " ",
            AppState::Quitting => " ",
        };

        if shortcuts.trim().is_empty() {
            return;
        }

        let footer = Paragraph::new(Text::styled(shortcuts, Theme::dim()))
            .block(Block::default().borders(Borders::ALL).border_style(Theme::dim()));
        frame.render_widget(footer, area);
    }

    fn render_error_popup(&self, frame: &mut ratatui::Frame, area: ratatui::layout::Rect, message: &str) {
        use crate::tui::theme::Theme;
        use ratatui::widgets::{Block, Borders, Paragraph, Clear};
        use ratatui::text::Text;
        use ratatui::layout::{Constraint, Direction, Layout};

        let popup_area = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Percentage(30),
                Constraint::Length(7),
                Constraint::Percentage(30),
            ])
            .split(area)[1];

        let popup_area = Layout::default()
            .direction(Direction::Horizontal)
            .constraints([
                Constraint::Percentage(15),
                Constraint::Percentage(70),
                Constraint::Percentage(15),
            ])
            .split(popup_area)[1];

        frame.render_widget(Clear, popup_area);

        let error_text = format!(" Error \n\n{}\n\n[Enter] Dismiss  [r] Retry ", message);
        let popup = Paragraph::new(Text::styled(error_text, Theme::bad()))
            .block(Block::default().borders(Borders::ALL).border_style(Theme::bad()))
            .centered();
        frame.render_widget(popup, popup_area);
    }

    pub(crate) async fn handle_event(&mut self, event: crossterm::event::Event) -> anyhow::Result<()> {
        if matches!(event, crossterm::event::Event::Paste(_)) && self.state == AppState::Profile {
            return profile_screen::handle_event(event, self).await;
        }
        if matches!(event, crossterm::event::Event::Paste(_)) {
            return Ok(());
        }

        if let crossterm::event::Event::Key(key) = event
            && key.kind == crossterm::event::KeyEventKind::Press {
            if self.error_message.is_some() {
                return self.handle_error_popup(key).await;
            }

            if self.state == AppState::Auth {
                return auth_screen::handle_event(event, self).await;
            }

            if self.state == AppState::Profile {
                return profile_screen::handle_event(event, self).await;
            }

            // Uses take/putback to avoid double-borrow of self.job_detail_screen
            if self.state == AppState::JobDetail
                && let Some(mut screen) = self.job_detail_screen.take() {
                    let result = job_detail_screen::handle_event(event, self, &mut screen).await;
                    self.job_detail_screen = Some(screen);
                    return result;
                }

            // When search is focused, intercept all keys for search handling before global match
            if self.state == AppState::JobList
                && self.job_list_screen.as_ref().is_some_and(|s| s.search_focus == SearchFocus::Focused)
            {
                match key.code {
                    crossterm::event::KeyCode::Esc | crossterm::event::KeyCode::Enter => {
                        if let Some(s) = &mut self.job_list_screen { s.blur_search(); }
                    }
                    crossterm::event::KeyCode::Backspace => {
                        if let Some(s) = &mut self.job_list_screen { s.handle_search_backspace(); }
                    }
                    crossterm::event::KeyCode::Char(c) => {
                        if let Some(s) = &mut self.job_list_screen { s.handle_search_char(c); }
                    }
                    _ => {}
                }
                return Ok(());
            }

            match key.code {
                crossterm::event::KeyCode::Char('q') | crossterm::event::KeyCode::Char('Q') => {
                    self.handle_back();
                }
                crossterm::event::KeyCode::Char('/') if self.state == AppState::JobList => {
                    if let Some(screen) = &mut self.job_list_screen {
                        screen.focus_search();
                    }
                }
                crossterm::event::KeyCode::Char('f') if self.state == AppState::JobList
                    && key.modifiers.contains(crossterm::event::KeyModifiers::CONTROL) => {
                    if let Some(screen) = &mut self.job_list_screen {
                        screen.focus_search();
                    }
                }
                crossterm::event::KeyCode::Char('p') | crossterm::event::KeyCode::Char('P') => {
                    if self.state == AppState::JobList {
                        self.state = AppState::Profile;
                    }
                }
                crossterm::event::KeyCode::Char('t') | crossterm::event::KeyCode::Char('T') => {
                    if self.state == AppState::JobList
                        && let Some(screen) = &mut self.job_list_screen {
                        screen.cycle_apply_type_filter();
                    }
                }
                crossterm::event::KeyCode::Char('s') | crossterm::event::KeyCode::Char('S') => {
                    if self.state == AppState::JobList
                        && let Some(screen) = &mut self.job_list_screen {
                        screen.cycle_seniority_filter();
                    }
                }
                crossterm::event::KeyCode::Char('d') | crossterm::event::KeyCode::Char('D') => {
                    if self.state == AppState::JobList
                        && let Some(screen) = &mut self.job_list_screen {
                        screen.toggle_dev_only();
                    }
                }
                crossterm::event::KeyCode::Char('o') | crossterm::event::KeyCode::Char('O') => {
                    if self.state == AppState::JobList
                        && let Some(screen) = &mut self.job_list_screen {
                        screen.open_selected_job_url();
                    }
                }
                crossterm::event::KeyCode::Char('b') | crossterm::event::KeyCode::Char('B') => {
                    self.handle_back();
                }
                crossterm::event::KeyCode::Char('a') | crossterm::event::KeyCode::Char('A') => {
                    if self.state == AppState::JobDetail
                        && let Some(screen) = &mut self.job_detail_screen {
                        let _ = screen.analyze_job().await;
                    }
                }
                crossterm::event::KeyCode::Char('e') | crossterm::event::KeyCode::Char('E') => {
                    if self.state == AppState::JobDetail
                        && let Some(screen) = &mut self.job_detail_screen {
                        let _ = screen.generate_email().await;
                    }
                }
                crossterm::event::KeyCode::Char('r') | crossterm::event::KeyCode::Char('R')
                    if self.state == AppState::JobList => {
                    self.handle_refresh().await;
                }
                crossterm::event::KeyCode::Esc => {
                    self.should_quit = true;
                    self.state = AppState::Quitting;
                }
                crossterm::event::KeyCode::Tab => {
                    self.handle_tab();
                }
                crossterm::event::KeyCode::Enter => {
                    self.handle_enter()?;
                }
                crossterm::event::KeyCode::Up | crossterm::event::KeyCode::Char('k') => {
                    self.handle_up();
                }
                crossterm::event::KeyCode::Down | crossterm::event::KeyCode::Char('j') => {
                    self.handle_down();
                }

                _ => {}
            }
        }
        Ok(())
    }

    async fn handle_error_popup(&mut self, key: crossterm::event::KeyEvent) -> anyhow::Result<()> {
        match key.code {
            crossterm::event::KeyCode::Enter => {
                self.clear_error();
            }
            crossterm::event::KeyCode::Char('r') | crossterm::event::KeyCode::Char('R') => {
                self.clear_error();
            }
            _ => {}
        }
        Ok(())
    }

    pub fn handle_escape(&mut self) {
        match self.state {
            AppState::JobDetail => {
                self.state = AppState::JobList;
                self.selected_job = None;
            }
            AppState::Profile => {
                self.state = AppState::JobList;
            }
            AppState::Auth => {
                self.should_quit = true;
                self.state = AppState::Quitting;
            }
            _ => {}
        }
    }

    fn handle_tab(&mut self) {
    }

    fn handle_enter(&mut self) -> anyhow::Result<()> {
        match self.state {
            AppState::Auth => {
                if self.token.is_some() {
                    self.state = AppState::JobList;
                }
            }
            AppState::JobList => {
                if let Some(job) = self.job_list_screen.as_ref().and_then(|s| s.selected_job().cloned()) {
                    if let Some(screen) = &mut self.job_detail_screen {
                        screen.set_job(job.clone());
                        screen.apply_cached_data();
                    }
                    self.selected_job = Some(job);
                    self.state = AppState::JobDetail;
                }
            }
            _ => {}
        }
        Ok(())
    }

    fn handle_up(&mut self) {
        if self.state == AppState::JobList
            && let Some(screen) = &mut self.job_list_screen
        {
            screen.select_prev();
        }
    }

    fn handle_down(&mut self) {
        if self.state == AppState::JobList
            && let Some(screen) = &mut self.job_list_screen
        {
            screen.select_next();
        }
    }

    fn handle_back(&mut self) {
        match self.state {
            AppState::JobDetail => {
                self.state = AppState::JobList;
                self.selected_job = None;
            }
            AppState::Profile => {
                self.state = AppState::JobList;
            }
            _ => {}
        }
    }
}