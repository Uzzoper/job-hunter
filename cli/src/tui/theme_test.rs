#[cfg(test)]
mod tests {
    use crate::tui::theme::{spinner_frame, render_loading, SPINNER_FRAMES, Theme};
    use ratatui::style::{Color, Modifier};

    #[test]
    fn spinner_frame_cycles_within_10_frames() {
        // spinner_frame() indexes a 10-frame cycle by (now_ms / 100) % 10,
        // so successive instant calls share a bucket. Sample across ~1.1s to
        // observe rotation ("no stuck frame").
        let mut seen = std::collections::HashSet::new();
        for _ in 0..10 {
            seen.insert(spinner_frame());
            std::thread::sleep(std::time::Duration::from_millis(110));
        }
        assert!(
            seen.len() >= 3,
            "spinner must rotate over time, only saw {} distinct frames: {:?}",
            seen.len(),
            seen
        );
        for frame in seen {
            assert!(
                SPINNER_FRAMES.contains(&frame),
                "spinner frame {frame:?} not in SPINNER_FRAMES"
            );
        }
    }

    #[test]
    fn render_loading_does_not_panic_with_fetch_message() {
        let mut terminal =
            ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();
        terminal
            .draw(|frame| {
                render_loading(
                    frame,
                    frame.area(),
                    &Theme::detect(),
                    &"Scraping Gupy… (1/3) — 3s / ~30s  [Esc] Cancel".to_string(),
                );
            })
            .unwrap();
    }

    #[test]
    fn cyberpunk_theme_has_correct_colors() {
        let theme = Theme::cyberpunk();

        assert_eq!(theme.primary, Color::Rgb(0, 240, 255));
        assert_eq!(theme.secondary, Color::Rgb(0, 255, 80));
        assert_eq!(theme.accent, Color::Rgb(0, 200, 180));
        assert_eq!(theme.warn, Color::Rgb(255, 170, 0));
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

        assert_eq!(theme.primary, Color::Indexed(51));
        assert_eq!(theme.secondary, Color::Indexed(46));
        assert_eq!(theme.accent, Color::Indexed(43));
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

        assert_eq!(style.fg, Some(Color::Rgb(0, 240, 255)));
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

        assert_eq!(style.fg, Some(Color::Rgb(0, 200, 180)));
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

        assert_eq!(style.fg, Some(Color::Rgb(255, 170, 0)));
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
        assert_eq!(style.fg, Some(Color::Rgb(255, 170, 0)));

        let style = Theme::score_color(79);
        assert_eq!(style.fg, Some(Color::Rgb(255, 170, 0)));

        let style = Theme::score_color(65);
        assert_eq!(style.fg, Some(Color::Rgb(255, 170, 0)));
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
        assert_eq!(style.fg, Some(Color::Rgb(0, 240, 255)));
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
        assert!(theme.primary == Color::Rgb(0, 240, 255) || theme.primary == Color::Indexed(51));
    }

    #[test]
    fn fallback_256_title_style_uses_indexed_cyan() {
        let theme = Theme::fallback_256();
        let style = theme.style_title();
        assert_eq!(style.fg, Some(Color::Indexed(51)));
    }

    #[test]
    fn fallback_256_selected_style_uses_indexed_green() {
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