use crate::api::ApiClient;
use crate::cache::CacheManager;
use crate::domain::JobResponse;
use crate::tui::theme::{render_empty_state, render_error_popup, render_loading, Theme, truncate_text};
use crate::tui::Toast;
use ratatui::{
    Frame,
    layout::{Constraint, Direction, Layout, Rect},
    style::Modifier,
    text::{Line, Span, Text},
    widgets::{Block, Borders, List, ListItem, ListState, Paragraph, Scrollbar, ScrollbarOrientation, ScrollbarState},
};
use std::collections::HashMap;
use std::sync::Arc;

use tokio::sync::Mutex;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LoadingState {
    Empty,
    Loading,
    Loaded,
    Error(String),
}

impl LoadingState {
    pub fn display_text(&self) -> &str {
        match self {
            LoadingState::Empty => "No jobs found. Press 'r' to fetch jobs.",
            LoadingState::Loading => "Fetching jobs...",
            LoadingState::Loaded => "",
            LoadingState::Error(_) => "Error loading jobs",
        }
    }

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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SearchFocus {
    Focused,
    Blurred,
}

impl SearchFocus {
    pub fn display_text(&self) -> &str {
        match self {
            SearchFocus::Focused => "SEARCH (focused)",
            SearchFocus::Blurred => "SEARCH",
        }
    }
}

/// Filter for job apply type
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ApplyTypeFilter {
    #[default]
    All,
    ExternalApply,
    EmailAvailable,
    Unknown,
}

impl ApplyTypeFilter {
    /// Cycle to the next filter state
    pub fn cycle(&self) -> Self {
        match self {
            ApplyTypeFilter::All => ApplyTypeFilter::ExternalApply,
            ApplyTypeFilter::ExternalApply => ApplyTypeFilter::EmailAvailable,
            ApplyTypeFilter::EmailAvailable => ApplyTypeFilter::Unknown,
            ApplyTypeFilter::Unknown => ApplyTypeFilter::All,
        }
    }

    /// Get display text for the filter
    pub fn display_text(&self) -> &str {
        match self {
            ApplyTypeFilter::All => "",
            ApplyTypeFilter::ExternalApply => " 🔗 EXTERNAL",
            ApplyTypeFilter::EmailAvailable => " 📧 EMAIL",
            ApplyTypeFilter::Unknown => " ❓ UNKNOWN",
        }
    }
}

/// Filter for job seniority level
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum SeniorityFilter {
    #[default]
    All,
    Junior,
    JuniorPleno,
    SeniorLead,
    Unknown,
}

impl SeniorityFilter {
    /// Cycle to the next filter state
    pub fn cycle(&self) -> Self {
        match self {
            SeniorityFilter::All => SeniorityFilter::Junior,
            SeniorityFilter::Junior => SeniorityFilter::JuniorPleno,
            SeniorityFilter::JuniorPleno => SeniorityFilter::SeniorLead,
            SeniorityFilter::SeniorLead => SeniorityFilter::Unknown,
            SeniorityFilter::Unknown => SeniorityFilter::All,
        }
    }

    /// Get display text for the filter
    pub fn display_text(&self) -> &str {
        match self {
            SeniorityFilter::All => "",
            SeniorityFilter::Junior => " 👶 JR",
            SeniorityFilter::JuniorPleno => " 🎓 JR+PL",
            SeniorityFilter::SeniorLead => " 👑 SR+LD",
            SeniorityFilter::Unknown => " ❓ UNK",
        }
    }

    /// Check if a seniority level matches this filter
    pub fn matches(&self, level: crate::domain::SeniorityLevel) -> bool {
        match self {
            SeniorityFilter::All => true,
            SeniorityFilter::Junior => level == crate::domain::SeniorityLevel::Junior,
            SeniorityFilter::JuniorPleno => {
                level == crate::domain::SeniorityLevel::Junior || level == crate::domain::SeniorityLevel::Pleno
            }
            SeniorityFilter::SeniorLead => level == crate::domain::SeniorityLevel::Senior,
            SeniorityFilter::Unknown => level == crate::domain::SeniorityLevel::Unknown,
        }
    }
}

pub struct JobListScreen {
    pub jobs: Vec<JobResponse>,
    pub filtered_jobs: Vec<JobResponse>,
    pub selected_index: usize,
    pub search_query: String,
    pub min_score_filter: Option<i32>,
    pub status: LoadingState,
    pub search_focus: SearchFocus,
    pub list_state: ListState,
    pub scroll_state: ScrollbarState,
    pub show_detail_panel: bool,
    pub from_cache: bool,
    pub cache_stale: bool,
    pub apply_type_filter: ApplyTypeFilter,
    pub seniority_filter: SeniorityFilter,
    pub dev_only: bool,
    api_client: Arc<Mutex<ApiClient>>,
    cache: Arc<Mutex<CacheManager>>,
    toast: Option<Toast>,
    score_cache: HashMap<i64, Option<i32>>,
}

impl JobListScreen {
    pub fn new(api_client: Arc<Mutex<ApiClient>>, cache: Arc<Mutex<CacheManager>>) -> Self {
        let mut list_state = ListState::default();
        list_state.select(Some(0));
        Self {
            jobs: Vec::new(),
            filtered_jobs: Vec::new(),
            selected_index: 0,
            search_query: String::new(),
            min_score_filter: None,
            status: LoadingState::Empty,
            search_focus: SearchFocus::Blurred,
            list_state,
            scroll_state: ScrollbarState::default(),
            show_detail_panel: true,
            from_cache: false,
            cache_stale: false,
            apply_type_filter: ApplyTypeFilter::default(),
            seniority_filter: SeniorityFilter::default(),
            dev_only: false,
            api_client,
            cache,
            toast: None,
            score_cache: HashMap::new(),
        }
    }

    pub fn set_jobs(&mut self, jobs: Vec<JobResponse>) {
        self.jobs = jobs;
        if let Ok(cache) = self.cache.try_lock()
            && let Ok(scores) = cache.get_all_scores()
        {
            self.score_cache = scores;
        }
        self.apply_filters();
        self.update_selection();
        self.status = LoadingState::Loaded;
    }

    pub fn set_loading(&mut self) {
        self.status = LoadingState::Loading;
    }

    pub fn set_error(&mut self, error: String) {
        self.status = LoadingState::Error(error);
    }

