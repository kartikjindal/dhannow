import Link from "next/link";
import Image from "next/image";
import { cn } from "@/lib/utils";
import { BRAND } from "@/lib/brand";

/** DHAN NOW wordmark + emblem (`/dhannow-mark.png`, cut from `docs/brand/dhannow-logo.jpeg`). */
export function Brand({
  href = "/",
  className,
  tag = "Staff Console",
  light = false,
}: {
  href?: string;
  className?: string;
  tag?: string;
  /** Light variant for dark surfaces (e.g. the staff sidebar). */
  light?: boolean;
}) {
  return (
    <Link href={href} className={cn("brand", light && "brand--light", className)} aria-label={`${BRAND.name} home`} style={{ textDecoration: "none" }}>
      <span className="brand-mark brand-mark--img">
        <Image src="/dhannow-mark.png" alt={BRAND.name} width={42} height={42} priority />
      </span>
      <span className="brand-text">
        <span className="brand-name">{BRAND.wordmark}</span>
        <span className="brand-tag">{tag}</span>
      </span>
    </Link>
  );
}
