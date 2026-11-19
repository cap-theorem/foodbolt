export type Level = "INFO" | "WARN" | "ERROR";

export function log(level: Level, msg: string, fields: Record<string, unknown> = {}): void {
  const parts = [new Date().toISOString(), level, msg];
  for (const [key, value] of Object.entries(fields)) {
    if (value === undefined || value === null || value === "") continue;
    parts.push(`${key}=${formatValue(value)}`);
  }
  process.stdout.write(parts.join(" ") + "\n");
}

function formatValue(value: unknown): string {
  const text = typeof value === "string" ? value : typeof value === "number" || typeof value === "boolean" ? String(value) : JSON.stringify(value);
  if (text === "" || /[\s"=]/.test(text)) {
    return `"${text.replaceAll("\\", "\\\\").replaceAll('"', '\\"')}"`;
  }
  return text;
}