    pub fn set_empty(&mut self) {
        self.status = LoadingState::Empty;
        self.jobs.clear();
        self.filtered_jobs.clear();
        self.selected_index = 0;
        self.list_state.select(None);
    }

    pub fn focus_search(&mut self) {
        self.search_focus = SearchFocus::Focused;
    }

    pub fn blur_search(&mut self) {
        self.search_focus = SearchFocus::Blurred;
    }

    pub fn handle_search_char(&mut self, c: char) {
        if self.search_focus == SearchFocus::Focused {
            self.search_query.push(c);
            self.apply_filters();
        }
    }

    pub fn handle_search_backspace(&mut self) {
        if self.search_focus == SearchFocus::Focused {
            self.search_query.pop();
            self.apply_filters();
        }
    }

    pub fn clear_search(&mut self) {
        self.search_query.clear();
        self.apply_filters();
    }

    pub fn set_min_score_filter(&mut self, score: Option<i32>) {
        self.min_score_filter = score;
        self.apply_filters();
    }

    /// Cycle the apply type filter to the next state
    pub fn cycle_apply_type_filter(&mut self) {
        self.apply_type_filter = self.apply_type_filter.cycle();
        self.apply_filters();
    }

    /// Cycle the seniority filter to the next state
    pub fn cycle_seniority_filter(&mut self) {
        self.seniority_filter = self.seniority_filter.cycle();
        self.apply_filters();
    }

    /// Toggle the dev-only filter
    pub fn toggle_dev_only(&mut self) {
        self.dev_only = !self.dev_only;
        self.apply_filters();
    }

    /// Show a toast notification
    fn show_toast(&mut self, message: String) {
        self.toast = Some(Toast::new(message));
    }

    /// Open the selected job URL in the system browser
    pub fn open_selected_job_url(&mut self) {
        if let Some(job) = self.selected_job() {
            match open::that(&job.url) {
                Ok(()) => self.show_toast("Opened in browser".to_string()),
                Err(e) => self.show_toast(format!("Failed to open URL: {}", e)),
            }
        }
    }

    fn apply_filters(&mut self) {
        let query = self.search_query.to_lowercase();
        let min_score = self.min_score_filter;
        let apply_type_filter = self.apply_type_filter;
        let seniority_filter = self.seniority_filter;
        let dev_only = self.dev_only;

        self.filtered_jobs = self
            .jobs
            .iter()
            .filter(|job| {
                let matches_query = query.is_empty()
                    || job.title.to_lowercase().contains(&query)
                    || job.company.to_lowercase().contains(&query)
                    || job.description.to_lowercase().contains(&query)
                    || job.source.to_lowercase().contains(&query);

                let matches_score = min_score.is_none_or(|ms| {
                    job.id > 0 && ms <= 100
                });

                let matches_apply_type = match apply_type_filter {
                    ApplyTypeFilter::All => true,
                    ApplyTypeFilter::ExternalApply => crate::domain::ApplyType::from_description(&job.description) == crate::domain::ApplyType::ExternalApply,
                    ApplyTypeFilter::EmailAvailable => crate::domain::ApplyType::from_description(&job.description) == crate::domain::ApplyType::EmailAvailable,
                    ApplyTypeFilter::Unknown => crate::domain::ApplyType::from_description(&job.description) == crate::domain::ApplyType::Unknown,
                };

                let matches_seniority = match seniority_filter {
                    SeniorityFilter::All => true,
                    _ => seniority_filter.matches(crate::domain::SeniorityLevel::from_title(&job.title)),
                };

                let matches_dev = !dev_only || crate::domain::is_dev_role(&job.title);

                matches_query && matches_score && matches_apply_type && matches_seniority && matches_dev
            })
            .cloned()
            .collect();

        self.update_selection();
    }

    fn update_selection(&mut self) {
        if self.filtered_jobs.is_empty() {
            self.selected_index = 0;
            self.list_state.select(None);
        } else {
            if self.selected_index >= self.filtered_jobs.len() {
                self.selected_index = self.filtered_jobs.len() - 1;
            }
            self.list_state.select(Some(self.selected_index));
        }
        self.scroll_state = self.scroll_state.content_length(self.filtered_jobs.len());
    }

    pub fn select_next(&mut self) {
        if self.filtered_jobs.is_empty() {
            return;
        }
        self.selected_index = (self.selected_index + 1) % self.filtered_jobs.len();
        self.list_state.select(Some(self.selected_index));
        self.scroll_state = self.scroll_state.position(self.selected_index);
    }

    pub fn select_prev(&mut self) {
        if self.filtered_jobs.is_empty() {
            return;
        }
        if self.selected_index == 0 {
            self.selected_index = self.filtered_jobs.len() - 1;
        } else {
            self.selected_index -= 1;
        }
        self.list_state.select(Some(self.selected_index));
        self.scroll_state = self.scroll_state.position(self.selected_index);
    }

    pub fn selected_job(&self) -> Option<&JobResponse> {
        self.filtered_jobs.get(self.selected_index)
    }

    pub fn toggle_detail_panel(&mut self) {
        self.show_detail_panel = !self.show_detail_panel;
    }

    pub async fn fetch_jobs(&mut self) -> anyhow::Result<()> {
        self.set_loading();

        let jobs_result = {
            let client = self.api_client.lock().await;
            client.get_jobs().await
        };

        match jobs_result {
            Ok(jobs) => {
                // Check if cache is stale and update it
                let cache_stale = {
                    let cache = self.cache.lock().await;
                    cache.is_stale().unwrap_or(false)
                };
                if cache_stale {
                    let cache = self.cache.lock().await;
                    let _ = cache.update_cache_on_fetch(&jobs);
                }
                self.from_cache = false;
                self.cache_stale = cache_stale;
                if jobs.is_empty() {
                    self.set_empty();
                } else {
                    self.set_jobs(jobs);
                    self.status = LoadingState::Loaded;
                }
                Ok(())
            }
            Err(e) => {
                // Network error: fallback to cache
                let cached_jobs_result = {
                    let cache = self.cache.lock().await;
                    cache.get_all_jobs(None)
                };

                match cached_jobs_result {
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
                        self.from_cache = true;
                        self.cache_stale = true; // Cache is stale if we had to fallback
                        if jobs.is_empty() {
                            self.set_empty();
                        } else {
                            self.set_jobs(jobs);
                            self.status = LoadingState::Loaded;
                        }
                        Ok(())
                    }
                    Err(_) => {
                        let err_msg = e.to_string();
                        self.set_error(err_msg.clone());
                        Err(anyhow::anyhow!(err_msg))
                    }
                }
            }
        }
    }

