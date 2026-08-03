# 자체 엔진 특성 커버리지

> 이 문서는 `npm run audit:abilities`로 생성한다. 특성 구현 여부를 확인할 때
> 엔진 코드를 직접 훑기 전에 이 문서와 `ABILITY_IMPLEMENTATION_CHECKLIST.md`를 본다.

- 트레이너 수: 224
- 실제 사용 고유 특성: 208
- 지원: 137
- 미지원: 71

## 미지원 특성

| 우선순위 | ID | 사용 수 | 사용 예시 |
|---:|---|---:|---|
| 1 | `imposter` | 3 | Ditto (Champion Terry), Ditto (Champion Terry), Ditto (Luciano) |
| 2 | `leafguard` | 3 | Hoppip (Lass Dalia), Skiploom (Lass Dalia), Skiploom (Lass Dalia) |
| 3 | `prankster` | 3 | Sableye (Angelo), Grimmsnarl (Ester), Sableye (Tell) |
| 4 | `reckless` | 3 | Decidueye-Hisui (Ace Trainer Symes), Bouffalant (Biker Virgil), Blaziken (Bird Keeper Mitch) |
| 5 | `stancechange` | 3 | Aegislash (Ace Trainer Runan), Aegislash (Angelo), Aegislash (Dumbass Jojo Fan) |
| 6 | `stench` | 3 | Stunky (Camper Drew), Muk (Team Rocket General Archer), Muk (Team Rocket General Archer) |
| 7 | `sweetveil` | 3 | Alcremie (Ace Trainer Symes), Alcremie (Lass Anne), Alcremie (Petra) |
| 8 | `trace` | 3 | Gardevoir (Gengar), Gardevoir (Marzia), Kirlia (Petra) |
| 9 | `cloudnine` | 2 | Altaria (Alice), Psyduck (Misty) |
| 10 | `damp` | 2 | Poliwrath (Swimmer♀ Shirley), Poliwhirl (Swimmer♀ Shirley) |
| 11 | `dragonsmaw` | 2 | Regidrago (Champion Terry), Regidrago (Vulcano) |
| 12 | `flowerveil` | 2 | Florges (Lass Ann), Florges (Petra) |
| 13 | `gooey` | 2 | Goodra-Hisui (Dragon Tamer Ramiro), Goodra-Hisui (Lance) |
| 14 | `gorillatactics` | 2 | Darmanitan-Galar (Alfredo), Darmanitan-Galar (Bianca) |
| 15 | `grassysurge` | 2 | Rillaboom (Ace Trainer Symes), Rillaboom (Blue) |
| 16 | `heavymetal` | 2 | Copperajah (Jasmine), Copperajah (Tamer Phil) |
| 17 | `illusion` | 2 | Zoroark-Hisui (Angelo), Zorua (Picnicker Dana) |
| 18 | `justified` | 2 | Cobalion (Bruno), Keldeo (Champion Terry) |
| 19 | `magicguard` | 2 | Clefable (Ace Trainer Alexa), Arceus (Luciano) |
| 20 | `magician` | 2 | Hoopa-Unbound (Dumbass Jojo Fan), Hoopa-Unbound (Marzia) |
| 21 | `mirrorarmor` | 2 | Corviknight (Omar), Corviknight (Valerio) |
| 22 | `pickup` | 2 | Pachirisu (Gengar), Pumpkaboo (Pokéfan Laurenzia) |
| 23 | `primordialsea` | 2 | Kyogre-Primal (Leader Ivan), Kyogre-Primal (Rocco) |
| 24 | `protean` | 2 | Greninja (Biker Hideo), Meowscarada (Gardenia) |
| 25 | `sandforce` | 2 | Dugtrio-Alola (Biker Lao), Gigalith (Biker Ruben) |
| 26 | `victorystar` | 2 | Victini (Ace Trainer Runan), Victini (Rocket Admin Ariana) |
| 27 | `airlock` | 1 | Rayquaza (Lyris) |
| 28 | `anticipation` | 1 | Toxicroak (Ace Trainer Janny) |
| 29 | `arenatrap` | 1 | Diglett (Camper Flint) |
| 30 | `aromaveil` | 1 | Lechonk (School Kid BrunoRe) |
| 31 | `aurabreak` | 1 | Zygarde-Mega (Luciano) |
| 32 | `ballfetch` | 1 | Yamper (Fabbo67) |
| 33 | `beadsofruin` | 1 | Chi-Yu (Karen) |
| 34 | `cheekpouch` | 1 | Dedenne (Lt. Surge) |
| 35 | `comatose` | 1 | Komala (Ace Trainer Alexa) |
| 36 | `contrary` | 1 | Malamar (Karen) |
| 37 | `cottondown` | 1 | Eldegoss (Erika) |
| 38 | `darkaura` | 1 | Yveltal (Ester) |
| 39 | `defeatist` | 1 | Archeops (Scientist Ed) |
| 40 | `deltastream` | 1 | Rayquaza-Mega (Rocco) |
| 41 | `desolateland` | 1 | Groudon-Primal (Rocco) |
| 42 | `fairyaura` | 1 | Xerneas (Champion Terry) |
| 43 | `flowergift` | 1 | Cherrim (Fosco) |
| 44 | `forecast` | 1 | Rampardos (Scientist Ivan) |
| 45 | `forewarn` | 1 | Hypno (Gambler Darian) |
| 46 | `gulpmissile` | 1 | Cramorant (Sailor Trevor) |
| 47 | `harvest` | 1 | Exeggutor (Sabrina) |
| 48 | `healer` | 1 | Hatenna (Lass Haley) |
| 49 | `honeygather` | 1 | Combee (Bug Catcher Rick) |
| 50 | `icebody` | 1 | Vanillish (Vale_Fragola) |
| 51 | `illuminate` | 1 | Volbeat (Gentleman Komodo) |
| 52 | `liquidooze` | 1 | Tentacruel (Swimmer♀ Tisha) |
| 53 | `poisonheal` | 1 | Breloom (Trainer May) |
| 54 | `poisonpoint` | 1 | Dragalge (Alice) |
| 55 | `poisonpuppeteer` | 1 | Pecharunt (Koga) |
| 56 | `psychicsurge` | 1 | Indeedee (Pino) |
| 57 | `queenlymajesty` | 1 | Tsareena (Gardenia) |
| 58 | `quickdraw` | 1 | Slowbro-Galar (Pino) |
| 59 | `ripen` | 1 | Applin (Lass Miriam) |
| 60 | `slushrush` | 1 | Arctovish (Alfredo) |
| 61 | `solarpower` | 1 | Heliolisk (Fosco) |
| 62 | `soulheart` | 1 | Magearna (Jasmine) |
| 63 | `superluck` | 1 | Honchkrow (Boss Giovanni) |
| 64 | `swordofruin` | 1 | Chien-Pao (Karen) |
| 65 | `tabletsofruin` | 1 | Wo-Chien (Karen) |
| 66 | `tangledfeet` | 1 | Pidgeot (Lass Megan) |
| 67 | `telepathy` | 1 | Gardevoir (Frida) |
| 68 | `transistor` | 1 | Regieleki (Champion Terry) |
| 69 | `truant` | 1 | Slaking (Cue Ball Isaiah) |
| 70 | `waterbubble` | 1 | Araquanid (Raffaello) |
| 71 | `zenmode` | 1 | Darmanitan-Galar (Crush Girl Sharon) |

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
| `ironbarbs` | 3 |
| `limber` | 3 |
| `mindseye` | 3 |
| `pickpocket` | 3 |
| `purepower` | 3 |
| `scrappy` | 3 |
| `skilllink` | 3 |
| `stamina` | 3 |
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

