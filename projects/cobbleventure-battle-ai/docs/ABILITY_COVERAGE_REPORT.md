# 자체 엔진 특성 커버리지

> 이 문서는 `npm run audit:abilities`로 생성한다. 특성 구현 여부를 확인할 때
> 엔진 코드를 직접 훑기 전에 이 문서와 `ABILITY_IMPLEMENTATION_CHECKLIST.md`를 본다.

- 트레이너 수: 224
- 실제 사용 고유 특성: 208
- 지원: 197
- 미지원: 11

## 미지원 특성

| 우선순위 | ID | 사용 수 | 사용 예시 |
|---:|---|---:|---|
| 1 | `solarpower` | 1 | Heliolisk (Fosco) |
| 2 | `soulheart` | 1 | Magearna (Jasmine) |
| 3 | `superluck` | 1 | Honchkrow (Boss Giovanni) |
| 4 | `swordofruin` | 1 | Chien-Pao (Karen) |
| 5 | `tabletsofruin` | 1 | Wo-Chien (Karen) |
| 6 | `tangledfeet` | 1 | Pidgeot (Lass Megan) |
| 7 | `telepathy` | 1 | Gardevoir (Frida) |
| 8 | `transistor` | 1 | Regieleki (Champion Terry) |
| 9 | `truant` | 1 | Slaking (Cue Ball Isaiah) |
| 10 | `waterbubble` | 1 | Araquanid (Raffaello) |
| 11 | `zenmode` | 1 | Darmanitan-Galar (Crush Girl Sharon) |

## 지원 특성

