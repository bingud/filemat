-- SQLITE SCHEMA

CREATE TABLE IF NOT EXISTS users (
    user_id TEXT PRIMARY KEY,
    email TEXT NOT NULL,
    username TEXT NOT NULL,
    password TEXT NOT NULL,
    mfa_totp_secret TEXT,
    mfa_totp_status INTEGER NOT NULL,
    mfa_totp_codes TEXT,
    created_date INTEGER NOT NULL,
    last_login_date INTEGER,
    is_banned INTEGER NOT NULL
) STRICT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username ON users(username);


CREATE TABLE IF NOT EXISTS role (
    role_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    created_date INTEGER NOT NULL,
    permissions TEXT NOT NULL
) STRICT;

CREATE TABLE IF NOT EXISTS permissions (
    permission_id TEXT PRIMARY KEY,
    permission_type INTEGER NOT NULL,
    entity_id TEXT NOT NULL,
    user_id TEXT,
    role_id TEXT,
    permissions TEXT NOT NULL,
    created_date INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES role(role_id) ON DELETE CASCADE,
    CHECK (user_id IS NOT NULL OR role_id IS NOT NULL)
) STRICT;
CREATE INDEX IF NOT EXISTS idx_permissions_entity_id ON permissions(entity_id);
CREATE INDEX IF NOT EXISTS idx_permissions_user_id ON permissions(user_id);
CREATE INDEX IF NOT EXISTS idx_permissions_role_id ON permissions(role_id);


CREATE TABLE IF NOT EXISTS files (
    entity_id TEXT PRIMARY KEY,
    path TEXT,
    inode INTEGER,
    is_filesystem_supported INTEGER NOT NULL,
    owner_user_id TEXT,
    FOREIGN KEY (owner_user_id) REFERENCES users(user_id) ON DELETE CASCADE
) STRICT;
CREATE INDEX IF NOT EXISTS idx_files_path ON files(path);
CREATE INDEX IF NOT EXISTS idx_files_inode ON files(inode);

CREATE TABLE IF NOT EXISTS auth_token (
    auth_token TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    created_date INTEGER NOT NULL,
    user_agent TEXT NOT NULL,
    max_age INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) STRICT;
CREATE INDEX IF NOT EXISTS idx_auth_token_user_id ON auth_token(user_id);


CREATE TABLE IF NOT EXISTS log (
    log_id INTEGER PRIMARY KEY,
    level INTEGER NOT NULL,
    type INTEGER NOT NULL,
    action INTEGER NOT NULL,
    created_date INTEGER NOT NULL,
    description TEXT NOT NULL,
    message TEXT NOT NULL,
    initiator_user_id TEXT,
    initiator_ip TEXT,
    target_id TEXT,
    metadata TEXT,
    FOREIGN KEY (initiator_user_id) REFERENCES users(user_id) ON DELETE CASCADE
) STRICT;
CREATE INDEX IF NOT EXISTS idx_log_initiator_user_id ON log(initiator_user_id);


CREATE TABLE IF NOT EXISTS settings (
    name TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    created_date INTEGER NOT NULL
) STRICT;

CREATE TABLE IF NOT EXISTS shared_files (
    share_id TEXT PRIMARY KEY,
    file_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    created_date INTEGER NOT NULL,
    max_age INTEGER NOT NULL,
    is_password INTEGER NOT NULL,
    password TEXT,
    FOREIGN KEY (file_id) REFERENCES files(entity_id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) STRICT;
CREATE INDEX IF NOT EXISTS idx_shared_files_file_id ON shared_files(file_id);
CREATE INDEX IF NOT EXISTS idx_shared_files_user_id ON shared_files(user_id);

CREATE TABLE IF NOT EXISTS user_roles (
    role_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    created_date INTEGER NOT NULL,
    PRIMARY KEY (role_id, user_id),
    FOREIGN KEY (role_id) REFERENCES role(role_id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) STRICT;
CREATE INDEX IF NOT EXISTS idx_user_roles_role_id ON user_roles(role_id);
CREATE INDEX IF NOT EXISTS idx_user_roles_user_id ON user_roles(user_id);

CREATE TABLE IF NOT EXISTS folder_visibility (
    path TEXT PRIMARY KEY,
    is_exposed INTEGER NOT NULL,
    created_date INTEGER NOT NULL
) STRICT;
