-- PHASE-2 §8: optimistic locking on page definitions — the builder's 409 →
-- rebase prompt needs a version to check against (the same discipline as Data
-- Runtime record locking). Suites/entities keep last-write-wins drafts; pages
-- carry concurrent-edit risk (two builders customizing the same page), so they
-- pin first.
ALTER TABLE md_pages ADD COLUMN revision integer NOT NULL DEFAULT 1;

-- Concurrent-edit detection for md_pages: bump on every update.
CREATE OR REPLACE FUNCTION nf_pages_bump_revision() RETURNS trigger AS $$
BEGIN
  NEW.revision := OLD.revision + 1;
  NEW.updated_at := now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER md_pages_revision BEFORE UPDATE ON md_pages
  FOR EACH ROW EXECUTE FUNCTION nf_pages_bump_revision();
