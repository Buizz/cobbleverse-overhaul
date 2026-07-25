import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const coveragePath = fileURLToPath(
  new URL("../public/data/native-mechanics-coverage.json", import.meta.url),
);
const outputPath = fileURLToPath(
  new URL("../../docs/NATIVE_MOVE_BACKLOG.md", import.meta.url),
);

const coverage = JSON.parse(await readFile(coveragePath, "utf8"));
const unresolved = coverage.moves
  .filter((move) => move.status !== "SUPPORTED")
  .sort(
    (left, right) =>
      Number(right.usedByTrainers) - Number(left.usedByTrainers) ||
      right.trainerUsageCount - left.trainerUsageCount ||
      left.id.localeCompare(right.id),
  );
const trainerUnresolved = unresolved.filter((move) => move.usedByTrainers);

function requirementText(move) {
  const parts = [
    ...new Set([
      ...(move.requirements ?? []),
      ...(move.callbacks ?? []).map((callback) => `callback:${callback}`),
    ]),
  ];
  return parts.length ? parts.join(", ") : "-";
}

function moveRow(move, index) {
  return [
    String(index + 1),
    `\`${move.id}\``,
    move.name,
    move.status,
    move.availability,
    move.usedByTrainers ? String(move.trainerUsageCount) : "-",
    requirementText(move),
  ].join(" | ");
}

const generatedAt = new Date().toISOString();
const lines = [
  "# Cobbleverse 자체 엔진 기술 구현 Backlog",
  "",
  "> 이 문서는 `npm run mechanics:backlog`로 자동 생성한다.",
  "> 수동으로 미구현 기술을 지우지 말고, 엔진 구현과 테스트를 추가한 뒤 감사 스크립트로 상태를 갱신한다.",
  "",
  `- 생성 시각: ${generatedAt}`,
  `- 전체 기술: ${coverage.totalMoveCount}`,
  `- 전체 지원: ${coverage.statusCounts.SUPPORTED}`,
  `- 전체 부분 지원: ${coverage.statusCounts.PARTIAL}`,
  `- 전체 미지원: ${coverage.statusCounts.UNSUPPORTED}`,
  `- 트레이너 사용 기술: ${coverage.trainerUsedMoveCount}`,
  `- 트레이너 사용 지원: ${coverage.trainerStatusCounts.SUPPORTED}`,
  `- 트레이너 사용 부분 지원: ${coverage.trainerStatusCounts.PARTIAL}`,
  `- 트레이너 사용 미지원: ${coverage.trainerStatusCounts.UNSUPPORTED}`,
  "",
  "## 진행 원칙",
  "",
  "1. 트레이너가 실제 사용하는 미구현 기술을 먼저 처리한다.",
  "2. 같은 엔진 기능으로 묶을 수 있는 기술은 한 번에 구현한다.",
  "3. 각 묶음은 최소 하나 이상의 회귀 테스트를 추가한다.",
  "4. `SUPPORTED`가 되려면 Showdown 데이터에서 발견된 요구사항과 커스텀 콜백이 자체 엔진에 연결되어야 한다.",
  "5. 모든 기술을 예외 없이 구현하는 것이 목표이며, 의도적 비활성화는 별도 사유와 콘텐츠 차단 규칙이 필요하다.",
  "",
  "## 트레이너 사용 미완료 기술",
  "",
  "| # | ID | 이름 | 상태 | 분류 | 사용 수 | 남은 요구사항 |",
  "|---|----|------|------|------|--------|---------------|",
  ...trainerUnresolved.map(moveRow),
  "",
  "## 전체 미완료 기술",
  "",
  "| # | ID | 이름 | 상태 | 분류 | 사용 수 | 남은 요구사항 |",
  "|---|----|------|------|------|--------|---------------|",
  ...unresolved.map(moveRow),
  "",
];

await writeFile(outputPath, `${lines.join("\n")}\n`, "utf8");

console.log(
  JSON.stringify(
    {
      output: outputPath,
      unresolved: unresolved.length,
      trainerUnresolved: trainerUnresolved.length,
      statusCounts: coverage.statusCounts,
      trainerStatusCounts: coverage.trainerStatusCounts,
    },
    null,
    2,
  ),
);
