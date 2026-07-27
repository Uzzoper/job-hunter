use ratatui::{
    layout::{Constraint, Direction, Layout, Rect},
    style::{Color, Modifier, Style},
    text::{Line, Span, Text},
    widgets::{Block, Borders, Clear, Paragraph, Wrap},
    Frame,
};

/// Cyberpunk neon colour palette.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Theme {
    pub primary: Color,
    pub secondary: Color,
    pub accent: Color,
    pub warn: Color,
    pub bad: Color,
    pub bg: Color,
    pub surface: Color,
    pub text: Color,
    pub dim: Color,
    pub good: Color,
}

impl Theme {
    /// Cyan + neon green theme (24-bit RGB).
    pub const fn cyberpunk() -> Self {
        Self {
            primary: Color::Rgb(0, 240, 255),    // cyan
            secondary: Color::Rgb(0, 255, 80),   // neon green — selection/focus
            accent: Color::Rgb(0, 200, 180),     // teal
            warn: Color::Rgb(255, 170, 0),       // orange
            bad: Color::Rgb(240, 70, 80),        // red
            bg: Color::Rgb(13, 17, 23),          // keep
            surface: Color::Rgb(22, 27, 34),     // keep
            text: Color::Rgb(230, 230, 230),     // keep
            dim: Color::Rgb(139, 148, 158),      // keep
            good: Color::Rgb(0, 255, 100),       // neon green for scores
        }
    }

    /// 256-color fallback theme (8-bit indexed colors).
    pub const fn fallback_256() -> Self {
        Self {
            primary: Color::Indexed(51),   // cyan
            secondary: Color::Indexed(46), // neon green
            accent: Color::Indexed(43),    // teal
            warn: Color::Indexed(214),     // keep
            bad: Color::Indexed(196),      // keep
            bg: Color::Indexed(234),       // keep
            surface: Color::Indexed(235),  // keep
            text: Color::Indexed(252),     // keep
            dim: Color::Indexed(240),      // keep
            good: Color::Indexed(46),      // neon green for scores
        }
    }

    /// Detect true-color support and return the appropriate theme.
    pub fn detect() -> Self {
        if supports_true_color() {
            Self::cyberpunk()
        } else {
            Self::fallback_256()
        }
    }

    /// Style for titles: bold primary color.
    pub fn title() -> Style {
        let theme = Self::cyberpunk();
        Style::default()
            .fg(theme.primary)
            .add_modifier(Modifier::BOLD)
    }

    /// Style for selected items: bold secondary color.
    pub fn selected() -> Style {
        let theme = Self::cyberpunk();
        Style::default()
            .fg(theme.secondary)
            .add_modifier(Modifier::BOLD)
    }

    /// Style for highlights: accent color.
    pub fn highlight() -> Style {
        let theme = Self::cyberpunk();
        Style::default().fg(theme.accent)
    }

    /// Style for normal text.
    pub fn normal() -> Style {
        let theme = Self::cyberpunk();
        Style::default().fg(theme.text)
    }

    /// Style for dimmed text.
    pub fn dim() -> Style {
        let theme = Self::cyberpunk();
        Style::default().fg(theme.dim)
    }

    /// Style for good/positive values: neon green.
    pub fn good() -> Style {
        let theme = Self::cyberpunk();
        Style::default().fg(theme.good)
    }

    /// Style for bad/negative values: red.
    pub fn bad() -> Style {
        let theme = Self::cyberpunk();
        Style::default().fg(theme.bad)
    }

    /// Style for warnings: amber.
    pub fn warn() -> Style {
        let theme = Self::cyberpunk();
        Style::default().fg(theme.warn)
    }

    /// Style for score values: green (>=80), amber (50-79), red (<50).
    pub fn score_color(score: i32) -> Style {
        let theme = Self::cyberpunk();
        let color = if score >= 80 {
            theme.good
        } else if score >= 50 {
            theme.warn
        } else {
            theme.bad
        };
        Style::default().fg(color)
    }

    /// Style for borders based on focus state.
    pub fn border(focused: bool) -> Style {
        let theme = Self::cyberpunk();
        if focused {
            Style::default().fg(theme.primary)
        } else {
            Style::default().fg(theme.dim)
        }
    }

    /// Style for surface backgrounds.
    pub fn surface_style() -> Style {
        let theme = Self::cyberpunk();
        Style::default().bg(theme.surface).fg(theme.text)
    }

    /// Style for primary backgrounds.
    pub fn bg_style() -> Style {
        let theme = Self::cyberpunk();
        Style::default().bg(theme.bg).fg(theme.text)
    }

    /// Render the "JOB HUNTER" cyberpunk ASCII logo.
    pub fn render_logo(&self, frame: &mut Frame, area: Rect) {
        let logo_lines = [
            "██╗  ██╗ █████╗ ███╗   ██╗████████╗",
            "██║  ██║██╔══██╗████╗  ██║╚══██╔══╝",
            "███████║███████║██╔██╗ ██║   ██║   ",
            "██╔══██║██╔══██║██║╚██╗██║   ██║   ",
            "██║  ██║██║  ██║██║ ╚████║   ██║   ",
            "╚═╝  ╚═╝╚═╝  ╚═╝╚═╝  ╚═══╝   ╚═╝   ",
        ];

        let subtitle = "  CYBERPUNK JOB HUNTER  ";

        let mut lines = Vec::new();
        for (i, line) in logo_lines.iter().enumerate() {
            let style = if i < 3 {
                self.style_title()
            } else {
                self.style_highlight()
            };
            lines.push(Line::from(Span::styled(*line, style)));
        }

        let subtitle_style = self.style_dim();
        lines.push(Line::from(Span::styled(subtitle, subtitle_style)));

        let logo_area = Rect {
            x: area.x + (area.width.saturating_sub(38)) / 2,
            y: area.y,
            width: 38.min(area.width),
            height: (logo_lines.len() + 1) as u16,
        };

        frame.render_widget(
            Paragraph::new(lines).alignment(ratatui::layout::Alignment::Center),
            logo_area,
        );
    }

