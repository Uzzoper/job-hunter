// Allow dead code during scaffolding — will be removed in later tasks


pub mod domain;
pub mod api;
pub mod config;
pub mod cache;
pub mod error;
pub mod util;
pub mod batch;
pub mod tui;

use clap::{Parser, Subcommand};

#[derive(Parser)]
#[command(name = "jh-cli")]
#[command(about = "Job Hunter CLI — TUI and batch client")]
pub struct Cli {
    #[command(subcommand)]
    pub command: Option<Command>,

    /// API base URL (overrides config)
    #[arg(short = 'u', long)]
    pub api_url: Option<String>,

    /// Config file path
    #[arg(short = 'c', long)]
    pub config: Option<String>,

    /// API token (overrides config)
    #[arg(short = 't', long)]
    pub token: Option<String>,

    /// Start in TUI mode (default if no subcommand)
    #[arg(short = 'T', long)]
    pub tui: bool,
}

#[derive(Subcommand)]
pub enum Command {
    /// Authenticate (login or register)
    Auth {
        #[command(subcommand)]
        action: AuthAction,
    },
    /// List jobs with optional filters and format flags
    List {
        /// Keyword filter (matches title and company)
        #[arg(short, long)]
        keyword: Option<String>,
        /// Minimum match score filter (may trigger analysis)
        #[arg(short = 's', long)]
        min_score: Option<u8>,
        /// Filter by source (gupy, linkedin, infojobs)
        #[arg(short = 'S', long)]
        source: Option<String>,
        /// Output as CSV
        #[arg(long)]
        csv: bool,
        /// Output as JSON
        #[arg(long)]
        json: bool,
        /// Force offline mode (load from cache only)
        #[arg(long)]
        offline: bool,
        /// Force cache refresh (fetch from API and update cache)
        #[arg(long)]
        refresh: bool,
    },
    /// Show full job detail
    Detail {
        /// Job ID
        id: i64,
        /// Output as JSON
        #[arg(long)]
        json: bool,
    },
    /// Fetch new jobs from all providers (or a specific source)
    Fetch {
        /// Optional source (gupy, linkedin, infojobs)
        source: Option<String>,
    },
    /// Analyze a job with AI
    Analyze {
        /// Job ID
        job_id: String,
        /// Output analysis as JSON
        #[arg(long)]
        json: bool,
    },
    /// Manage email drafts
    Email {
        #[command(subcommand)]
        action: EmailAction,
    },
    /// Manage profile
    Profile {
        #[command(subcommand)]
        action: ProfileAction,
    },
    /// Export jobs to CSV
    Export {
        /// Output file path
        output: String,
        /// Keyword filter
        #[arg(short, long)]
        keyword: Option<String>,
    },
    /// Clear local cache
    ClearCache,
}

#[derive(Subcommand)]
pub enum AuthAction {
    /// Log in with email and password
    Login {
        email: String,
        /// Password (prompted interactively if omitted)
        #[arg(short = 'p', long)]
        password: Option<String>,
    },
    /// Register a new account
    Register {
        name: String,
        email: String,
        /// Password (prompted interactively if omitted)
        #[arg(short = 'p', long)]
        password: Option<String>,
    },
    /// Clear stored credentials and log out
    Logout,
}

#[derive(Subcommand)]
pub enum EmailAction {
    /// Show email draft for a job
    Show {
        job_id: String,
        /// Output email as JSON
        #[arg(long)]
        json: bool,
        /// Copy email body to clipboard
        #[arg(long)]
        copy: bool,
    },
    /// Generate a new email draft for a job
    Generate { job_id: String },
}

#[derive(Subcommand)]
pub enum ProfileAction {
    /// Show current profile
    Show {
        /// Output as JSON
        #[arg(long)]
        json: bool,
    },
    /// Edit profile fields
    Edit {
        /// Resume text (replaces existing)
        #[arg(long)]
        resume: Option<String>,
        /// Skills as comma-separated list (replaces existing)
        #[arg(long)]
        skills: Option<String>,
        /// Tone: formal, casual, or startup
        #[arg(long)]
        tone: Option<String>,
    },
}
