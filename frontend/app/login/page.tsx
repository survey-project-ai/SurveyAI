"use client";

import { useEffect, useState, useSyncExternalStore, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

// The seeded owner account is filled in and shown only in `next dev`; a deployed build never shows it.
const isLocalPreview = process.env.NODE_ENV === "development";
const LOCAL_PREVIEW_EMAIL = "owner@acme-research.test";
const LOCAL_PREVIEW_PASSWORD = "change-me-123";

function subscribeToNothing(): () => void {
  return () => {};
}

// Only a path inside the app, so a crafted link cannot send the user elsewhere after signing in.
function resolveNextPage(search: string): string {
  const next = new URLSearchParams(search).get("next");
  return next && next.startsWith("/") && !next.startsWith("//") && !next.startsWith("/login") ? next : "/";
}

export default function LoginPage() {
  const router = useRouter();
  const { status, login } = useAuth();
  // Read from the URL on the client only; the prerendered page has no query.
  const search = useSyncExternalStore(subscribeToNothing, () => window.location.search, () => "");
  const sessionExpired = new URLSearchParams(search).get("expired") === "1";
  const nextPage = resolveNextPage(search);
  const [email, setEmail] = useState(isLocalPreview ? LOCAL_PREVIEW_EMAIL : "");
  const [password, setPassword] = useState(isLocalPreview ? LOCAL_PREVIEW_PASSWORD : "");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    if (status === "authenticated") {
      router.replace(nextPage);
    }
  }, [nextPage, router, status]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrorMessage(null);
    setIsSubmitting(true);

    try {
      await login({
        email: email.trim(),
        password,
      });
      router.replace(nextPage);
    } catch (error) {
      setErrorMessage(error instanceof Error ? error.message : "Şu anda oturum açılamıyor.");
      setIsSubmitting(false);
    }
  }

  return (
    <main className="auth-screen auth-screen-minimal">
      <section className="auth-layout auth-layout-minimal">
        <section className="auth-card panel-card auth-card-minimal">
          <div className="auth-system-meta">
            <span className="eyebrow">Erişim</span>
            <div className="auth-system-badges">
              {isLocalPreview ? <span className="chip">Yerel oturum</span> : null}
              <span className="chip">Şirket bağlamlı</span>
            </div>
          </div>

          <div className="section-copy">
            <h2>Oturum aç</h2>
            <p>Çalışma alanını açmak için hesabınızla giriş yapın.</p>
          </div>

          <form className="auth-form" onSubmit={handleSubmit}>
            <label className="builder-field">
              <span>E-posta</span>
              <input
                type="email"
                autoComplete="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                placeholder="ornek@sirket.com"
                disabled={isSubmitting || status === "loading"}
              />
            </label>

            <label className="builder-field">
              <span>Şifre</span>
              <input
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                placeholder="Şifreniz"
                disabled={isSubmitting || status === "loading"}
              />
            </label>

            {sessionExpired && !errorMessage ? (
              <div className="operation-inline-message is-accent compact">
                <strong>Oturumunuz sona erdi</strong>
                <span>Devam etmek için yeniden giriş yapın; kaldığınız sayfaya döneceksiniz.</span>
              </div>
            ) : null}

            {errorMessage ? (
              <div className="operation-inline-message is-danger compact">
                <strong>Oturum açılamadı</strong>
                <span>{errorMessage}</span>
              </div>
            ) : null}

            <button type="submit" className="button-primary auth-submit" disabled={isSubmitting || status === "loading"}>
              {isSubmitting ? "Oturum açılıyor..." : "Çalışma alanını aç"}
            </button>
          </form>

          {isLocalPreview ? (
            <div className="auth-helper auth-helper-minimal">
              <strong>Yerel önizleme hesabı</strong>
              <p>
                {LOCAL_PREVIEW_EMAIL} / {LOCAL_PREVIEW_PASSWORD}
              </p>
            </div>
          ) : null}
        </section>
      </section>
    </main>
  );
}
