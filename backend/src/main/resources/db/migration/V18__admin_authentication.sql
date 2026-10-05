CREATE TABLE admin_users (
    id uuid PRIMARY KEY,
    username varchar(100) NOT NULL UNIQUE,
    email varchar(255),
    password_hash varchar(255) NOT NULL,
    role varchar(50) NOT NULL DEFAULT 'ROLE_ADMIN',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_users_username_lower ON admin_users (LOWER(username));
CREATE INDEX idx_admin_users_email_lower ON admin_users (LOWER(email));
