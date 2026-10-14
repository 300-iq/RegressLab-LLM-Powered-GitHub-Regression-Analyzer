import Link from "next/link";
import type { DashboardData } from "@/lib/api";
export function ServiceNotice({ data }: { data: DashboardData }) {
  if (data.kind === "ready") return null;
  return <div className="service-notice" role="status"><strong>API unavailable</strong><span>Stored results cannot be loaded. Start PostgreSQL and the backend, then refresh.</span><Link href="/pipeline">Setup guide →</Link></div>;
}
