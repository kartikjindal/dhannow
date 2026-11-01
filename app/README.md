# DhanNow app

The Flutter Android borrower app (`com.dhannow.app`, minSdk 24). The plan is in `docs/PLAN.md` Phase 11; the screens are in `docs/wireframes/`.

- `lib/core/theme/`: tokens and type, copied value for value from `docs/wireframes/ui-kit.html`.
- `lib/core/widgets/`: the `Dn*` kit. Every screen is built from these.
- `lib/core/api/`: `Api` (what screens call), `HttpApi` + `ApiClient` (the backend: envelope, error codes, token refresh, Idempotency-Key) and `DemoApi` (in-memory, for screenshots and tests).
- `lib/core/router.dart`: routes (paths match the wireframe boards) and the Home / Repay / Loans / Profile tab shell.
- `lib/features/`: one folder per journey row (start, onboarding, offer, dashboard, money, account, edge).
- `lib/dev/`: `/demo` jumps the demo server to any journey state; `/kit` is the widget gallery.

Builds talk to the backend by default. Dart defines:

| Define | Default | Use |
|---|---|---|
| `API_BASE` | `https://api.dhannow.com` | Backend base URL (staging: pass the staging host) |
| `DEMO` | `false` | `true` runs the in-memory demo server and shows the demo controls |

```sh
flutter run --dart-define=DEMO=true            # demo server, every journey state from /demo
flutter analyze
flutter test                                   # includes 320/412dp × 1.0/1.3/2.0 layout checks (CC-58)
flutter build apk --release --split-per-abi    # budget: under 12 MB per ABI
```
