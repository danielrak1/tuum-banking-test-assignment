-- json keeps the payload text exactly as the event mapper wrote it; jsonb would reorder keys and
-- rewrite whitespace, so the published bytes would differ from the serialised envelope (design.md §4).
ALTER TABLE outbox_event ALTER COLUMN payload TYPE json USING payload::json;
