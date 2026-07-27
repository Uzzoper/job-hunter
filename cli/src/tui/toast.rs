use std::time::{Duration, Instant};

#[derive(Debug, Clone)]
pub struct Toast {
    pub message: String,
    pub is_error: bool,
    created_at: Instant,
    duration: Duration,
}

impl Toast {
    pub fn new(message: String) -> Self {
        Self {
            message,
            is_error: false,
            created_at: Instant::now(),
            duration: Duration::from_secs(3),
        }
    }

    pub fn error(message: String) -> Self {
        Self {
            message,
            is_error: true,
            created_at: Instant::now(),
            duration: Duration::from_secs(5),
        }
    }

    pub fn is_expired(&self) -> bool {
        self.created_at.elapsed() > self.duration
    }
}
