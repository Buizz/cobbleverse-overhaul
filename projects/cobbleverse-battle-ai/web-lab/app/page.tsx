import type { Metadata } from "next";
import { BattleLab } from "./BattleLab";

export const metadata: Metadata = {
  title: "Cobbleverse Battle Lab",
  description: "코블몬 AI의 PvE·EvE 전투 구성을 만들고 검증하는 웹 실험실",
};

export default function Home() {
  return <BattleLab />;
}
