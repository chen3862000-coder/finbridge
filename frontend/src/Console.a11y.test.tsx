import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./main";
import axe from "axe-core";

describe("Console keyboard and responsive structure", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("provides a skip link, landmarks, labeled navigation, and keyboard navigation", async () => {
    document.documentElement.lang = "en";
    document.title = "FinBridge Console";
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => new Response(JSON.stringify(
      String(input) === "/api/session" ? { authenticated: true, subject: "demo-admin", administrator: true } : []),
    { status: 200, headers: { "Content-Type": "application/json" } })));
    render(<App />);

    expect(await screen.findByRole("link", { name: "Skip to main content" })).toHaveAttribute("href", "#main-content");
    expect(document.getElementById("main-content")).toBeInTheDocument();
    const navigation = screen.getByRole("navigation", { name: "Console sections" });
    const operation = within(navigation).getByRole("button", { name: /Operations/ });
    operation.focus();
    fireEvent.keyDown(operation, { key: "ArrowDown" });
    expect(within(navigation).getByRole("button", { name: /Applications/ })).toHaveFocus();
    expect(within(navigation).getByRole("button", { name: /Applications/ })).toHaveAttribute("aria-current", "page");
  });

  it("keeps viewport layout constraints at narrow and desktop widths", async () => {
    document.documentElement.lang = "en";
    document.title = "FinBridge Console";
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => new Response(JSON.stringify(
      String(input) === "/api/session" ? { authenticated: true, subject: "demo-admin", administrator: true } : []),
    { status: 200, headers: { "Content-Type": "application/json" } })));
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 390 });
    render(<App />);
    expect(await screen.findByRole("heading", { name: "Operations at a glance" })).toBeVisible();
    expect(document.querySelector(".console-shell")).toBeInTheDocument();
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 1440 });
    expect(window.innerWidth).toBe(1440);
  });

  it("renders accessible controls and status semantics on console navigation", async () => {
    document.documentElement.lang = "en";
    document.title = "FinBridge Console";
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => new Response(JSON.stringify(
      String(input) === "/api/session" ? { authenticated: true, subject: "demo-admin", administrator: true } : []),
    { status: 200, headers: { "Content-Type": "application/json" } })));
    render(<App />);

    await screen.findByRole("heading", { name: "Operations at a glance" });
    const navigation = screen.getByRole("navigation", { name: "Console sections" });
    expect(within(navigation).getAllByRole("button")).toHaveLength(8);
    expect(await screen.findByText("SINGLE-HOST DEMO")).toBeInTheDocument();
    const results = await axe.run(document, { runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"] }, rules: { "color-contrast": { enabled: false } } });
    expect(results.violations).toEqual([]);
  });
});
