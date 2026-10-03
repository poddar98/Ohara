CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP NULL
);

CREATE TABLE user_profile (
    user_id UUID PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    CONSTRAINT fk_user_profile_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE user_role (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    name VARCHAR(64) NOT NULL,
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_user_role UNIQUE (user_id, name)
);

CREATE TABLE auth_identity (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    provider VARCHAR(64) NOT NULL,
    identifier VARCHAR(255) NOT NULL,
    verified_at TIMESTAMP NULL,
    CONSTRAINT fk_auth_identity_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_auth_identity UNIQUE (provider, identifier)
);

CREATE TABLE password_credential (
    id UUID PRIMARY KEY,
    auth_identity_id UUID NOT NULL UNIQUE,
    encoded_password VARCHAR(255) NOT NULL,
    password_changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_password_credential_auth_identity FOREIGN KEY (auth_identity_id) REFERENCES auth_identity (id) ON DELETE CASCADE
);

CREATE TABLE refresh_token (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
