# Wireframes

One file per app screen (`*.dc.html`) plus `canvas.json`, which holds the board positions, titles, row headings and sticky notes.

- **Review copy:** https://claude.ai/artifact/9mpCW1pqqzADmTcoVXNCqJ (private; ask the owner for access).
- **Rows:** S start and login · A sign-up (12 steps) · B loan offer (11 steps + KFS) · C dashboard, repay, loans, rollover, EMI, closure · D account (profile, settings, support, trust, complaints, loan account, devices, delete account) · E edge cases and the two website pages (E15 return-to-app, E16 download) · F specs, UI kit, first-time walkthrough, milestone motion.
- **Theme:** app_navix UI kit with the cobalt accent. Board F2 lists every token.
- **Links:** every `<a href="X.dc.html">` is a prototype link between screens.
- **Local preview:** the boards expect the canvas runtime (`support.js`), so they render in the artifact. To preview one locally, strip the `<helmet>` tags and the `text/x-dc` script and open the file in a browser.
