# 자체 엔진 특성 커버리지

> 이 문서는 `npm run audit:abilities`로 생성한다. 특성 구현 여부를 확인할 때
> 엔진 코드를 직접 훑기 전에 이 문서와 `ABILITY_IMPLEMENTATION_CHECKLIST.md`를 본다.

- 트레이너 수: 224
- 실제 사용 고유 특성: 208
- 지원: 142
- 미지원: 66

## 미지원 특성

| 우선순위 | ID | 사용 수 | 사용 예시 |
|---:|---|---:|---|
| 1 | `stancechange` | 3 | Aegislash (Ace Trainer Runan), Aegislash (Angelo), Aegislash (Dumbass Jojo Fan) |
| 2 | `sweetveil` | 3 | Alcremie (Ace Trainer Symes), Alcremie (Lass Anne), Alcremie (Petra) |
| 3 | `trace` | 3 | Gardevoir (Gengar), Gardevoir (Marzia), Kirlia (Petra) |
| 4 | `cloudnine` | 2 | Altaria (Alice), Psyduck (Misty) |
| 5 | `damp` | 2 | Poliwrath (Swimmer♀ Shirley), Poliwhirl (Swimmer♀ Shirley) |
| 6 | `dragonsmaw` | 2 | Regidrago (Champion Terry), Regidrago (Vulcano) |
| 7 | `flowerveil` | 2 | Florges (Lass Ann), Florges (Petra) |
| 8 | `gooey` | 2 | Goodra-Hisui (Dragon Tamer Ramiro), Goodra-Hisui (Lance) |
| 9 | `gorillatactics` | 2 | Darmanitan-Galar (Alfredo), Darmanitan-Galar (Bianca) |
| 10 | `grassysurge` | 2 | Rillaboom (Ace Trainer Symes), Rillaboom (Blue) |
| 11 | `heavymetal` | 2 | Copperajah (Jasmine), Copperajah (Tamer Phil) |
| 12 | `illusion` | 2 | Zoroark-Hisui (Angelo), Zorua (Picnicker Dana) |
| 13 | `justified` | 2 | Cobalion (Bruno), Keldeo (Champion Terry) |
| 14 | `magicguard` | 2 | Clefable (Ace Trainer Alexa), Arceus (Luciano) |
| 15 | `magician` | 2 | Hoopa-Unbound (Dumbass Jojo Fan), Hoopa-Unbound (Marzia) |
| 16 | `mirrorarmor` | 2 | Corviknight (Omar), Corviknight (Valerio) |
| 17 | `pickup` | 2 | Pachirisu (Gengar), Pumpkaboo (Pokéfan Laurenzia) |
| 18 | `primordialsea` | 2 | Kyogre-Primal (Leader Ivan), Kyogre-Primal (Rocco) |
| 19 | `protean` | 2 | Greninja (Biker Hideo), Meowscarada (Gardenia) |
| 20 | `sandforce` | 2 | Dugtrio-Alola (Biker Lao), Gigalith (Biker Ruben) |
| 21 | `victorystar` | 2 | Victini (Ace Trainer Runan), Victini (Rocket Admin Ariana) |
| 22 | `airlock` | 1 | Rayquaza (Lyris) |
| 23 | `anticipation` | 1 | Toxicroak (Ace Trainer Janny) |
| 24 | `arenatrap` | 1 | Diglett (Camper Flint) |
| 25 | `aromaveil` | 1 | Lechonk (School Kid BrunoRe) |
| 26 | `aurabreak` | 1 | Zygarde-Mega (Luciano) |
| 27 | `ballfetch` | 1 | Yamper (Fabbo67) |
| 28 | `beadsofruin` | 1 | Chi-Yu (Karen) |
| 29 | `cheekpouch` | 1 | Dedenne (Lt. Surge) |
| 30 | `comatose` | 1 | Komala (Ace Trainer Alexa) |
| 31 | `contrary` | 1 | Malamar (Karen) |
| 32 | `cottondown` | 1 | Eldegoss (Erika) |
| 33 | `darkaura` | 1 | Yveltal (Ester) |
| 34 | `defeatist` | 1 | Archeops (Scientist Ed) |
| 35 | `deltastream` | 1 | Rayquaza-Mega (Rocco) |
| 36 | `desolateland` | 1 | Groudon-Primal (Rocco) |
| 37 | `fairyaura` | 1 | Xerneas (Champion Terry) |
| 38 | `flowergift` | 1 | Cherrim (Fosco) |
| 39 | `forecast` | 1 | Rampardos (Scientist Ivan) |
| 40 | `forewarn` | 1 | Hypno (Gambler Darian) |
| 41 | `gulpmissile` | 1 | Cramorant (Sailor Trevor) |
| 42 | `harvest` | 1 | Exeggutor (Sabrina) |
| 43 | `healer` | 1 | Hatenna (Lass Haley) |
| 44 | `honeygather` | 1 | Combee (Bug Catcher Rick) |
| 45 | `icebody` | 1 | Vanillish (Vale_Fragola) |
| 46 | `illuminate` | 1 | Volbeat (Gentleman Komodo) |
| 47 | `liquidooze` | 1 | Tentacruel (Swimmer♀ Tisha) |
| 48 | `poisonheal` | 1 | Breloom (Trainer May) |
| 49 | `poisonpoint` | 1 | Dragalge (Alice) |
| 50 | `poisonpuppeteer` | 1 | Pecharunt (Koga) |
| 51 | `psychicsurge` | 1 | Indeedee (Pino) |
| 52 | `queenlymajesty` | 1 | Tsareena (Gardenia) |
| 53 | `quickdraw` | 1 | Slowbro-Galar (Pino) |
| 54 | `ripen` | 1 | Applin (Lass Miriam) |
| 55 | `slushrush` | 1 | Arctovish (Alfredo) |
| 56 | `solarpower` | 1 | Heliolisk (Fosco) |
| 57 | `soulheart` | 1 | Magearna (Jasmine) |
| 58 | `superluck` | 1 | Honchkrow (Boss Giovanni) |
| 59 | `swordofruin` | 1 | Chien-Pao (Karen) |
| 60 | `tabletsofruin` | 1 | Wo-Chien (Karen) |
| 61 | `tangledfeet` | 1 | Pidgeot (Lass Megan) |
| 62 | `telepathy` | 1 | Gardevoir (Frida) |
| 63 | `transistor` | 1 | Regieleki (Champion Terry) |
| 64 | `truant` | 1 | Slaking (Cue Ball Isaiah) |
| 65 | `waterbubble` | 1 | Araquanid (Raffaello) |
| 66 | `zenmode` | 1 | Darmanitan-Galar (Crush Girl Sharon) |

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
| `stench` | 3 |
| `toxicdebris` | 3 |
| `vesselofruin` | 3 |
| `voltabsorb` | 3 |
| `baddreams` | 2 |
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

