# 자체 엔진 특성 커버리지

> 이 문서는 `npm run audit:abilities`로 생성한다. 특성 구현 여부를 확인할 때
> 엔진 코드를 직접 훑기 전에 이 문서와 `ABILITY_IMPLEMENTATION_CHECKLIST.md`를 본다.

- 트레이너 수: 224
- 실제 사용 고유 특성: 208
- 지원: 112
- 미지원: 96

## 미지원 특성

| 우선순위 | ID | 사용 수 | 사용 예시 |
|---:|---|---:|---|
| 1 | `shedskin` | 12 | Arbok (Biker Malik), Karrablast (Bug Catcher Keigo), Scrafty (Crush Girl Tanya) |
| 2 | `analytic` | 9 | Watchog (Norman), Magneton (Team Rocket Agent), Magneton (Team Rocket Grunt) |
| 3 | `cursedbody` | 9 | Gengar (Agatha), Gengar (Angelo), Incineroar (Ester) |
| 4 | `synchronize` | 9 | Umbreon (Friendly Anastasia), Umbreon (Friendly Coach), Espeon (Friendly Coach) |
| 5 | `rivalry` | 8 | Shinx (Friendly Coach), Nidoking (Giovanni), Nidoqueen (Giovanni) |
| 6 | `poisontouch` | 7 | Roselia (Beauty Bridget), Dragalge (Biker Lukas), Roserade (Biker Malik) |
| 7 | `serenegrace` | 7 | Blissey (Ace Trainer Alexa), Meloetta (Biker Ruben), Dudunsparce (Cue Ball Isaiah) |
| 8 | `steadfast` | 7 | Farfetch’d-Galar (Camper Flint), Gallade (Crush Girl Sharon), Sirfetch’d (Edo_Leone) |
| 9 | `aftermath` | 6 | Seviper (Tamer Phil), Skuntank (Team Rocket Grunt), Skuntank (Team Rocket Grunt) |
| 10 | `disguise` | 6 | Mimikyu (Agatha), Mimikyu (Morn27015), Mimikyu (Team Rocket Admin) |
| 11 | `stickyhold` | 6 | Muk (Atena), Muk (Team Rocket Grunt), Muk (Team Rocket Officer) |
| 12 | `cutecharm` | 5 | Wigglytuff (Aaron), Lopunny (Chiara), Wigglytuff (DummyH77) |
| 13 | `frisk` | 5 | Exeggutor-Alola (Dragon Tamer Ramiro), Banette (Team Rocket Spy), Banette (Team Rocket Spy) |
| 14 | `gluttony` | 5 | Appletun (Bobbo), Zigzagoon (Friendly Coach), Lechonk (Friendly Coach) |
| 15 | `swarm` | 5 | Beedrill (Biker Hideo), Sewaddle (Bug Catcher Cale), Ledyba (Bug Catcher Cale) |
| 16 | `unburden` | 5 | Hawlucha (Bird Keeper Mitch), Sneasler (Koga), Liepard (Team Rocket Grunt) |
| 17 | `weakarmor` | 5 | Garbodor (Biker Jaxon), Ceruledge (Fosco), Kabutops (Team Rocket Scientist) |
| 18 | `neutralizinggas` | 4 | Weezing (Biker Lukas), Weezing-Galar (Friendly Teacher), Weezing (Team Rocket General Archer) |
| 19 | `oblivious` | 4 | Jynx (Sabrina), Wailord (Sailor Trevor), Salazzle (Team Rocket Officer) |
| 20 | `shadowtag` | 4 | Gothitelle (Frida), Gothitelle (Marzia), Wobbuffet (Team Rocket Admin) |
| 21 | `shielddust` | 4 | Caterpie (Bug Catcher Rick), Caterpie (Friendly Coach), Dustox (Koga) |
| 22 | `sniper` | 4 | Drapion (Apollo), Horsea (Misty), Golbat (Team Rocket Grunt) |
| 23 | `thermalexchange` | 4 | Baxcalibur (Alice), Baxcalibur (Dragon Tamer Ramiro), Baxcalibur (Lance) |
| 24 | `earlybird` | 3 | Kangaskhan (Norman), Houndoom (Team Rocket General Archer), Houndoom (Team Rocket General Archer) |
| 25 | `effectspore` | 3 | Vileplume (Atena), Amoonguss (Gardenia), Breloom (Rudi) |
| 26 | `imposter` | 3 | Ditto (Champion Terry), Ditto (Champion Terry), Ditto (Luciano) |
| 27 | `leafguard` | 3 | Hoppip (Lass Dalia), Skiploom (Lass Dalia), Skiploom (Lass Dalia) |
| 28 | `prankster` | 3 | Sableye (Angelo), Grimmsnarl (Ester), Sableye (Tell) |
| 29 | `reckless` | 3 | Decidueye-Hisui (Ace Trainer Symes), Bouffalant (Biker Virgil), Blaziken (Bird Keeper Mitch) |
| 30 | `stancechange` | 3 | Aegislash (Ace Trainer Runan), Aegislash (Angelo), Aegislash (Dumbass Jojo Fan) |
| 31 | `stench` | 3 | Stunky (Camper Drew), Muk (Team Rocket General Archer), Muk (Team Rocket General Archer) |
| 32 | `sweetveil` | 3 | Alcremie (Ace Trainer Symes), Alcremie (Lass Anne), Alcremie (Petra) |
| 33 | `trace` | 3 | Gardevoir (Gengar), Gardevoir (Marzia), Kirlia (Petra) |
| 34 | `cloudnine` | 2 | Altaria (Alice), Psyduck (Misty) |
| 35 | `damp` | 2 | Poliwrath (Swimmer♀ Shirley), Poliwhirl (Swimmer♀ Shirley) |
| 36 | `dragonsmaw` | 2 | Regidrago (Champion Terry), Regidrago (Vulcano) |
| 37 | `flowerveil` | 2 | Florges (Lass Ann), Florges (Petra) |
| 38 | `gooey` | 2 | Goodra-Hisui (Dragon Tamer Ramiro), Goodra-Hisui (Lance) |
| 39 | `gorillatactics` | 2 | Darmanitan-Galar (Alfredo), Darmanitan-Galar (Bianca) |
| 40 | `grassysurge` | 2 | Rillaboom (Ace Trainer Symes), Rillaboom (Blue) |
| 41 | `heavymetal` | 2 | Copperajah (Jasmine), Copperajah (Tamer Phil) |
| 42 | `illusion` | 2 | Zoroark-Hisui (Angelo), Zorua (Picnicker Dana) |
| 43 | `justified` | 2 | Cobalion (Bruno), Keldeo (Champion Terry) |
| 44 | `magicguard` | 2 | Clefable (Ace Trainer Alexa), Arceus (Luciano) |
| 45 | `magician` | 2 | Hoopa-Unbound (Dumbass Jojo Fan), Hoopa-Unbound (Marzia) |
| 46 | `mirrorarmor` | 2 | Corviknight (Omar), Corviknight (Valerio) |
| 47 | `pickup` | 2 | Pachirisu (Gengar), Pumpkaboo (Pokéfan Laurenzia) |
| 48 | `primordialsea` | 2 | Kyogre-Primal (Leader Ivan), Kyogre-Primal (Rocco) |
| 49 | `protean` | 2 | Greninja (Biker Hideo), Meowscarada (Gardenia) |
| 50 | `sandforce` | 2 | Dugtrio-Alola (Biker Lao), Gigalith (Biker Ruben) |
| 51 | `victorystar` | 2 | Victini (Ace Trainer Runan), Victini (Rocket Admin Ariana) |
| 52 | `airlock` | 1 | Rayquaza (Lyris) |
| 53 | `anticipation` | 1 | Toxicroak (Ace Trainer Janny) |
| 54 | `arenatrap` | 1 | Diglett (Camper Flint) |
| 55 | `aromaveil` | 1 | Lechonk (School Kid BrunoRe) |
| 56 | `aurabreak` | 1 | Zygarde-Mega (Luciano) |
| 57 | `ballfetch` | 1 | Yamper (Fabbo67) |
| 58 | `beadsofruin` | 1 | Chi-Yu (Karen) |
| 59 | `cheekpouch` | 1 | Dedenne (Lt. Surge) |
| 60 | `comatose` | 1 | Komala (Ace Trainer Alexa) |
| 61 | `contrary` | 1 | Malamar (Karen) |
| 62 | `cottondown` | 1 | Eldegoss (Erika) |
| 63 | `darkaura` | 1 | Yveltal (Ester) |
| 64 | `defeatist` | 1 | Archeops (Scientist Ed) |
| 65 | `deltastream` | 1 | Rayquaza-Mega (Rocco) |
| 66 | `desolateland` | 1 | Groudon-Primal (Rocco) |
| 67 | `fairyaura` | 1 | Xerneas (Champion Terry) |
| 68 | `flowergift` | 1 | Cherrim (Fosco) |
| 69 | `forecast` | 1 | Rampardos (Scientist Ivan) |
| 70 | `forewarn` | 1 | Hypno (Gambler Darian) |
| 71 | `gulpmissile` | 1 | Cramorant (Sailor Trevor) |
| 72 | `harvest` | 1 | Exeggutor (Sabrina) |
| 73 | `healer` | 1 | Hatenna (Lass Haley) |
| 74 | `honeygather` | 1 | Combee (Bug Catcher Rick) |
| 75 | `icebody` | 1 | Vanillish (Vale_Fragola) |
| 76 | `illuminate` | 1 | Volbeat (Gentleman Komodo) |
| 77 | `liquidooze` | 1 | Tentacruel (Swimmer♀ Tisha) |
| 78 | `poisonheal` | 1 | Breloom (Trainer May) |
| 79 | `poisonpoint` | 1 | Dragalge (Alice) |
| 80 | `poisonpuppeteer` | 1 | Pecharunt (Koga) |
| 81 | `psychicsurge` | 1 | Indeedee (Pino) |
| 82 | `queenlymajesty` | 1 | Tsareena (Gardenia) |
| 83 | `quickdraw` | 1 | Slowbro-Galar (Pino) |
| 84 | `ripen` | 1 | Applin (Lass Miriam) |
| 85 | `slushrush` | 1 | Arctovish (Alfredo) |
| 86 | `solarpower` | 1 | Heliolisk (Fosco) |
| 87 | `soulheart` | 1 | Magearna (Jasmine) |
| 88 | `superluck` | 1 | Honchkrow (Boss Giovanni) |
| 89 | `swordofruin` | 1 | Chien-Pao (Karen) |
| 90 | `tabletsofruin` | 1 | Wo-Chien (Karen) |
| 91 | `tangledfeet` | 1 | Pidgeot (Lass Megan) |
| 92 | `telepathy` | 1 | Gardevoir (Frida) |
| 93 | `transistor` | 1 | Regieleki (Champion Terry) |
| 94 | `truant` | 1 | Slaking (Cue Ball Isaiah) |
| 95 | `waterbubble` | 1 | Araquanid (Raffaello) |
| 96 | `zenmode` | 1 | Darmanitan-Galar (Crush Girl Sharon) |

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
| `sheerforce` | 12 |
| `blaze` | 11 |
| `static` | 11 |
| `thickfat` | 11 |
| `unaware` | 11 |
| `flashfire` | 10 |
| `sandstream` | 10 |
| `sandveil` | 10 |
| `guts` | 9 |
| `overgrow` | 9 |
| `regenerator` | 9 |
| `roughskin` | 9 |
| `speedboost` | 9 |
| `waterabsorb` | 9 |
| `drought` | 8 |
| `flamebody` | 8 |
| `multiscale` | 8 |
| `noguard` | 8 |
| `owntempo` | 8 |
| `protosynthesis` | 8 |
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

