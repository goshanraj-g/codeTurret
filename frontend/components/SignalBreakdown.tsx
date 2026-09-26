"use client";

import { useState } from "react";
import { ChevronDown, Sparkles } from "lucide-react";
import { cn } from "@/lib/utils";

export type FindingSource = "LLM" | "STATIC_LLM" | "STATIC" | "";

const SOURCE_META: Record<Exclude<FindingSource, "">, { label: string; hint: string; className: string }> = {
    STATIC_LLM: {
        label: "Semgrep + AI",
        hint: "Flagged by Semgrep and confirmed by the AI reviewer",
        className: "text-sky-300 border-sky-400/40 bg-sky-400/10",
    },
    LLM: {
        label: "AI",
        hint: "Found by the AI reviewer in code the ranking model selected",
        className: "text-purple-300 border-purple-400/40 bg-purple-400/10",
    },
    STATIC: {
        label: "Semgrep only",
        hint: "Semgrep result the AI reviewer could not confirm (reviewer unavailable)",
        className: "text-white/60 border-white/20 bg-white/5",
    },
};

export function SourceBadge({ source }: { source: FindingSource }) {
    if (!source) return null;
    const meta = SOURCE_META[source];
    return (
        <span title={meta.hint} className={cn("rounded-md border px-2 py-1 text-xs font-semibold", meta.className)}>
            {meta.label}
        </span>
    );
}

const SIGNALS: { key: string; label: string; explain: string }[] = [
    { key: "ml", label: "ML model", explain: "Probability from the trained vulnerability classifier" },
    { key: "static", label: "Static analysis", explain: "Severity of the strongest Semgrep hit in this code" },
    { key: "reachability", label: "Reachability", explain: "How close this code is to a route handler that receives user input" },
    { key: "structure", label: "Dangerous call", explain: "Calls a known sink such as exec, a raw SQL query, or a file read" },
    { key: "git", label: "Git history", explain: "Recently changed often, or touched by security-related commits" },
];

export function parseSignals(raw: string | null | undefined): Record<string, number> {
    if (!raw) return {};
    try {
        const parsed = JSON.parse(raw);
        return parsed && typeof parsed === "object" ? parsed : {};
    } catch {
        return {};
    }
}

/** Collapsible panel explaining why the engine chose to analyse the code behind a finding. */
export function SignalBreakdown({ signals }: { signals: Record<string, number> }) {
    const [open, setOpen] = useState(false);
    if (Object.keys(signals).length === 0) return null;

    return (
        <div className="rounded-lg border border-white/10 bg-white/[0.02]">
            <button
                onClick={() => setOpen(o => !o)}
                aria-expanded={open}
                className="flex w-full items-center justify-between px-3 py-2 text-xs text-muted-foreground hover:text-white transition-colors"
            >
                <span className="flex items-center gap-2">
                    <Sparkles className="h-3 w-3" /> Why was this code analysed?
                </span>
                <ChevronDown className={cn("h-3 w-3 transition-transform", open && "rotate-180")} />
            </button>

            {open && (
                <div className="space-y-2 border-t border-white/5 px-3 py-3">
                    {SIGNALS.map(({ key, label, explain }) => {
                        const value = Math.max(0, Math.min(1, signals[key] ?? 0));
                        return (
                            <div key={key} title={explain} className="grid grid-cols-[8rem_1fr_2.5rem] items-center gap-3 text-xs">
                                <span className="text-gray-400">{label}</span>
                                <div className="h-1.5 overflow-hidden rounded-full bg-white/10">
                                    <div className="h-full rounded-full bg-purple-400/80" style={{ width: `${value * 100}%` }} />
                                </div>
                                <span className="text-right font-mono text-gray-400">{value.toFixed(2)}</span>
                            </div>
                        );
                    })}
                    <p className="pt-1 text-[11px] leading-relaxed text-white/40">
                        The engine ranks every function by these signals and sends only the highest-ranked code to the AI
                        reviewer, within a fixed line budget.
                    </p>
                </div>
            )}
        </div>
    );
}
