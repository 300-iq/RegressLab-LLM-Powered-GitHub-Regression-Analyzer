"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Icon, type IconName } from "./icon";
const links: { href: string; label: string; icon: IconName }[] = [
  { href: "/", label: "Overview", icon: "overview" },
  { href: "/repositories", label: "Repositories", icon: "repository" },
  { href: "/investigations", label: "Investigations", icon: "investigate" },
  { href: "/trends", label: "Historical trends", icon: "trend" },
  { href: "/pipeline", label: "Benchmark pipeline", icon: "pipeline" },
  { href: "/methodology", label: "Methodology", icon: "book" },
];
export function Navigation() {
  const pathname = usePathname();
  const active = (href: string) =>
    href === "/"
      ? pathname === "/"
      : pathname === href ||
        pathname.startsWith(`${href}/`) ||
        (href === "/trends" && pathname.startsWith("/benchmarks/"));
  return (
    <nav aria-label="Main navigation" className="navigation">
      {links.map((link) => (
        <Link
          key={link.href}
          href={link.href}
          aria-current={active(link.href) ? "page" : undefined}
          className={active(link.href) ? "nav-link active" : "nav-link"}
        >
          <Icon name={link.icon} />
          <span>{link.label}</span>
        </Link>
      ))}
    </nav>
  );
}
