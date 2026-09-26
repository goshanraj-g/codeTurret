import type { Request, Response } from "express";

const ALLOWED_HOSTS = new Set(["shop.example.com"]);

export function safeRedirect(res: Response, path: string) {
  const url = new URL(path, "https://shop.example.com");
  if (!ALLOWED_HOSTS.has(url.host)) return res.redirect("/");
  return res.redirect(url.pathname + url.search);
}

export function followNext(req: Request, res: Response) {
  const next = String(req.query.next || "/");
  return res.redirect(next);
}
