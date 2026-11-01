import type { MetadataRoute } from "next";

/** The staff console is private: nothing on it is for crawlers. */
export default function robots(): MetadataRoute.Robots {
  return { rules: [{ userAgent: "*", disallow: "/" }] };
}
