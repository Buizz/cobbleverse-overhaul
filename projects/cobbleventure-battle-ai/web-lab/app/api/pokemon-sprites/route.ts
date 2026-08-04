import { showdownSpriteId } from "../../../lib/showdown-species.mjs";

const SHOWDOWN_SPRITE_ORIGIN = "https://play.pokemonshowdown.com";
const VALID_SPRITE_ID = /^[a-z0-9-]+$/;

function fallbackSprite() {
  return new Response(
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 160">
      <circle cx="80" cy="80" r="58" fill="#f6f5ee" stroke="#16243d" stroke-width="10"/>
      <path d="M22 80h116" stroke="#16243d" stroke-width="10"/>
      <path d="M25 73a58 58 0 0 1 110 0z" fill="#ef625c"/>
      <circle cx="80" cy="80" r="18" fill="#f6f5ee" stroke="#16243d" stroke-width="8"/>
    </svg>`,
    {
      status: 200,
      headers: {
        "content-type": "image/svg+xml; charset=utf-8",
        "cache-control": "public, max-age=300",
      },
    },
  );
}

export async function GET(request: Request) {
  const url = new URL(request.url);
  const requestedSpecies = (url.searchParams.get("species") ?? "").toLowerCase();
  const species = showdownSpriteId(requestedSpecies);
  const back = url.searchParams.get("back") === "1";
  const remote = url.searchParams.get("remote") === "1";
  const fallback = url.searchParams.get("fallback") === "1";
  if (fallback) {
    return fallbackSprite();
  }
  if (!VALID_SPRITE_ID.test(species)) {
    return fallbackSprite();
  }

  const folder = back ? "gen5-back" : "gen5";
  const upstreamUrl = `${SHOWDOWN_SPRITE_ORIGIN}/sprites/${folder}/${species}.png`;
  if (remote) {
    return new Response(null, {
      status: 302,
      headers: {
        location: upstreamUrl,
        "cache-control": "public, max-age=86400, stale-while-revalidate=604800",
      },
    });
  }

  try {
    const upstream = await fetch(upstreamUrl, {
      headers: { accept: "image/png,image/*;q=0.8" },
    });
    if (!upstream.ok || !upstream.body) {
      return fallbackSprite();
    }
    return new Response(upstream.body, {
      status: 200,
      headers: {
        "content-type": upstream.headers.get("content-type") ?? "image/png",
        "cache-control": "public, max-age=86400, stale-while-revalidate=604800",
      },
    });
  } catch {
    return fallbackSprite();
  }
}
