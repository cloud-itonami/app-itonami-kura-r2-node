# kura-r2-node

A kura storage node whose disk is an R2 bucket.

`kura-conformance` already proves R2 can hold shards. What it could not do is
make R2 reachable *the way a machine on a shelf is reachable*: the binding only
exists inside a Worker, so R2 was usable **from** code and not **as** a node. A
placement plan, a coordinator, a durability run all had to special-case it.

Serving the same HTTP protocol `kura-node`'s `script/run_node.cljs` serves
removes the special case. `kura.node.http-node` talks to this exactly as it
talks to a Mac mini in Fukuoka — and that is what gave the fleet its fourth
failure domain, which is what made the target layout (n=26, 1.625×) fit at all:
4 domains × tolerance 7 = 28 ≥ 26.

## It refuses to serve without a token

judah and gad are reachable only from inside a tailnet of authenticated
devices, so Phase 0 could honestly say authentication was unnecessary — there
was nothing reachable to attack. **A Worker has no such perimeter.** Every route
except `/descriptor` requires `Authorization: Bearer $NODE_TOKEN`, and there is
no default: with the secret unset the node answers **503** and explains why,
rather than falling back to open access.

A storage node that fails open is worse than one that fails to start. An open
`PUT` is free storage for strangers; an open `DELETE` plus the `/shards`
listing hands an attacker the ids and the means.

`/descriptor` is deliberately ungated. A descriptor is a public claim about
failure domain and availability — a coordinator has to read it to decide
anything, and it exposes no data.

## Failure domain

`:shared-provider`, not `:independent`. Every bucket in one Cloudflare account
fails together, so the domain is the provider — the same reading
`kura.node.store/domain-key` applies to B2. Declaring per-bucket independence is
how a fleet counts one outage as four domains.

## Routes

| | |
|---|---|
| `GET /descriptor` | who this node claims to be (**ungated**) |
| `GET /self-check` | run the shard-store contract against the bucket |
| `GET /shards?prefix=` | what is held |
| `PUT /shard/<id>` | store |
| `GET /shard/<id>[?range=off,len]` | fetch |
| `HEAD /shard/<id>` | size without reading the body |
| `DELETE /shard/<id>` | remove |

## Verify it

From `kura-node`, the client's view — which is the one that matters, since a
backend is not verified by the machine that hosts it:

```bash
KURA_NODE_TOKEN=$(kagi get kura-r2-node-token) \
  nbb --classpath "src:script:../kura/src:../erasure/src:../merkle-sum/src:../sigv4/src" \
      script/remote_node_check.cljs https://kura-r2-node.04-feasts-minded.workers.dev
# => over HTTP : 19 / 19 PASS
```

Without the token the same run stops at the first read with HTTP 401.

## Deploy

```bash
npm install
node ../../../scripts/resource-guard.mjs run build -- npx shadow-cljs release worker
npx wrangler deploy
printf '%s' "$(kagi get kura-r2-node-token)" | npx wrangler secret put NODE_TOKEN
```

The token lives in kagi as `kura-r2-node-token` (compartment `personal`).
Wrangler secrets cannot be read back, so kagi is the only readable copy.
