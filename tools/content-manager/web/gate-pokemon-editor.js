/* Shared by the placement tool and selected-gate inspector. */
const GatePokemonEditor = (() => {
  const resource = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;
  const escape = (value) => String(value).replace(/[&<>"']/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);
  function render(root, value = {}) {
    const field = (key, label, fallback, type = "text", limits = "") => `<label><span>${label}</span><input data-pokemon-field="${key}" value="${escape(value[key] ?? fallback)}" type="${type}" ${limits}></label>`;
    const collision = value.collision || { width: 3, height: 2, depth: 4 };
    root.innerHTML = `<h4>이벤트 포켓몬</h4>
      ${field("species", "포켓몬 종 ID", "cobblemon:snorlax")}
      <div class="inline-fields">${field("level", "레벨", 30, "number", 'min="1" max="100" step="1"')}${field("scale", "모델·충돌 배율", 1, "number", 'min="0.25" max="4" step="0.05"')}</div>
      <label><span>대기 자세</span><select data-pokemon-field="pose"><option value="stand">서 있기</option><option value="sleep">누워 자기 · 모델 수면 자세</option></select></label>
      <div class="inline-fields">${["width", "height", "depth"].map((key, index) => `<label><span>충돌 ${["폭", "높이", "깊이"][index]}</span><input data-pokemon-field="${key}" type="number" min="0.5" max="${key === "height" ? 8 : 16}" step="0.1" value="${escape(collision[key])}"></label>`).join("")}</div>
      <small>충돌 크기는 기본 배율 기준입니다. 방향·배율을 함께 적용합니다. 몸과 지형 사이에 틈이 없도록 통로 폭을 맞추세요.</small>
      <button class="button secondary" type="button" data-pokemon-fit-passage>현재 충돌 폭에 통로 맞추기</button>
      ${field("completion_flag", "완료 플래그 · 비우면 관문 ID로 자동 생성", "")}
      ${field("activation_item", "직접 사용할 도구 ID · 선택", "")}
      <small>도구를 지정하면 소지만으로는 깨울 수 없습니다. 해당 도구를 손에 들고 상호작용하거나 근처에서 사용해야 합니다.</small>
      <div class="gate-condition-builder" data-pokemon-activation data-gate-condition-editor><header><span>깨우기·전투 시작 조건 · 모두 충족</span><button type="button" data-gate-condition-add>+ 조건 추가</button></header><div data-gate-condition-list class="gate-condition-list"></div></div>
      <small>조건은 아이템을 소비하지 않습니다. 기본 동작은 1초 뒤 야생전투, 승리·포획 시 개인별 해제입니다. 도주·패배는 재도전합니다.</small>
      ${field("event_binding", "CVES 이벤트 바인딩 ID · 선택", "")}
      <small>지정하면 기본 전투 대신 해당 이벤트를 실행합니다. 이벤트에서 완료 플래그를 설정해야 해제됩니다. 아래 통과 조건은 완료 이후의 추가 조건입니다.</small>`;
    root.querySelector('[data-pokemon-field="pose"]').value = value.pose || "sleep";
    const conditions = root.querySelector("[data-pokemon-activation]");
    PlayerConditionEditor.initialize(conditions);
    PlayerConditionEditor.render(conditions, value.activation_conditions || []);
  }
  function read(root) {
    const get = key => root.querySelector(`[data-pokemon-field="${key}"]`).value.trim();
    const number = (key, min, max, integer = false) => {
      const value = Number(get(key));
      if (!Number.isFinite(value) || value < min || value > max || (integer && !Number.isInteger(value))) throw new Error(`포켓몬 ${key}: ${min}~${max} ${integer ? "정수" : "숫자"}를 입력하세요.`);
      return value;
    };
    const species = get("species");
    if (!resource.test(species)) throw new Error("포켓몬 종은 cobblemon:snorlax 형식으로 입력하세요.");
    if (!["stand", "sleep"].includes(get("pose"))) throw new Error("포켓몬 자세는 서 있기 또는 수면을 선택하세요.");
    const result = { species, level: number("level", 1, 100, true), pose: get("pose"), scale: number("scale", 0.25, 4),
      collision: { width: number("width", 0.5, 16), height: number("height", 0.5, 8), depth: number("depth", 0.5, 16) },
      activation_conditions: PlayerConditionEditor.validate(PlayerConditionEditor.read(root.querySelector("[data-pokemon-activation]"))) };
    for (const key of ["completion_flag", "event_binding", "activation_item"]) {
      const value = get(key);
      if (value && !resource.test(value)) throw new Error(`포켓몬 ${key}: 리소스 ID를 입력하세요.`);
      if (value) result[key] = value;
    }
    return result;
  }
  return { render, read };
})();
