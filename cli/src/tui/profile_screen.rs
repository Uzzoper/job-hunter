use crate::api::ApiClient;
use crate::config::ConfigManager;
use crate::domain::{CompanyTone, ProfileRequest, ProfileResponse, ProjectRequest, ProjectResponse};
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

/// Max lengths for contact fields, mirroring backend validation.
const PHONE_MAX_LEN: usize = 30;
const CONTACT_EMAIL_MAX_LEN: usize = 255;
const URL_MAX_LEN: usize = 500;

/// Width of the label column inside the contact block (longest label is
/// "Portfolio URL" / "Contact Email" = 13 chars + padding).
const CONTACT_LABEL_WIDTH: u16 = 15;
/// Width of the focus marker ("► ") before each contact label.
const CONTACT_MARKER_WIDTH: u16 = 2;

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
    Phone,
    ContactEmail,
    PortfolioUrl,
    GithubUrl,
    LinkedinUrl,
    Tone,
    Projects,
}

impl ProfileField {
    pub fn display_name(&self) -> &'static str {
        match self {
            ProfileField::Resume => "Resume",
            ProfileField::Skills => "Skills",
            ProfileField::Phone => "Phone",
            ProfileField::ContactEmail => "Contact Email",
            ProfileField::PortfolioUrl => "Portfolio URL",
            ProfileField::GithubUrl => "GitHub URL",
            ProfileField::LinkedinUrl => "LinkedIn URL",
            ProfileField::Tone => "Tone",
            ProfileField::Projects => "Projects",
        }
    }

    pub fn all() -> Vec<Self> {
        vec![
            ProfileField::Resume,
            ProfileField::Skills,
            ProfileField::Phone,
            ProfileField::ContactEmail,
            ProfileField::PortfolioUrl,
            ProfileField::GithubUrl,
            ProfileField::LinkedinUrl,
            ProfileField::Tone,
            ProfileField::Projects,
        ]
    }

    /// Whether this field holds free text with an inline cursor
    /// (supports Left/Right arrow navigation).
    pub fn is_text_input(&self) -> bool {
        matches!(
            self,
            ProfileField::Resume
                | ProfileField::Skills
                | ProfileField::Phone
                | ProfileField::ContactEmail
                | ProfileField::PortfolioUrl
                | ProfileField::GithubUrl
                | ProfileField::LinkedinUrl
        )
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

/// Single-line text input state (value + char cursor) for a contact field.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct ContactInput {
    pub text: String,
    pub cursor: usize,
}

impl ContactInput {
    /// Build an input from an optional stored value ("not set" = empty).
    fn from_value(value: Option<&str>) -> Self {
        Self {
            text: value.unwrap_or_default().to_string(),
            cursor: 0,
        }
    }

    fn insert_char(&mut self, c: char) {
        let byte_pos = Self::byte_index(&self.text, self.cursor);
        self.text.insert(byte_pos, c);
        self.cursor += 1;
    }

    fn backspace(&mut self) {
        if self.cursor == 0 {
            return;
        }
        let byte_pos = Self::byte_index(&self.text, self.cursor - 1);
        self.text.remove(byte_pos);
        self.cursor -= 1;
    }

    fn paste(&mut self, text: &str) {
        let byte_pos = Self::byte_index(&self.text, self.cursor);
        self.text.insert_str(byte_pos, text);
        self.cursor += text.chars().count();
    }

    fn move_left(&mut self) {
        self.cursor = self.cursor.saturating_sub(1);
    }

    fn move_right(&mut self) {
        let max = self.text.chars().count();
        self.cursor = self.cursor.saturating_add(1).min(max);
    }

    /// Convert character index to byte index for String operations.
    fn byte_index(text: &str, char_index: usize) -> usize {
        text.char_indices()
            .nth(char_index)
            .map(|(i, _)| i)
            .unwrap_or(text.len())
    }
}

/// Fields inside the project edit popup
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProjectPopupField {
    Name,
    Description,
    TechStack,
}

