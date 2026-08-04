import type { Metadata } from "next";
import { headers } from "next/headers";
import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";
import "./globals.css";

export async function generateMetadata(): Promise<Metadata> {
  const requestHeaders = await headers();
  const host = requestHeaders.get("x-forwarded-host") ?? requestHeaders.get("host");
  const protocol =
    requestHeaders.get("x-forwarded-proto") ??
    (host?.startsWith("localhost") ? "http" : "https");
  const socialImage = host ? `${protocol}://${host}/og.png` : undefined;

  return {
    title: {
      default: "Cobbleventure Battle Lab",
      template: "%s · Cobbleventure",
    },
    description: "코블몬 AI의 PvE·EvE 전투 구성을 만들고 검증하는 웹 실험실",
    openGraph: {
      title: "Cobbleventure Battle Lab",
      description: "트레이너 데이터와 직접 만든 파티로 AI 전투를 준비하세요.",
      type: "website",
      locale: "ko_KR",
      images: socialImage ? [socialImage] : [],
    },
    twitter: {
      card: "summary_large_image",
      title: "Cobbleventure Battle Lab",
      description: "코블몬 AI 전투 실험실",
      images: socialImage ? [socialImage] : [],
    },
  };
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
