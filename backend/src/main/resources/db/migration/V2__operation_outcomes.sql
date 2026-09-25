ALTER TABLE generation_costs
    ADD COLUMN outcome varchar(20) NOT NULL DEFAULT 'SUCCEEDED'
        CHECK (outcome IN ('STARTED', 'SUCCEEDED', 'FAILED'));
