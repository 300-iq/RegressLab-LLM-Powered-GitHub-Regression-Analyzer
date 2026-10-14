import type { CSSProperties } from "react";
const paths = {
  overview: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
  repository: "M4 4h14v16H4z M8 4v16 M18 7h3v13H7",
  investigate: "M10.5 3a7.5 7.5 0 1 0 0 15 7.5 7.5 0 0 0 0-15 M16 16l5 5 M7 11h2l2-4 2 7 2-3",
  trend: "M3 3v18h18 M6 15l5-5 4 3 6-8",
  pipeline: "M5 5h5v5H5z M14 14h5v5h-5z M10 7h6v7 M7 10v6h7",
  arrow: "M5 12h14 M14 7l5 5-5 5",
  github: "M8 20v-3c-4 1-4-2-6-2 M16 20v-4c0-1-.3-2-1-2 4-.5 6-2 6-5 0-1-.4-2-1-3 0-1 0-2-.5-3-2 0-3 1-3 1a12 12 0 0 0-7 0S8 3 6 3c-.5 1-.5 2-.5 3-1 1-1.5 2-1.5 3 0 3 2 4.5 6 5-.7.5-1 1-1 2v4",
  check: "M5 12l4 4L19 6",
  flask: "M9 3h6 M10 3v6L4 19c-.5 1 0 2 1 2h14c1 0 1.5-1 1-2L14 9V3 M7 15h10",
  book: "M3 4h7l2 2 2-2h7v15h-7l-2 2-2-2H3z M12 6v15",
};
export type IconName = keyof typeof paths;
export function Icon({ name, className = "", style }: { name: IconName; className?: string; style?: CSSProperties }) {
  return <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" className={className} style={style}><path d={paths[name]} /></svg>;
}