impl ProjectPopupField {
    pub fn all() -> Vec<Self> {
        vec![ProjectPopupField::Name, ProjectPopupField::Description, ProjectPopupField::TechStack]
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

/// State for the project edit popup
#[derive(Debug, Clone)]
pub struct ProjectPopupState {
    pub index: Option<usize>,
    pub name: String,
    pub description: String,
    pub tech_stack: String,
    pub name_cursor: usize,
    pub description_cursor: usize,
    pub tech_stack_cursor: usize,
    pub focused_field: ProjectPopupField,
}

/// State for the upload path input popup
#[derive(Debug, Clone)]
pub struct UploadPathState {
    pub path: String,
    pub cursor: usize,
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
    pub phone: ContactInput,
    pub contact_email: ContactInput,
    pub portfolio_url: ContactInput,
    pub github_url: ContactInput,
    pub linkedin_url: ContactInput,
    pub tone_selection: usize,
    pub projects: Vec<ProjectResponse>,
    pub project_popup: Option<ProjectPopupState>,
    pub upload_path_popup: Option<UploadPathState>,
    pub focused_field: ProfileField,
    pub loading: LoadingState,
    pub saving: bool,
    pub uploading: bool,
    pub pending_upload: Option<String>,
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
            phone: ContactInput::default(),
            contact_email: ContactInput::default(),
            portfolio_url: ContactInput::default(),
            github_url: ContactInput::default(),
            linkedin_url: ContactInput::default(),
            tone_selection: 0,
            projects: Vec::new(),
            project_popup: None,
            upload_path_popup: None,
            focused_field: ProfileField::Resume,
            loading: LoadingState::Idle,
            saving: false,
            uploading: false,
            pending_upload: None,
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
        self.set_contact_inputs(&profile);
        self.tone_selection = match profile.tone {
            CompanyTone::Formal => 0,
            CompanyTone::Casual => 1,
            CompanyTone::Startup => 2,
        };
        self.projects = profile.projects.clone();
        self.profile = Some(profile);
        self.clear_validation_errors();
        self.resume_cursor = 0;
        self.skills_cursor = 0;
    }

    /// Populate the five contact inputs from a profile response.
    fn set_contact_inputs(&mut self, profile: &ProfileResponse) {
        self.phone = ContactInput::from_value(profile.phone.as_deref());
        self.contact_email = ContactInput::from_value(profile.contact_email.as_deref());
        self.portfolio_url = ContactInput::from_value(profile.portfolio_url.as_deref());
        self.github_url = ContactInput::from_value(profile.github_url.as_deref());
        self.linkedin_url = ContactInput::from_value(profile.linkedin_url.as_deref());
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

    fn show_error_toast(&mut self, message: String) {
        self.toast = Some(Toast::error(message));
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
            ProfileField::Phone
            | ProfileField::ContactEmail
            | ProfileField::PortfolioUrl
            | ProfileField::GithubUrl
            | ProfileField::LinkedinUrl => {
                if let Some(input) = self.focused_contact_mut() {
                    input.insert_char(c);
                }
            }
            ProfileField::Tone => {} // Tone is selected via arrow keys
            ProfileField::Projects => {}
        }
        self.clear_validation_errors();
    }

    /// Mutable reference to the currently focused contact input, if any.
    fn focused_contact_mut(&mut self) -> Option<&mut ContactInput> {
        match self.focused_field {
            ProfileField::Phone => Some(&mut self.phone),
            ProfileField::ContactEmail => Some(&mut self.contact_email),
            ProfileField::PortfolioUrl => Some(&mut self.portfolio_url),
            ProfileField::GithubUrl => Some(&mut self.github_url),
            ProfileField::LinkedinUrl => Some(&mut self.linkedin_url),
            _ => None,
        }
    }

    /// Index (row) of the focused field inside the contact block, if any.
    fn focused_contact_index(&self) -> Option<usize> {
        match self.focused_field {
            ProfileField::Phone => Some(0),
            ProfileField::ContactEmail => Some(1),
            ProfileField::PortfolioUrl => Some(2),
            ProfileField::GithubUrl => Some(3),
            ProfileField::LinkedinUrl => Some(4),
            _ => None,
        }
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
                    line_len.div_ceil(width)
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
                line_len.div_ceil(width)
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
            ProfileField::Phone
            | ProfileField::ContactEmail
            | ProfileField::PortfolioUrl
            | ProfileField::GithubUrl
            | ProfileField::LinkedinUrl => {
                if let Some(input) = self.focused_contact_mut() {
                    input.backspace();
                }
            }
            ProfileField::Tone => {}
            ProfileField::Projects => {}
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
            _ => {} // handled by caller (save or project edit)
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
            ProfileField::Phone
            | ProfileField::ContactEmail
            | ProfileField::PortfolioUrl
            | ProfileField::GithubUrl
            | ProfileField::LinkedinUrl => {
                if let Some(input) = self.focused_contact_mut() {
                    input.paste(&text);
                }
            }
            ProfileField::Tone => {} // Tone is selected via arrow keys
            ProfileField::Projects => {}
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

    /// Handle left arrow for cursor navigation within text fields
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
            ProfileField::Phone
            | ProfileField::ContactEmail
            | ProfileField::PortfolioUrl
            | ProfileField::GithubUrl
            | ProfileField::LinkedinUrl => {
                if let Some(input) = self.focused_contact_mut() {
                    input.move_left();
                }
            }
            ProfileField::Tone => {}
            ProfileField::Projects => {}
        }
    }

    /// Handle right arrow for cursor navigation within text fields
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
            ProfileField::Phone
            | ProfileField::ContactEmail
            | ProfileField::PortfolioUrl
            | ProfileField::GithubUrl
            | ProfileField::LinkedinUrl => {
                if let Some(input) = self.focused_contact_mut() {
                    input.move_right();
                }
            }
            ProfileField::Tone => {}
            ProfileField::Projects => {}
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

        // Contact fields — mirror backend validation limits so users get
        // immediate feedback instead of a failed request.
        if self.phone.text.chars().count() > PHONE_MAX_LEN {
            self.validation_errors
                .push(format!("phone must be at most {PHONE_MAX_LEN} characters"));
        }

        let email = self.contact_email.text.trim();
        if !email.is_empty() && !Self::is_valid_email(email) {
            self.validation_errors
                .push("contactEmail must be a valid email address".to_string());
        }
        if email.chars().count() > CONTACT_EMAIL_MAX_LEN {
            self.validation_errors
                .push(format!("contactEmail must be at most {CONTACT_EMAIL_MAX_LEN} characters"));
        }

        for url in [&self.portfolio_url.text, &self.github_url.text, &self.linkedin_url.text] {
            if url.chars().count() > URL_MAX_LEN {
                self.validation_errors
                    .push(format!("URL must be at most {URL_MAX_LEN} characters"));
            }
        }

