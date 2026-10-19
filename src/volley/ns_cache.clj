(ns volley.ns-cache
  "Which namespace infos a JVM run can take from the last run's cache
  (`.volley/ns-infos.edn`) and what to save for the next one.
  `volley.discover/ns-info` is a function of a file's path and text, so an
  info is reused when its file's SHA-256 and the parser's key are
  unchanged. The shell reads the files, hashes them and parses the rest.

  The cache is `{:parser key :files {path entry}}`, an entry being `{:sha
  hex :info ns-info}`, or `{:sha hex :uncacheable? true}` for an info
  that does not survive an EDN round trip: that file is parsed on every
  run, but an unchanged one does not make the cache be saved again.")

(defn plan-reuse
  "How to get the infos of `files` (`[{:path p :sha hex}]`) from `cache`
  under parser key `key`: `{:reuse {path info} :parse [path ...] :write?
  bool}`. A file is reused when its cached entry has its SHA and an info,
  and parsed otherwise. `:write?` is true when the cache does not
  already hold every file as it is now: a new or changed file, a file
  gone, or another parser."
  [cache key files]
  (let [known (if (= key (:parser cache)) (:files cache) {})
        hit (fn [{:keys [path sha]}]
              (let [entry (get known path)]
                (when (and (map? entry) (= sha (:sha entry))) entry)))
        reusable? #(when-let [entry (hit %)] (contains? entry :info))]
    {:reuse (into {} (comp (filter reusable?) (map (juxt :path #(:info (hit %))))) files)
     :parse (into [] (comp (remove reusable?) (map :path)) files)
     :write? (not (and (map? known)
                       (= (count known) (count (set (map :path files))))
                       (every? hit files)))}))

(defn next-cache
  "The cache to save under parser key `key` for `entries` (`[{:path p
  :sha hex :info ns-info}]`): each file's SHA, with its info when
  `(cacheable? info)`, else marked `:uncacheable?`."
  [key entries cacheable?]
  {:parser key
   :files (into {} (map (fn [{:keys [path sha info]}]
                          [path (if (cacheable? info)
                                  {:sha sha :info info}
                                  {:sha sha :uncacheable? true})]))
                entries)})
