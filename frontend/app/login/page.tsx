"use client";

import { useEffect, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

export default function LoginPage() {
  const router = useRouter();
  const { status, login } = useAuth();
  const [email, setEmail] = useState("owner@acme-research.test");
  const [password, setPassword] = useState("change-me-123");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    if (status === "authenticated") {
      router.replace("/");
    }
  }, [router, status]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrorMessage(null);
    setIsSubmitting(true);

    try {
      await login({
        email: email.trim(),
        password,
      });
      router.replace("/");
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
              <span className="chip">Yerel oturum</span>
              <span className="chip">Şirket bağlamlı</span>
            </div>
          </div>

          <div className="section-copy">
            <h2>Oturum aç</h2>
            <p>Çalışma alanını açmak için test hesabı ile kimlik doğrulayın.</p>
          </div>

          <form className="auth-form" onSubmit={handleSubmit}>
            <label className="builder-field">
              <span>E-posta</span>
              <input
                type="email"
                autoComplete="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                placeholder="owner@acme-research.test"
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
                placeholder="change-me-123"
                disabled={isSubmitting || status === "loading"}
              />
            </label>

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

          <div className="auth-helper auth-helper-minimal">
            <strong>Yerel önizleme hesabı</strong>
            <p>owner@acme-research.test / change-me-123</p>
          </div>
        </section>
      </section>
    </main>
  );
}
