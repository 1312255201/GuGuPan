import { reactive } from "vue";

export interface FileItem {
  id: string;
  name: string;
  size: number;
  inboxToken: string | null;
  expiresAt: number;
  createdAt: number;
}
export interface LinkItem {
  token: string;
  code: string;
  kind: "SHARE" | "INBOX";
  title: string;
  note: string;
  passwordProtected: boolean;
  expiresAt: number;
  revoked: boolean;
  quotaBytes: number;
  maxFiles: number;
  maxFileBytes: number;
  createdAt: number;
}
export interface Settings {
  username: string;
  quotaBytes: number;
  maxFileBytes: number;
  retentionDays: number;
}
export interface Stats {
  usedBytes: number;
  reservedBytes: number;
  fileCount: number;
  settings: Settings;
}
export interface PublicLink {
  link: LinkItem;
  locked: boolean;
  files: FileItem[];
  usage?: { usedBytes: number; fileCount: number };
  systemMaxFileBytes?: number;
}

export const auth = reactive({
  authenticated: false,
  username: "",
  csrfToken: "",
});
export const notification = reactive({ message: "", kind: "success" });
let notificationTimer: ReturnType<typeof setTimeout>;
export function toast(message: string, kind = "success") {
  clearTimeout(notificationTimer);
  notification.message = message;
  notification.kind = kind;
  notificationTimer = setTimeout(() => {
    notification.message = "";
  }, 4500);
}
export async function refreshSession() {
  const response = await fetch("/api/auth/session", {
    credentials: "same-origin",
    cache: "no-store",
  });
  if (!response.ok) throw new Error("连接失败，请稍后重试");
  Object.assign(auth, await response.json());
  return auth;
}
export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const headers: Record<string, string> = {};
  const options: RequestInit = {
    method,
    headers,
    credentials: "same-origin",
    cache: "no-store",
  };
  if (method !== "GET") {
    await refreshSession();
    headers["X-CSRF-TOKEN"] = auth.csrfToken;
  }
  if (body instanceof URLSearchParams) {
    headers["Content-Type"] = "application/x-www-form-urlencoded";
    options.body = body;
  } else if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  const response = await fetch(`/api${path}`, options);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401) auth.authenticated = false;
    throw new Error(data.message || "请求失败，请稍后重试");
  }
  return data as T;
}
export function message(error: unknown) {
  return error instanceof Error ? error.message : "操作失败，请稍后重试";
}
export function bytes(value: number) {
  if (value === 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const unit = Math.min(
    Math.floor(Math.log(Math.max(1, value)) / Math.log(1024)),
    units.length - 1,
  );
  return `${Number((value / 1024 ** unit).toFixed(unit > 1 ? 1 : 0))} ${units[unit]}`;
}
export function date(value: number) {
  return new Date(value).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}
export function expiry(value: number) {
  const days = Math.ceil((value - Date.now()) / 86400000);
  return days <= 0 ? "已过期" : days === 1 ? "1 天内到期" : `${days} 天后到期`;
}
export function linkUrl(link: LinkItem) {
  return `${location.origin}/${link.kind === "SHARE" ? "s" : "r"}/${link.token}`;
}
export async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text);
    toast("已复制到剪贴板");
  } catch {
    // Clipboard API is unavailable on HTTP hosts. A selectable text field remains usable.
    window.prompt("复制以下内容", text);
  }
}