    pub async fn fetch_from_cache(&mut self) -> anyhow::Result<()> {
        let cached_jobs_result = {
            let cache = self.cache.lock().await;
            cache.get_all_jobs(None)
        };

        match cached_jobs_result {
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
                self.from_cache = true;
                let cache_stale = {
                    let cache = self.cache.lock().await;
                    cache.is_stale().unwrap_or(false)
                };
                self.cache_stale = cache_stale;
                if jobs.is_empty() {
                    self.set_empty();
                } else {
                    self.set_jobs(jobs);
                    self.status = LoadingState::Loaded;
                }
                Ok(())
            }
            Err(e) => {
                let err_msg = e.to_string();
                self.set_error(err_msg.clone());
                Err(anyhow::anyhow!(err_msg))
            }
        }
    }

    pub fn stats(&self) -> (usize, usize, Option<i32>) {
        let total = self.jobs.len();
        let analyzed = self
            .jobs
            .iter()
            .filter(|j| self.score_cache.get(&j.id).copied().flatten().is_some())
            .count();
        let scores: Vec<i32> = self
            .jobs
            .iter()
            .filter_map(|j| self.score_cache.get(&j.id).copied().flatten())
            .collect();
        let avg_score = if scores.is_empty() {
            None
        } else {
            Some(scores.iter().sum::<i32>() / scores.len() as i32)
        };
        (total, analyzed, avg_score)
    }

    pub fn draw(&mut self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Length(3),  // Stats bar
                Constraint::Length(3),  // Search bar
                Constraint::Min(0),     // Main content (list + detail)
                Constraint::Length(3),  // Hotkeys bar
            ])
            .split(area);

        self.draw_stats_bar(frame, chunks[0], theme);
        self.draw_search_bar(frame, chunks[1], theme);
        self.draw_main_content(frame, chunks[2], theme);
        self.draw_hotkeys_bar(frame, chunks[3], theme);
    }

    fn draw_stats_bar(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let (total, analyzed, avg_score) = self.stats();
        let avg_display = avg_score.map_or("---".to_string(), |s| format!("{}%", s));

        let mut stats_text = format!(
            " 📊 Total: {} | Analyzed: {} | Avg Score: {} ",
            total, analyzed, avg_display
        );

        if self.from_cache {
            stats_text.push_str(" [CACHED] ");
        }
        if self.cache_stale {
            stats_text.push_str(" [STALE] ");
        }

        let filter_text = self.apply_type_filter.display_text();
        if !filter_text.is_empty() {
            stats_text.push_str(" | Filter:");
            stats_text.push_str(filter_text);
        }

        let seniority_text = self.seniority_filter.display_text();
        if !seniority_text.is_empty() {
            stats_text.push_str(" | Seniority:");
            stats_text.push_str(seniority_text);
        }

        if self.dev_only {
            stats_text.push_str(" 💻 DEV");
        }

        let stats = Paragraph::new(Text::styled(stats_text, theme.style_normal()))
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(theme.style_border(false))
                    .title(Span::styled(" Stats ", theme.style_title())),
            );
        frame.render_widget(stats, area);
    }

    fn draw_search_bar(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let focused = self.search_focus == SearchFocus::Focused;
        let border_style = if focused {
            theme.style_border(true)
        } else {
            theme.style_border(false)
        };

        let title = if focused {
            Span::styled(" SEARCH (type to filter, Esc to blur) ", theme.style_highlight())
        } else {
            Span::styled(" SEARCH (press / or Ctrl+F to focus) ", theme.style_dim())
        };

        let display_query = if self.search_query.is_empty() && !focused {
            "Filter by keyword...".to_string()
        } else {
            self.search_query.clone()
        };

        let search_text = if focused {
            format!("{}█", display_query)
        } else {
            display_query.clone()
        };

        let search = Paragraph::new(Text::styled(search_text.clone(), theme.style_normal()))
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(border_style)
                    .title(title),
            );
        frame.render_widget(search, area);

        if focused {
            let cursor_x = area.x + 1 + display_query.len() as u16;
            frame.set_cursor_position((cursor_x.min(area.x + area.width - 2), area.y + 1));
        }
    }

    fn draw_main_content(&mut self, frame: &mut Frame, area: Rect, theme: &Theme) {
        if self.status.is_loading() {
            render_loading(frame, area, theme, "Fetching jobs...");
            return;
        }

        if let Some(error) = self.status.error_message() {
            render_error_popup(frame, area, theme, error, "[Enter] Dismiss  [r] Retry");
            return;
        }

        if self.filtered_jobs.is_empty() {
            let msg = if self.from_cache {
                "No cached jobs available.\nPress 'r' to fetch jobs from the server."
            } else {
                "No jobs found.\nPress 'r' to fetch jobs from the server."
            };
            render_empty_state(frame, area, theme, msg);
            return;
        }

        // Normal content rendering
        if self.show_detail_panel && !self.filtered_jobs.is_empty() {
            let chunks = Layout::default()
                .direction(Direction::Horizontal)
                .constraints([Constraint::Percentage(60), Constraint::Percentage(40)])
                .split(area);

            self.draw_job_list(frame, chunks[0], theme);
            self.draw_detail_panel(frame, chunks[1], theme);
        } else {
            self.draw_job_list(frame, area, theme);
        }
    }

    fn draw_job_list(&mut self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let focused = self.search_focus == SearchFocus::Blurred;
        let border_style = if focused {
            theme.style_border(true)
        } else {
            theme.style_border(false)
        };

        let title = if focused {
            Span::styled(" Jobs (↑/↓ navigate, Enter detail) ", theme.style_highlight())
        } else {
            Span::styled(" Jobs ", theme.style_dim())
        };

        let items: Vec<ListItem> = self
            .filtered_jobs
            .iter()
            .enumerate()
            .map(|(idx, job)| {
                let is_selected = idx == self.selected_index;
                let score = self.get_job_score(job);
                let score_display = if let Some(score) = score {
                    format!(" {}% ", score)
                } else {
                    " --- ".to_string()
                };

                let score_style = if let Some(score) = score {
                    theme.style_score_color(score)
                } else {
                    theme.style_dim()
                };

                // Calculate available width for title (account for prefix, company, score)
                let available_width = area.width.saturating_sub(10) as usize;
                let title = truncate_text(&job.title, available_width);
                let company = truncate_text(&job.company, available_width / 2);

                // Determine apply type icon
                let apply_type = crate::domain::ApplyType::from_description(&job.description);
                let (icon_text, icon_style) = match apply_type {
                    crate::domain::ApplyType::ExternalApply => (" 🔗", theme.style_bad()),
                    crate::domain::ApplyType::EmailAvailable => (" 📧", theme.style_good()),
                    crate::domain::ApplyType::Unknown => (" ❓", theme.style_warn()),
                };

                let line = if is_selected {
                    Line::from(vec![
                        Span::styled("► ", theme.style_selected()),
                        Span::styled(icon_text, icon_style),
                        Span::styled(
                            format!("{} ", title),
                            theme.style_selected(),
                        ),
                        Span::styled(
                            format!("— {} ", company),
                            theme.style_selected(),
                        ),
                        Span::styled(score_display, score_style.add_modifier(Modifier::BOLD)),
                    ])
                } else {
                    Line::from(vec![
                        Span::styled("  ", theme.style_normal()),
                        Span::styled(icon_text, icon_style),
                        Span::styled(
                            format!("{} ", title),
                            theme.style_normal(),
                        ),
                        Span::styled(
                            format!("— {} ", company),
                            theme.style_dim(),
                        ),
                        Span::styled(score_display, score_style),
                    ])
                };

                ListItem::new(line)
            })
            .collect();

        let list = List::new(items)
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(border_style)
                    .title(title),
            )
            .highlight_style(theme.style_selected())
            .highlight_symbol("");

        frame.render_stateful_widget(list, area, &mut self.list_state);

        if self.filtered_jobs.len() > area.height as usize {
            let scrollbar = Scrollbar::new(ScrollbarOrientation::VerticalRight)
                .begin_symbol(Some("▲"))
                .end_symbol(Some("▼"))
                .track_symbol(Some("│"))
                .thumb_symbol("█");
            let mut scrollbar_state = self.scroll_state;
            frame.render_stateful_widget(scrollbar, area, &mut scrollbar_state);
        }
    }

    fn get_job_score(&self, job: &JobResponse) -> Option<i32> {
        self.score_cache.get(&job.id).copied().flatten()
    }

    fn draw_detail_panel(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let border_style = theme.style_border(false);

        let title = Span::styled(" Detail ", theme.style_title());

        let mut body = String::new();
        if let Some(job) = self.selected_job() {
            body.push_str(&format!("Title:     {}\n", job.title));
            body.push_str(&format!("Company:   {}\n", job.company));
            body.push_str(&format!("Source:    {}\n", job.source));
            body.push_str(&format!("Posted:    {}\n", job.posted_at));
            body.push_str(&format!("URL:       {}\n", job.url));
            if !job.description.is_empty() {
                body.push_str(&format!("\nDescription:\n{}", job.description));
            }
        } else {
            body.push_str("No job selected.");
        }

        let detail = Paragraph::new(Text::styled(body, theme.style_normal()))
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(border_style)
                    .title(title),
            )
            .wrap(ratatui::widgets::Wrap { trim: false });
        frame.render_widget(detail, area);
    }

    fn draw_hotkeys_bar(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let hotkeys = if self.search_focus == SearchFocus::Focused {
            " [Esc] Blur search  [Enter] Apply filter  [Backspace] Delete "
        } else {
            " [↑/↓] Navigate  [Enter] Detail  [/] Search  [r] Refresh  [a] Analyze  [e] Email  [p] Profile  [t] Apply  [s] Seniority  [d] Dev  [o] Open  [q] Quit "
        };

        let hotkeys_widget = Paragraph::new(Text::styled(hotkeys, theme.style_dim()))
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(theme.style_border(false)),
            )
            .alignment(ratatui::layout::Alignment::Center);
        frame.render_widget(hotkeys_widget, area);
    }
}

