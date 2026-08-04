# Third-party notices

## @pkmn/sim

- Version: `0.10.11`
- Source: [pkmn/ps](https://github.com/pkmn/ps)
- Upstream simulator: [smogon/pokemon-showdown](https://github.com/smogon/pokemon-showdown)
- License: MIT
- Purpose: server-side Generation 9 Custom Game compatibility baseline and deterministic automatic battle execution

`@pkmn/sim` is an automatically generated extraction of the Pokémon Showdown simulator. Substantial portions are derived or generated from Pokémon Showdown server code distributed under the MIT License.

Cobbleventure Battle AI does not treat this dependency as its final AI implementation. It supplies a working mechanics baseline and later serves as a differential-test oracle for the independent Cobbleventure rules engine.

## Cobblemon GUI textures

The type icons under `public/assets/cobblemon/type-icons` are extracted from the
user-selected Cobblemon JAR at
`assets/cobblemon/textures/item/type_gem/*_gem.png`. The current snapshot comes
from Cobblemon 1.7.1 and is refreshed together with the Korean localization.
The move-category sprite at
`public/assets/cobblemon/gui/move-categories.png` is extracted from
`assets/cobblemon/textures/gui/categories.png` in the same JAR.

The supplied Cobblemon JAR includes the Mozilla Public License 2.0. These
textures remain Cobblemon project assets and are kept in their original,
unmodified PNG form.

## Pretendard

The web interface uses Pretendard 1.3.9 by Kil Hyung-jin. Pretendard is
distributed under the SIL Open Font License 1.1.

## Pokémon Showdown sprite assets

The interactive battle screen requests battle sprites from the Pokémon Showdown
sprite service through a same-origin cache endpoint. The sprite collection is
maintained at <https://github.com/smogon/sprites>.

The repository code is MIT-licensed, but the Pokémon sprites are owned by
Nintendo / Game Freak / The Pokémon Company, and some community-created sprite
licensing is still being determined. These assets are used only as replaceable
development visuals and are not redistributed in this repository.
