use crate::api::ApiClient;
use crate::config::ConfigManager;
use crate::domain::{CompanyTone, ProfileRequest, ProfileResponse};
use crate::tui::theme::{Theme, render_empty_state, render_error_popup, render_loading};
use crate::tui::Toast;
use ratatui::{
    Frame,
    layout::{Alignment, Constraint, Direction, Layout, Margin, Rect},
    style::Modifier,
    text::{Line, Span, Text},
    widgets::{Block, Borders, Clear, List, ListItem, Paragraph, Wrap},
};
use std::sync::Arc;
use tokio::sync::Mutex;

/// Profile screen mode: View or Edit
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProfileMode {
    View,
    Edit,
}

impl ProfileMode {
    pub fn display_name(&self) -> &'static str {
        match self {
            ProfileMode::View => "VIEW",
            ProfileMode::Edit => "EDIT",
        }
    }

    pub fn toggle(&self) -> Self {
        match self {
            ProfileMode::View => ProfileMode::Edit,
            ProfileMode::Edit => ProfileMode::View,
        }
    }
}

/// Focusable fields in edit mode
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProfileField {
    Resume,
    Skills,
    Tone,
}

impl ProfileField {
    pub fn display_name(&self) -> &'static str {
        match self {
            ProfileField::Resume => "Resume",
            ProfileField::Skills => "Skills",
            ProfileField::Tone => "Tone",
        }
    }

    pub fn all() -> Vec<Self> {
        vec![ProfileField::Resume, ProfileField::Skills, ProfileField::Tone]
    }

    pub fn next(&self) -> Self {
        let fields = Self::all();
        let idx = fields.iter().position(|f| f == self).unwrap_or(0);
        fields[(idx + 1) % fields.len()]
    }

    pub fn prev(&self) -> Self {
        let fields = Self::all();
        let idx = fields.iter().position(|f| f == self).unwrap_or(0);
        fields[(idx + fields.len() - 1) % fields.len()]
    }
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

/// Profile Screen with view/edit modes
pub struct ProfileScreen {
    pub profile: Option<ProfileResponse>,
    pub mode: ProfileMode,
    pub resume_text: String,
    pub skills_text: String,
    pub tone_selection: usize,
    pub focused_field: ProfileField,
    pub loading: LoadingState,
    pub saving: bool,
    pub validation_errors: Vec<String>,
    pub resume_scroll: u16,
    pub resume_cursor: usize,
    pub skills_cursor: usize,
    api_client: Arc<Mutex<ApiClient>>,
    config_manager: Arc<Mutex<ConfigManager>>,
    toast: Option<Toast>,
}

impl ProfileScreen {
    pub fn new(
        api_client: Arc<Mutex<ApiClient>>,
        config_manager: Arc<Mutex<ConfigManager>>,
    ) -> Self {
        Self {
            profile: None,
            mode: ProfileMode::View,
            resume_text: String::new(),
            skills_text: String::new(),
            tone_selection: 0,
            focused_field: ProfileField::Resume,
            loading: LoadingState::Idle,
            saving: false,
            validation_errors: Vec::new(),
            resume_scroll: 0,
            resume_cursor: 0,
            skills_cursor: 0,
            api_client,
            config_manager,
            toast: None,
        }
    }

    /// Set the profile data and populate edit fields
    pub fn set_profile(&mut self, profile: ProfileResponse) {
        self.resume_text = Self::sanitize_text(&profile.resume_text);
        self.skills_text = profile.skills.join(", ");
        self.tone_selection = match profile.tone {
            CompanyTone::Formal => 0,
            CompanyTone::Casual => 1,
            CompanyTone::Startup => 2,
        };
        self.profile = Some(profile);
        self.clear_validation_errors();
        self.resume_cursor = 0;
        self.skills_cursor = 0;
    }

    pub fn clear_validation_errors(&mut self) {
        self.validation_errors.clear();
    }

    /// Sanitize text for TUI display: remove \r (carriage return) and
    /// replace bullet (U+F0B7) with "- " for cleaner rendering.
    fn sanitize_text(s: &str) -> String {
        s.replace('\r', "").replace('\u{f0b7}', "- ")
    }

    fn show_toast(&mut self, message: String) {
        self.toast = Some(Toast::new(message));
    }

    fn update_toast(&mut self) {
        if let Some(toast) = &self.toast
            && toast.is_expired() {
                self.toast = None;
            }
    }

    pub fn toggle_mode(&mut self) {
        self.mode = self.mode.toggle();
        if self.mode == ProfileMode::Edit {
            self.focused_field = ProfileField::Resume;
        }
        self.clear_validation_errors();
    }

    pub fn focus_next(&mut self) {
        if self.mode == ProfileMode::Edit {
            self.focused_field = self.focused_field.next();
        }
    }

    pub fn focus_prev(&mut self) {
        if self.mode == ProfileMode::Edit {
            self.focused_field = self.focused_field.prev();
        }
    }

    /// Handle character input in edit mode
    pub fn handle_char(&mut self, c: char) {
        if self.mode != ProfileMode::Edit || self.saving || self.loading.is_loading() {
            return;
        }
        match self.focused_field {
            ProfileField::Resume => {
                let byte_pos = self.char_to_byte_index(&self.resume_text, self.resume_cursor);
                self.resume_text.insert(byte_pos, c);
                self.resume_cursor += 1;
            }
            ProfileField::Skills => {
                let byte_pos = self.char_to_byte_index(&self.skills_text, self.skills_cursor);
                self.skills_text.insert(byte_pos, c);
                self.skills_cursor += 1;
            }
            ProfileField::Tone => {} // Tone is selected via arrow keys
        }
        self.clear_validation_errors();
    }

    /// Convert character index to byte index for String operations
    fn char_to_byte_index(&self, text: &str, char_index: usize) -> usize {
        text.char_indices()
            .nth(char_index)
            .map(|(i, _)| i)
            .unwrap_or(text.len())
    }

