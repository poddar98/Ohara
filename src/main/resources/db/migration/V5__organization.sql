-- Tenants (colleges and other institutions).
CREATE TABLE organization (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    slug VARCHAR(64) NOT NULL,
    type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NULL,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_organization_slug UNIQUE (slug),
    CONSTRAINT ck_organization_slug CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$'),
    CONSTRAINT ck_organization_type CHECK (type IN ('COLLEGE', 'CORPORATE', 'OTHER')),
    CONSTRAINT ck_organization_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED'))
);

-- A person's affiliation with an organization. One row per (organization, user); roles are a set.
-- Never deleted: removal is a status so history and reports survive.
CREATE TABLE organization_membership (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    user_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    external_ref VARCHAR(64) NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_org_membership_org FOREIGN KEY (organization_id) REFERENCES organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_org_membership_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT uq_org_membership UNIQUE (organization_id, user_id),
    CONSTRAINT ck_org_membership_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REMOVED'))
);
-- "My organizations" and per-request authorization.
CREATE INDEX ix_org_membership_user_active ON organization_membership (user_id) WHERE status = 'ACTIVE';
-- College roll numbers are unique within an organization.
CREATE UNIQUE INDEX uq_org_membership_external_ref
    ON organization_membership (organization_id, external_ref) WHERE external_ref IS NOT NULL;

CREATE TABLE organization_membership_role (
    membership_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    CONSTRAINT pk_org_membership_role PRIMARY KEY (membership_id, role),
    CONSTRAINT fk_org_membership_role_membership
        FOREIGN KEY (membership_id) REFERENCES organization_membership (id) ON DELETE CASCADE,
    CONSTRAINT ck_org_membership_role CHECK (role IN ('ORG_ADMIN', 'INSTRUCTOR', 'LEARNER'))
);

CREATE TABLE cohort (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    name VARCHAR(200) NOT NULL,
    code VARCHAR(64) NOT NULL,
    starts_on DATE NULL,
    ends_on DATE NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PLANNED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NULL,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_cohort_org FOREIGN KEY (organization_id) REFERENCES organization (id) ON DELETE RESTRICT,
    CONSTRAINT uq_cohort_code UNIQUE (organization_id, code),
    -- Target of the composite FK that keeps cohort memberships inside the cohort's organization.
    CONSTRAINT uq_cohort_id_org UNIQUE (id, organization_id),
    CONSTRAINT ck_cohort_dates CHECK (ends_on IS NULL OR starts_on IS NULL OR ends_on >= starts_on),
    CONSTRAINT ck_cohort_status CHECK (status IN ('PLANNED', 'ACTIVE', 'COMPLETED', 'ARCHIVED'))
);
CREATE INDEX ix_cohort_org_status ON cohort (organization_id, status);

-- Learner placement in a cohort. Leaving sets left_at; rows are kept for history.
CREATE TABLE cohort_membership (
    id UUID PRIMARY KEY,
    cohort_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    user_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at TIMESTAMPTZ NULL,
    transferred_to_cohort_id UUID NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_cohort_membership_cohort
        FOREIGN KEY (cohort_id, organization_id) REFERENCES cohort (id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_cohort_membership_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cohort_membership_transfer FOREIGN KEY (transferred_to_cohort_id) REFERENCES cohort (id),
    CONSTRAINT ck_cohort_membership_status CHECK (status IN ('ACTIVE', 'TRANSFERRED', 'REMOVED', 'COMPLETED')),
    CONSTRAINT ck_cohort_membership_left CHECK ((status = 'ACTIVE') = (left_at IS NULL))
);
-- At most one open placement per learner per cohort.
CREATE UNIQUE INDEX uq_cohort_membership_open ON cohort_membership (cohort_id, user_id) WHERE left_at IS NULL;
CREATE INDEX ix_cohort_membership_user_open ON cohort_membership (user_id) WHERE left_at IS NULL;