        self.validation_errors.is_empty()
    }

    /// Basic email plausibility check: local@domain with no whitespace.
    /// Full RFC validation is left to the backend.
    fn is_valid_email(s: &str) -> bool {
        match s.split_once('@') {
            Some((local, domain)) => {
                !local.is_empty() && !domain.is_empty() && !s.chars().any(char::is_whitespace)
            }
            None => false,
        }
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

    /// Trim a single-line input; whitespace-only becomes None ("not set").
    fn optional_trimmed(s: &str) -> Option<String> {
        let trimmed = s.trim();
        (!trimmed.is_empty()).then(|| trimmed.to_string())
    }

    /// Build the API request from the current form state.
    fn build_request(&self) -> ProfileRequest {
        ProfileRequest {
            resume_text: self.resume_text.trim().to_string(),
            skills: self.parse_skills(),
            tone: self.selected_tone(),
            projects: self.projects.iter().map(|p| ProjectRequest {
                name: p.name.clone(),
                description: p.description.clone(),
                tech_stack: p.tech_stack.clone(),
            }).collect(),
            phone: Self::optional_trimmed(&self.phone.text),
            contact_email: Self::optional_trimmed(&self.contact_email.text),
            portfolio_url: Self::optional_trimmed(&self.portfolio_url.text),
            github_url: Self::optional_trimmed(&self.github_url.text),
            linkedin_url: Self::optional_trimmed(&self.linkedin_url.text),
        }
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

        let request = self.build_request();

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

    /// Show file path input popup
    pub fn show_upload_popup(&mut self) {
        self.upload_path_popup = Some(UploadPathState {
            path: String::new(),
            cursor: 0,
        });
    }

    /// Start upload — validates path and sets pending flag (synchronous)
    pub fn start_upload(&mut self) {
        let path = match self.upload_path_popup.take() {
            Some(p) => p.path.trim().to_string(),
            None => return,
        };

        if path.is_empty() {
            self.show_toast("Upload cancelled".to_string());
            return;
        }

        if !path.to_lowercase().ends_with(".pdf") {
            self.show_toast("File must be a PDF".to_string());
            return;
        }

        if !std::path::Path::new(&path).exists() {
            self.show_toast(format!("File not found: {}", path));
            return;
        }

        self.uploading = true;
        self.pending_upload = Some(path);
    }

    /// Finish upload — does the API call (async)
    pub async fn finish_upload(&mut self, path: &str) {
        let client = self.api_client.clone();
        let result = client.lock().await.upload_resume(path).await;

        self.uploading = false;
        self.pending_upload = None;

        match &result {
            Ok(profile) => {
                self.set_profile(profile.clone());
                self.show_toast(format!(
                    "Resume uploaded! Skills: {} | Projects: {}",
                    self.skills_count(),
                    self.projects.len()
                ));
            }
            Err(e) => {
                self.show_error_toast(format!("Upload failed: {}", e));
            }
        }
    }

    pub fn cancel_edit(&mut self) {
        // Clone so the mutable updates below don't alias self.profile
        if let Some(profile) = self.profile.clone() {
            self.resume_text = Self::sanitize_text(&profile.resume_text);
            self.skills_text = profile.skills.join(", ");
            self.set_contact_inputs(&profile);
            self.tone_selection = match profile.tone {
                CompanyTone::Formal => 0,
                CompanyTone::Casual => 1,
                CompanyTone::Startup => 2,
            };
            self.projects = profile.projects;
        }
        self.project_popup = None;
        self.upload_path_popup = None;
        self.pending_upload = None;
        self.resume_scroll = 0;
        self.resume_cursor = 0;
        self.skills_cursor = 0;
        self.mode = ProfileMode::View;
        self.clear_validation_errors();
    }

    /// Add a new project — opens popup with empty fields
    pub fn add_project(&mut self) {
        self.project_popup = Some(ProjectPopupState {
            index: None,
            name: String::new(),
            description: String::new(),
            tech_stack: String::new(),
            name_cursor: 0,
            description_cursor: 0,
            tech_stack_cursor: 0,
            focused_field: ProjectPopupField::Name,
        });
    }

    /// Edit project at index — opens popup with existing data
    pub fn edit_project(&mut self, index: usize) {
        if let Some(project) = self.projects.get(index) {
            self.project_popup = Some(ProjectPopupState {
                index: Some(index),
                name: project.name.clone(),
                description: project.description.clone(),
                tech_stack: project.tech_stack.join(", "),
                name_cursor: project.name.chars().count(),
                description_cursor: project.description.chars().count(),
                tech_stack_cursor: project.tech_stack.join(", ").chars().count(),
                focused_field: ProjectPopupField::Name,
            });
        }
    }

    /// Delete project at index
    pub fn delete_project(&mut self, index: usize) {
        if index < self.projects.len() {
            self.projects.remove(index);
        }
    }

    /// Confirm the project popup — saves to projects list
    pub fn confirm_project_popup(&mut self) {
        let popup = match &self.project_popup {
            Some(p) => p.clone(),
            None => return,
        };
        let tech_stack: Vec<String> = popup.tech_stack
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect();
        let project = ProjectResponse {
            name: popup.name.clone(),
            description: popup.description.clone(),
            tech_stack,
        };
        match popup.index {
            Some(i) if i < self.projects.len() => self.projects[i] = project,
            _ => self.projects.push(project),
        }
        self.project_popup = None;
        self.clear_validation_errors();
    }

    /// Cancel the project popup — discards changes
    pub fn cancel_project_popup(&mut self) {
        self.project_popup = None;
    }

    pub fn popup_handle_char(&mut self, c: char) {
        let popup = match self.project_popup.as_mut() {
            Some(p) => p,
            None => return,
        };
        let (text, cursor) = match popup.focused_field {
            ProjectPopupField::Name => (&mut popup.name, &mut popup.name_cursor),
            ProjectPopupField::Description => (&mut popup.description, &mut popup.description_cursor),
            ProjectPopupField::TechStack => (&mut popup.tech_stack, &mut popup.tech_stack_cursor),
        };
        let byte_pos = {
            let text_ref: &str = text;
            text_ref.char_indices()
                .nth(*cursor)
                .map(|(i, _)| i)
                .unwrap_or(text_ref.len())
        };
        text.insert(byte_pos, c);
        *cursor += 1;
    }

    pub fn popup_handle_backspace(&mut self) {
        let popup = match self.project_popup.as_mut() {
            Some(p) => p,
            None => return,
        };
        let (text, cursor) = match popup.focused_field {
            ProjectPopupField::Name => (&mut popup.name, &mut popup.name_cursor),
            ProjectPopupField::Description => (&mut popup.description, &mut popup.description_cursor),
            ProjectPopupField::TechStack => (&mut popup.tech_stack, &mut popup.tech_stack_cursor),
        };
        if *cursor == 0 {
            return;
        }
        let byte_pos = {
            let text_ref: &str = text;
            text_ref.char_indices()
                .nth(*cursor - 1)
                .map(|(i, _)| i)
                .unwrap_or(text_ref.len())
        };
        text.remove(byte_pos);
        *cursor -= 1;
    }

    pub fn popup_handle_left(&mut self) {
        let popup = match self.project_popup.as_mut() {
            Some(p) => p,
            None => return,
        };
        let cursor = match popup.focused_field {
            ProjectPopupField::Name => &mut popup.name_cursor,
            ProjectPopupField::Description => &mut popup.description_cursor,
            ProjectPopupField::TechStack => &mut popup.tech_stack_cursor,
        };
        *cursor = cursor.saturating_sub(1);
    }

    pub fn popup_handle_right(&mut self) {
        let popup = match self.project_popup.as_mut() {
            Some(p) => p,
            None => return,
        };
        let (text, cursor): (&mut String, &mut usize) = match popup.focused_field {
            ProjectPopupField::Name => (&mut popup.name, &mut popup.name_cursor),
            ProjectPopupField::Description => (&mut popup.description, &mut popup.description_cursor),
            ProjectPopupField::TechStack => (&mut popup.tech_stack, &mut popup.tech_stack_cursor),
        };
        let max = text.chars().count();
        *cursor = cursor.saturating_add(1).min(max);
    }

    pub fn popup_focus_next(&mut self) {
        if let Some(popup) = self.project_popup.as_mut() {
            popup.focused_field = popup.focused_field.next();
        }
    }

    pub fn popup_focus_prev(&mut self) {
        if let Some(popup) = self.project_popup.as_mut() {
            popup.focused_field = popup.focused_field.prev();
        }
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

        if self.uploading {
            render_loading(frame, area, theme, "Uploading resume...");
            return;
        }

        if let Some(error) = self.loading.error_message() {
            render_error_popup(frame, area, theme, error, "[Enter] Dismiss  [r] Retry");
            return;
        }

        if self.upload_path_popup.is_some() {
            self.draw_upload_path_popup(frame, area, theme);
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
                Constraint::Min(6),    // Resume
                Constraint::Length(4), // Skills
                Constraint::Length(7), // Contact
                Constraint::Length(4), // Tone
                Constraint::Min(3),    // Projects
            ])
            .split(area);

        self.draw_resume_view(frame, chunks[0], theme, profile);
        self.draw_skills_view(frame, chunks[1], theme, profile);
        self.draw_contacts_view(frame, chunks[2], theme);
        self.draw_tone_view(frame, chunks[3], theme, profile);
        self.draw_projects_view(frame, chunks[4], theme);
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

    fn draw_projects_view(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let items: Vec<ListItem> = if self.projects.is_empty() {
            vec![ListItem::new(Line::from(vec![
                Span::styled("  (no projects)", theme.style_dim()),
            ]))]
        } else {
            self.projects.iter().map(|p| {
                let tech = p.tech_stack.join(", ");
                ListItem::new(Line::from(vec![
                    Span::styled("  ", theme.style_dim()),
                    Span::styled(&p.name, theme.style_normal()),
                    Span::styled(" — ", theme.style_dim()),
                    Span::styled(&p.description, theme.style_good()),
                    Span::styled(" [", theme.style_dim()),
                    Span::styled(tech, theme.style_warn()),
                    Span::styled("]", theme.style_dim()),
                ]))
            }).collect()
        };

        let list = List::new(items).block(
            Block::default()
                .borders(Borders::ALL)
                .border_style(theme.style_border(false))
                .title(Span::styled(" Projects ", theme.style_title())),
        );

        frame.render_widget(list, area);
    }

    fn draw_edit_mode(&mut self, frame: &mut Frame, area: Rect, theme: &Theme) {
        // If project popup is active, draw the popup over the main content
        if self.project_popup.is_some() {
            self.draw_project_popup(frame, area, theme);
            return;
        }

        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Min(4),    // Resume editor
                Constraint::Length(4), // Skills editor
                Constraint::Length(7), // Contact editor
                Constraint::Length(5), // Tone selector
                Constraint::Min(2),    // Projects editor
                Constraint::Length(3), // Validation errors
            ])
            .split(area);

        // Resume editor
        self.draw_resume_edit(frame, chunks[0], theme);

        // Skills editor
        self.draw_skills_edit(frame, chunks[1], theme);

        // Contact editor
        self.draw_contacts_edit(frame, chunks[2], theme);

        // Tone selector
        self.draw_tone_edit(frame, chunks[3], theme);

        // Projects editor
        self.draw_projects_edit(frame, chunks[4], theme);

        // Validation errors
        self.draw_validation_errors(frame, chunks[5], theme);
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

    /// Immutable reference to the currently focused contact input, if any.
    fn focused_contact(&self) -> Option<&ContactInput> {
        match self.focused_field {
            ProfileField::Phone => Some(&self.phone),
            ProfileField::ContactEmail => Some(&self.contact_email),
            ProfileField::PortfolioUrl => Some(&self.portfolio_url),
            ProfileField::GithubUrl => Some(&self.github_url),
            ProfileField::LinkedinUrl => Some(&self.linkedin_url),
            _ => None,
        }
    }

    /// Rows of the contact block in display order.
    fn contact_rows(&self) -> [(ProfileField, &ContactInput); 5] {
        [
            (ProfileField::Phone, &self.phone),
            (ProfileField::ContactEmail, &self.contact_email),
            (ProfileField::PortfolioUrl, &self.portfolio_url),
            (ProfileField::GithubUrl, &self.github_url),
            (ProfileField::LinkedinUrl, &self.linkedin_url),
        ]
    }

    /// Read-only contact block (view mode).
    fn draw_contacts_view(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        self.render_contact_block(frame, area, theme, false);
    }

    /// Editable contact block (edit mode): five single-line inputs sharing
    /// one bordered group so the form stays compact on small terminals.
    fn draw_contacts_edit(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        self.render_contact_block(frame, area, theme, true);
    }

    /// Render the contact group: one row per field with a focus marker,
    /// a fixed-width label column and the current value (or placeholder).
    fn render_contact_block(&self, frame: &mut Frame, area: Rect, theme: &Theme, editable: bool) {
        let focused = editable && self.focused_contact_index().is_some();
        let border_style = if focused {
            theme.style_border(true)
        } else {
            theme.style_border(false)
        };
        let title_style = if focused {
            theme.style_title()
        } else {
            theme.style_dim()
        };

        let mut lines: Vec<Line> = Vec::new();
        for (field, input) in self.contact_rows() {
            let is_row_focused = editable && self.focused_field == field;
            let label_style = if is_row_focused {
                theme.style_title()
            } else {
                theme.style_dim()
            };
            let label = format!(
                "{:<width$}",
                field.display_name(),
                width = CONTACT_LABEL_WIDTH as usize
            );
            let value_span = if input.text.is_empty() {
                Span::styled("(not set)", theme.style_dim())
            } else {
                Span::styled(&input.text, theme.style_normal())
            };
            lines.push(Line::from(vec![
                Span::styled(
                    if is_row_focused { "► " } else { "  " },
                    label_style,
                ),
                Span::styled(label, label_style),
                value_span,
            ]));
        }

        let content = Paragraph::new(Text::from(lines)).block(
            Block::default()
                .borders(Borders::ALL)
                .border_style(border_style)
                .title(Span::styled(" Contact ", title_style)),
        );
        frame.render_widget(content, area);

        // Position the terminal cursor inside the focused row's value area
        if focused {
            if let (Some(row), Some(input)) =
                (self.focused_contact_index(), self.focused_contact())
            {
                let inner = area.inner(Margin { vertical: 1, horizontal: 1 });
                let gutter = (CONTACT_MARKER_WIDTH + CONTACT_LABEL_WIDTH) as usize;
                let visible_width = inner.width as usize;
                if (row as u16) < inner.height && gutter < visible_width {
                    let col = input.cursor.min(visible_width - gutter - 1);
                    frame.set_cursor_position((inner.x + gutter as u16 + col as u16, inner.y + row as u16));
                }
            }
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

    fn draw_projects_edit(&mut self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let is_focused = self.focused_field == ProfileField::Projects;
        let border_style = if is_focused { theme.style_border(true) } else { theme.style_border(false) };
        let title_style = if is_focused { theme.style_title() } else { theme.style_dim() };

        let mut items: Vec<ListItem> = if self.projects.is_empty() {
            vec![ListItem::new(Line::from(vec![
                Span::styled("  (no projects — press 'n' to add)", theme.style_dim()),
            ]))]
        } else {
            self.projects.iter().map(|p| {
                let tech = p.tech_stack.join(", ");
                ListItem::new(Line::from(vec![
                    Span::styled(&p.name, theme.style_normal()),
                    Span::styled(" — ", theme.style_dim()),
                    Span::styled(tech, theme.style_warn()),
                ]))
            }).collect()
        };

        items.push(ListItem::new(Line::from(vec![
            Span::styled("  [n] Add  [d] Delete  [Enter] Edit", theme.style_dim()),
        ])));

        let list = List::new(items)
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(border_style)
                    .title(Span::styled(" Projects (n=add, d=delete, Enter=edit) ", title_style)),
            );

        frame.render_widget(list, area);
    }

    fn draw_project_popup(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let popup = match &self.project_popup {
            Some(p) => p,
            None => return,
        };

        let popup_area = Rect {
            x: area.x + area.width.saturating_sub(60) / 2,
            y: area.y + area.height.saturating_sub(12) / 2,
            width: 60.min(area.width),
            height: 12.min(area.height),
        };

        frame.render_widget(Clear, popup_area);

        let inner = popup_area.inner(Margin {
            vertical: 1,
            horizontal: 1,
        });

        let rows = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Length(3),
                Constraint::Length(3),
                Constraint::Length(3),
                Constraint::Length(1),
            ])
            .split(inner);

        let title = if popup.index.is_some() { " Edit Project " } else { " Add Project " };

        // Name field
        let name_focused = popup.focused_field == ProjectPopupField::Name;
        let name_border = if name_focused { theme.style_border(true) } else { theme.style_border(false) };
        let name_title = if name_focused { theme.style_title() } else { theme.style_dim() };
        frame.render_widget(
            Paragraph::new(Text::styled(&popup.name, theme.style_normal()))
                .block(Block::default()
                    .borders(Borders::ALL)
                    .border_style(name_border)
                    .title(Span::styled(" Name ", name_title))),
            rows[0],
        );
        if name_focused {
            frame.set_cursor_position((
                rows[0].x + 1 + popup.name_cursor.min((rows[0].width as usize).saturating_sub(2)) as u16,
                rows[0].y + 1,
            ));
        }

        // Description field
        let desc_focused = popup.focused_field == ProjectPopupField::Description;
        let desc_border = if desc_focused { theme.style_border(true) } else { theme.style_border(false) };
        let desc_title = if desc_focused { theme.style_title() } else { theme.style_dim() };
        frame.render_widget(
            Paragraph::new(Text::styled(&popup.description, theme.style_normal()))
                .block(Block::default()
                    .borders(Borders::ALL)
                    .border_style(desc_border)
                    .title(Span::styled(" Description ", desc_title))),
            rows[1],
        );
        if desc_focused {
            frame.set_cursor_position((
                rows[1].x + 1 + popup.description_cursor.min((rows[1].width as usize).saturating_sub(2)) as u16,
                rows[1].y + 1,
            ));
        }

        // TechStack field
        let tech_focused = popup.focused_field == ProjectPopupField::TechStack;
        let tech_border = if tech_focused { theme.style_border(true) } else { theme.style_border(false) };
        let tech_title = if tech_focused { theme.style_title() } else { theme.style_dim() };
        frame.render_widget(
            Paragraph::new(Text::styled(&popup.tech_stack, theme.style_normal()))
                .block(Block::default()
                    .borders(Borders::ALL)
                    .border_style(tech_border)
                    .title(Span::styled(" Tech Stack (comma-separated) ", tech_title))),
            rows[2],
        );
        if tech_focused {
            frame.set_cursor_position((
                rows[2].x + 1 + popup.tech_stack_cursor.min((rows[2].width as usize).saturating_sub(2)) as u16,
                rows[2].y + 1,
            ));
        }

        // Hint
        let hint = Block::default()
            .borders(Borders::ALL)
            .border_style(theme.style_border(false));
        frame.render_widget(
            Paragraph::new(Text::styled(
                " [Tab] Navigate  [Ctrl+S] Confirm  [Esc] Cancel ",
                theme.style_dim(),
            ))
            .alignment(Alignment::Center),
            hint.inner(rows[3]),
        );
        frame.render_widget(hint, rows[3]);

        // Popup border
        let outer = Block::default()
            .borders(Borders::ALL)
            .border_style(theme.style_border(true))
            .title(Span::styled(title, theme.style_title()));
        frame.render_widget(outer, popup_area);
    }

    fn draw_upload_path_popup(&self, frame: &mut Frame, area: Rect, theme: &Theme) {
        let popup = match &self.upload_path_popup {
            Some(p) => p,
            None => return,
        };

        let popup_area = Rect {
            x: area.x + area.width.saturating_sub(60) / 2,
            y: area.y + area.height.saturating_sub(5) / 2,
            width: 60.min(area.width),
            height: 5.min(area.height),
        };

        frame.render_widget(Clear, popup_area);

        let inner = popup_area.inner(Margin {
            vertical: 1,
            horizontal: 1,
        });

        // Path input field
        frame.render_widget(
            Paragraph::new(Text::styled(&popup.path, theme.style_normal()))
                .block(Block::default()
                    .borders(Borders::ALL)
                    .border_style(theme.style_border(true))
                    .title(Span::styled(" Resume PDF path ", theme.style_title()))),
            inner,
        );

        // Cursor
        let cursor_x = inner.x + 1 + popup.cursor.min((inner.width as usize).saturating_sub(2)) as u16;
        frame.set_cursor_position((cursor_x, inner.y + 1));

        // Popup border
        let outer = Block::default()
            .borders(Borders::ALL)
            .border_style(theme.style_border(true))
            .title(Span::styled(" Upload Resume ", theme.style_title()));
        frame.render_widget(outer, popup_area);
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
            ProfileMode::View => {
                if self.upload_path_popup.is_some() {
                    " [Enter] Confirm  [Esc] Cancel "
                } else {
                    " [e] Edit  [u] Upload PDF  [r] Reload  [q/b] Back  [Esc] Quit "
                }
            }
            ProfileMode::Edit => {
                if self.project_popup.is_some() {
                    " [Tab/↑↓] Navigate  [Ctrl+S] Confirm  [Esc] Cancel "
                } else if self.saving {
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

            let style = if toast.is_error { theme.style_bad() } else { theme.style_good() };

            let toast_widget = Paragraph::new(Text::styled(
                format!(" {} ", toast.message),
                style,
            ))
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(style),
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
                if screen.upload_path_popup.is_some() {
                    if let Some(popup) = &mut screen.upload_path_popup {
                        let byte_pos = popup.path.char_indices()
                            .nth(popup.cursor)
                            .map(|(i, _)| i)
                            .unwrap_or(popup.path.len());
                        popup.path.insert_str(byte_pos, &text);
                        popup.cursor += text.chars().count();
                    }
                } else {
                    screen.handle_paste(&text);
                }
            }
            return Ok(());
        }

    if let crossterm::event::Event::Key(key) = event {
        if key.kind != KeyEventKind::Press {
            return Ok(());
        }

        // Handle global keys first
        let is_editing = app.profile_screen.as_ref().is_some_and(|s| s.mode == ProfileMode::Edit);
        let has_popup = app.profile_screen.as_ref()
            .and_then(|s| s.project_popup.as_ref())
            .is_some();
        let has_upload_popup = app.profile_screen.as_ref()
            .and_then(|s| s.upload_path_popup.as_ref())
            .is_some();
        let is_uploading = app.profile_screen.as_ref()
            .map(|s| s.uploading)
            .unwrap_or(false);
        match key.code {
            KeyCode::Char('q') | KeyCode::Char('Q') if !is_editing && !has_popup && !has_upload_popup && !is_uploading => {
                app.state = crate::tui::app::AppState::JobList;
                return Ok(());
            }
            KeyCode::Char('b') | KeyCode::Char('B') if !has_popup && !has_upload_popup && !is_uploading => {
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
                if let Some(screen) = &mut app.profile_screen {
                    if screen.upload_path_popup.is_some() {
                        screen.upload_path_popup = None;
                    } else if screen.project_popup.is_some() {
                        screen.cancel_project_popup();
                    } else if screen.mode == ProfileMode::Edit {
                        screen.cancel_edit();
                    } else if !screen.uploading {
                        app.should_quit = true;
                        app.state = crate::tui::app::AppState::Quitting;
                    }
                } else if !is_editing {
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
                        if screen.project_popup.is_some() {
                            screen.confirm_project_popup();
                        } else if screen.mode == ProfileMode::Edit && !screen.saving && !screen.loading.is_loading() {
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
            // If upload path popup is active, handle its input
            if screen.upload_path_popup.is_some() {
                match key.code {
                    KeyCode::Enter => {
                        screen.start_upload();
                    }
                    KeyCode::Esc => {
                        screen.upload_path_popup = None;
                    }
                    KeyCode::Backspace => {
                        if let Some(popup) = &mut screen.upload_path_popup && popup.cursor > 0 {
                            let byte_pos = popup.path.char_indices()
                                .nth(popup.cursor - 1)
                                .map(|(i, _)| i)
                                .unwrap_or(0);
                            popup.path.remove(byte_pos);
                            popup.cursor -= 1;
                        }
                    }
                    KeyCode::Left => {
                        if let Some(popup) = &mut screen.upload_path_popup {
                            popup.cursor = popup.cursor.saturating_sub(1);
                        }
                    }
                    KeyCode::Right => {
                        if let Some(popup) = &mut screen.upload_path_popup {
                            let max = popup.path.chars().count();
                            popup.cursor = popup.cursor.saturating_add(1).min(max);
                        }
                    }
                    KeyCode::Home => {
                        if let Some(popup) = &mut screen.upload_path_popup {
                            popup.cursor = 0;
                        }
                    }
                    KeyCode::End => {
                        if let Some(popup) = &mut screen.upload_path_popup {
                            popup.cursor = popup.path.chars().count();
                        }
                    }
                    KeyCode::Char(c) => {
                        if let Some(popup) = &mut screen.upload_path_popup {
                            let byte_pos = popup.path.char_indices()
                                .nth(popup.cursor)
                                .map(|(i, _)| i)
                                .unwrap_or(popup.path.len());
                            popup.path.insert(byte_pos, c);
                            popup.cursor += 1;
                        }
                    }
                    _ => {}
                }
                return Ok(());
            }

            // If project popup is active, handle popup input
            if screen.project_popup.is_some() {
                match key.code {
                    KeyCode::Tab => {
                        screen.popup_focus_next();
                    }
                    KeyCode::BackTab => {
                        screen.popup_focus_prev();
                    }
                    KeyCode::Enter => {
                        screen.confirm_project_popup();
                    }
                    KeyCode::Backspace => {
                        screen.popup_handle_backspace();
                    }
                    KeyCode::Left => {
                        screen.popup_handle_left();
                    }
                    KeyCode::Right => {
                        screen.popup_handle_right();
                    }
                    KeyCode::Char(c) => {
                        screen.popup_handle_char(c);
                    }
                    _ => {}
                }
                return Ok(());
            }

            // Normal edit/view mode events
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
                KeyCode::Char('u') | KeyCode::Char('U')
                    if screen.mode == ProfileMode::View && !screen.loading.is_loading() && !screen.saving && !screen.uploading =>
                {
                    screen.show_upload_popup();
                }
                KeyCode::Char('n') | KeyCode::Char('N')
                    if screen.mode == ProfileMode::Edit && screen.focused_field == ProfileField::Projects
                        && !screen.saving && !screen.loading.is_loading() =>
                {
                    screen.add_project();
                }
                KeyCode::Char('d') | KeyCode::Char('D')
                    if screen.mode == ProfileMode::Edit && screen.focused_field == ProfileField::Projects
                        && !screen.saving && !screen.loading.is_loading() && !screen.projects.is_empty() =>
                {
                    screen.delete_project(screen.projects.len() - 1);
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
                            ProfileField::Projects if !screen.projects.is_empty() => {
                                screen.edit_project(screen.projects.len() - 1);
                            }
                            _ => {
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
                    if screen.mode == ProfileMode::Edit && !screen.saving && !screen.loading.is_loading()
                        && screen.focused_field.is_text_input()
                    {
                        screen.handle_left();
                    }
                }
                KeyCode::Right => {
                    if screen.mode == ProfileMode::Edit && !screen.saving && !screen.loading.is_loading()
                        && screen.focused_field.is_text_input()
                    {
                        screen.handle_right();
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
            projects: vec![
                ProjectResponse {
                    name: "Job Hunter CLI".into(),
                    description: "Rust TUI client".into(),
                    tech_stack: vec!["Rust".into(), "Ratatui".into()],
                },
            ],
            phone: Some("+55 42 99833-1363".to_string()),
            contact_email: Some("juan@example.com".to_string()),
            portfolio_url: Some("https://juanperuzzo.dev".to_string()),
            github_url: None,
            linkedin_url: None,
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
        assert!(screen.projects.is_empty());
        assert!(screen.project_popup.is_none());
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
        assert_eq!(screen.focused_field, ProfileField::Phone);

        screen.focus_next();
        assert_eq!(screen.focused_field, ProfileField::ContactEmail);

        screen.focus_next();
        assert_eq!(screen.focused_field, ProfileField::PortfolioUrl);

        screen.focus_next();
        assert_eq!(screen.focused_field, ProfileField::GithubUrl);

        screen.focus_next();
        assert_eq!(screen.focused_field, ProfileField::LinkedinUrl);

        screen.focus_next();
        assert_eq!(screen.focused_field, ProfileField::Tone);

        screen.focus_next();
        assert_eq!(screen.focused_field, ProfileField::Projects);

        screen.focus_next();
        assert_eq!(screen.focused_field, ProfileField::Resume); // Wraps around

        screen.focus_prev();
        assert_eq!(screen.focused_field, ProfileField::Projects);

        screen.focus_prev();
        assert_eq!(screen.focused_field, ProfileField::Tone);

        screen.focus_prev();
        assert_eq!(screen.focused_field, ProfileField::LinkedinUrl);
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
        screen.focus_next(); // Phone
        screen.focus_next(); // ContactEmail
        screen.focus_next(); // PortfolioUrl
        screen.focus_next(); // GithubUrl
        screen.focus_next(); // LinkedinUrl
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
        assert_eq!(ProfileField::Phone.display_name(), "Phone");
        assert_eq!(ProfileField::ContactEmail.display_name(), "Contact Email");
        assert_eq!(ProfileField::PortfolioUrl.display_name(), "Portfolio URL");
        assert_eq!(ProfileField::GithubUrl.display_name(), "GitHub URL");
        assert_eq!(ProfileField::LinkedinUrl.display_name(), "LinkedIn URL");
        assert_eq!(ProfileField::Tone.display_name(), "Tone");
        assert_eq!(ProfileField::Projects.display_name(), "Projects");
    }

    #[test]
    fn profile_field_navigation() {
        let fields = ProfileField::all();
        assert_eq!(fields.len(), 9);

        assert_eq!(ProfileField::Resume.next(), ProfileField::Skills);
        assert_eq!(ProfileField::Skills.next(), ProfileField::Phone);
        assert_eq!(ProfileField::Phone.next(), ProfileField::ContactEmail);
        assert_eq!(ProfileField::ContactEmail.next(), ProfileField::PortfolioUrl);
        assert_eq!(ProfileField::PortfolioUrl.next(), ProfileField::GithubUrl);
        assert_eq!(ProfileField::GithubUrl.next(), ProfileField::LinkedinUrl);
        assert_eq!(ProfileField::LinkedinUrl.next(), ProfileField::Tone);
        assert_eq!(ProfileField::Tone.next(), ProfileField::Projects);
        assert_eq!(ProfileField::Projects.next(), ProfileField::Resume);

        assert_eq!(ProfileField::Resume.prev(), ProfileField::Projects);
        assert_eq!(ProfileField::Projects.prev(), ProfileField::Tone);
        assert_eq!(ProfileField::Tone.prev(), ProfileField::LinkedinUrl);
        assert_eq!(ProfileField::LinkedinUrl.prev(), ProfileField::GithubUrl);
        assert_eq!(ProfileField::GithubUrl.prev(), ProfileField::PortfolioUrl);
        assert_eq!(ProfileField::PortfolioUrl.prev(), ProfileField::ContactEmail);
        assert_eq!(ProfileField::ContactEmail.prev(), ProfileField::Phone);
        assert_eq!(ProfileField::Phone.prev(), ProfileField::Skills);
        assert_eq!(ProfileField::Skills.prev(), ProfileField::Resume);
    }

    #[test]
    fn profile_field_text_input_classification() {
        assert!(ProfileField::Resume.is_text_input());
        assert!(ProfileField::Skills.is_text_input());
        assert!(ProfileField::Phone.is_text_input());
        assert!(ProfileField::ContactEmail.is_text_input());
        assert!(ProfileField::PortfolioUrl.is_text_input());
        assert!(ProfileField::GithubUrl.is_text_input());
        assert!(ProfileField::LinkedinUrl.is_text_input());
        assert!(!ProfileField::Tone.is_text_input());
        assert!(!ProfileField::Projects.is_text_input());
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

    #[test]
    fn project_add_and_delete() {
        let mut screen = create_test_screen();
        assert!(screen.projects.is_empty());

        screen.add_project();
        assert!(screen.project_popup.is_some());
        assert_eq!(screen.project_popup.as_ref().unwrap().index, None);
        assert_eq!(screen.project_popup.as_ref().unwrap().name, "");

        // Fill popup fields
        screen.popup_handle_char('M');
        screen.popup_handle_char('y');
        screen.popup_handle_char(' ');
        screen.popup_handle_char('P');
        screen.popup_handle_char('r');
        screen.popup_handle_char('o');
        screen.popup_handle_char('j');
        assert_eq!(screen.project_popup.as_ref().unwrap().name, "My Proj");

        screen.popup_focus_next();
        assert_eq!(screen.project_popup.as_ref().unwrap().focused_field, ProjectPopupField::Description);
        screen.popup_handle_char('d');
        screen.popup_handle_char('e');
        screen.popup_handle_char('s');
        screen.popup_handle_char('c');
        assert_eq!(screen.project_popup.as_ref().unwrap().description, "desc");

        screen.popup_focus_next();
        screen.popup_handle_char('R');
        screen.popup_handle_char('u');
        screen.popup_handle_char('s');
        screen.popup_handle_char('t');
        assert_eq!(screen.project_popup.as_ref().unwrap().tech_stack, "Rust");

        // Confirm
        screen.confirm_project_popup();
        assert!(screen.project_popup.is_none());
        assert_eq!(screen.projects.len(), 1);
        assert_eq!(screen.projects[0].name, "My Proj");
        assert_eq!(screen.projects[0].tech_stack, vec!["Rust"]);

        // Delete
        screen.delete_project(0);
        assert!(screen.projects.is_empty());
    }

    #[test]
    fn project_edit_existing() {
        let mut screen = create_test_screen();
        screen.projects = vec![ProjectResponse {
            name: "Old Name".into(),
            description: "Old desc".into(),
            tech_stack: vec!["Java".into()],
        }];

        screen.edit_project(0);
        assert!(screen.project_popup.is_some());
        assert_eq!(screen.project_popup.as_ref().unwrap().index, Some(0));
        assert_eq!(screen.project_popup.as_ref().unwrap().name, "Old Name");
        assert_eq!(screen.project_popup.as_ref().unwrap().tech_stack, "Java");

        // Modify name
        let popup = screen.project_popup.as_mut().unwrap();
        popup.name = "New Name".into();
        popup.tech_stack = "Rust, Go".into();

        screen.confirm_project_popup();
        assert_eq!(screen.projects.len(), 1);
        assert_eq!(screen.projects[0].name, "New Name");
        assert_eq!(screen.projects[0].tech_stack, vec!["Rust", "Go"]);
    }

    #[test]
    fn project_popup_cancel_discards_changes() {
        let mut screen = create_test_screen();
        screen.projects = vec![ProjectResponse {
            name: "Keep Me".into(),
            description: "desc".into(),
            tech_stack: vec!["Rust".into()],
        }];

        screen.edit_project(0);
        let popup = screen.project_popup.as_mut().unwrap();
        popup.name = "Changed".into();

        screen.cancel_project_popup();
        assert!(screen.project_popup.is_none());
        assert_eq!(screen.projects[0].name, "Keep Me");
    }

    #[test]
    fn project_popup_field_navigation() {
        let mut screen = create_test_screen();
        screen.add_project();

        assert_eq!(screen.project_popup.as_ref().unwrap().focused_field, ProjectPopupField::Name);

        screen.popup_focus_next();
        assert_eq!(screen.project_popup.as_ref().unwrap().focused_field, ProjectPopupField::Description);

        screen.popup_focus_next();
        assert_eq!(screen.project_popup.as_ref().unwrap().focused_field, ProjectPopupField::TechStack);

        screen.popup_focus_next();
        assert_eq!(screen.project_popup.as_ref().unwrap().focused_field, ProjectPopupField::Name); // wraps

        screen.popup_focus_prev();
        assert_eq!(screen.project_popup.as_ref().unwrap().focused_field, ProjectPopupField::TechStack);
    }
}