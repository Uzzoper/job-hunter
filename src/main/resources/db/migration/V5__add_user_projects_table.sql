-- V5: Create user_projects table for dynamic project management
CREATE TABLE user_projects (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    tech_stack  VARCHAR(500) NOT NULL
);

CREATE INDEX idx_user_projects_user_id ON user_projects(user_id);