    /// Calculate visual cursor position (col, row) from character index
    /// Accounts for explicit newlines and text wrapping within field_width
    fn cursor_visual_position(&self, text: &str, cursor: usize, field_width: u16) -> (u16, u16) {
        let width = field_width as usize;
        if width == 0 {
            return (0, 0);
        }

        let mut visual_row = 0;
        let mut visual_col = 0;
        let mut char_idx = 0;

        for line in text.split('\n') {
            let line_chars: Vec<char> = line.chars().collect();
            let line_len = line_chars.len();

            if char_idx + line_len >= cursor {
                // Cursor is on this logical line
                let cursor_in_line = cursor - char_idx;
                // Calculate how many visual lines this logical line wraps to
                let _wrapped_lines = if line_len == 0 {
                    1
                } else {
                    (line_len + width - 1) / width
                };
                // Find which wrapped line the cursor is on
                let target_wrapped_line = cursor_in_line / width;
                visual_row += target_wrapped_line as u16;
                visual_col = (cursor_in_line % width) as u16;
                return (visual_col, visual_row);
            }

            // Cursor is past this logical line
            let wrapped_lines = if line_len == 0 {
                1
            } else {
                (line_len + width - 1) / width
            };
            visual_row += wrapped_lines as u16;
            char_idx += line_len + 1; // +1 for the newline character
        }

        // Cursor is at the very end (after all text)
        // Handle case where text ends with newline
        if text.ends_with('\n') {
            visual_row += 1;
            visual_col = 0;
        }
        (visual_col, visual_row)
    }

    /// Handle backspace in edit mode
    pub fn handle_backspace(&mut self) {
        if self.mode != ProfileMode::Edit || self.saving || self.loading.is_loading() {
            return;
        }
        match self.focused_field {
            ProfileField::Resume => {
                if self.resume_cursor > 0 {
                    let byte_pos = self.char_to_byte_index(&self.resume_text, self.resume_cursor - 1);
                    self.resume_text.remove(byte_pos);
                    self.resume_cursor -= 1;
                }
            }
            ProfileField::Skills => {
                if self.skills_cursor > 0 {
                    let byte_pos = self.char_to_byte_index(&self.skills_text, self.skills_cursor - 1);
                    self.skills_text.remove(byte_pos);
                    self.skills_cursor -= 1;
                }
            }
            ProfileField::Tone => {}
        }
        self.clear_validation_errors();
    }

    /// Handle newline (Enter) in edit mode - inserts newline in Resume field
    pub fn handle_newline(&mut self) {
        if self.mode != ProfileMode::Edit || self.saving || self.loading.is_loading() {
            return;
        }
        match self.focused_field {
            ProfileField::Resume => {
                let byte_pos = self.char_to_byte_index(&self.resume_text, self.resume_cursor);
                self.resume_text.insert(byte_pos, '\n');
                self.resume_cursor += 1;
            }
            ProfileField::Skills | ProfileField::Tone => {} // handled by caller
        }
        self.clear_validation_errors();
    }

    /// Handle bracketed paste event - inserts entire pasted text at once
    pub fn handle_paste(&mut self, text: &str) {
        if self.mode != ProfileMode::Edit || self.saving || self.loading.is_loading() {
            return;
        }
        let text = Self::sanitize_text(text);
        match self.focused_field {
            ProfileField::Resume => {
                let byte_pos = self.char_to_byte_index(&self.resume_text, self.resume_cursor);
                self.resume_text.insert_str(byte_pos, &text);
                self.resume_cursor += text.chars().count();
            }
            ProfileField::Skills => {
                let byte_pos = self.char_to_byte_index(&self.skills_text, self.skills_cursor);
                self.skills_text.insert_str(byte_pos, &text);
                self.skills_cursor += text.chars().count();
            }
            ProfileField::Tone => {} // Tone is selected via arrow keys
        }
        self.clear_validation_errors();
    }

    pub fn handle_scroll_up(&mut self) {
        if self.mode == ProfileMode::Edit && self.focused_field == ProfileField::Resume {
            self.resume_scroll = self.resume_scroll.saturating_sub(1);
        }
    }

    pub fn handle_scroll_down(&mut self) {
        if self.mode == ProfileMode::Edit && self.focused_field == ProfileField::Resume {
            self.resume_scroll = self.resume_scroll.saturating_add(1);
        }
    }

    /// Handle arrow keys for tone selection
    pub fn handle_up(&mut self) {
        if self.mode != ProfileMode::Edit || self.saving || self.loading.is_loading() {
            return;
        }
        if self.focused_field == ProfileField::Tone {
            if self.tone_selection > 0 {
                self.tone_selection -= 1;
            }
        } else {
            self.focus_prev();
        }
    }

    pub fn handle_down(&mut self) {
        if self.mode != ProfileMode::Edit || self.saving || self.loading.is_loading() {
            return;
        }
        if self.focused_field == ProfileField::Tone {
            if self.tone_selection < 2 {
                self.tone_selection += 1;
            }
        } else {
            self.focus_next();
        }
    }

    /// Handle left arrow for cursor navigation within Resume or Skills text
    pub fn handle_left(&mut self) {
        if self.mode != ProfileMode::Edit || self.saving || self.loading.is_loading() {
            return;
        }
        match self.focused_field {
            ProfileField::Resume => {
                self.resume_cursor = self.resume_cursor.saturating_sub(1);
            }
            ProfileField::Skills => {
                self.skills_cursor = self.skills_cursor.saturating_sub(1);
            }
            ProfileField::Tone => {}
        }
    }

    /// Handle right arrow for cursor navigation within Resume or Skills text
    pub fn handle_right(&mut self) {
        if self.mode != ProfileMode::Edit || self.saving || self.loading.is_loading() {
            return;
        }
        match self.focused_field {
            ProfileField::Resume => {
                let max = self.resume_text.chars().count();
                self.resume_cursor = self.resume_cursor.saturating_add(1).min(max);
            }
            ProfileField::Skills => {
                let max = self.skills_text.chars().count();
                self.skills_cursor = self.skills_cursor.saturating_add(1).min(max);
            }
            ProfileField::Tone => {}
        }
    }

    /// Validate current form data
    fn validate(&mut self) -> bool {
        self.clear_validation_errors();

        if self.resume_text.trim().len() < 50 {
            self.validation_errors
                .push("Resume must be at least 50 characters".to_string());
        }

        let skills: Vec<String> = self
            .skills_text
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect();

        if skills.is_empty() {
            self.validation_errors
                .push("At least one skill is required".to_string());
        }

        self.validation_errors.is_empty()
    }

    fn selected_tone(&self) -> CompanyTone {
        match self.tone_selection {
            0 => CompanyTone::Formal,
            1 => CompanyTone::Casual,
            _ => CompanyTone::Startup,
        }
    }

    /// Parse skills from comma-separated text
    fn parse_skills(&self) -> Vec<String> {
        self.skills_text
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect()
    }

