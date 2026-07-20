#[cfg(test)]
mod tests {
    use crate::tui::theme::Theme;
    use ratatui::style::{Color, Modifier};

    #[test]
    fn cyberpunk_theme_has_correct_colors() {
        let theme = Theme::cyberpunk();

        assert_eq!(theme.primary, Color::Rgb(0, 130, 90));
        assert_eq!(theme.secondary, Color::Rgb(0, 255, 80));
        assert_eq!(theme.accent, Color::Rgb(0, 210, 130));
        assert_eq!(theme.warn, Color::Rgb(250, 190, 50));
        assert_eq!(theme.bad, Color::Rgb(240, 70, 80));
        assert_eq!(theme.bg, Color::Rgb(13, 17, 23));
        assert_eq!(theme.surface, Color::Rgb(22, 27, 34));
        assert_eq!(theme.text, Color::Rgb(230, 230, 230));
        assert_eq!(theme.dim, Color::Rgb(139, 148, 158));
        assert_eq!(theme.good, Color::Rgb(0, 255, 100));
    }

    #[test]
    fn fallback_256_theme_has_correct_colors() {
        let theme = Theme::fallback_256();

        assert_eq!(theme.primary, Color::Indexed(28));
        assert_eq!(theme.secondary, Color::Indexed(46));
        assert_eq!(theme.accent, Color::Indexed(35));
        assert_eq!(theme.warn, Color::Indexed(214));
        assert_eq!(theme.bad, Color::Indexed(196));
        assert_eq!(theme.bg, Color::Indexed(234));
        assert_eq!(theme.surface, Color::Indexed(235));
        assert_eq!(theme.text, Color::Indexed(252));
        assert_eq!(theme.dim, Color::Indexed(240));
        assert_eq!(theme.good, Color::Indexed(46));
    }

    #[test]
    fn title_style_uses_primary_and_bold() {
        let style = Theme::title();

        assert_eq!(style.fg, Some(Color::Rgb(0, 130, 90)));
        assert!(style.add_modifier.contains(Modifier::BOLD));
    }

    #[test]
    fn selected_style_uses_secondary_and_bold() {
        let style = Theme::selected();

        assert_eq!(style.fg, Some(Color::Rgb(0, 255, 80)));
        assert!(style.add_modifier.contains(Modifier::BOLD));
    }

    #[test]
    fn highlight_style_uses_accent() {
        let style = Theme::highlight();

        assert_eq!(style.fg, Some(Color::Rgb(0, 210, 130)));
    }

    #[test]
    fn normal_style_uses_text_color() {
        let style = Theme::normal();

        assert_eq!(style.fg, Some(Color::Rgb(230, 230, 230)));
    }

    #[test]
    fn dim_style_uses_dim_color() {
        let style = Theme::dim();

        assert_eq!(style.fg, Some(Color::Rgb(139, 148, 158)));
    }

    #[test]
    fn good_style_uses_green() {
        let style = Theme::good();

        assert_eq!(style.fg, Some(Color::Rgb(0, 255, 100)));
    }

    #[test]
    fn bad_style_uses_red() {
        let style = Theme::bad();

        assert_eq!(style.fg, Some(Color::Rgb(240, 70, 80)));
    }

    #[test]
    fn warn_style_uses_amber() {
        let style = Theme::warn();

        assert_eq!(style.fg, Some(Color::Rgb(250, 190, 50)));
    }

    #[test]
    fn score_color_green_for_80_and_above() {
        let style = Theme::score_color(80);
        assert_eq!(style.fg, Some(Color::Rgb(0, 255, 100)));

        let style = Theme::score_color(100);
        assert_eq!(style.fg, Some(Color::Rgb(0, 255, 100)));

        let style = Theme::score_color(85);
        assert_eq!(style.fg, Some(Color::Rgb(0, 255, 100)));
    }

    #[test]
    fn score_color_amber_for_50_to_79() {
        let style = Theme::score_color(50);
        assert_eq!(style.fg, Some(Color::Rgb(250, 190, 50)));

        let style = Theme::score_color(79);
        assert_eq!(style.fg, Some(Color::Rgb(250, 190, 50)));

        let style = Theme::score_color(65);
        assert_eq!(style.fg, Some(Color::Rgb(250, 190, 50)));
    }

    #[test]
    fn score_color_red_below_50() {
        let style = Theme::score_color(49);
        assert_eq!(style.fg, Some(Color::Rgb(240, 70, 80)));

        let style = Theme::score_color(0);
        assert_eq!(style.fg, Some(Color::Rgb(240, 70, 80)));

        let style = Theme::score_color(30);
        assert_eq!(style.fg, Some(Color::Rgb(240, 70, 80)));
    }

    #[test]
    fn border_style_primary_when_focused() {
        let style = Theme::border(true);
        assert_eq!(style.fg, Some(Color::Rgb(0, 130, 90)));
    }

    #[test]
    fn border_style_dim_when_unfocused() {
        let style = Theme::border(false);
        assert_eq!(style.fg, Some(Color::Rgb(139, 148, 158)));
    }

    #[test]
    fn surface_style_uses_surface_bg_and_text_fg() {
        let style = Theme::surface_style();
        assert_eq!(style.bg, Some(Color::Rgb(22, 27, 34)));
        assert_eq!(style.fg, Some(Color::Rgb(230, 230, 230)));
    }

    #[test]
    fn bg_style_uses_bg_and_text() {
        let style = Theme::bg_style();
        assert_eq!(style.bg, Some(Color::Rgb(13, 17, 23)));
        assert_eq!(style.fg, Some(Color::Rgb(230, 230, 230)));
    }

    #[test]
    fn default_theme_detects_true_color() {
        let theme = Theme::default();
        assert!(theme.primary == Color::Rgb(0, 130, 90) || theme.primary == Color::Indexed(28));
    }

    #[test]
    fn fallback_256_title_style_uses_indexed_cyan() {
        let theme = Theme::fallback_256();
        let style = theme.style_title();
        assert_eq!(style.fg, Some(Color::Indexed(28)));
    }

    #[test]
    fn fallback_256_selected_style_uses_indexed_magenta() {
        let theme = Theme::fallback_256();
        let style = theme.style_selected();
        assert_eq!(style.fg, Some(Color::Indexed(46)));
    }

    #[test]
    fn fallback_256_score_color_maps_correctly() {
        let theme = Theme::fallback_256();

        let style = theme.style_score_color(85);
        assert_eq!(style.fg, Some(Color::Indexed(46)));

        let style = theme.style_score_color(60);
        assert_eq!(style.fg, Some(Color::Indexed(214)));

        let style = theme.style_score_color(30);
        assert_eq!(style.fg, Some(Color::Indexed(196)));
    }
}