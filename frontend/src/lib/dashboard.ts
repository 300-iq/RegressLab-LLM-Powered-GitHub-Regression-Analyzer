import { cache } from "react";
import { loadDashboard } from "./api";
// Deduplicate within a server render, never across requests.
export const getDashboard = cache(loadDashboard);