    /// Load profile from API
    pub async fn load_profile(&mut self) -> anyhow::Result<()> {
        if self.loading.is_loading() {
            return Ok(());
        }

        self.loading = LoadingState::Loading;

        let client = self.api_client.clone();
        let result = client.lock().await.get_profile().await;

        match result {
            Ok(profile) => {
                self.set_profile(profile);
                self.loading = LoadingState::Success;
                self.show_toast("Profile loaded".to_string());
            }
            Err(e) => {
                let err_msg = e.to_string();
                self.loading = LoadingState::Error(err_msg.clone());
                self.show_toast(format!("Failed to load profile: {}", err_msg));
            }
        }

        Ok(())
    }

    /// Save profile via API
    pub async fn save_profile(&mut self) -> anyhow::Result<()> {
        if self.saving || self.loading.is_loading() {
            return Ok(());
        }

        if !self.validate() {
            return Ok(());
        }

        self.saving = true;
        self.clear_validation_errors();

        let request = ProfileRequest {
            resume_text: self.resume_text.trim().to_string(),
            skills: self.parse_skills(),
            tone: self.selected_tone(),
        };

        let client = self.api_client.clone();
        let config_manager = self.config_manager.clone();
        let result = client.lock().await.update_profile(&request).await;

        self.saving = false;

        match result {
            Ok(profile) => {
                self.set_profile(profile);
                self.mode = ProfileMode::View;
                self.show_toast("Profile saved successfully".to_string());

                // Update config manager token if needed (token doesn't change on profile update)
                let _ = config_manager.lock().await.save();
            }
            Err(e) => {
                let err_msg = e.to_string();
                self.validation_errors.push(format!("Save failed: {}", err_msg));
                self.show_toast(format!("Save failed: {}", err_msg));
            }
        }

        Ok(())
    }

    pub fn cancel_edit(&mut self) {
        if let Some(profile) = &self.profile {
            self.resume_text = Self::sanitize_text(&profile.resume_text);
            self.skills_text = profile.skills.join(", ");
            self.tone_selection = match profile.tone {
                CompanyTone::Formal => 0,
                CompanyTone::Casual => 1,
                CompanyTone::Startup => 2,
            };
        }
        self.resume_scroll = 0;
        self.resume_cursor = 0;
        self.skills_cursor = 0;
        self.mode = ProfileMode::View;
        self.clear_validation_errors();
    }

    /// Get resume character count
    pub fn resume_char_count(&self) -> usize {
        self.resume_text.chars().count()
    }

    /// Check if resume meets minimum length
    pub fn resume_valid(&self) -> bool {
        self.resume_char_count() >= 50
    }

    /// Get skills count
    pub fn skills_count(&self) -> usize {
        self.parse_skills().len()
    }

    /// Draw the profile screen
    pub fn draw(&mut self, frame: &mut Frame, area: Rect) {
        let theme = Theme::detect();
        self.update_toast();

        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Length(3),  // Header with mode indicator
                Constraint::Min(0),     // Content
                Constraint::Length(3),  // Footer with shortcuts
            ])
            .split(area);

        self.draw_header(frame, chunks[0], &theme);
        self.draw_content(frame, chunks[1], &theme);
        self.draw_footer(frame, chunks[2], &theme);
        self.draw_toast(frame, area, &theme);
    }

    fn draw_header(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let title = format!(
            " My Profile [{}] ",
            self.mode.display_name()
        );

        let header = Paragraph::new(Text::styled(title, theme.style_title()))
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(theme.style_border(true)),
            )
            .alignment(Alignment::Center);

        frame.render_widget(header, area);
    }

    fn draw_content(&mut self, frame: &mut Frame, area: Rect, theme: &Theme) {
        if self.loading.is_loading() {
            render_loading(frame, area, theme, "Loading profile...");
            return;
        }

        if let Some(error) = self.loading.error_message() {
            render_error_popup(frame, area, theme, error, "[Enter] Dismiss  [r] Retry");
            return;
        }

        if self.mode == ProfileMode::Edit {
            self.draw_edit_mode(frame, area, theme);
            return;
        }

        let Some(profile) = &self.profile else {
            render_empty_state(frame, area, theme, "No profile loaded.\nPress 'r' to refresh or 'e' to edit.");
            return;
        };

        self.draw_view_mode(frame, area, theme, profile);
    }

    fn draw_view_mode(&self, frame: &mut Frame, area: Rect, theme: &Theme, profile: &ProfileResponse) {
        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Min(8),   // Resume
                Constraint::Length(5), // Skills
                Constraint::Length(5), // Tone
            ])
            .split(area);

        // Resume section
        self.draw_resume_view(frame, chunks[0], theme, profile);

        // Skills section
        self.draw_skills_view(frame, chunks[1], theme, profile);

        // Tone section
        self.draw_tone_view(frame, chunks[2], theme, profile);
    }

