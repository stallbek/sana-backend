ALTER TABLE product
    ADD COLUMN deleted_at TIMESTAMP(6);

DO $$
DECLARE
    legacy_constraint record;
BEGIN
    FOR legacy_constraint IN
        SELECT constraint_record.conname
        FROM pg_constraint constraint_record
        JOIN pg_class product_table
          ON product_table.oid = constraint_record.conrelid
        JOIN pg_namespace schema_record
          ON schema_record.oid = product_table.relnamespace
        CROSS JOIN LATERAL (
            SELECT array_agg(attribute_record.attname::text ORDER BY attribute_record.attnum) AS column_names
            FROM unnest(constraint_record.conkey) AS constraint_key(attnum)
            JOIN pg_attribute attribute_record
              ON attribute_record.attrelid = product_table.oid
             AND attribute_record.attnum = constraint_key.attnum
        ) constraint_columns
        WHERE schema_record.nspname = current_schema()
          AND product_table.relname = 'product'
          AND constraint_record.contype = 'u'
          AND (
              constraint_columns.column_names = ARRAY['name']::text[]
              OR constraint_columns.column_names = ARRAY['user_id', 'name']::text[]
          )
    LOOP
        EXECUTE format(
            'ALTER TABLE product DROP CONSTRAINT IF EXISTS %I',
            legacy_constraint.conname
        );
    END LOOP;
END $$;

ALTER TABLE product
    DROP CONSTRAINT IF EXISTS uk_product_name;

ALTER TABLE product
    DROP CONSTRAINT IF EXISTS product_name_key;

ALTER TABLE product
    DROP CONSTRAINT IF EXISTS uk_product_user_name;

DROP INDEX IF EXISTS idx_product_user_name;
DROP INDEX IF EXISTS idx_product_user_name_ci;
DROP INDEX IF EXISTS uk_product_user_name;
DROP INDEX IF EXISTS uk_product_user_lower_name;

CREATE UNIQUE INDEX uk_product_user_active_name_ci
    ON product (user_id, lower(name))
    WHERE deleted_at IS NULL;
