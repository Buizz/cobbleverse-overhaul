# 자체 엔진 특성 커버리지

> 이 문서는 `npm run audit:abilities`로 생성한다. 특성 구현 여부를 확인할 때
> 엔진 코드를 직접 훑기 전에 이 문서와 `ABILITY_IMPLEMENTATION_CHECKLIST.md`를 본다.

- 트레이너 수: 224
- 실제 사용 고유 특성: 208
- 지원: 147
- 미지원: 61

## 미지원 특성

| 우선순위 | ID | 사용 수 | 사용 예시 |
|---:|---|---:|---|
| 1 | `dragonsmaw` | 2 | Regidrago (Champion Terry), Regidrago (Vulcano) |
| 2 | `flowerveil` | 2 | Florges (Lass Ann), Florges (Petra) |
| 3 | `gooey` | 2 | Goodra-Hisui (Dragon Tamer Ramiro), Goodra-Hisui (Lance) |
| 4 | `gorillatactics` | 2 | Darmanitan-Galar (Alfredo), Darmanitan-Galar (Bianca) |
| 5 | `grassysurge` | 2 | Rillaboom (Ace Trainer Symes), Rillaboom (Blue) |
| 6 | `heavymetal` | 2 | Copperajah (Jasmine), Copperajah (Tamer Phil) |
| 7 | `illusion` | 2 | Zoroark-Hisui (Angelo), Zorua (Picnicker Dana) |
| 8 | `justified` | 2 | Cobalion (Bruno), Keldeo (Champion Terry) |
| 9 | `magicguard` | 2 | Clefable (Ace Trainer Alexa), Arceus (Luciano) |
| 10 | `magician` | 2 | Hoopa-Unbound (Dumbass Jojo Fan), Hoopa-Unbound (Marzia) |
| 11 | `mirrorarmor` | 2 | Corviknight (Omar), Corviknight (Valerio) |
| 12 | `pickup` | 2 | Pachirisu (Gengar), Pumpkaboo (Pokéfan Laurenzia) |
| 13 | `primordialsea` | 2 | Kyogre-Primal (Leader Ivan), Kyogre-Primal (Rocco) |
| 14 | `protean` | 2 | Greninja (Biker Hideo), Meowscarada (Gardenia) |
| 15 | `sandforce` | 2 | Dugtrio-Alola (Biker Lao), Gigalith (Biker Ruben) |
| 16 | `victorystar` | 2 | Victini (Ace Trainer Runan), Victini (Rocket Admin Ariana) |
| 17 | `airlock` | 1 | Rayquaza (Lyris) |
| 18 | `anticipation` | 1 | Toxicroak (Ace Trainer Janny) |
| 19 | `arenatrap` | 1 | Diglett (Camper Flint) |
| 20 | `aromaveil` | 1 | Lechonk (School Kid BrunoRe) |
| 21 | `aurabreak` | 1 | Zygarde-Mega (Luciano) |
| 22 | `ballfetch` | 1 | Yamper (Fabbo67) |
| 23 | `beadsofruin` | 1 | Chi-Yu (Karen) |
| 24 | `cheekpouch` | 1 | Dedenne (Lt. Surge) |
| 25 | `comatose` | 1 | Komala (Ace Trainer Alexa) |
| 26 | `contrary` | 1 | Malamar (Karen) |
| 27 | `cottondown` | 1 | Eldegoss (Erika) |
| 28 | `darkaura` | 1 | Yveltal (Ester) |
| 29 | `defeatist` | 1 | Archeops (Scientist Ed) |
| 30 | `deltastream` | 1 | Rayquaza-Mega (Rocco) |
| 31 | `desolateland` | 1 | Groudon-Primal (Rocco) |
| 32 | `fairyaura` | 1 | Xerneas (Champion Terry) |
| 33 | `flowergift` | 1 | Cherrim (Fosco) |
| 34 | `forecast` | 1 | Rampardos (Scientist Ivan) |
| 35 | `forewarn` | 1 | Hypno (Gambler Darian) |
| 36 | `gulpmissile` | 1 | Cramorant (Sailor Trevor) |
| 37 | `harvest` | 1 | Exeggutor (Sabrina) |
| 38 | `healer` | 1 | Hatenna (Lass Haley) |
| 39 | `honeygather` | 1 | Combee (Bug Catcher Rick) |
| 40 | `icebody` | 1 | Vanillish (Vale_Fragola) |
| 41 | `illuminate` | 1 | Volbeat (Gentleman Komodo) |
| 42 | `liquidooze` | 1 | Tentacruel (Swimmer♀ Tisha) |
| 43 | `poisonheal` | 1 | Breloom (Trainer May) |
| 44 | `poisonpoint` | 1 | Dragalge (Alice) |
| 45 | `poisonpuppeteer` | 1 | Pecharunt (Koga) |
| 46 | `psychicsurge` | 1 | Indeedee (Pino) |
| 47 | `queenlymajesty` | 1 | Tsareena (Gardenia) |
| 48 | `quickdraw` | 1 | Slowbro-Galar (Pino) |
| 49 | `ripen` | 1 | Applin (Lass Miriam) |
| 50 | `slushrush` | 1 | Arctovish (Alfredo) |
| 51 | `solarpower` | 1 | Heliolisk (Fosco) |
| 52 | `soulheart` | 1 | Magearna (Jasmine) |
| 53 | `superluck` | 1 | Honchkrow (Boss Giovanni) |
| 54 | `swordofruin` | 1 | Chien-Pao (Karen) |
| 55 | `tabletsofruin` | 1 | Wo-Chien (Karen) |
| 56 | `tangledfeet` | 1 | Pidgeot (Lass Megan) |
| 57 | `telepathy` | 1 | Gardevoir (Frida) |
| 58 | `transistor` | 1 | Regieleki (Champion Terry) |
| 59 | `truant` | 1 | Slaking (Cue Ball Isaiah) |
| 60 | `waterbubble` | 1 | Araquanid (Raffaello) |
| 61 | `zenmode` | 1 | Darmanitan-Galar (Crush Girl Sharon) |

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
| `dryskin` | 2 |
| `fluffy` | 2 |
| `galewings` | 2 |
| `heatproof` | 2 |
| `immunity` | 2 |
| `intrepidsword` | 2 |
| `lightmetal` | 2 |
| `moxie` | 2 |
| `overcoat` | 2 |
| `purifyingsalt` | 2 |
| `runaway` | 2 |
| `soundproof` | 2 |
| `stormdrain` | 2 |
| `supremeoverlord` | 2 |
| `teravolt` | 2 |
| `unnerve` | 2 |
| `vitalspirit` | 2 |
| `whitesmoke` | 2 |
| `adaptability` | 1 |
| `armortail` | 1 |
| `asonespectrier` | 1 |
| `chillingneigh` | 1 |
| `dauntlessshield` | 1 |
| `eartheater` | 1 |
| `electricsurge` | 1 |
| `furcoat` | 1 |
| `hadronengine` | 1 |
| `liquidvoice` | 1 |
| `magnetpull` | 1 |
| `multitype` | 1 |
| `orichalcumpulse` | 1 |
| `prismarmor` | 1 |
| `shadowshield` | 1 |
| `toughclaws` | 1 |
| `wellbakedbody` | 1 |

