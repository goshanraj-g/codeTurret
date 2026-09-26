import React from "react";
import { formatPrice } from "../lib/format";

export function Price({ cents, currency }: { cents: number; currency?: string }) {
  return <span className="price">{formatPrice(cents, currency)}</span>;
}
