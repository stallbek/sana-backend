CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_logbook_user_timestamp_id
    ON logbook (user_id, timestamp DESC, id DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_logbook_user_action_timestamp_id
    ON logbook (user_id, action_type, timestamp DESC, id DESC)
    INCLUDE (product_id, amount, quantity);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_user_name_ci
    ON product (user_id, lower(name), id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_location_user_product_name_ci
    ON product_location (user_id, product_id, lower(location_name))
    INCLUDE (quantity);