    /// Instance method for title style.
    pub fn style_title(&self) -> Style {
        Style::default()
            .fg(self.primary)
            .add_modifier(Modifier::BOLD)
    }

    /// Instance method for selected style.
    pub fn style_selected(&self) -> Style {
        Style::default()
            .fg(self.secondary)
            .add_modifier(Modifier::BOLD)
    }

    /// Instance method for highlight style.
    pub fn style_highlight(&self) -> Style {
        Style::default().fg(self.accent)
    }

    /// Instance method for normal text style.
    pub fn style_normal(&self) -> Style {
        Style::default().fg(self.text)
    }

    /// Instance method for dim text style.
    pub fn style_dim(&self) -> Style {
        Style::default().fg(self.dim)
    }

    /// Instance method for good style.
    pub fn style_good(&self) -> Style {
        Style::default().fg(self.good)
    }

    /// Instance method for bad style.
    pub fn style_bad(&self) -> Style {
        Style::default().fg(self.bad)
    }

    /// Instance method for warn style.
    pub fn style_warn(&self) -> Style {
        Style::default().fg(self.warn)
    }

    /// Instance method for score color.
    pub fn style_score_color(&self, score: i32) -> Style {
        let color = if score >= 80 {
            self.good
        } else if score >= 50 {
            self.warn
        } else {
            self.bad
        };
        Style::default().fg(color)
    }

    /// Instance method for border style.
    pub fn style_border(&self, focused: bool) -> Style {
        if focused {
            Style::default().fg(self.primary)
        } else {
            Style::default().fg(self.dim)
        }
    }

}

impl Default for Theme {
    fn default() -> Self {
        Self::detect()
    }
}

/// Check if the terminal supports true color (24-bit RGB).
fn supports_true_color() -> bool {
    std::env::var("COLORTERM")
        .map(|v| v == "truecolor" || v == "24bit")
        .unwrap_or(false)
}

/// Spinner animation frames for loading states.
pub const SPINNER_FRAMES: &[&str] = &["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"];

/// Get the current spinner frame based on elapsed time.
pub fn spinner_frame() -> &'static str {
    let idx = (std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() / 100) as usize;
    SPINNER_FRAMES[idx % SPINNER_FRAMES.len()]
}

/// Render a loading spinner with message in a centered popup.
pub fn render_loading(frame: &mut Frame, area: Rect, theme: &Theme, message: &str) {
    let popup_area = centered_rect(50, 8, area);

    frame.render_widget(Clear, popup_area);

    let text = format!(" {} {} ", spinner_frame(), message);
    let para = Paragraph::new(Text::styled(text, theme.style_warn()))
        .alignment(ratatui::layout::Alignment::Center)
        .block(
            Block::default()
                .borders(Borders::ALL)
                .border_style(theme.style_warn()),
        );
    frame.render_widget(para, popup_area);
}

/// Render an error popup modal.
pub fn render_error_popup(frame: &mut Frame, area: Rect, theme: &Theme, message: &str, hint: &str) {
    let popup_area = centered_rect(60, 20, area);

    frame.render_widget(Clear, popup_area);

    let full_message = format!(" Error \n\n{}\n\n{} ", message, hint);
    let popup = Paragraph::new(Text::styled(full_message, theme.style_bad()))
        .block(
            Block::default()
                .borders(Borders::ALL)
                .border_style(theme.style_bad()),
        )
        .alignment(ratatui::layout::Alignment::Center)
        .wrap(Wrap { trim: false });
    frame.render_widget(popup, popup_area);
}

/// Render an empty state message.
pub fn render_empty_state(frame: &mut Frame, area: Rect, theme: &Theme, message: &str) {
    let para = Paragraph::new(Text::styled(message, theme.style_dim()))
        .alignment(ratatui::layout::Alignment::Center)
        .block(
            Block::default()
                .borders(Borders::ALL)
                .border_style(theme.style_border(false)),
        )
        .wrap(Wrap { trim: false });
    frame.render_widget(para, area);
}

/// Helper to create a centered rectangle.
fn centered_rect(percent_x: u16, percent_y: u16, r: Rect) -> Rect {
    let popup_layout = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Percentage((100 - percent_y) / 2),
            Constraint::Percentage(percent_y),
            Constraint::Percentage((100 - percent_y) / 2),
        ])
        .split(r);

    Layout::default()
        .direction(Direction::Horizontal)
        .constraints([
            Constraint::Percentage((100 - percent_x) / 2),
            Constraint::Percentage(percent_x),
            Constraint::Percentage((100 - percent_x) / 2),
        ])
        .split(popup_layout[1])[1]
}

/// Truncate text to fit within a given width, adding ellipsis if needed.
pub fn truncate_text(text: &str, max_width: usize) -> String {
    if text.chars().count() <= max_width {
        text.to_string()
    } else {
        let truncated: String = text.chars().take(max_width.saturating_sub(1)).collect();
        format!("{}…", truncated)
    }
}



