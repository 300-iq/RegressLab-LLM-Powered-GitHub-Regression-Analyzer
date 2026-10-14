import type { Metadata } from "next";
import Link from "next/link";
import { Navigation } from "@/components/navigation";
import { Icon } from "@/components/icon";
import { getDashboard } from "@/lib/dashboard";
import "./globals.css";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: { default: "RegressLab · Performance investigations", template: "%s · RegressLab" }, description: "Evidence-driven performance investigations for GitHub pull requests." };

export default async function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  const data = await getDashboard();
  return <html lang="en"><body><a href="#main" className="skip-link">Skip to content</a><div className="app-layout">
    <aside className="sidebar"><Link href="/" className="brand"><span className="brand-mark"><Icon name="flask" /></span>RegressLab<span className="brand-tag">LAB</span></Link>
      <div className="workspace"><span className="workspace-symbol">R</span><div><strong>Development workspace</strong><span>Single repository · Investigations</span></div></div>
      <p className="nav-caption">WORKSPACE</p><Navigation />
      <div className="sidebar-bottom"><div className="scope-note"><Icon name="investigate" /><strong>Evidence over assumptions.</strong><p>Raw measurements. Visible uncertainty. Reproducible comparisons.</p></div><a href="https://github.com/300-iq/RegressLab" target="_blank" rel="noreferrer" className="source-link"><Icon name="github" />Project on GitHub<span>↗</span></a><div className="sidebar-version"><span className={`status-dot ${data.kind === "ready" ? "online" : "offline"}`} />{data.kind === "ready" ? "API & database connected" : "API unavailable"}<span>v0.1</span></div></div>
    </aside>
    <div className="main-column"><div className="topbar"><span><span className="topbar-product">RegressLab</span><span className="topbar-divider">/</span> Performance workspace</span><span className="foundation-tag">Evidence-driven analysis</span></div><main id="main" className="main-content">{data.kind === "ready" && data.repositories.some(repo => repo.dataOrigin === "DEMO") && <div className="service-notice" role="status"><strong>DEMO · Development fixtures</strong><span>These persisted records and measurements are synthetic. GitHub provenance is unverified.</span></div>}{data.kind === "ready" && data.repositories.some(repo => repo.provenance === "GITHUB_METADATA") && <div className="service-notice" role="note"><strong>Public demonstration workload</strong><span>Real executed measurements. GitHub metadata checked; conditional evidence, no statistical significance.</span></div>}{children}</main><footer className="footer"><span>Built for careful performance engineering.</span><Link href="/methodology">Measurement policy <span>↗</span></Link></footer></div>
  </div></body></html>;
}
