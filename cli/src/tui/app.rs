use crate::api::ApiClient;
use crate::cache::CacheManager;
use crate::config::Config;
use crate::config::ConfigManager;
use crate::domain::{AuthResponse, JobResponse, ProfileResponse};
use crate::tui::{auth_screen, job_detail_screen, job_list_screen, profile_screen};
use crate::tui::job_detail_screen::JobDetailScreen;
use crate::tui::job_list_screen::{JobListScreen, LoadingState};
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

/// Transition state for smooth screen transitions
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransitionState {
    None,
    FadingOut,
    FadingIn,
    Entering,
}

pub struct App {
    pub api_client: Arc<Mutex<ApiClient>>,
    pub config: Config,
    pub cache: Arc<Mutex<CacheManager>>,
    pub state: AppState,
    pub prev_state: AppState,
    pub transition: TransitionState,
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
            prev_state: AppState::Auth,
            transition: TransitionState::None,
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
        self.prev_state = self.state;
        self.state = new_state;
        self.transition = TransitionState::Entering;
        if new_state == AppState::JobList {
            self.load_jobs_on_startup();
        }
    }

    pub fn complete_transition(&mut self) {
        self.transition = TransitionState::None;
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

            // Handle events with timeout to allow for resize handling
            if crossterm::event::poll(std::time::Duration::from_millis(50))? {
                let event = crossterm::event::read()?;
                
                // Handle terminal resize
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
                
                self.handle_event(event).await?;
            }
        }
        Ok(())
    }

    fn render(&mut self, frame: &mut ratatui::Frame) {
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
            AppState::Auth => " [Enter] Continue  [q] Quit ",
            AppState::JobList => " [↑/↓] Navigate  [Enter] Detail  [r] Refresh  [p] Profile  [q] Quit ",
            AppState::JobDetail => " [b] Back  [a] Analyze  [e] Email  [q] Quit ",
            AppState::Profile => " [b] Back  [r] Refresh  [q] Quit ",
            AppState::Quitting => " ",
        };

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

    async fn handle_event(&mut self, event: crossterm::event::Event) -> anyhow::Result<()> {
        if let crossterm::event::Event::Key(key) = event
            && key.kind == crossterm::event::KeyEventKind::Press {
            if self.error_message.is_some() {
                return self.handle_error_popup(key).await;
            }

            if self.state == AppState::Auth {
                return auth_screen::handle_event(event, self).await;
            }

            match key.code {
                crossterm::event::KeyCode::Char('q') | crossterm::event::KeyCode::Char('Q') => {
                    self.should_quit = true;
                    self.state = AppState::Quitting;
                }
                crossterm::event::KeyCode::Esc => {
                    self.handle_escape();
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
                crossterm::event::KeyCode::Char('p') | crossterm::event::KeyCode::Char('P') => {
                    if self.state == AppState::JobList {
                        self.state = AppState::Profile;
                    }
                }
                crossterm::event::KeyCode::Char('b') | crossterm::event::KeyCode::Char('B') => {
                    self.handle_back();
                }
                crossterm::event::KeyCode::Char('a') | crossterm::event::KeyCode::Char('A') => {
                    if self.state == AppState::JobDetail {
                    }
                }
                crossterm::event::KeyCode::Char('e') | crossterm::event::KeyCode::Char('E') => {
                    if self.state == AppState::JobDetail {
                    }
                }
                crossterm::event::KeyCode::Char('r') | crossterm::event::KeyCode::Char('R')
                    if self.state == AppState::JobList => {
                    self.handle_refresh().await;
                }
                crossterm::event::KeyCode::Char('r') | crossterm::event::KeyCode::Char('R')
                    if self.state == AppState::Profile => {
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
                // TODO: retry last operation
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
        if self.state == AppState::JobList {
        }
    }

    fn handle_enter(&mut self) -> anyhow::Result<()> {
        match self.state {
            AppState::Auth => {
                if self.token.is_some() {
                    self.state = AppState::JobList;
                }
            }
            AppState::JobList => {
                if let Some(job) = self.jobs.first().cloned() {
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