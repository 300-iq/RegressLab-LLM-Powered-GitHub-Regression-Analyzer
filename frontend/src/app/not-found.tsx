import Link from "next/link";
export default function NotFound() {
  return <div className="panel content-panel"><p className="eyebrow">404</p><h1>Page not found</h1><p>This workspace page does not exist.</p><Link className="button button-secondary" href="/">Back to overview</Link></div>;
}
