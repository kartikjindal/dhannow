"""Give the demo staff rows (V10/V42/V55, `@navix.example`) a password, for the CI e2e stack only.

  python scripts/ci/seed_e2e_staff.py "$E2E_STAFF_PASSWORD" | docker compose -f docker-compose.ci.yml exec -T db psql -U navix -d navix

No password is seeded by any migration (PLAN Phase 0/9). This runs against the throwaway CI
database and never against staging or prod; those rows are deactivated before go-live
(docs/GO_LIVE.md). Needs `pip install bcrypt`.
"""
import sys

import bcrypt


def main(argv):
    if len(argv) < 2 or len(argv[1]) < 12:
        print("usage: seed_e2e_staff.py <password of 12+ characters>", file=sys.stderr)
        return 2
    hashed = bcrypt.hashpw(argv[1].encode(), bcrypt.gensalt(rounds=10)).decode()
    # Spring's BCryptPasswordEncoder reads $2a$/$2b$/$2y$.
    print("update staff_user set password_hash = '%s', status = 'ACTIVE' "
          "where email like '%%@navix.example';" % hashed.replace("'", "''"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
