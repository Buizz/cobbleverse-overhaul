# 자체 엔진 특성 커버리지

> 이 문서는 `npm run audit:abilities`로 생성한다. 특성 구현 여부를 확인할 때
> 엔진 코드를 직접 훑기 전에 이 문서와 `ABILITY_IMPLEMENTATION_CHECKLIST.md`를 본다.

- 트레이너 수: 224
- 실제 사용 고유 특성: 208
- 지원: 117
- 미지원: 91

## 미지원 특성

| 우선순위 | ID | 사용 수 | 사용 예시 |
|---:|---|---:|---|
| 1 | `poisontouch` | 7 | Roselia (Beauty Bridget), Dragalge (Biker Lukas), Roserade (Biker Malik) |
| 2 | `serenegrace` | 7 | Blissey (Ace Trainer Alexa), Meloetta (Biker Ruben), Dudunsparce (Cue Ball Isaiah) |
| 3 | `steadfast` | 7 | Farfetch’d-Galar (Camper Flint), Gallade (Crush Girl Sharon), Sirfetch’d (Edo_Leone) |
| 4 | `aftermath` | 6 | Seviper (Tamer Phil), Skuntank (Team Rocket Grunt), Skuntank (Team Rocket Grunt) |
| 5 | `disguise` | 6 | Mimikyu (Agatha), Mimikyu (Morn27015), Mimikyu (Team Rocket Admin) |
| 6 | `stickyhold` | 6 | Muk (Atena), Muk (Team Rocket Grunt), Muk (Team Rocket Officer) |
| 7 | `cutecharm` | 5 | Wigglytuff (Aaron), Lopunny (Chiara), Wigglytuff (DummyH77) |
| 8 | `frisk` | 5 | Exeggutor-Alola (Dragon Tamer Ramiro), Banette (Team Rocket Spy), Banette (Team Rocket Spy) |
| 9 | `gluttony` | 5 | Appletun (Bobbo), Zigzagoon (Friendly Coach), Lechonk (Friendly Coach) |
| 10 | `swarm` | 5 | Beedrill (Biker Hideo), Sewaddle (Bug Catcher Cale), Ledyba (Bug Catcher Cale) |
| 11 | `unburden` | 5 | Hawlucha (Bird Keeper Mitch), Sneasler (Koga), Liepard (Team Rocket Grunt) |
| 12 | `weakarmor` | 5 | Garbodor (Biker Jaxon), Ceruledge (Fosco), Kabutops (Team Rocket Scientist) |
| 13 | `neutralizinggas` | 4 | Weezing (Biker Lukas), Weezing-Galar (Friendly Teacher), Weezing (Team Rocket General Archer) |
| 14 | `oblivious` | 4 | Jynx (Sabrina), Wailord (Sailor Trevor), Salazzle (Team Rocket Officer) |
| 15 | `shadowtag` | 4 | Gothitelle (Frida), Gothitelle (Marzia), Wobbuffet (Team Rocket Admin) |
| 16 | `shielddust` | 4 | Caterpie (Bug Catcher Rick), Caterpie (Friendly Coach), Dustox (Koga) |
| 17 | `sniper` | 4 | Drapion (Apollo), Horsea (Misty), Golbat (Team Rocket Grunt) |
| 18 | `thermalexchange` | 4 | Baxcalibur (Alice), Baxcalibur (Dragon Tamer Ramiro), Baxcalibur (Lance) |
| 19 | `earlybird` | 3 | Kangaskhan (Norman), Houndoom (Team Rocket General Archer), Houndoom (Team Rocket General Archer) |
| 20 | `effectspore` | 3 | Vileplume (Atena), Amoonguss (Gardenia), Breloom (Rudi) |
| 21 | `imposter` | 3 | Ditto (Champion Terry), Ditto (Champion Terry), Ditto (Luciano) |
| 22 | `leafguard` | 3 | Hoppip (Lass Dalia), Skiploom (Lass Dalia), Skiploom (Lass Dalia) |
| 23 | `prankster` | 3 | Sableye (Angelo), Grimmsnarl (Ester), Sableye (Tell) |
| 24 | `reckless` | 3 | Decidueye-Hisui (Ace Trainer Symes), Bouffalant (Biker Virgil), Blaziken (Bird Keeper Mitch) |
| 25 | `stancechange` | 3 | Aegislash (Ace Trainer Runan), Aegislash (Angelo), Aegislash (Dumbass Jojo Fan) |
| 26 | `stench` | 3 | Stunky (Camper Drew), Muk (Team Rocket General Archer), Muk (Team Rocket General Archer) |
| 27 | `sweetveil` | 3 | Alcremie (Ace Trainer Symes), Alcremie (Lass Anne), Alcremie (Petra) |
| 28 | `trace` | 3 | Gardevoir (Gengar), Gardevoir (Marzia), Kirlia (Petra) |
| 29 | `cloudnine` | 2 | Altaria (Alice), Psyduck (Misty) |
| 30 | `damp` | 2 | Poliwrath (Swimmer♀ Shirley), Poliwhirl (Swimmer♀ Shirley) |
| 31 | `dragonsmaw` | 2 | Regidrago (Champion Terry), Regidrago (Vulcano) |
| 32 | `flowerveil` | 2 | Florges (Lass Ann), Florges (Petra) |
| 33 | `gooey` | 2 | Goodra-Hisui (Dragon Tamer Ramiro), Goodra-Hisui (Lance) |
| 34 | `gorillatactics` | 2 | Darmanitan-Galar (Alfredo), Darmanitan-Galar (Bianca) |
| 35 | `grassysurge` | 2 | Rillaboom (Ace Trainer Symes), Rillaboom (Blue) |
| 36 | `heavymetal` | 2 | Copperajah (Jasmine), Copperajah (Tamer Phil) |
| 37 | `illusion` | 2 | Zoroark-Hisui (Angelo), Zorua (Picnicker Dana) |
| 38 | `justified` | 2 | Cobalion (Bruno), Keldeo (Champion Terry) |
| 39 | `magicguard` | 2 | Clefable (Ace Trainer Alexa), Arceus (Luciano) |
| 40 | `magician` | 2 | Hoopa-Unbound (Dumbass Jojo Fan), Hoopa-Unbound (Marzia) |
| 41 | `mirrorarmor` | 2 | Corviknight (Omar), Corviknight (Valerio) |
| 42 | `pickup` | 2 | Pachirisu (Gengar), Pumpkaboo (Pokéfan Laurenzia) |
| 43 | `primordialsea` | 2 | Kyogre-Primal (Leader Ivan), Kyogre-Primal (Rocco) |
| 44 | `protean` | 2 | Greninja (Biker Hideo), Meowscarada (Gardenia) |
| 45 | `sandforce` | 2 | Dugtrio-Alola (Biker Lao), Gigalith (Biker Ruben) |
| 46 | `victorystar` | 2 | Victini (Ace Trainer Runan), Victini (Rocket Admin Ariana) |
| 47 | `airlock` | 1 | Rayquaza (Lyris) |
| 48 | `anticipation` | 1 | Toxicroak (Ace Trainer Janny) |
| 49 | `arenatrap` | 1 | Diglett (Camper Flint) |
| 50 | `aromaveil` | 1 | Lechonk (School Kid BrunoRe) |
| 51 | `aurabreak` | 1 | Zygarde-Mega (Luciano) |
| 52 | `ballfetch` | 1 | Yamper (Fabbo67) |
| 53 | `beadsofruin` | 1 | Chi-Yu (Karen) |
| 54 | `cheekpouch` | 1 | Dedenne (Lt. Surge) |
| 55 | `comatose` | 1 | Komala (Ace Trainer Alexa) |
| 56 | `contrary` | 1 | Malamar (Karen) |
| 57 | `cottondown` | 1 | Eldegoss (Erika) |
| 58 | `darkaura` | 1 | Yveltal (Ester) |
| 59 | `defeatist` | 1 | Archeops (Scientist Ed) |
| 60 | `deltastream` | 1 | Rayquaza-Mega (Rocco) |
| 61 | `desolateland` | 1 | Groudon-Primal (Rocco) |
| 62 | `fairyaura` | 1 | Xerneas (Champion Terry) |
| 63 | `flowergift` | 1 | Cherrim (Fosco) |
| 64 | `forecast` | 1 | Rampardos (Scientist Ivan) |
| 65 | `forewarn` | 1 | Hypno (Gambler Darian) |
| 66 | `gulpmissile` | 1 | Cramorant (Sailor Trevor) |
| 67 | `harvest` | 1 | Exeggutor (Sabrina) |
| 68 | `healer` | 1 | Hatenna (Lass Haley) |
| 69 | `honeygather` | 1 | Combee (Bug Catcher Rick) |
| 70 | `icebody` | 1 | Vanillish (Vale_Fragola) |
| 71 | `illuminate` | 1 | Volbeat (Gentleman Komodo) |
| 72 | `liquidooze` | 1 | Tentacruel (Swimmer♀ Tisha) |
| 73 | `poisonheal` | 1 | Breloom (Trainer May) |
| 74 | `poisonpoint` | 1 | Dragalge (Alice) |
| 75 | `poisonpuppeteer` | 1 | Pecharunt (Koga) |
| 76 | `psychicsurge` | 1 | Indeedee (Pino) |
| 77 | `queenlymajesty` | 1 | Tsareena (Gardenia) |
| 78 | `quickdraw` | 1 | Slowbro-Galar (Pino) |
| 79 | `ripen` | 1 | Applin (Lass Miriam) |
| 80 | `slushrush` | 1 | Arctovish (Alfredo) |
| 81 | `solarpower` | 1 | Heliolisk (Fosco) |
| 82 | `soulheart` | 1 | Magearna (Jasmine) |
| 83 | `superluck` | 1 | Honchkrow (Boss Giovanni) |
| 84 | `swordofruin` | 1 | Chien-Pao (Karen) |
| 85 | `tabletsofruin` | 1 | Wo-Chien (Karen) |
| 86 | `tangledfeet` | 1 | Pidgeot (Lass Megan) |
| 87 | `telepathy` | 1 | Gardevoir (Frida) |
| 88 | `transistor` | 1 | Regieleki (Champion Terry) |
| 89 | `truant` | 1 | Slaking (Cue Ball Isaiah) |
| 90 | `waterbubble` | 1 | Araquanid (Raffaello) |
| 91 | `zenmode` | 1 | Darmanitan-Galar (Crush Girl Sharon) |

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
| `compoundeyes` | 6 |
| `hypercutter` | 6 |
| `insomnia` | 6 |
| `sharpness` | 6 |
| `solidrock` | 6 |
| `strongjaw` | 6 |
| `tintedlens` | 6 |
| `battlearmor` | 5 |
| `hydration` | 5 |
| `quarkdrive` | 5 |
| `sandrush` | 5 |
| `competitive` | 4 |
| `download` | 4 |
| `drizzle` | 4 |
| `filter` | 4 |
| `lightningrod` | 4 |
| `magicbounce` | 4 |
| `sapsipper` | 4 |
| `snowcloak` | 4 |
| `snowwarning` | 4 |
| `unseenfist` | 4 |
| `wonderguard` | 4 |
| `defiant` | 3 |
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

