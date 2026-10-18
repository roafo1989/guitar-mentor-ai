-- DEV-01: establish a real, versioned PostgreSQL schema without
-- prematurely modelling student/lesson/exercise domain tables.
CREATE TABLE app_metadata (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value VARCHAR(500) NOT NULL
);
