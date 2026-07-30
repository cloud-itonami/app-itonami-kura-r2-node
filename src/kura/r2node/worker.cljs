(ns kura.r2node.worker
  "A kura storage node whose disk is an R2 bucket.

  `kura.conformance` already proves R2 can hold shards. What it could not do is
  make R2 reachable the way a machine on a shelf is reachable: the binding only
  exists inside a Worker, so R2 was usable *from* code and not *as* a node. A
  placement plan, a coordinator, a durability run all had to special-case it.
  Serving the same HTTP protocol `script/run_node.cljs` serves removes the
  special case — `kura.node.http-node` talks to this exactly as it talks to a
  Mac mini in Fukuoka.

  **This is a public write surface, which the tailnet nodes are not.** judah and
  gad are reachable only from inside a tailnet of authenticated devices, so
  Phase 0 could honestly say authentication was unnecessary — there was nothing
  reachable to attack. A Worker has no such perimeter, so every mutating and
  reading route requires a bearer token and there is no default value for it:
  `open>` refuses to serve rather than fall back to open access. A storage node
  that fails open is worse than one that fails to start.

  `/descriptor` is deliberately ungated. A descriptor is a public claim about
  failure domain and availability — a coordinator has to be able to read it to
  decide anything, and it exposes no data."
  (:require [clojure.string :as str]
            [kura.node.async :as async]
            [kura.node.r2 :as r2]
            [kura.node.store :as store]))

(def ^:private failure-domain
  ;; :shared-provider, not :independent. Every bucket in one Cloudflare account
  ;; fails together, so the domain is the provider — the same reading
  ;; kura.node.store/domain-key applies to B2. Declaring per-bucket
  ;; independence is how a fleet counts one outage as four domains.
  {:provider "cloudflare-r2" :account "kura-phase0"})

(defn- json [status body]
  (js/Response. (js/JSON.stringify (clj->js body) nil 2)
                #js {:status status
                     :headers #js {"content-type" "application/json; charset=utf-8"
                                   "cache-control" "no-store"}}))

(defn- authorised?
  "Constant-time-ish compare is not the concern here — the token is a random
  256-bit value and this is not an oracle for a low-entropy secret. What matters
  is that a MISSING secret denies rather than permits."
  [req env]
  (let [want (.-NODE_TOKEN ^js env)
        got (some-> (.get (.-headers ^js req) "authorization")
                    (str/replace #"^Bearer\s+" ""))]
    (and (string? want) (seq want) (= want got))))

(defn- store-of [env]
  (r2/open {:node-id "r2-phase0"
            :bucket (.-SHARDS ^js env)
            :prefix "kura"
            :independence :shared-provider
            :failure-domain failure-domain}))

(defn- shard-id-of [path]
  (second (re-matches #"^/shard/(.+)$" path)))

(defn- bytes-res [b]
  (js/Response. b #js {:status 200
                       :headers #js {"content-type" "application/octet-stream"
                                     "cache-control" "no-store"}}))

(defn- handle> [req env]
  (let [url (js/URL. (.-url ^js req))
        path (.-pathname url)
        method (.-method ^js req)
        s (store-of env)
        sid (shard-id-of path)]
    (cond
      ;; Ungated: a descriptor is a public claim, and a coordinator that cannot
      ;; read it cannot place anything.
      (= path "/descriptor")
      (js/Promise.resolve (json 200 (store/-descriptor s)))

      (not (.-NODE_TOKEN ^js env))
      (js/Promise.resolve
       (json 503 {:error "this node has no NODE_TOKEN configured and will not serve"
                  :why (str "A Worker has no network perimeter. Falling back to "
                            "open access would make every shard on this node "
                            "writable and deletable by anyone who found the "
                            "URL, so the node refuses to start serving instead.")}))

      (not (authorised? req env))
      (js/Promise.resolve (json 401 {:error "bearer token required"}))

      (= path "/self-check")
      (-> (async/run> s)
          (.then (fn [r] (json (if (zero? (:failed r)) 200 500) r))))

      (and sid (= method "PUT"))
      (-> (.arrayBuffer ^js req)
          (.then (fn [ab] (async/-put-shard!> s sid (js/Uint8Array. ab))))
          (.then (fn [r] (json 201 r)))
          (.catch (fn [e] (json 400 {:error (.-message e)}))))

      (and sid (= method "HEAD"))
      (-> (async/-shard-size> s sid)
          (.then (fn [n]
                   (if n
                     (js/Response. nil #js {:status 200
                                            :headers #js {"content-length" (str n)
                                                          "content-type" "application/octet-stream"}})
                     (js/Response. nil #js {:status 404})))))

      (and sid (= method "GET"))
      (let [range (.get (.-searchParams url) "range")]
        (-> (if range
              (let [[o l] (map js/parseInt (.split range ","))]
                (async/-get-range> s sid o l))
              (async/-get-shard> s sid))
            (.then (fn [b] (if b (bytes-res b) (json 404 {:error "absent"}))))
            (.catch (fn [e] (json 500 {:error (.-message e)})))))

      (and sid (= method "DELETE"))
      (-> (async/-delete-shard!> s sid)
          (.then (fn [removed] (json (if removed 200 404) {:removed (boolean removed)}))))

      (= path "/shards")
      (-> (async/-list-shards> s (or (.get (.-searchParams url) "prefix") ""))
          (.then (fn [ids] (json 200 {:shards (vec ids) :count (count ids)}))))

      :else
      (js/Promise.resolve
       (json 200 {:what "a kura storage node backed by an R2 bucket"
                  :routes {"GET /descriptor" "who this node claims to be (ungated)"
                           "GET /self-check" "run the shard-store contract against the bucket"
                           "GET /shards?prefix=" "what is held"
                           "PUT /shard/<id>" "store"
                           "GET /shard/<id>[?range=off,len]" "fetch"
                           "HEAD /shard/<id>" "size without reading the body"
                           "DELETE /shard/<id>" "remove"}
                  :auth "Bearer NODE_TOKEN on everything except /descriptor"
                  :note (str "Unlike a node on a tailnet, this has no network "
                             "perimeter — so it refuses to serve at all without "
                             "a token rather than defaulting to open.")})))))

(def handler
  #js {:fetch (fn [req env _ctx] (handle> req env))})
