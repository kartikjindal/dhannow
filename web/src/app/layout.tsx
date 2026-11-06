import type { Metadata } from "next";
import { Bebas_Neue, Inter } from "next/font/google";
import "../styles/theme.css";
import "./globals.css";
import { Providers } from "@/components/providers";
import { RouteProgress } from "@/components/app/route-progress";
import { BRAND } from "@/lib/brand";

/** Inter for everything; Bebas Neue (below) for page titles and big amounts (CLAUDE.md design system). */
const inter = Inter({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700", "800"],
  variable: "--font-inter",
  display: "block",
});

/**
 * Display face — condensed caps for page titles and headline figures only (theme.css
 * `--font-display`, tailwind `font-display`). Body copy and card titles stay Inter.
 */
const bebas = Bebas_Neue({
  subsets: ["latin"],
  weight: "400",
  variable: "--font-bebas",
  display: "block",
});

export const metadata: Metadata = {
  metadataBase: new URL(BRAND.staffUrl),
  title: `${BRAND.name} staff console`,
  description: `${BRAND.name} back office. Authorised staff only.`,
  robots: { index: false, follow: false },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en-IN"
      className={`${inter.variable} ${bebas.variable}`}
    >
      {/* suppressHydrationWarning: browser extensions (screenshot/zoom tools, etc.)
          mutate <body> attributes — e.g. style="zoom:1" — before React hydrates,
          producing a benign server/client attribute mismatch. This silences only
          that top-level attribute diff; it does not affect children. */}
      <body suppressHydrationWarning>
        <RouteProgress />
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
