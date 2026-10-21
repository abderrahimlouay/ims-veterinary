/**
 * The administrator bootstrapped by the e2e backend (ADR-040). Test-only values for a throw-away database;
 * marked for the secret scanner on purpose.
 */
export const ADMIN = {
  username: 'e2e.admin',
  bootstrapPassword: 'E2e-bootstrap-admin-2026', // gitleaks:allow
  newPassword: 'E2e-chosen-admin-pass-2026', // gitleaks:allow
}