fn draw_resume_view(&self, frame: &mut Frame, area: Rect, theme: &Theme, profile: &ProfileResponse) {
        let char_count = profile.resume_text.chars().count();
        let valid = char_count >= 50;
        let count_style = if valid { theme.style_good() } else { theme.style_warn() };
        let count_text = format!(" ({}/50 min)", char_count);

        let content = Paragraph::new(Text::styled(&profile.resume_text, theme.style_normal()))
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(theme.style_border(false))
                    .title(Span::styled(" Resume ", theme.style_title()))
                    .title_bottom(Span::styled(count_text, count_style)),
            )
            .wrap(Wrap { trim: false });

        frame.render_widget(content, area);
    }

    fn draw_skills_view(&self, frame: &mut Frame, area: Rect, theme: &Theme, profile: &ProfileResponse) {
        let skills_text = if profile.skills.is_empty() {
            "(no skills)".to_string()
        } else {
            profile.skills.join(", ")
        };

        let content = Paragraph::new(Text::styled(skills_text, theme.style_normal()))
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(theme.style_border(false))
                    .title(Span::styled(" Skills ", theme.style_title())),
            )
            .wrap(Wrap { trim: false });

        frame.render_widget(content, area);
    }

    fn draw_tone_view(&self, frame: &mut Frame, area: Rect, theme: &Theme, profile: &ProfileResponse) {
        let tone_style = match profile.tone {
            CompanyTone::Formal => theme.style_bad(),
            CompanyTone::Casual => theme.style_good(),
            CompanyTone::Startup => theme.style_warn(),
        };

        let content = Paragraph::new(Text::styled(
            format!(" [{}] ", profile.tone),
            tone_style.add_modifier(Modifier::BOLD),
        ))
        .alignment(Alignment::Center)
        .block(
            Block::default()
                .borders(Borders::ALL)
                .border_style(theme.style_border(false))
                .title(Span::styled(" Tone ", theme.style_title())),
        );

        frame.render_widget(content, area);
    }

    fn draw_edit_mode(&mut self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Min(8),    // Resume editor
                Constraint::Length(5), // Skills editor
                Constraint::Length(7), // Tone selector
                Constraint::Length(3), // Validation errors
            ])
            .split(area);

        // Resume editor
        self.draw_resume_edit(frame, chunks[0], theme);

        // Skills editor
        self.draw_skills_edit(frame, chunks[1], theme);

        // Tone selector
        self.draw_tone_edit(frame, chunks[2], theme);

        // Validation errors
        self.draw_validation_errors(frame, chunks[3], theme);
    }

    fn draw_resume_edit(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let is_focused = self.focused_field == ProfileField::Resume;
        let char_count = self.resume_char_count();
        let valid = char_count >= 50;
        let count_style = if valid { theme.style_good() } else { theme.style_warn() };
        let count_text = format!(" ({}/50 min)", char_count);

        let border_style = if is_focused {
            theme.style_border(true)
        } else {
            theme.style_border(false)
        };

        let title_style = if is_focused {
            theme.style_title()
        } else {
            theme.style_dim()
        };

        let content = Paragraph::new(Text::styled(&self.resume_text, theme.style_normal()))
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(border_style)
                    .title(Span::styled(" Resume ", title_style))
                    .title_bottom(Span::styled(count_text, count_style)),
            )
            .scroll((self.resume_scroll, 0))
            .wrap(Wrap { trim: false });

        frame.render_widget(content, area);

        // Set cursor position for resume field
        if is_focused {
            let inner = area.inner(Margin { vertical: 1, horizontal: 1 });
            let (cursor_col, cursor_row) = self.cursor_visual_position(&self.resume_text, self.resume_cursor, inner.width);
            // Adjust for scroll offset
            let cursor_y = inner.y.saturating_add(cursor_row).saturating_sub(self.resume_scroll);
            let cursor_x = inner.x + cursor_col.min(inner.width.saturating_sub(1));
            frame.set_cursor_position((cursor_x, cursor_y));
        }
    }

    fn draw_skills_edit(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let is_focused = self.focused_field == ProfileField::Skills;
        let skills_count = self.skills_count();
        let count_style = if skills_count > 0 { theme.style_good() } else { theme.style_warn() };
        let count_text = format!(" ({} skills)", skills_count);

        let border_style = if is_focused {
            theme.style_border(true)
        } else {
            theme.style_border(false)
        };

        let title_style = if is_focused {
            theme.style_title()
        } else {
            theme.style_dim()
        };

        let content = Paragraph::new(Text::styled(&self.skills_text, theme.style_normal()))
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(border_style)
                    .title(Span::styled(" Skills (comma-separated) ", title_style))
                    .title_bottom(Span::styled(count_text, count_style)),
            )
            .wrap(Wrap { trim: false });

        frame.render_widget(content, area);

        if is_focused {
            let inner = area.inner(Margin { vertical: 1, horizontal: 1 });
            let (cursor_col, cursor_row) = self.cursor_visual_position(&self.skills_text, self.skills_cursor, inner.width);
            let cursor_y = inner.y + cursor_row;
            let cursor_x = inner.x + cursor_col.min(inner.width.saturating_sub(1));
            frame.set_cursor_position((cursor_x, cursor_y));
        }
    }

    fn draw_tone_edit(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let is_focused = self.focused_field == ProfileField::Tone;
        let tones = [CompanyTone::Formal, CompanyTone::Casual, CompanyTone::Startup];

        let items: Vec<ListItem> = tones
            .iter()
            .enumerate()
            .map(|(i, tone)| {
                let is_selected = i == self.tone_selection;
                let style = if is_selected {
                    if is_focused {
                        theme.style_selected()
                    } else {
                        theme.style_normal()
                    }
                } else {
                    theme.style_dim()
                };

                let prefix = if is_selected { "► " } else { "  " };
                let text = format!("{}{}", prefix, tone);

                ListItem::new(Line::from(vec![
                    Span::styled(text, style),
                    Span::styled(" ", theme.style_dim()),
                ]))
            })
            .collect();

        let border_style = if is_focused {
            theme.style_border(true)
        } else {
            theme.style_border(false)
        };

        let title_style = if is_focused {
            theme.style_title()
        } else {
            theme.style_dim()
        };

        let list = List::new(items)
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(border_style)
                    .title(Span::styled(" Tone (↑/↓ to select) ", title_style)),
            )
            .highlight_style(theme.style_selected());

        frame.render_widget(list, area);
    }

    fn draw_validation_errors(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        if self.validation_errors.is_empty() {
            return;
        }

        let error_text = self.validation_errors.join("\n");
        let para = Paragraph::new(Text::styled(error_text, theme.style_bad()))
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(theme.style_bad())
                    .title(Span::styled(" Validation Errors ", theme.style_bad())),
            )
            .wrap(Wrap { trim: false });

        frame.render_widget(para, area);
    }

    fn draw_footer(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let shortcuts = match self.mode {
            ProfileMode::View => " [e] Edit  [r] Reload  [q/b] Back  [Esc] Quit ",
            ProfileMode::Edit => {
                if self.saving {
                    " [Enter] Saving...  [Esc] Cancel "
                } else {
                    " [Tab/↑↓] Navigate  [Enter] Save  [Esc] Cancel "
                }
            }
        };

        let footer = Paragraph::new(Text::styled(shortcuts, theme.style_dim()))
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(theme.style_border(false)),
            )
            .alignment(Alignment::Center);

        frame.render_widget(footer, area);
    }

    fn draw_toast(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        if let Some(toast) = &self.toast {
            let toast_area = Rect {
                x: area.x + (area.width.saturating_sub(50)) / 2,
                y: area.y + 2,
                width: 50.min(area.width),
                height: 3,
            };

            frame.render_widget(Clear, toast_area);

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
}

/// Draw function for the app
pub fn draw(frame: &mut Frame, area: Rect, app: &mut crate::tui::app::App) {
    if app.profile_screen.is_none() {
        let api_client = app.api_client.clone();
        let config_manager = Arc::new(Mutex::new(
            ConfigManager::load(None).unwrap_or_default(),
        ));
        app.profile_screen = Some(ProfileScreen::new(api_client, config_manager));
    }

    if let Some(screen) = &mut app.profile_screen {
        screen.draw(frame, area);
    }
}

/// Handle keyboard events for the profile screen
pub async fn handle_event(
    event: crossterm::event::Event,
    app: &mut crate::tui::app::App,
) -> anyhow::Result<()> {
    use crossterm::event::{KeyCode, KeyEventKind};

    // Handle bracketed paste events
    if let crossterm::event::Event::Paste(text) = event {
        if let Some(screen) = &mut app.profile_screen {
            screen.handle_paste(&text);
        }
        return Ok(());
    }

    if let crossterm::event::Event::Key(key) = event {
        if key.kind != KeyEventKind::Press {
            return Ok(());
        }

        // Handle global keys first
        // NOTE: 'q', 'b', 'e' shortcuts are guarded by mode check so they don't
        // conflict with typing those characters in text fields during Edit mode.
        let is_editing = app.profile_screen.as_ref().is_some_and(|s| s.mode == ProfileMode::Edit);
        match key.code {
            KeyCode::Char('q') | KeyCode::Char('Q') if !is_editing => {
                app.state = crate::tui::app::AppState::JobList;
                return Ok(());
            }
            KeyCode::Char('b') | KeyCode::Char('B') if !is_editing => {
                if let Some(screen) = &mut app.profile_screen {
                    if screen.mode == ProfileMode::Edit {
                        screen.cancel_edit();
                    } else {
                        app.state = crate::tui::app::AppState::JobList;
                    }
                }
                return Ok(());
            }
            KeyCode::Esc => {
                if is_editing {
                    if let Some(screen) = &mut app.profile_screen {
                        screen.cancel_edit();
                    }
                } else {
                    app.should_quit = true;
                    app.state = crate::tui::app::AppState::Quitting;
                }
                return Ok(());
            }
            _ => {}
        }

        if key.modifiers.contains(crossterm::event::KeyModifiers::CONTROL) {
            match key.code {
                KeyCode::Char('s') | KeyCode::Char('S') => {
                    if let Some(screen) = &mut app.profile_screen {
                        if screen.mode == ProfileMode::Edit && !screen.saving && !screen.loading.is_loading() {
                            let _ = screen.save_profile().await;
                        }
                    }
                    return Ok(());
                }
                _ => {}
            }
        }

        // Delegate to profile screen
        if let Some(screen) = &mut app.profile_screen {
            match key.code {
                KeyCode::Char('e') | KeyCode::Char('E')
                    if screen.mode == ProfileMode::View && !screen.loading.is_loading() && !screen.saving =>
                {
                    screen.toggle_mode();
                }
                KeyCode::Char('r') | KeyCode::Char('R')
                    if screen.mode == ProfileMode::View && !screen.loading.is_loading() && !screen.saving =>
                {
                    let _ = screen.load_profile().await;
                }
                KeyCode::Tab => {
                    if screen.mode == ProfileMode::Edit && !screen.saving && !screen.loading.is_loading() {
                        screen.focus_next();
                    }
                }
                KeyCode::BackTab => {
                    if screen.mode == ProfileMode::Edit && !screen.saving && !screen.loading.is_loading() {
                        screen.focus_prev();
                    }
                }
                KeyCode::Enter => {
                    if screen.mode == ProfileMode::Edit && !screen.saving && !screen.loading.is_loading() {
                        match screen.focused_field {
                            ProfileField::Resume => screen.handle_newline(),
                            ProfileField::Skills | ProfileField::Tone => {
                                let _ = screen.save_profile().await;
                            }
                        }
                    }
                }
                KeyCode::Up => {
                    if screen.mode == ProfileMode::Edit && !screen.saving && !screen.loading.is_loading() {
                        if screen.focused_field == ProfileField::Resume {
                            screen.handle_scroll_up();
                        } else {
                            screen.handle_up();
                        }
                    }
                }
                KeyCode::Down => {
                    if screen.mode == ProfileMode::Edit && !screen.saving && !screen.loading.is_loading() {
                        if screen.focused_field == ProfileField::Resume {
                            screen.handle_scroll_down();
                        } else {
                            screen.handle_down();
                        }
                    }
                }
                KeyCode::Left => {
                    if screen.mode == ProfileMode::Edit && !screen.saving && !screen.loading.is_loading() {
                        if screen.focused_field == ProfileField::Resume || screen.focused_field == ProfileField::Skills {
                            screen.handle_left();
                        }
                    }
                }
                KeyCode::Right => {
                    if screen.mode == ProfileMode::Edit && !screen.saving && !screen.loading.is_loading() {
                        if screen.focused_field == ProfileField::Resume || screen.focused_field == ProfileField::Skills {
                            screen.handle_right();
                        }
                    }
                }
                KeyCode::Backspace => {
                    if screen.mode == ProfileMode::Edit && !screen.saving && !screen.loading.is_loading() {
                        screen.handle_backspace();
                    }
                }
                KeyCode::Char(c)
                    if screen.mode == ProfileMode::Edit && !screen.saving && !screen.loading.is_loading() => {
                        screen.handle_char(c);
                    }
                _ => {}
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::ApiClient;
    use crate::config::ConfigManager;
    use crate::domain::{CompanyTone, ProfileResponse};
    use ratatui::backend::TestBackend;
    use ratatui::Terminal;
    use std::sync::Arc;
    use tokio::sync::Mutex;

    fn create_test_screen() -> ProfileScreen {
        let api_client = Arc::new(Mutex::new(ApiClient::new("http://localhost:8080")));
        let config_manager = Arc::new(Mutex::new(ConfigManager::new()));
        ProfileScreen::new(api_client, config_manager)
    }

    fn sample_profile() -> ProfileResponse {
        ProfileResponse {
            id: Some(1),
            user_id: 1,
            resume_text: "Experienced software developer with 5 years of experience in Rust, Java, and PostgreSQL. Passionate about building CLI tools and backend systems.".to_string(),
            skills: vec!["Rust".to_string(), "Java".to_string(), "PostgreSQL".to_string(), "Docker".to_string()],
            tone: CompanyTone::Formal,
        }
    }

    #[test]
    fn profile_screen_new_creates_default_state() {
        let screen = create_test_screen();

        assert_eq!(screen.mode, ProfileMode::View);
        assert!(screen.profile.is_none());
        assert!(screen.resume_text.is_empty());
        assert!(screen.skills_text.is_empty());
        assert_eq!(screen.tone_selection, 0);
        assert_eq!(screen.focused_field, ProfileField::Resume);
        assert_eq!(screen.loading, LoadingState::Idle);
        assert!(!screen.saving);
        assert!(screen.validation_errors.is_empty());
    }

    #[test]
    fn profile_screen_set_profile_populates_fields() {
        let mut screen = create_test_screen();
        let profile = sample_profile();

        screen.set_profile(profile.clone());

        assert_eq!(screen.profile, Some(profile.clone()));
        assert_eq!(screen.resume_text, profile.resume_text);
        assert_eq!(screen.skills_text, "Rust, Java, PostgreSQL, Docker");
        assert_eq!(screen.tone_selection, 0); // Formal = 0
    }

    #[test]
    fn profile_screen_toggle_mode_switches_between_view_and_edit() {
        let mut screen = create_test_screen();

        assert_eq!(screen.mode, ProfileMode::View);

        screen.toggle_mode();
        assert_eq!(screen.mode, ProfileMode::Edit);
        assert_eq!(screen.focused_field, ProfileField::Resume);

        screen.toggle_mode();
        assert_eq!(screen.mode, ProfileMode::View);
    }

    #[test]
    fn profile_screen_focus_navigation_in_edit_mode() {
        let mut screen = create_test_screen();
        screen.toggle_mode(); // Enter edit mode

        assert_eq!(screen.focused_field, ProfileField::Resume);

        screen.focus_next();
        assert_eq!(screen.focused_field, ProfileField::Skills);

        screen.focus_next();
        assert_eq!(screen.focused_field, ProfileField::Tone);

        screen.focus_next();
        assert_eq!(screen.focused_field, ProfileField::Resume); // Wraps around

        screen.focus_prev();
        assert_eq!(screen.focused_field, ProfileField::Tone);

        screen.focus_prev();
        assert_eq!(screen.focused_field, ProfileField::Skills);
    }

    #[test]
    fn profile_screen_handle_char_input_in_edit_mode() {
        let mut screen = create_test_screen();
        screen.toggle_mode();

        screen.handle_char('H');
        screen.handle_char('i');
        assert_eq!(screen.resume_text, "Hi");

        screen.focus_next(); // Skills
        screen.handle_char('R');
        screen.handle_char('u');
        screen.handle_char('s');
        screen.handle_char('t');
        assert_eq!(screen.skills_text, "Rust");
    }

    #[test]
    fn profile_screen_handle_char_ignored_in_view_mode() {
        let mut screen = create_test_screen();
        // In view mode
        screen.handle_char('H');
        assert!(screen.resume_text.is_empty());
    }

    #[test]
    fn profile_screen_handle_backspace() {
        let mut screen = create_test_screen();
        screen.toggle_mode();

        screen.handle_char('H');
        screen.handle_char('i');
        assert_eq!(screen.resume_text, "Hi");

        screen.handle_backspace();
        assert_eq!(screen.resume_text, "H");

        screen.handle_backspace();
        assert_eq!(screen.resume_text, "");

        // Backspace on empty should not panic
        screen.handle_backspace();
        assert_eq!(screen.resume_text, "");
    }

    #[test]
    fn profile_screen_tone_selection_with_arrows() {
        let mut screen = create_test_screen();
        screen.toggle_mode();

        // Navigate to tone field
        screen.focus_next(); // Skills
        screen.focus_next(); // Tone
        assert_eq!(screen.focused_field, ProfileField::Tone);
        assert_eq!(screen.tone_selection, 0); // Formal

        screen.handle_down();
        assert_eq!(screen.tone_selection, 1); // Casual

        screen.handle_down();
        assert_eq!(screen.tone_selection, 2); // Startup

        screen.handle_down(); // Should not go beyond
        assert_eq!(screen.tone_selection, 2);

        screen.handle_up();
        assert_eq!(screen.tone_selection, 1);

        screen.handle_up();
        assert_eq!(screen.tone_selection, 0);
    }

    #[test]
    fn profile_screen_validate_resume_min_50_chars() {
        let mut screen = create_test_screen();
        screen.toggle_mode();

        // Less than 50 chars
        screen.resume_text = "Short resume".to_string();
        screen.skills_text = "Rust".to_string();
        assert!(!screen.validate());
        assert!(screen.validation_errors.iter().any(|e| e.contains("50 characters")));

        // Exactly 50 chars
        screen.resume_text = "a".repeat(50);
        screen.skills_text = "Rust".to_string();
        assert!(screen.validate());
        assert!(screen.validation_errors.is_empty());

        // More than 50 chars
        screen.resume_text = "a".repeat(100);
        screen.skills_text = "Rust".to_string();
        assert!(screen.validate());
    }

    #[test]
    fn profile_screen_validate_skills_non_empty() {
        let mut screen = create_test_screen();
        screen.toggle_mode();

        // Empty skills
        screen.resume_text = "a".repeat(50);
        screen.skills_text = "".to_string();
        assert!(!screen.validate());
        assert!(screen.validation_errors.iter().any(|e| e.contains("skill")));

        // Whitespace only
        screen.skills_text = "   ,  , ".to_string();
        assert!(!screen.validate());

        // Valid skills
        screen.skills_text = "Rust, Java".to_string();
        assert!(screen.validate());
    }

    #[test]
    fn profile_screen_parse_skills_handles_comma_separated() {
        let mut screen = create_test_screen();
        screen.skills_text = "Rust, Java,  PostgreSQL , Docker".to_string();

        let skills = screen.parse_skills();

        assert_eq!(skills, vec!["Rust", "Java", "PostgreSQL", "Docker"]);
    }

    #[test]
    fn profile_screen_selected_tone_matches_selection() {
        let mut screen = create_test_screen();

        screen.tone_selection = 0;
        assert_eq!(screen.selected_tone(), CompanyTone::Formal);

        screen.tone_selection = 1;
        assert_eq!(screen.selected_tone(), CompanyTone::Casual);

        screen.tone_selection = 2;
        assert_eq!(screen.selected_tone(), CompanyTone::Startup);
    }

    #[test]
    fn profile_screen_resume_char_count() {
        let mut screen = create_test_screen();
        assert_eq!(screen.resume_char_count(), 0);

        screen.resume_text = "Hello".to_string();
        assert_eq!(screen.resume_char_count(), 5);

        screen.resume_text = "Hello, 世界".to_string(); // Unicode chars
        assert_eq!(screen.resume_char_count(), 9); // 5 + 1 + 3
    }

    #[test]
    fn profile_screen_resume_valid_check() {
        let mut screen = create_test_screen();

        screen.resume_text = "a".repeat(49);
        assert!(!screen.resume_valid());

        screen.resume_text = "a".repeat(50);
        assert!(screen.resume_valid());

        screen.resume_text = "a".repeat(100);
        assert!(screen.resume_valid());
    }

    #[test]
    fn profile_screen_skills_count() {
        let mut screen = create_test_screen();

        screen.skills_text = "".to_string();
        assert_eq!(screen.skills_count(), 0);

        screen.skills_text = "Rust".to_string();
        assert_eq!(screen.skills_count(), 1);

        screen.skills_text = "Rust, Java, Go".to_string();
        assert_eq!(screen.skills_count(), 3);

        screen.skills_text = "Rust, , Java,  ".to_string();
        assert_eq!(screen.skills_count(), 2); // Empty entries filtered
    }

    #[test]
    fn profile_screen_cancel_edit_reverts_to_original() {
        let mut screen = create_test_screen();
        let profile = sample_profile();
        screen.set_profile(profile.clone());
        screen.toggle_mode();

        // Modify fields
        screen.resume_text = "Modified resume text that is long enough to be valid".to_string();
        screen.skills_text = "Go, Python".to_string();
        screen.tone_selection = 2; // Startup

        // Cancel edit
        screen.cancel_edit();

        // Should revert to original
        assert_eq!(screen.mode, ProfileMode::View);
        assert_eq!(screen.resume_text, profile.resume_text);
        assert_eq!(screen.skills_text, "Rust, Java, PostgreSQL, Docker");
        assert_eq!(screen.tone_selection, 0); // Formal
    }

    #[test]
    fn profile_mode_display_names() {
        assert_eq!(ProfileMode::View.display_name(), "VIEW");
        assert_eq!(ProfileMode::Edit.display_name(), "EDIT");
    }

    #[test]
    fn profile_mode_toggle() {
        assert_eq!(ProfileMode::View.toggle(), ProfileMode::Edit);
        assert_eq!(ProfileMode::Edit.toggle(), ProfileMode::View);
    }

    #[test]
    fn profile_field_display_names() {
        assert_eq!(ProfileField::Resume.display_name(), "Resume");
        assert_eq!(ProfileField::Skills.display_name(), "Skills");
        assert_eq!(ProfileField::Tone.display_name(), "Tone");
    }

    #[test]
    fn profile_field_navigation() {
        let fields = ProfileField::all();
        assert_eq!(fields.len(), 3);

        assert_eq!(ProfileField::Resume.next(), ProfileField::Skills);
        assert_eq!(ProfileField::Skills.next(), ProfileField::Tone);
        assert_eq!(ProfileField::Tone.next(), ProfileField::Resume);

        assert_eq!(ProfileField::Resume.prev(), ProfileField::Tone);
        assert_eq!(ProfileField::Tone.prev(), ProfileField::Skills);
        assert_eq!(ProfileField::Skills.prev(), ProfileField::Resume);
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
    fn profile_screen_render_does_not_panic_empty() {
        let mut screen = create_test_screen();
        let mut terminal = Terminal::new(TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn profile_screen_render_does_not_panic_with_profile_view() {
        let mut screen = create_test_screen();
        screen.set_profile(sample_profile());
        let mut terminal = Terminal::new(TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn profile_screen_render_does_not_panic_edit_mode() {
        let mut screen = create_test_screen();
        screen.set_profile(sample_profile());
        screen.toggle_mode();
        let mut terminal = Terminal::new(TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn profile_screen_render_does_not_panic_loading() {
        let mut screen = create_test_screen();
        screen.loading = LoadingState::Loading;
        let mut terminal = Terminal::new(TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn profile_screen_render_does_not_panic_error() {
        let mut screen = create_test_screen();
        screen.loading = LoadingState::Error("Network error".to_string());
        let mut terminal = Terminal::new(TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn profile_screen_render_does_not_panic_with_validation_errors() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.resume_text = "short".to_string();
        screen.skills_text = "".to_string();
        screen.validate(); // Populate errors
        let mut terminal = Terminal::new(TestBackend::new(80, 24)).unwrap();

        terminal.draw(|frame| {
            screen.draw(frame, frame.area());
        }).unwrap();
    }

    #[test]
    fn profile_screen_toast_notification() {
        let mut screen = create_test_screen();
        assert!(screen.toast.is_none());

        screen.show_toast("Test message".to_string());
        assert!(screen.toast.is_some());
        assert_eq!(screen.toast.as_ref().unwrap().message, "Test message");
    }

    #[test]
    fn handle_paste_resume_appends_text() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.handle_paste("JUAN ANTONIO PERUZZO\n(42) 99833-1363 • juanperuzzo.dev");
        assert_eq!(screen.resume_text, "JUAN ANTONIO PERUZZO\n(42) 99833-1363 • juanperuzzo.dev");
    }

    #[test]
    fn handle_paste_skills_appends_text() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Skills;
        screen.handle_paste("Java, TypeScript, Spring Boot");
        assert_eq!(screen.skills_text, "Java, TypeScript, Spring Boot");
    }

    #[test]
    fn handle_paste_tone_ignored() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Tone;
        screen.handle_paste("any text");
        assert_eq!(screen.resume_text, "");
        assert_eq!(screen.skills_text, "");
    }

    #[test]
    fn handle_paste_in_view_mode_ignored() {
        let mut screen = create_test_screen();
        screen.focused_field = ProfileField::Resume;
        screen.handle_paste("some text");
        assert_eq!(screen.resume_text, "");
    }

    #[test]
    fn handle_paste_multi_line() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.handle_paste("Line 1\nLine 2\nLine 3");
        assert_eq!(screen.resume_text, "Line 1\nLine 2\nLine 3");
        assert_eq!(screen.resume_text.lines().count(), 3);
    }

    #[test]
    fn handle_paste_with_keyboard_shortcut_chars() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.handle_paste("Desenvolvedor Busco sólida Banco de Dados Backend Frontend bem");
        assert_eq!(screen.resume_text, "Desenvolvedor Busco sólida Banco de Dados Backend Frontend bem");
    }

    #[test]
    fn handle_paste_preserves_unicode() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.handle_paste("• — → ✅");
        assert_eq!(screen.resume_text, "• — → ✅");
    }

    #[test]
    fn handle_paste_appends_to_existing_text() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.resume_text = "Previous text. ".to_string();
        // Set cursor to end to test append behavior (regression test)
        screen.resume_cursor = screen.resume_text.chars().count();
        screen.handle_paste("Pasted text.");
        assert_eq!(screen.resume_text, "Previous text. Pasted text.");
    }

    #[test]
    fn resume_scroll_starts_at_zero() {
        let screen = create_test_screen();
        assert_eq!(screen.resume_scroll, 0);
    }

    #[test]
    fn handle_scroll_down_increments_scroll() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.handle_scroll_down();
        assert_eq!(screen.resume_scroll, 1);
        screen.handle_scroll_down();
        assert_eq!(screen.resume_scroll, 2);
    }

    #[test]
    fn handle_scroll_up_decrements_scroll() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.resume_scroll = 5;
        screen.handle_scroll_up();
        assert_eq!(screen.resume_scroll, 4);
    }

    #[test]
    fn handle_scroll_up_does_not_go_below_zero() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.handle_scroll_up();
        assert_eq!(screen.resume_scroll, 0);
    }

    #[test]
    fn handle_scroll_ignored_when_not_focused_on_resume() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Skills;
        screen.handle_scroll_down();
        assert_eq!(screen.resume_scroll, 0);
    }

    #[test]
    fn cancel_edit_resets_scroll() {
        let mut screen = create_test_screen();
        let profile = sample_profile();
        screen.set_profile(profile);
        screen.toggle_mode();
        screen.resume_scroll = 10;
        screen.cancel_edit();
        assert_eq!(screen.resume_scroll, 0);
    }

    #[test]
    fn handle_left_at_zero_should_not_move() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.resume_text = "Hello".to_string();
        screen.resume_cursor = 0;
        screen.handle_left();
        assert_eq!(screen.resume_cursor, 0);

        // Skills field
        screen.focused_field = ProfileField::Skills;
        screen.skills_text = "Rust, Java".to_string();
        screen.skills_cursor = 0;
        screen.handle_left();
        assert_eq!(screen.skills_cursor, 0);
    }

    #[test]
    fn handle_left_moves_back_one() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.resume_text = "Hello".to_string();
        screen.resume_cursor = 3;
        screen.handle_left();
        assert_eq!(screen.resume_cursor, 2);
    }

    #[test]
    fn handle_right_at_end_should_not_move() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.resume_text = "Hi".to_string();
        screen.resume_cursor = 2;
        screen.handle_right();
        assert_eq!(screen.resume_cursor, 2);
    }

    #[test]
    fn handle_right_moves_forward_one() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.resume_text = "Hi".to_string();
        screen.resume_cursor = 0;
        screen.handle_right();
        assert_eq!(screen.resume_cursor, 1);
    }

    #[test]
    fn handle_left_skills_field() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Skills;
        screen.skills_text = "Rust, Java".to_string();
        screen.skills_cursor = 5;
        screen.handle_left();
        assert_eq!(screen.skills_cursor, 4);
    }

    #[test]
    fn handle_right_skills_field() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Skills;
        screen.skills_text = "Rust, Java".to_string();
        screen.skills_cursor = 0;
        screen.handle_right();
        assert_eq!(screen.skills_cursor, 1);
    }

    #[test]
    fn handle_left_right_with_unicode_characters() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.resume_text = "Hello 世界".to_string(); // 8 chars: H e l l o space 世 界
        screen.resume_cursor = 8;
        screen.handle_left();
        assert_eq!(screen.resume_cursor, 7);
        screen.handle_right();
        assert_eq!(screen.resume_cursor, 8);
        // At end, right should not move
        screen.handle_right();
        assert_eq!(screen.resume_cursor, 8);
    }

    #[test]
    fn handle_char_at_cursor_inserts_in_middle() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.resume_text = "Hello World".to_string();
        screen.resume_cursor = 5; // After "Hello"
        screen.handle_char('X');
        assert_eq!(screen.resume_text, "HelloX World");
        assert_eq!(screen.resume_cursor, 6);
    }

    #[test]
    fn handle_char_at_end_appends() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.resume_text = "Hello".to_string();
        screen.resume_cursor = 5; // At end
        screen.handle_char('X');
        assert_eq!(screen.resume_text, "HelloX");
        assert_eq!(screen.resume_cursor, 6);
    }

    #[test]
    fn handle_char_advances_cursor() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.resume_text = "Hi".to_string();
        screen.resume_cursor = 1;
        screen.handle_char('X');
        assert_eq!(screen.resume_cursor, 2);
    }

    #[test]
    fn handle_backspace_at_middle_removes_before_cursor() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.resume_text = "Hello World".to_string();
        screen.resume_cursor = 6; // After "Hello "
        screen.handle_backspace();
        assert_eq!(screen.resume_text, "HelloWorld");
        assert_eq!(screen.resume_cursor, 5);
    }

    #[test]
    fn handle_backspace_at_zero_does_nothing() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.resume_text = "Hello".to_string();
        screen.resume_cursor = 0;
        screen.handle_backspace();
        assert_eq!(screen.resume_text, "Hello");
        assert_eq!(screen.resume_cursor, 0);
    }

    #[test]
    fn handle_backspace_moves_cursor_back() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.resume_text = "Hello".to_string();
        screen.resume_cursor = 3;
        screen.handle_backspace();
        assert_eq!(screen.resume_cursor, 2);
    }

    #[test]
    fn handle_newline_at_cursor_splits_text() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.resume_text = "Line1Line2".to_string();
        screen.resume_cursor = 5; // After "Line1"
        screen.handle_newline();
        assert_eq!(screen.resume_text, "Line1\nLine2");
        assert_eq!(screen.resume_cursor, 6);
    }

    #[test]
    fn handle_paste_at_cursor_inserts_and_advances() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.resume_text = "Hello World".to_string();
        screen.resume_cursor = 5; // After "Hello"
        screen.handle_paste("X");
        assert_eq!(screen.resume_text, "HelloX World");
        assert_eq!(screen.resume_cursor, 6);
    }

    #[test]
    fn handle_paste_at_end_appends() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Resume;
        screen.resume_text = "Hello".to_string();
        screen.resume_cursor = 5; // At end
        screen.handle_paste(" World");
        assert_eq!(screen.resume_text, "Hello World");
        assert_eq!(screen.resume_cursor, 11);
    }

    #[test]
    fn handle_char_skills_at_cursor_inserts_in_middle() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Skills;
        screen.skills_text = "Rust, Java".to_string();
        screen.skills_cursor = 4; // After "Rust"
        screen.handle_char('X');
        assert_eq!(screen.skills_text, "RustX, Java");
        assert_eq!(screen.skills_cursor, 5);
    }

    #[test]
    fn handle_backspace_skills_at_middle_removes_before_cursor() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Skills;
        screen.skills_text = "Rust, Java".to_string();
        screen.skills_cursor = 5; // After "Rust,"
        screen.handle_backspace();
        assert_eq!(screen.skills_text, "Rust Java");
        assert_eq!(screen.skills_cursor, 4);
    }

    #[test]
    fn handle_paste_skills_at_cursor_inserts_and_advances() {
        let mut screen = create_test_screen();
        screen.toggle_mode();
        screen.focused_field = ProfileField::Skills;
        screen.skills_text = "Rust, Java".to_string();
        screen.skills_cursor = 4; // After "Rust"
        screen.handle_paste("Go");
        assert_eq!(screen.skills_text, "RustGo, Java");
        assert_eq!(screen.skills_cursor, 6);
    }
}