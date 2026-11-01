/** @type {import('next').NextConfig} */

// Content-Security-Policy for the staff console (PLAN Phase 10: the marketing site and the borrower web are
// gone, so Google Analytics and the Signzy liveness iframe are no longer allowed). External origins:
// (1) Amazon S3 (ap-south-1) — the browser uploads (PUT) and views (GET) documents, selfie frames, receipts
// and payment assets DIRECTLY via short-lived presigned URLs, so the S3 host is in connect-src, img-src and
// frame-src (PDF previews); (2) Cloudflare Turnstile on the password forms. `'unsafe-inline'` is required for
// script (Next's inline hydration bootstrap) and style (inline style= attrs). `data:`/`blob:` cover
// client-side jsPDF exports. `'unsafe-eval'` is added in DEV only (React Refresh/HMR).
const isDev = process.env.NODE_ENV !== "production";
// S3 (ap-south-1) — presigned direct browser<->S3 upload/view. Region-scoped: virtual-hosted
// (bucket.s3.…) covers any bucket/env; path-style (s3.…) is a fallback for that URL form.
const s3 = "https://*.s3.ap-south-1.amazonaws.com https://s3.ap-south-1.amazonaws.com";
// Cloudflare Turnstile: api.js is a script, the widget renders in a cross-origin iframe, and it posts the
// challenge result back over fetch. Miss any one and the widget fails silently.
const turnstile = "https://challenges.cloudflare.com";
const csp = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline' ${turnstile}${isDev ? " 'unsafe-eval'" : ""}`,
  "style-src 'self' 'unsafe-inline'",
  `img-src 'self' data: blob: ${s3}`,
  "media-src 'self' blob:",
  "font-src 'self'",
  `connect-src 'self' ${s3} ${turnstile}`,
  "worker-src 'self' blob:",
  `frame-src 'self' ${s3} ${turnstile}`,
  "frame-ancestors 'self'",
  "form-action 'self'",
  "base-uri 'self'",
  "object-src 'none'",
].join("; ");

const nextConfig = {
  reactStrictMode: true,
  // Baseline security headers (applied to all routes). HSTS ships with includeSubDomains but
  // WITHOUT `preload`: preload (once submitted to hstspreload.org) forces every subdomain to
  // HTTPS near-permanently — add it only after auditing subdomains.
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "Content-Security-Policy", value: csp },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "SAMEORIGIN" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
          { key: "Strict-Transport-Security", value: "max-age=63072000; includeSubDomains" },
        ],
      },
    ];
  },
  // staff.dhannow.com serves only the staff console (PLAN Phase 10): anything outside /staff (except the
  // BFF, Next assets and public files) lands on /staff, which routes to login or the dashboard.
  async redirects() {
    return [
      {
        source: "/:path((?!staff|api/|_next/|.*[.]).+)",
        destination: "/staff",
        permanent: false,
      },
    ];
  },
  // typedRoutes intentionally disabled: the app uses many dynamic, hash, and
  // data-driven hrefs (marketing anchors, staff case IDs, mock flows) where
  // compile-time route literals add friction without runtime benefit.
  // TODO: add rewrites() to proxy /api/* to BACKEND_BASE_URL (http://localhost:8080)
  // when the backend integration is wired up. Example:
  // async rewrites() {
  //   return [{ source: "/api/:path*", destination: `${process.env.BACKEND_BASE_URL}/:path*` }];
  // }
};

export default nextConfig;
