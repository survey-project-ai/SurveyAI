"use client";

import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { API_BASE_URL, SESSION_EXPIRED_EVENT } from "@/lib/api";

type ApiErrorResponse = {
  code?: string;
  message?: string;
  details?: string[];
};

export type AuthenticatedUser = {
  companyId: string;
  company: {
    id: string;
    name: string;
    slug: string;
    timezone: string | null;
    status: string;
    metadata: Record<string, unknown>;
  };
  user: {
    id: string;
    email: string;
    firstName: string | null;
    lastName: string | null;
    fullName: string;
    role: string;
    status: string;
    lastLoginAt: string | null;
  };
};

type LoginPayload = {
  email: string;
  password: string;
};

type AuthContextValue = {
  status: "loading" | "authenticated" | "unauthenticated";
  // True when the session ended while the app was open (an API call answered 401), not after a logout.
  sessionExpired: boolean;
  currentUser: AuthenticatedUser | null;
  login: (payload: LoginPayload) => Promise<AuthenticatedUser>;
  logout: () => Promise<void>;
  refreshSession: () => Promise<AuthenticatedUser | null>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

let authSnapshot: AuthenticatedUser | null = null;

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthContextValue["status"]>("loading");
  const [currentUser, setCurrentUser] = useState<AuthenticatedUser | null>(null);

  const [sessionExpired, setSessionExpired] = useState(false);

  useEffect(() => {
    void refreshSession();
  }, []);

  useEffect(() => {
    function handleSessionExpired() {
      authSnapshot = null;
      setCurrentUser(null);
      setSessionExpired(true);
      setStatus("unauthenticated");
    }

    window.addEventListener(SESSION_EXPIRED_EVENT, handleSessionExpired);
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, handleSessionExpired);
  }, []);

  async function refreshSession() {
    try {
      const user = await fetchCurrentUser();
      authSnapshot = user;
      setCurrentUser(user);
      setStatus("authenticated");
      return user;
    } catch {
      authSnapshot = null;
      setCurrentUser(null);
      setStatus("unauthenticated");
      return null;
    }
  }

  async function login(payload: LoginPayload) {
    const response = await fetch(`${API_BASE_URL}/api/v1/auth/login`, {
      method: "POST",
      credentials: "include",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      cache: "no-store",
      body: JSON.stringify(payload),
    });

    if (!response.ok) {
      throw new Error(await readApiError(response, "login"));
    }

    const user = (await response.json()) as AuthenticatedUser;
    authSnapshot = user;
    setCurrentUser(user);
    setSessionExpired(false);
    setStatus("authenticated");
    return user;
  }

  async function logout() {
    await fetch(`${API_BASE_URL}/api/v1/auth/logout`, {
      method: "POST",
      credentials: "include",
      headers: {
        Accept: "application/json",
      },
      cache: "no-store",
    });

    authSnapshot = null;
    setCurrentUser(null);
    setStatus("unauthenticated");
  }

  const value = useMemo<AuthContextValue>(() => ({
    status,
    sessionExpired,
    currentUser,
    login,
    logout,
    refreshSession,
  }), [currentUser, sessionExpired, status]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);

  if (!context) {
    throw new Error("useAuth must be used within AuthProvider.");
  }

  return context;
}

export function requireCompanyId(companyId?: string): string {
  const resolvedCompanyId = companyId ?? authSnapshot?.companyId;

  if (!resolvedCompanyId) {
    throw new Error("Authenticated company context is not available yet.");
  }

  return resolvedCompanyId;
}

export function requireCurrentUserId(userId?: string | null): string {
  const resolvedUserId = userId ?? authSnapshot?.user.id;

  if (!resolvedUserId) {
    throw new Error("Authenticated user context is not available yet.");
  }

  return resolvedUserId;
}

async function fetchCurrentUser(): Promise<AuthenticatedUser> {
  const response = await fetch(`${API_BASE_URL}/api/v1/auth/me`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json",
    },
    cache: "no-store",
  });

  if (!response.ok) {
    throw new Error(await readApiError(response, "current user"));
  }

  return (await response.json()) as AuthenticatedUser;
}

async function readApiError(response: Response, resourceName: string): Promise<string> {
  try {
    const payload = (await response.json()) as ApiErrorResponse;
    const details = payload.details?.filter(Boolean) ?? [];
    const fallback = `Failed to load ${resourceName} (${response.status})`;
    const message = payload.message?.trim() || fallback;
    return details.length > 0 ? `${message}: ${details.join(" ")}` : message;
  } catch {
    return `Failed to load ${resourceName} (${response.status})`;
  }
}
