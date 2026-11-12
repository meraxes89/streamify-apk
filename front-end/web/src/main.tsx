/**
 * Streamify Web — application entry point (P0 proof phase).
 *
 * Mounts the proof-phase shell into the #root container declared in
 * index.html. StrictMode keeps development-time double-render checks on
 * so the W0.4 engine wiring inherits the discipline for free.
 */

import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";
import App from "./App";

const container = document.getElementById("root");
if (!container) {
  throw new Error("Streamify Web: #root container missing from index.html");
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