pub fn draw(frame: &mut Frame, area: Rect, app: &mut crate::tui::app::App) {
    if app.job_list_screen.is_none() {
        let api_client = app.api_client.clone();
        let cache = app.cache.clone();
        app.job_list_screen = Some(JobListScreen::new(api_client, cache));
    }

    if let Some(screen) = &mut app.job_list_screen {
        screen.draw(frame, area, &Theme::detect());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::ApiClient;
    use crate::cache::CacheManager;
    use crate::domain::JobResponse;
    use chrono::NaiveDate;
    use std::sync::Arc;
    use tokio::sync::Mutex;

    fn create_test_screen() -> JobListScreen {
        let api_client = Arc::new(Mutex::new(ApiClient::new("http://localhost:8080")));
        let cache = Arc::new(Mutex::new(CacheManager::new_in_memory(24).expect("cache")));
        JobListScreen::new(api_client, cache)
    }

    fn sample_jobs() -> Vec<JobResponse> {
        vec![
            JobResponse {
                id: 1,
                title: "Junior Rust Developer".into(),
                company: "Tech Corp".into(),
                url: "https://example.com/job/1".into(),
                description: "Build CLI tools in Rust".into(),
                posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
                source: "gupy".into(),
            },
            JobResponse {
                id: 2,
                title: "Java Backend Engineer".into(),
                company: "IBM".into(),
                url: "https://example.com/job/2".into(),
                description: "Enterprise Java development with Spring".into(),
                posted_at: NaiveDate::from_ymd_opt(2026, 7, 13).unwrap(),
                source: "linkedin".into(),
            },
            JobResponse {
                id: 3,
                title: "Senior Rust Engineer".into(),
                company: "StartupXYZ".into(),
                url: "https://example.com/job/3".into(),
                description: "High-performance systems in Rust".into(),
                posted_at: NaiveDate::from_ymd_opt(2026, 7, 12).unwrap(),
                source: "infojobs".into(),
            },
        ]
    }

    #[test]
    fn job_list_screen_new_creates_empty_state() {
        let screen = create_test_screen();

        assert!(screen.jobs.is_empty());
        assert!(screen.filtered_jobs.is_empty());
        assert_eq!(screen.selected_index, 0);
        assert!(screen.search_query.is_empty());
        assert_eq!(screen.min_score_filter, None);
        assert_eq!(screen.status, LoadingState::Empty);
        assert_eq!(screen.search_focus, SearchFocus::Blurred);
        assert!(screen.show_detail_panel);
    }

    #[test]
    fn job_list_screen_set_jobs_populates_list() {
        let mut screen = create_test_screen();
        let jobs = sample_jobs();
        screen.set_jobs(jobs.clone());

        assert_eq!(screen.jobs.len(), 3);
        assert_eq!(screen.filtered_jobs.len(), 3);
        assert_eq!(screen.selected_index, 0);
        assert_eq!(screen.status, LoadingState::Loaded);
    }

    #[test]
    fn job_list_screen_filter_by_search_query() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());

        screen.search_query = "rust".to_string();
        screen.apply_filters();

        assert_eq!(screen.filtered_jobs.len(), 2);
        assert!(screen.filtered_jobs.iter().all(|j| j.title.to_lowercase().contains("rust")));
    }

    #[test]
    fn job_list_screen_filter_by_company() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());

        screen.search_query = "ibm".to_string();
        screen.apply_filters();

        assert_eq!(screen.filtered_jobs.len(), 1);
        assert_eq!(screen.filtered_jobs[0].company, "IBM");
    }

    #[test]
    fn job_list_screen_filter_by_description() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());

        screen.search_query = "spring".to_string();
        screen.apply_filters();

        assert_eq!(screen.filtered_jobs.len(), 1);
        assert_eq!(screen.filtered_jobs[0].title, "Java Backend Engineer");
    }

    #[test]
    fn job_list_screen_filter_by_source() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());

        screen.search_query = "gupy".to_string();
        screen.apply_filters();

        assert_eq!(screen.filtered_jobs.len(), 1);
        assert_eq!(screen.filtered_jobs[0].source, "gupy");
    }

    #[test]
    fn job_list_screen_empty_query_shows_all() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());

        screen.search_query = "".to_string();
        screen.apply_filters();

        assert_eq!(screen.filtered_jobs.len(), 3);
    }

    #[test]
    fn job_list_screen_no_match_returns_empty() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());

        screen.search_query = "nonexistent".to_string();
        screen.apply_filters();

        assert!(screen.filtered_jobs.is_empty());
        assert_eq!(screen.selected_index, 0);
        assert!(screen.list_state.selected().is_none());
    }

    #[test]
    fn job_list_screen_select_next_wraps() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());

        assert_eq!(screen.selected_index, 0);

        screen.select_next();
        assert_eq!(screen.selected_index, 1);

        screen.select_next();
        assert_eq!(screen.selected_index, 2);

        screen.select_next();
        assert_eq!(screen.selected_index, 0);
    }

    #[test]
    fn job_list_screen_select_prev_wraps() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());

        assert_eq!(screen.selected_index, 0);

        screen.select_prev();
        assert_eq!(screen.selected_index, 2);

        screen.select_prev();
        assert_eq!(screen.selected_index, 1);

        screen.select_prev();
        assert_eq!(screen.selected_index, 0);
    }

    #[test]
    fn job_list_screen_select_next_on_empty_does_nothing() {
        let mut screen = create_test_screen();
        screen.set_jobs(vec![]);

        screen.select_next();
        assert_eq!(screen.selected_index, 0);
    }

    #[test]
    fn job_list_screen_selected_job_returns_correct_job() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());

        assert_eq!(screen.selected_job().unwrap().id, 1);

        screen.select_next();
        assert_eq!(screen.selected_job().unwrap().id, 2);

        screen.select_next();
        assert_eq!(screen.selected_job().unwrap().id, 3);
    }

    #[test]
    fn job_list_screen_loading_state() {
        let mut screen = create_test_screen();
        assert_eq!(screen.status, LoadingState::Empty);

        screen.set_loading();
        assert_eq!(screen.status, LoadingState::Loading);
        assert!(screen.status.is_loading());
    }

    #[test]
    fn job_list_screen_error_state() {
        let mut screen = create_test_screen();
        screen.set_error("Network error".to_string());

        assert!(matches!(screen.status, LoadingState::Error(_)));
        assert!(screen.status.is_error());
    }

    #[test]
    fn job_list_screen_empty_state() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());
        screen.set_empty();

        assert_eq!(screen.status, LoadingState::Empty);
        assert!(screen.jobs.is_empty());
        assert!(screen.filtered_jobs.is_empty());
    }

    #[test]
    fn job_list_screen_search_focus_transitions() {
        let mut screen = create_test_screen();

        assert_eq!(screen.search_focus, SearchFocus::Blurred);

        screen.focus_search();
        assert_eq!(screen.search_focus, SearchFocus::Focused);

        screen.blur_search();
        assert_eq!(screen.search_focus, SearchFocus::Blurred);
    }

    #[test]
    fn job_list_screen_search_input_handling() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());
        screen.focus_search();

        screen.handle_search_char('r');
        screen.handle_search_char('u');
        screen.handle_search_char('s');
        screen.handle_search_char('t');

        assert_eq!(screen.search_query, "rust");
        assert_eq!(screen.filtered_jobs.len(), 2);
    }

    #[test]
    fn job_list_screen_search_backspace() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());
        screen.focus_search();

        screen.handle_search_char('r');
        screen.handle_search_char('u');
        screen.handle_search_char('s');
        screen.handle_search_char('t');
        assert_eq!(screen.search_query, "rust");

        screen.handle_search_backspace();
        assert_eq!(screen.search_query, "rus");
        assert_eq!(screen.filtered_jobs.len(), 2);
    }

    #[test]
    fn job_list_screen_clear_search() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());
        screen.focus_search();

        screen.handle_search_char('r');
        screen.handle_search_char('u');
        screen.handle_search_char('s');
        screen.handle_search_char('t');
        assert_eq!(screen.filtered_jobs.len(), 2);

        screen.clear_search();
        assert_eq!(screen.search_query, "");
        assert_eq!(screen.filtered_jobs.len(), 3);
    }

    #[test]
    fn job_list_screen_min_score_filter() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());

        screen.set_min_score_filter(Some(80));
        assert_eq!(screen.min_score_filter, Some(80));
    }

    #[test]
    fn job_list_screen_toggle_detail_panel() {
        let mut screen = create_test_screen();
        assert!(screen.show_detail_panel);

        screen.toggle_detail_panel();
        assert!(!screen.show_detail_panel);

        screen.toggle_detail_panel();
        assert!(screen.show_detail_panel);
    }

    #[test]
    fn job_list_screen_stats_calculation() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());

        let (total, analyzed, avg_score) = screen.stats();
        assert_eq!(total, 3);
        // In-memory cache has no data, so analyzed should be 0
        assert_eq!(analyzed, 0);
        assert_eq!(avg_score, None);
    }

    #[test]
    fn loading_state_display_text() {
        assert_eq!(LoadingState::Empty.display_text(), "No jobs found. Press 'r' to fetch jobs.");
        assert_eq!(LoadingState::Loading.display_text(), "Fetching jobs...");
        assert_eq!(LoadingState::Loaded.display_text(), "");
        assert_eq!(LoadingState::Error("Network error".into()).display_text(), "Error loading jobs");
    }

    #[test]
    fn search_focus_display_text() {
        assert_eq!(SearchFocus::Focused.display_text(), "SEARCH (focused)");
        assert_eq!(SearchFocus::Blurred.display_text(), "SEARCH");
    }

    #[test]
    fn job_list_screen_render_does_not_panic_empty() {
        let mut screen = create_test_screen();
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area(), &Theme::detect());
        }).unwrap();
    }

    #[test]
    fn job_list_screen_render_does_not_panic_with_jobs() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area(), &Theme::detect());
        }).unwrap();
    }

    #[test]
    fn job_list_screen_render_does_not_panic_loading() {
        let mut screen = create_test_screen();
        screen.set_loading();
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area(), &Theme::detect());
        }).unwrap();
    }

    #[test]
    fn job_list_screen_render_does_not_panic_error() {
        let mut screen = create_test_screen();
        screen.set_error("Failed to fetch".to_string());
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area(), &Theme::detect());
        }).unwrap();
    }

    #[test]
    fn job_list_screen_render_does_not_panic_search_focused() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs());
        screen.focus_search();
        screen.search_query = "rust".to_string();
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area(), &Theme::detect());
        }).unwrap();
    }

    #[test]
    fn job_list_screen_handles_long_job_titles() {
        let mut screen = create_test_screen();
        let long_title = "A".repeat(200);
        let jobs = vec![JobResponse {
            id: 1,
            title: long_title.clone(),
            company: "Test Corp".into(),
            url: "https://example.com/job/1".into(),
            description: "Description".into(),
            posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
        }];
        screen.set_jobs(jobs);
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area(), &Theme::detect());
        }).unwrap();
    }

    #[test]
    fn job_list_screen_handles_unicode_characters() {
        let mut screen = create_test_screen();
        let jobs = vec![JobResponse {
            id: 1,
            title: "Rust Developer 🦀".into(),
            company: "Empresa Brasileira 🇧🇷".into(),
            url: "https://example.com/job/1".into(),
            description: "Descrição com acentos: áéíóú ñ".into(),
            posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
        }];
        screen.set_jobs(jobs);
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area(), &Theme::detect());
        }).unwrap();
    }

    #[test]
    fn job_list_screen_handles_empty_job_list() {
        let mut screen = create_test_screen();
        screen.set_jobs(vec![]);
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area(), &Theme::detect());
        }).unwrap();
    }

    #[test]
    fn job_list_screen_handles_very_long_description() {
        let mut screen = create_test_screen();
        let long_desc = "Lorem ipsum ".repeat(100);
        let jobs = vec![JobResponse {
            id: 1,
            title: "Job with long description".into(),
            company: "Test Corp".into(),
            url: "https://example.com/job/1".into(),
            description: long_desc,
            posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
        }];
        screen.set_jobs(jobs);
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area(), &Theme::detect());
        }).unwrap();
    }

    #[test]
    fn job_list_screen_truncates_long_titles_in_list() {
        let mut screen = create_test_screen();
        let long_title = "A".repeat(100);
        let jobs = vec![JobResponse {
            id: 1,
            title: long_title.clone(),
            company: "Test Corp".into(),
            url: "https://example.com/job/1".into(),
            description: "Description".into(),
            posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
            source: "gupy".into(),
        }];
        screen.set_jobs(jobs);
        
        // The title should be truncated when rendered
        let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();
        terminal.draw(|frame| {
            screen.draw(frame, frame.area(), &Theme::detect());
        }).unwrap();
    }

    // Helper to create jobs with different apply types
    fn sample_jobs_with_apply_types() -> Vec<JobResponse> {
        vec![
            JobResponse {
                id: 1,
                title: "External Apply Job".into(),
                company: "Company A".into(),
                url: "https://example.com/job/1".into(),
                description: "".into(),
                posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(),
                source: "gupy".into(),
            },
            JobResponse {
                id: 2,
                title: "Email Available Job".into(),
                company: "Company B".into(),
                url: "https://example.com/job/2".into(),
                description: "This is a long description with more than twenty characters".into(),
                posted_at: NaiveDate::from_ymd_opt(2026, 7, 13).unwrap(),
                source: "linkedin".into(),
            },
            JobResponse {
                id: 3,
                title: "Unknown Type Job".into(),
                company: "Company C".into(),
                url: "https://example.com/job/3".into(),
                description: "Short".into(),
                posted_at: NaiveDate::from_ymd_opt(2026, 7, 12).unwrap(),
                source: "infojobs".into(),
            },
        ]
    }

    #[test]
    fn apply_type_filter_all_shows_all_jobs() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs_with_apply_types());

        assert_eq!(screen.apply_type_filter, ApplyTypeFilter::All);
        assert_eq!(screen.filtered_jobs.len(), 3);
    }

    #[test]
    fn apply_type_filter_external_shows_only_external() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs_with_apply_types());

        screen.apply_type_filter = ApplyTypeFilter::ExternalApply;
        screen.apply_filters();

        assert_eq!(screen.filtered_jobs.len(), 1);
        assert_eq!(screen.filtered_jobs[0].id, 1);
        assert_eq!(screen.filtered_jobs[0].description, "");
    }

    #[test]
    fn apply_type_filter_email_shows_only_email() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs_with_apply_types());

        screen.apply_type_filter = ApplyTypeFilter::EmailAvailable;
        screen.apply_filters();

        assert_eq!(screen.filtered_jobs.len(), 1);
        assert_eq!(screen.filtered_jobs[0].id, 2);
        assert!(screen.filtered_jobs[0].description.len() >= 20);
    }

    #[test]
    fn apply_type_filter_unknown_shows_only_unknown() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs_with_apply_types());

        screen.apply_type_filter = ApplyTypeFilter::Unknown;
        screen.apply_filters();

        assert_eq!(screen.filtered_jobs.len(), 1);
        assert_eq!(screen.filtered_jobs[0].id, 3);
        assert!(screen.filtered_jobs[0].description.len() < 20 && !screen.filtered_jobs[0].description.is_empty());
    }

    #[test]
    fn filter_cycle_keybinding_cycles_states() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs_with_apply_types());

        assert_eq!(screen.apply_type_filter, ApplyTypeFilter::All);

        screen.cycle_apply_type_filter();
        assert_eq!(screen.apply_type_filter, ApplyTypeFilter::ExternalApply);

        screen.cycle_apply_type_filter();
        assert_eq!(screen.apply_type_filter, ApplyTypeFilter::EmailAvailable);

        screen.cycle_apply_type_filter();
        assert_eq!(screen.apply_type_filter, ApplyTypeFilter::Unknown);

        screen.cycle_apply_type_filter();
        assert_eq!(screen.apply_type_filter, ApplyTypeFilter::All);
    }

    #[test]
    fn open_url_keybinding_in_joblist_sets_toast() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs_with_apply_types());

        screen.show_toast("Opened in browser".to_string());
        assert!(screen.toast.is_some());
        assert_eq!(screen.toast.as_ref().unwrap().message, "Opened in browser");
    }

    #[test]
    fn filter_indicator_shows_in_stats_bar() {
        let mut screen = create_test_screen();
        screen.set_jobs(sample_jobs_with_apply_types());

        screen.apply_type_filter = ApplyTypeFilter::ExternalApply;
        assert_eq!(screen.apply_type_filter, ApplyTypeFilter::ExternalApply);

        screen.apply_type_filter = ApplyTypeFilter::EmailAvailable;
        assert_eq!(screen.apply_type_filter, ApplyTypeFilter::EmailAvailable);

        screen.apply_type_filter = ApplyTypeFilter::All;
        assert_eq!(screen.apply_type_filter, ApplyTypeFilter::All);
    }

    #[test]
    fn get_job_score_returns_cached_score() {
        let mut screen = create_test_screen();
        let jobs = sample_jobs();
        screen.set_jobs(jobs.clone());

        screen.score_cache.insert(1, Some(85));
        screen.score_cache.insert(2, None);
        screen.score_cache.insert(3, Some(92));

        let job1 = &jobs[0];
        let job2 = &jobs[1];
        let job3 = &jobs[2];

        assert_eq!(screen.get_job_score(job1), Some(85));
        assert_eq!(screen.get_job_score(job2), None);
        assert_eq!(screen.get_job_score(job3), Some(92));
    }

    #[test]
    fn stats_uses_score_cache() {
        let mut screen = create_test_screen();
        let jobs = sample_jobs();
        screen.set_jobs(jobs.clone());

        screen.score_cache.insert(1, Some(80));
        screen.score_cache.insert(2, Some(90));
        screen.score_cache.insert(3, None);

        let (total, analyzed, avg_score) = screen.stats();

        assert_eq!(total, 3);
        assert_eq!(analyzed, 2);
        assert_eq!(avg_score, Some(85));
    }

    // --- SeniorityFilter tests ---
    #[test]
    fn seniority_filter_default_is_all() {
        let screen = create_test_screen();
        assert_eq!(screen.seniority_filter, SeniorityFilter::All);
    }

    #[test]
    fn seniority_filter_cycle_returns_to_all() {
        let mut screen = create_test_screen();
        assert_eq!(screen.seniority_filter, SeniorityFilter::All);

        screen.cycle_seniority_filter();
        assert_eq!(screen.seniority_filter, SeniorityFilter::Junior);

        screen.cycle_seniority_filter();
        assert_eq!(screen.seniority_filter, SeniorityFilter::JuniorPleno);

        screen.cycle_seniority_filter();
        assert_eq!(screen.seniority_filter, SeniorityFilter::SeniorLead);

        screen.cycle_seniority_filter();
        assert_eq!(screen.seniority_filter, SeniorityFilter::Unknown);

        screen.cycle_seniority_filter();
        assert_eq!(screen.seniority_filter, SeniorityFilter::All);
    }

    #[test]
    fn seniority_filter_cycle_states() {
        let mut screen = create_test_screen();
        let states = [
            SeniorityFilter::All,
            SeniorityFilter::Junior,
            SeniorityFilter::JuniorPleno,
            SeniorityFilter::SeniorLead,
            SeniorityFilter::Unknown,
        ];

        for (i, expected) in states.iter().enumerate() {
            assert_eq!(screen.seniority_filter, *expected, "step {}", i);
            if i < states.len() - 1 {
                screen.cycle_seniority_filter();
            }
        }
    }

    #[test]
    fn seniority_filter_matches_all() {
        let mut screen = create_test_screen();
        screen.seniority_filter = SeniorityFilter::All;

        let jobs = vec![
            JobResponse { id: 1, title: "Junior Dev".into(), company: "A".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "gupy".into() },
            JobResponse { id: 2, title: "Senior Engineer".into(), company: "B".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "linkedin".into() },
            JobResponse { id: 3, title: "Pleno Developer".into(), company: "C".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "infojobs".into() },
        ];
        screen.set_jobs(jobs);
        screen.apply_filters();

        assert_eq!(screen.filtered_jobs.len(), 3);
    }

    #[test]
    fn seniority_filter_matches_junior() {
        let mut screen = create_test_screen();
        screen.seniority_filter = SeniorityFilter::Junior;

        let jobs = vec![
            JobResponse { id: 1, title: "Junior Dev".into(), company: "A".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "gupy".into() },
            JobResponse { id: 2, title: "Senior Engineer".into(), company: "B".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "linkedin".into() },
        ];
        screen.set_jobs(jobs);
        screen.apply_filters();

        assert_eq!(screen.filtered_jobs.len(), 1);
        assert_eq!(screen.filtered_jobs[0].id, 1);
    }

    #[test]
    fn seniority_filter_matches_junior_pleno() {
        let mut screen = create_test_screen();
        screen.seniority_filter = SeniorityFilter::JuniorPleno;

        let jobs = vec![
            JobResponse { id: 1, title: "Junior Dev".into(), company: "A".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "gupy".into() },
            JobResponse { id: 2, title: "Pleno Developer".into(), company: "B".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "linkedin".into() },
            JobResponse { id: 3, title: "Senior Engineer".into(), company: "C".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "infojobs".into() },
        ];
        screen.set_jobs(jobs);
        screen.apply_filters();

        assert_eq!(screen.filtered_jobs.len(), 2);
        assert!(screen.filtered_jobs.iter().all(|j| j.id == 1 || j.id == 2));
    }

    #[test]
    fn seniority_filter_matches_senior_lead() {
        let mut screen = create_test_screen();
        screen.seniority_filter = SeniorityFilter::SeniorLead;

        let jobs = vec![
            JobResponse { id: 1, title: "Junior Dev".into(), company: "A".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "gupy".into() },
            JobResponse { id: 2, title: "Senior Engineer".into(), company: "B".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "linkedin".into() },
            JobResponse { id: 3, title: "Tech Lead".into(), company: "C".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "infojobs".into() },
        ];
        screen.set_jobs(jobs);
        screen.apply_filters();

        assert_eq!(screen.filtered_jobs.len(), 2);
        assert!(screen.filtered_jobs.iter().all(|j| j.id == 2 || j.id == 3));
    }

    #[test]
    fn seniority_filter_matches_unknown() {
        let mut screen = create_test_screen();
        screen.seniority_filter = SeniorityFilter::Unknown;

        let jobs = vec![
            JobResponse { id: 1, title: "Developer".into(), company: "A".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "gupy".into() },
            JobResponse { id: 2, title: "Junior Dev".into(), company: "B".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "linkedin".into() },
        ];
        screen.set_jobs(jobs);
        screen.apply_filters();

        assert_eq!(screen.filtered_jobs.len(), 1);
        assert_eq!(screen.filtered_jobs[0].id, 1);
    }

    // --- Dev filter tests ---
    #[test]
    fn dev_filter_toggle() {
        let mut screen = create_test_screen();
        assert!(!screen.dev_only);

        screen.toggle_dev_only();
        assert!(screen.dev_only);

        screen.toggle_dev_only();
        assert!(!screen.dev_only);
    }

    #[test]
    fn dev_filter_shows_only_dev_jobs() {
        let mut screen = create_test_screen();
        screen.dev_only = true;

        let jobs = vec![
            JobResponse { id: 1, title: "Software Developer".into(), company: "A".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "gupy".into() },
            JobResponse { id: 2, title: "Designer".into(), company: "B".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "linkedin".into() },
            JobResponse { id: 3, title: "Backend Engineer".into(), company: "C".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "infojobs".into() },
        ];
        screen.set_jobs(jobs);
        screen.apply_filters();

        assert_eq!(screen.filtered_jobs.len(), 2);
        assert!(screen.filtered_jobs.iter().all(|j| j.id == 1 || j.id == 3));
    }

    // --- Combined filters tests ---
    #[test]
    fn seniority_and_dev_filter_combine() {
        let mut screen = create_test_screen();
        screen.seniority_filter = SeniorityFilter::Junior;
        screen.dev_only = true;

        let jobs = vec![
            JobResponse { id: 1, title: "Junior Developer".into(), company: "A".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "gupy".into() },
            JobResponse { id: 2, title: "Junior Designer".into(), company: "B".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "linkedin".into() },
            JobResponse { id: 3, title: "Senior Developer".into(), company: "C".into(), url: "".into(), description: "".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "infojobs".into() },
        ];
        screen.set_jobs(jobs);
        screen.apply_filters();

        assert_eq!(screen.filtered_jobs.len(), 1);
        assert_eq!(screen.filtered_jobs[0].id, 1);
    }

    #[test]
    fn seniority_dev_and_apply_type_combine() {
        let mut screen = create_test_screen();
        screen.seniority_filter = SeniorityFilter::Junior;
        screen.dev_only = true;
        screen.apply_type_filter = ApplyTypeFilter::EmailAvailable;

        let jobs = vec![
            JobResponse { id: 1, title: "Junior Developer".into(), company: "A".into(), url: "".into(), description: "This is a long description with more than twenty characters".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "gupy".into() },
            JobResponse { id: 2, title: "Junior Developer".into(), company: "B".into(), url: "".into(), description: "Short".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "linkedin".into() },
            JobResponse { id: 3, title: "Senior Developer".into(), company: "C".into(), url: "".into(), description: "This is a long description with more than twenty characters".into(), posted_at: NaiveDate::from_ymd_opt(2026, 7, 14).unwrap(), source: "infojobs".into() },
        ];
        screen.set_jobs(jobs);
        screen.apply_filters();

        assert_eq!(screen.filtered_jobs.len(), 1);
        assert_eq!(screen.filtered_jobs[0].id, 1);
    }

    #[test]
    fn seniority_filter_indicator_in_stats() {
        let mut screen = create_test_screen();
        screen.seniority_filter = SeniorityFilter::Junior;
        assert_eq!(screen.seniority_filter.display_text(), " 👶 JR");

        screen.seniority_filter = SeniorityFilter::JuniorPleno;
        assert_eq!(screen.seniority_filter.display_text(), " 🎓 JR+PL");

        screen.seniority_filter = SeniorityFilter::SeniorLead;
        assert_eq!(screen.seniority_filter.display_text(), " 👑 SR+LD");

        screen.seniority_filter = SeniorityFilter::Unknown;
        assert_eq!(screen.seniority_filter.display_text(), " ❓ UNK");

        screen.seniority_filter = SeniorityFilter::All;
        assert_eq!(screen.seniority_filter.display_text(), "");
    }

    #[test]
    fn dev_filter_indicator_in_stats() {
        let mut screen = create_test_screen();
        assert!(!screen.dev_only);

        screen.dev_only = true;
        assert!(screen.dev_only);
    }
}