| ID | 사용 수 |
|---|---:|
| `levitate` | 43 |
| `sturdy` | 25 |
| `intimidate` | 20 |
| `pressure` | 19 |
| `technician` | 18 |
| `innerfocus` | 16 |
| `clearbody` | 14 |
| `chlorophyll` | 13 |
| `naturalcure` | 13 |
| `swiftswim` | 13 |
| `rockhead` | 12 |
| `shedskin` | 12 |
| `sheerforce` | 12 |
| `blaze` | 11 |
| `static` | 11 |
| `thickfat` | 11 |
| `unaware` | 11 |
| `flashfire` | 10 |
| `sandstream` | 10 |
| `sandveil` | 10 |
| `analytic` | 9 |
| `cursedbody` | 9 |
| `guts` | 9 |
| `overgrow` | 9 |
| `regenerator` | 9 |
| `roughskin` | 9 |
| `speedboost` | 9 |
| `synchronize` | 9 |
| `waterabsorb` | 9 |
| `drought` | 8 |
| `flamebody` | 8 |
| `multiscale` | 8 |
| `noguard` | 8 |
| `owntempo` | 8 |
| `protosynthesis` | 8 |
| `rivalry` | 8 |
| `shellarmor` | 8 |
| `torrent` | 8 |
| `beastboost` | 7 |
| `hustle` | 7 |
| `infiltrator` | 7 |
| `ironfist` | 7 |
| `keeneye` | 7 |
| `moldbreaker` | 7 |
| `poisontouch` | 7 |
| `serenegrace` | 7 |
| `steadfast` | 7 |
| `aftermath` | 6 |
| `compoundeyes` | 6 |
| `disguise` | 6 |
| `hypercutter` | 6 |
| `insomnia` | 6 |
| `sharpness` | 6 |
| `solidrock` | 6 |
| `stickyhold` | 6 |
| `strongjaw` | 6 |
| `tintedlens` | 6 |
| `battlearmor` | 5 |
| `cutecharm` | 5 |
| `frisk` | 5 |
| `gluttony` | 5 |
| `hydration` | 5 |
| `quarkdrive` | 5 |
| `sandrush` | 5 |
| `swarm` | 5 |
| `unburden` | 5 |
| `weakarmor` | 5 |
| `competitive` | 4 |
| `download` | 4 |
| `drizzle` | 4 |
| `filter` | 4 |
| `lightningrod` | 4 |
| `magicbounce` | 4 |
| `neutralizinggas` | 4 |
| `oblivious` | 4 |
| `sapsipper` | 4 |
| `shadowtag` | 4 |
| `shielddust` | 4 |
| `sniper` | 4 |
| `snowcloak` | 4 |
| `snowwarning` | 4 |
| `thermalexchange` | 4 |
| `unseenfist` | 4 |
| `wonderguard` | 4 |
| `defiant` | 3 |
| `earlybird` | 3 |
| `effectspore` | 3 |
| `goodasgold` | 3 |
| `hospitality` | 3 |
| `hugepower` | 3 |
| `imposter` | 3 |
| `ironbarbs` | 3 |
| `leafguard` | 3 |
| `limber` | 3 |
| `mindseye` | 3 |
| `pickpocket` | 3 |
| `prankster` | 3 |
| `purepower` | 3 |
| `reckless` | 3 |
| `scrappy` | 3 |
| `skilllink` | 3 |
| `stamina` | 3 |
| `stancechange` | 3 |
| `stench` | 3 |
| `sweetveil` | 3 |
| `toxicdebris` | 3 |
| `trace` | 3 |
| `vesselofruin` | 3 |
| `voltabsorb` | 3 |
| `baddreams` | 2 |
| `cloudnine` | 2 |
| `damp` | 2 |
| `dragonsmaw` | 2 |
| `dryskin` | 2 |
| `flowerveil` | 2 |
| `fluffy` | 2 |
| `galewings` | 2 |
| `gooey` | 2 |
| `gorillatactics` | 2 |
| `grassysurge` | 2 |
| `heatproof` | 2 |
| `heavymetal` | 2 |
| `illusion` | 2 |
| `immunity` | 2 |
| `intrepidsword` | 2 |
| `justified` | 2 |
| `lightmetal` | 2 |
| `magicguard` | 2 |
| `magician` | 2 |
| `mirrorarmor` | 2 |
| `moxie` | 2 |
| `overcoat` | 2 |
| `pickup` | 2 |
| `primordialsea` | 2 |
| `protean` | 2 |
| `purifyingsalt` | 2 |
| `runaway` | 2 |
| `sandforce` | 2 |
| `soundproof` | 2 |
| `stormdrain` | 2 |
| `supremeoverlord` | 2 |
| `teravolt` | 2 |
| `unnerve` | 2 |
| `victorystar` | 2 |
| `vitalspirit` | 2 |
| `whitesmoke` | 2 |
| `adaptability` | 1 |
| `airlock` | 1 |
| `anticipation` | 1 |
| `arenatrap` | 1 |
| `armortail` | 1 |
| `aromaveil` | 1 |
| `asonespectrier` | 1 |
| `aurabreak` | 1 |
| `ballfetch` | 1 |
| `beadsofruin` | 1 |
| `cheekpouch` | 1 |
| `chillingneigh` | 1 |
| `comatose` | 1 |
| `contrary` | 1 |
| `cottondown` | 1 |
| `darkaura` | 1 |
| `dauntlessshield` | 1 |
| `defeatist` | 1 |
| `deltastream` | 1 |
| `desolateland` | 1 |
| `eartheater` | 1 |
| `electricsurge` | 1 |
| `fairyaura` | 1 |
| `flowergift` | 1 |
| `forecast` | 1 |
| `forewarn` | 1 |
| `furcoat` | 1 |
| `gulpmissile` | 1 |
| `hadronengine` | 1 |
| `harvest` | 1 |
| `healer` | 1 |
| `honeygather` | 1 |
| `icebody` | 1 |
| `illuminate` | 1 |
| `liquidooze` | 1 |
| `liquidvoice` | 1 |
| `magnetpull` | 1 |
| `multitype` | 1 |
| `orichalcumpulse` | 1 |
| `poisonheal` | 1 |
| `poisonpoint` | 1 |
| `poisonpuppeteer` | 1 |
| `prismarmor` | 1 |
| `psychicsurge` | 1 |
| `queenlymajesty` | 1 |
| `quickdraw` | 1 |
| `ripen` | 1 |
| `shadowshield` | 1 |
| `slushrush` | 1 |
| `toughclaws` | 1 |
| `wellbakedbody` | 1 |

