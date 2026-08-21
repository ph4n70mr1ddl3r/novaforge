-- PHASE-2 §9: app-defined roles ride the same assignment table scoped "app.role"
-- (App PascalCase + role camelCase); the platform set stays the fixed three.

ALTER TABLE platform.role_assignments DROP CONSTRAINT role_assignments_role_check;
ALTER TABLE platform.role_assignments ADD CONSTRAINT role_assignments_role_check CHECK (
  role IN ('admin', 'builder', 'user')
  OR role ~ '^[A-Z][A-Za-z0-9]*\.[a-z][A-Za-z0-9]*$'
);
