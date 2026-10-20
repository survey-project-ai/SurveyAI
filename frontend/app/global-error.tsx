"use client";

// Replaces the whole document when the root layout itself fails, so it cannot use the app's providers or styles.
export default function GlobalError({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <html lang="tr">
      <body style={{ margin: 0, fontFamily: "system-ui, sans-serif", background: "#f4f6f5", color: "#1d2623" }}>
        <main style={{ maxWidth: 520, margin: "15vh auto", padding: "0 16px" }}>
          <h1 style={{ fontSize: "1.4rem" }}>SurveyAI şu anda açılamadı</h1>
          <p style={{ lineHeight: 1.5 }}>Beklenmeyen bir hata oluştu. Sayfayı yeniden yüklemeyi deneyin.</p>
          <button
            type="button"
            onClick={() => reset()}
            style={{ padding: "8px 16px", border: 0, borderRadius: 10, background: "#4f9d8b", color: "#fff", cursor: "pointer" }}
          >
            Tekrar dene
          </button>
        </main>
      </body>
    </html>
  );
}
