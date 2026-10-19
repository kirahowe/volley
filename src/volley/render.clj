(ns volley.render
  "Run state to text. Everything here is a pure function of the state and an
  options map `{:width n :color? bool :now millis}`.

  Live rendering is a function of the transition between two states:
  `(live-frame prev state opts)` returns the lines that became final since
  `prev` (`:emit`, printed once and never redrawn) and the lines that change
  on every tick (`:frame`, redrawn in place). The shell keeps `prev` as the
  state it rendered last, which is the only extra memory the live view needs.

  Every returned line is at most `:width` columns wide. Text from tests (names,
  messages, captured output) is stripped of control characters and escape
  sequences before it is used, so colour appears only where this namespace
  puts it."
  (:require
   [volley.run-state :as rs]
   [clojure.string :as str]))

;;; Text, width and colour

(def ^:private default-width 80)

(def ^:private ansi-re #"\u001b\[[0-9;?]*[ -/]*[@-~]")

(defn- clean
  "Stringifies `s` and removes escape sequences and control characters other
  than newlines. Tabs become spaces."
  [s]
  (-> (str s)
      (str/replace ansi-re "")
      (str/replace #"\r\n?" "\n")
      (str/replace "\t" "    ")
      (str/replace #"[\u0000-\u0009\u000B-\u001F\u007F-\u009F]" "")))

(defn- ext
  "Cleans external text and caps its size so that huge values stay cheap."
  [s]
  (let [s (clean s)]
    (if (> (count s) 20000) (str (subs s 0 20000) "…") s)))

(defn- cp-width [cp]
  (cond
    (or (<= 0x300 cp 0x36F) (<= 0x200B cp 0x200F) (= cp 0xFE0F)) 0
    (or (<= 0x1100 cp 0x115F) (<= 0x2E80 cp 0xA4CF) (<= 0xAC00 cp 0xD7A3)
        (<= 0xF900 cp 0xFAFF) (<= 0xFE30 cp 0xFE6F) (<= 0xFF00 cp 0xFF60)
        (<= 0xFFE0 cp 0xFFE6) (<= 0x1F300 cp 0x1F6FF) (<= 0x1F900 cp 0x1F9FF)
        (<= 0x20000 cp 0x3FFFD)) 2
    :else 1))

(defn- take-width
  "Longest prefix of `s` that fits in `limit` columns, with its width."
  [^String s limit]
  (loop [i 0 w 0]
    (if (>= i (count s))
      [s w]
      (let [cp (.codePointAt s (int i))
            cw (cp-width cp)]
        (if (> (+ w cw) limit)
          [(subs s 0 i) w]
          (recur (+ i (Character/charCount cp)) (+ w cw)))))))

(defn- strip-ansi [s] (str/replace s ansi-re ""))

(defn- visible-width [s]
  (second (take-width (strip-ansi s) Integer/MAX_VALUE)))

(defn- fit
  "Truncates a single line to `width` visible columns with an ellipsis,
  passing colour escapes through and closing them when it cuts."
  [s width]
  (cond
    (<= width 0) ""
    (<= (visible-width s) width) s
    :else
    (loop [[tok & more] (re-seq #"\u001b\[[0-9;]*m|[^\u001b]+" s)
           room (dec width)
           out []]
      (cond
        (nil? tok) (str (apply str out) "…")
        (= \u001b (first tok)) (recur more room (conj out tok))
        :else (let [[head w] (take-width tok room)]
                (if (= head tok)
                  (recur more (- room w) (conj out tok))
                  (str (apply str out) head "…"
                       (when (some #(= \u001b (first %)) out) "\u001b[0m"))))))))

(defn- finish
  "Splits entries on newlines and fits each line to the width in `opts`."
  [lines opts]
  (let [width (:width opts default-width)]
    (into [] (comp (mapcat #(str/split-lines (str %))) (map #(fit % width))) lines)))

(def ^:private codes
  {:green "32" :red "31" :yellow "33" :cyan "36" :gray "90" :bold "1"})

(defn- paint [{:keys [color?]} style s]
  (if (and color? (seq s))
    (str "\u001b[" (codes style) "m" s "\u001b[0m")
    s))

(defn- spaces [n] (apply str (repeat (max 0 n) \space)))

(defn- two-col
  "`left` and `right` on one line, right-aligned, with `left` shortened first."
  [left right width]
  (let [rw   (visible-width right)
        left (fit left (max 0 (- width rw 1)))
        lw   (visible-width left)]
    (if (< (+ lw 1 rw) (inc width))
      (str left (spaces (- width lw rw)) right)
      left)))

(defn- pad-left [s n] (str (spaces (- n (count s))) s))

(defn- fmt-ms
  "340ms, 2.31s or 1m 05s."
  [ms]
  (let [ms (if (number? ms) (max 0 ms) 0)
        p2 (fn [n] (if (< n 10) (str "0" n) (str n)))]
    (cond
      (< ms 1000)  (str (Math/round (double ms)) "ms")
      (< ms 60000) (let [h (Math/round (/ (double ms) 10.0))]
                     (str (quot h 100) "." (p2 (rem h 100)) "s"))
      :else        (let [s (quot (Math/round (double ms)) 1000)]
                     (str (quot s 60) "m " (p2 (rem s 60)) "s")))))

(defn- plural [n singular]
  (str n " " singular (when (not= 1 n) "s")))

(defn- qualified [ns var]
  (str (ext ns) (when var (str "/" (ext var)))))

(defn- location [{:keys [file line]}]
  (when file (str (ext file) (when line (str ":" line)))))

(defn- limit-lines
  "Keeps the first `n` of `lines`, replacing the rest by a count."
  [lines n]
  (if (> (count lines) n)
    (conj (vec (take n lines)) (str "… " (- (count lines) n) " more lines"))
    (vec lines)))

;;; Structural diff

(declare diff)

(defn- same-node [v] {:type :same :value v})

(defn- collapse
  "A container node whose children are all unchanged is just :same."
  [node original children]
  (if (every? #(= :same (:type %)) children) (same-node original) node))

(defn- by-pr [xs] (sort-by pr-str xs))

(defn- diff-maps [a b]
  (let [ks      (by-pr (distinct (concat (keys a) (keys b))))
        entries (mapv (fn [k]
                        [k (cond
                             (not (contains? b k)) {:type :missing :value (get a k)}
                             (not (contains? a k)) {:type :extra :value (get b k)}
                             :else                 (diff (get a k) (get b k)))])
                      ks)]
    (collapse {:type :map :entries entries} a (map second entries))))

(defn- diff-sets [a b]
  (let [missing (vec (by-pr (remove b a)))
        extra   (vec (by-pr (remove a b)))]
    (if (and (empty? missing) (empty? extra))
      (same-node a)
      {:type :set :missing missing :extra extra
       :same-count (- (count a) (count missing))})))

(def ^:private lcs-cell-limit 40000)

(defn- lcs-table
  "Suffix LCS lengths for vectors `a` and `b`, as a vector of vectors."
  [a b]
  (let [n (count a) m (count b)
        row0 (vec (repeat (inc m) 0))]
    (reduce
     (fn [rows i]
       (let [below (first rows)
             row   (reduce (fn [row j]
                             (assoc row j (if (= (a i) (b j))
                                            (inc (below (inc j)))
                                            (max (below j) (row (inc j))))))
                           row0
                           (range (dec m) -1 -1))]
         (cons row rows)))
     (list row0)
     (range (dec n) -1 -1))))

(defn- seq-ops
  "Edit script `[[:same x] [:missing x] [:extra y] ...]` turning `a` into `b`."
  [a b]
  (let [table (vec (lcs-table a b))
        n (count a) m (count b)]
    (loop [i 0 j 0 ops []]
      (cond
        (and (= i n) (= j m)) ops
        (and (< i n) (< j m) (= (a i) (b j))) (recur (inc i) (inc j) (conj ops [:same (a i)]))
        (and (< i n) (or (= j m) (>= (get-in table [(inc i) j]) (get-in table [i (inc j)]))))
        (recur (inc i) j (conj ops [:missing (a i)]))
        :else (recur i (inc j) (conj ops [:extra (b j)]))))))

(defn- run->nodes
  "Pairs the missing and extra items of one change run position by position
  into nested diffs; leftovers stay missing or extra."
  [run]
  (let [ms (mapv second (filter #(= :missing (first %)) run))
        es (mapv second (filter #(= :extra (first %)) run))
        k  (min (count ms) (count es))]
    (concat (map diff (take k ms) (take k es))
            (map (fn [x] {:type :missing :value x}) (drop k ms))
            (map (fn [x] {:type :extra :value x}) (drop k es)))))

(defn- ops->nodes [ops]
  (mapcat (fn [[same? run]]
            (if same?
              (map (fn [[_ x]] (same-node x)) run)
              (run->nodes run)))
          (map (fn [run] [(= :same (ffirst run)) run])
               (partition-by #(= :same (first %)) ops))))

(defn- positional-nodes [a b]
  (let [k (min (count a) (count b))]
    (concat (map diff (take k a) (take k b))
            (map (fn [x] {:type :missing :value x}) (drop k a))
            (map (fn [x] {:type :extra :value x}) (drop k b)))))

(defn- diff-seqs [a b]
  (let [av (vec a) bv (vec b)
        nodes (vec (if (<= (* (count av) (count bv)) lcs-cell-limit)
                     (ops->nodes (seq-ops av bv))
                     (positional-nodes av bv)))
        [open close] (if (vector? a) ["[" "]"] ["(" ")"])]
    (collapse {:type :seq :open open :close close :items nodes} a nodes)))

(defn diff
  "Structural diff of `expected` against `actual`, as a tree. Node types:
  :same, :changed (`:expected` `:actual`), :missing and :extra (`:value`, in
  expected only or in actual only), :map (`:entries` of `[k node]`), :seq
  (`:items`), :set (`:missing` `:extra` `:same-count`). Sequential values are
  aligned with a longest-common-subsequence edit script."
  [expected actual]
  (cond
    (= expected actual) (same-node expected)
    (and (map? expected) (map? actual)) (diff-maps expected actual)
    (and (set? expected) (set? actual)) (diff-sets expected actual)
    (and (sequential? expected) (sequential? actual)) (diff-seqs expected actual)
    :else {:type :changed :expected expected :actual actual}))

(defn diff-stats
  "Counts leaf differences in a diff tree: `{:missing n :extra n :changed n}`."
  [node]
  (let [zero {:missing 0 :extra 0 :changed 0}
        add  #(merge-with + %1 %2)]
    (case (:type node)
      :same    zero
      :missing (assoc zero :missing 1)
      :extra   (assoc zero :extra 1)
      :changed (assoc zero :changed 1)
      :map     (reduce add zero (map (comp diff-stats second) (:entries node)))
      :seq     (reduce add zero (map diff-stats (:items node)))
      :set     (assoc zero :missing (count (:missing node)) :extra (count (:extra node))))))

(defn- abbrev [v]
  (let [s (ext (pr-str v))
        s (str/replace s "\n" " ")]
    (if (> (count s) 120) (str (subs s 0 119) "…") s)))

(defn- indent-of [depth] (spaces (* 2 depth)))

(declare node-lines)

(defn- group-same
  "Replaces runs of more than two unchanged children by one ellipsis entry."
  [children]
  (mapcat (fn [run]
            (if (and (= :same (:type (second (first run)))) (> (count run) 2))
              [[:unchanged (count run)]]
              run))
          (partition-by #(= :same (:type (second %))) children)))

(defn- container-lines [open close prefix children depth]
  (let [pad (indent-of depth)]
    (-> [[" " (str pad prefix open)]]
        (into (mapcat (fn [[p n]]
                        (if (= :unchanged p)
                          [[" " (str (indent-of (inc depth)) "… " n " unchanged")]]
                          (node-lines n p (inc depth))))
                      (group-same children)))
        (conj [" " (str pad close)]))))

(defn- node-lines
  "Diff tree to `[marker text]` pairs: \"-\" expected only, \"+\" actual only."
  [node prefix depth]
  (let [pad  (indent-of depth)
        line (fn [marker v] [marker (str pad prefix (abbrev v))])]
    (case (:type node)
      :same    [(line " " (:value node))]
      :missing [(line "-" (:value node))]
      :extra   [(line "+" (:value node))]
      :changed [(line "-" (:expected node)) (line "+" (:actual node))]
      :map     (container-lines "{" "}" prefix
                                (map (fn [[k n]] [(str (abbrev k) " ") n]) (:entries node))
                                depth)
      :seq     (container-lines (:open node) (:close node) prefix
                                (map (fn [n] ["" n]) (:items node))
                                depth)
      :set     (-> [[" " (str pad prefix "#{")]]
                   (into (when (pos? (:same-count node))
                           [[" " (str (indent-of (inc depth)) "… " (:same-count node) " unchanged")]]))
                   (into (map (fn [v] ["-" (str (indent-of (inc depth)) (abbrev v))]) (:missing node)))
                   (into (map (fn [v] ["+" (str (indent-of (inc depth)) (abbrev v))]) (:extra node)))
                   (conj [" " (str pad "}")])))))

(def ^:private max-diff-lines 40)

(defn diff-lines
  "Renders a diff tree as lines, each starting with \"- \", \"+ \" or two
  spaces. Colour marks removed lines red and added lines green."
  [node opts]
  (let [pairs (node-lines node "" 0)
        shown (map (fn [[marker text]]
                     (let [l (str marker " " text)]
                       (case marker
                         "-" (paint opts :red l)
                         "+" (paint opts :green l)
                         l)))
                   (take max-diff-lines pairs))
        more  (- (count pairs) max-diff-lines)]
    (cond-> (vec shown)
      (pos? more) (conj (paint opts :gray (str "  … " more " more diff lines"))))))

;;; Failure report

(defn- normalize-value
  "Values arrive as `{:str s :edn v}`; anything else is treated as the value."
  [v]
  (cond
    (nil? v) nil
    (and (map? v) (or (contains? v :str) (contains? v :edn))) v
    :else {:str (pr-str v) :edn v}))

(defn- value-str [v]
  (or (:str v) (when (contains? v :edn) (pr-str (:edn v)))))

(defn- not-equal-args
  "For clojure.test's `(not (= a b))` actual form (Kaocha's
  `kaocha.report/fail-summary` takes it apart the same way), returns [a b].
  The `=` may be qualified, as it is when a macro expands to the `is`."
  [form]
  (when (and (seq? form) (= 'not (first form)))
    (let [inner (second form)]
      (when (and (seq? inner) (contains? #{'= 'clojure.core/=} (first inner)) (= 3 (count inner)))
        [(nth inner 1) (nth inner 2)]))))

(defn- comparison
  "Returns `{:expected v :actual v :pair [e a]}` for an assertion, values
  as `{:str :edn}`. :pair is present only for a failed `(is (= e a))`, the
  one case where expected and actual are two values to diff (as in
  Kaocha's `kaocha.report/fail-summary`). Otherwise `:expected` is the
  asserted form and `:actual` what it evaluated to, and a diff of the two
  means nothing."
  [{:keys [expected actual]}]
  (let [e (normalize-value expected)
        a (normalize-value actual)
        [x y :as args] (when (contains? a :edn) (not-equal-args (:edn a)))]
    (if args
      {:expected {:edn x} :actual {:edn y} :pair [x y]}
      {:expected e :actual a})))

;;; Pretty values

(def ^:private max-value-lines
  "How many lines of one expected or actual value a report shows."
  80)

(defn- hard-wrap
  "`s` cut into pieces of at most `width` columns."
  [s width]
  (let [width (max 10 width)]
    (loop [s s out []]
      (let [[head w] (take-width s width)]
        (if (or (= head s) (zero? w))
          (conj out s)
          (recur (subs s (count head)) (conj out head)))))))

(declare pretty)

(defn- entry-lines
  "A map entry `k v` within `width` columns: on one line when it fits;
  else the value's lines start after a one-line key and continue below it
  when they fit there; else the key's lines and then the value's,
  indented by two."
  [k v width]
  (let [ks (pr-str k)
        one (str ks " " (pr-str v))
        room (- width (visible-width ks) 1)
        beside (when (>= room 10) (pretty v room))]
    (cond
      (<= (visible-width one) width) [one]
      (and beside (every? #(<= (visible-width %) room) beside))
      (let [pad (spaces (inc (visible-width ks)))]
        (into [(str ks " " (first beside))] (map #(str pad %)) (rest beside)))
      :else (into (pretty k width) (map #(str "  " %)) (pretty v (- width 2))))))

(defn pretty
  "Lines of `v` printed readably within `width` columns where its
  structure allows, the way `clojure.pprint` lays out code-free data:
  a collection that does not fit on one line puts each item on a line of
  its own, indented under the opening bracket. A scalar that is still too
  wide is left whole for the caller to wrap."
  [v width]
  (let [s (pr-str v)]
    (if (or (<= (visible-width s) width) (not (coll? v)) (empty? v) (record? v))
      [s]
      (let [[open close] (cond (map? v) ["{" "}"] (vector? v) ["[" "]"] (set? v) ["#{" "}"] :else ["(" ")"])
            pad (count open)
            ;; room for the closing bracket, which ends the last line
            inner (- width pad (count close))
            lines (vec (if (map? v)
                         (mapcat (fn [[k x]] (entry-lines k x inner)) v)
                         (mapcat #(pretty % inner) v)))]
        (-> (vec (map-indexed (fn [i l] (str (if (zero? i) open (spaces pad)) l)) lines))
            (update (dec (count lines)) str close))))))

(defn- value-lines
  "`label` and value `v` (`{:str :edn}`) pretty-printed and wrapped to the
  report's width, never cut short: continuation lines line up under the
  value. At most `max-value-lines` lines, then a count of the rest."
  [opts label style v]
  (let [width (- (:width opts default-width) 3 (count label))
        raw (if (contains? v :edn)
              (pretty (:edn v) width)
              (str/split-lines (ext (value-str v))))
        lines (vec (mapcat #(hard-wrap (ext %) width) raw))
        shown (take max-value-lines lines)
        more (- (count lines) (count shown))
        pad (spaces (count label))]
    (cond-> (vec (map-indexed (fn [i l] (str (if (zero? i) label pad) (paint opts style l))) shown))
      (pos? more) (conj (paint opts :gray (str pad "… " more " more lines"))))))

(defn- diff-section [opts [x y]]
  (when (or (coll? x) (coll? y))
    (let [d (diff x y)]
      (when-not (= :same (:type d))
        (into [(paint opts :gray "diff (- expected, + actual):")]
              (diff-lines d opts))))))

(defn- error-lines [opts err depth]
  (when (and err (< depth 4))
    (let [{:keys [class message data trace cause]} err
          head (str (when (pos? depth) "Caused by: ") (ext (or class "error"))
                    (when (some? message) (str ": " (ext message))))]
      (concat
       [(paint opts :red head)]
       (when data [(paint opts :gray (str "data: " (ext data)))])
       (map #(paint opts :gray (str "  at " (ext %))) (take 8 trace))
       (when (> (count trace) 8)
         [(paint opts :gray (str "  … " (- (count trace) 8) " more frames"))])
       (error-lines opts cause (inc depth))))))

(defn- indent-lines [n lines]
  (map #(str (spaces n) %) lines))

(defn- split-text [s]
  (str/split-lines (ext s)))

(defn- kind-label [opts kind]
  (case kind
    :fail     (paint opts :red " FAIL ")
    :error    (paint opts :red " ERROR ")
    :ns-error (paint opts :red " ERROR ")
    :crash    (paint opts :red " CRASH ")
    :file-error (paint opts :red " ERROR ")
    :worker-error (paint opts :red " ERROR ")
    :protocol-error (paint opts :red " ERROR ")
    (paint opts :red (str " " (name kind) " "))))

(defn- hook-name
  "`pre-test hook my.hooks/check` for a failure's `:hook`."
  [{:keys [kind entry]}]
  (str (some-> kind name) " hook " (ext (pr-str entry))))

(defn- header-text [{:keys [kind ns var] :as f}]
  (case kind
    :ns-error (str (qualified ns nil) (cond
                                        (= :setup (:phase f)) "  not run: worker setup failed"
                                        (:hook f) (str "  the " (hook-name (:hook f)) " failed")
                                        (= :run (:phase f)) "  failed in a fixture"
                                        :else "  failed to load"))
    :crash    (str (if ns (qualified ns var) (str "worker " (:worker f)))
                   "  worker exited with code " (:exit-code f))
    :file-error "ns form could not be read"
    :worker-error (str (if (next (:workers f))
                         (str "workers " (str/join ", " (:workers f)))
                         (str "worker " (:worker f)))
                       "  " (if-let [h (:hook f)] (str "the " (hook-name h)) "a hook")
                       " failed as " (if (next (:workers f)) "they" "it") " shut down")
    (:fail :error) (str (qualified ns var) (when-not var "  in a :once fixture"))
    :protocol-error (str (if ns (qualified ns var) (str "worker " (:worker f)))
                         "  a message from the worker could not be read")
    (qualified ns var)))

(defn- protocol-error-lines [opts {:keys [reason text]}]
  (let [t (ext text)]
    [(paint opts :red (str "reason: " (ext reason)))
     (paint opts :gray (str "line: " (if (> (count t) 300) (str (subs t 0 300) "…") t)))
     (paint opts :gray "the results of this worker may be incomplete")]))

(def ^:private output-tail-lines
  "How many of the last lines of a test's output a report shows."
  30)

(defn- output-section
  "The end of what a test printed: its last lines, after a count of the
  earlier ones, since the end is where a failure usually shows."
  [opts output]
  (when-not (str/blank? output)
    (let [lines (str/split-lines (str output))
          earlier (max 0 (- (count lines) output-tail-lines))]
      (concat [(paint opts :gray "output:")]
              (when (pos? earlier)
                [(paint opts :gray (str "  | … " earlier " earlier lines"))])
              (map #(paint opts :gray (str "  | " (ext %))) (drop earlier lines))))))

(defn- check-lines
  "A failed test.check property: how it failed, the shrunk input, and how
  to replay it with the same seed."
  [opts {:keys [ns var check]}]
  (let [{:keys [seed num-tests failing-size smallest result]} check]
    (concat
     [(str "property failed after " (plural num-tests "test")
           " (seed " seed (when failing-size (str ", size " failing-size)) ")")]
     (value-lines opts "smallest failing input: " :red (normalize-value smallest))
     (when result (value-lines opts "result: " :red (normalize-value result)))
     (when (and ns var)
       [(paint opts :gray (str "rerun: (" (qualified ns var) " " num-tests " :seed " seed ")"))]))))

(defn- failure-lines
  "Lines for one failure. Captured output is added when `output?` is true."
  [opts {:keys [kind contexts message error check] :as f} output?]
  (let [{:keys [expected actual pair]} (when (and (#{:fail :error} kind) (not check)) (comparison f))
        loc (location f)]
    (concat
     [(str (kind-label opts kind) " " (header-text f)
           (when loc (str "  " (paint opts :gray loc))))]
     (indent-lines
      3
      (concat
       (when (seq contexts)
         [(paint opts :gray (str "testing: " (str/join " > " (map ext contexts))))])
       (when-not (str/blank? (str message))
         (limit-lines (split-text message) 10))
       (when expected (value-lines opts "expected: " :green expected))
       (when actual (value-lines opts "actual:   " :red actual))
       (when pair (diff-section opts pair))
       (when check (check-lines opts f))
       (when (#{:error :ns-error :worker-error} kind) (error-lines opts error 0))
       (when (= :protocol-error kind) (protocol-error-lines opts f))
       (when-let [vs (seq (:not-run-vars f))]
         [(paint opts :yellow (str "not run: " (str/join ", " (map ext vs))))])
       (when output? (output-section opts (:output f))))))))

(defn still-running-line
  "`still running: ns/var (1m 05s)`, for a test that runs for a long time
  in CI, where no live view shows it."
  [{:keys [ns var elapsed]} opts]
  (first (finish [(paint opts :yellow (str "still running: " (qualified ns var) " (" (fmt-ms elapsed) ")"))]
                 opts)))

(defn stray-lines
  "Output a test printed outside capture, for the live view, which shows
  it above the frame. Control and escape sequences are removed, but lines
  keep their full length: they are printed once and scroll away with the
  terminal's own wrapping, and only the redrawn frame must fit the width."
  [text _opts]
  (str/split-lines (ext text)))

(defn protocol-notice
  "One line saying that a protocol line from `worker` could not be read,
  shown the moment it happens. `ns` is the namespace the worker held."
  [{:keys [worker ns reason]} opts]
  (first (finish [(paint opts :red (str "volley: a message from worker " worker
                                        (when ns (str " (running " (ext ns) ")"))
                                        " could not be read: " (ext reason)))]
                 opts)))

(defn- reported-failures
  "Failures that get a detailed entry. Namespaces that never ran are listed
  by `summary` instead."
  [state]
  (into [] (remove #(= :not-run (:kind %))) (rs/failures state)))

(defn- report-lines [fs opts]
  (let [last-of-var? (fn [f g] (not= [(:ns f) (:var f)] [(:ns g) (:var g)]))
        output?      (map-indexed (fn [i f] (last-of-var? f (get fs (inc i)))) fs)]
    (->> (concat
          [(paint opts :red "━━━ Failed Tests ━━━")]
          (mapcat (fn [f o?] (cons "" (failure-lines opts f o?))) fs output?))
         (#(finish % opts))
         (str/join "\n"))))

(defn orphan-report
  "Detailed report for failures that belong to no namespace that ran (test
  files whose ns form could not be read), which `ci-lines` never shows. \"\"
  when there are none."
  [state opts]
  (let [fs (filterv #(not (contains? (:namespaces state) (:ns %))) (reported-failures state))]
    (if (empty? fs) "" (report-lines fs opts))))

(defn failure-report
  "Detailed report for every failure in `state`, or \"\" when there are none."
  [state opts]
  (let [fs (reported-failures state)]
    (if (empty? fs) "" (report-lines fs opts))))

;;; Namespace lines and live view

(defn- ns-duration [{:keys [duration-ms start-at end-at]}]
  (or duration-ms (when (and start-at end-at) (- end-at start-at))))

(defn- interrupted-text
  "Why interrupted namespace record `rec` stopped part way."
  [rec]
  (if (= :stopped (:reason rec)) "stopped before all its tests ran" "interrupted by fail-fast"))

(defn- ns-detail [rec]
  (let [vars    (rs/run-vars rec)
        failed  (count (filter (comp #{:failed :errored :crashed} rs/var-status) vars))
        skipped (count (filter (comp #{:skipped} rs/var-status) vars))]
    (case (rs/ns-result rec)
      :passed  (str "(" (plural (count vars) "test")
                    (when (pos? skipped) (str ", " skipped " skipped")) ")")
      :failed  (if (zero? failed)
                 "(failed in a fixture)"
                 (str "(" failed "/" (count vars) " failed)"))
      :error   (cond
                 (= :setup (:phase rec)) "(worker setup failed)"
                 (:hook rec) (str "(" (some-> rec :hook :kind name) " hook failed)")
                 (= :run (:phase rec)) "(fixture failed)"
                 :else "(failed to load)")
      :crashed (str "(worker crashed" (when-let [c (:exit-code rec)] (str ", exit " c)) ")")
      :interrupted (str "(" (interrupted-text rec) ", " (count vars) " done)")
      "")))

(defn- ns-line
  "` ✓ my.app-test (12 tests) 340ms`, or `✗` with the failed count in red."
  [opts rec]
  (let [result (rs/ns-result rec)
        style  (case result :passed :green :interrupted :yellow :red)
        mark   (case result :passed "✓" :interrupted "-" "✗")
        dur    (ns-duration rec)]
    (str " " (paint opts style (str mark " " (ext (:ns rec))))
         " " (paint opts :gray (ns-detail rec))
         (when dur (str " " (paint opts :gray (fmt-ms dur)))))))

(defn- finished? [rec] (not= :running (rs/ns-result rec)))

(defn- candidates
  "Namespace records of `state` that may have changed since the last
  render: those named in `(:only opts)` when the caller tracks them, else
  every one."
  [state opts]
  (if-let [only (:only opts)]
    (keep #(get-in state [:namespaces %]) only)
    (vals (:namespaces state))))

(defn- newly-finished [prev state opts]
  (->> (candidates state opts)
       (filter finished?)
       (remove rs/unselected?)
       (remove #(some-> (get-in prev [:namespaces (:ns %)]) finished?))
       (sort-by (juxt #(or (:end-at %) 0) #(str (:ns %))))))

(defn- bad-var-keys [recs]
  (set (for [rec recs
             v   (vals (:vars rec))
             :when (#{:failed :errored} (rs/var-status v))]
         [(:ns rec) (:var v)])))

(defn- newly-failed-vars [prev state opts]
  (let [before (when prev (bad-var-keys (candidates prev opts)))]
    (->> (bad-var-keys (candidates state opts))
         (remove (or before #{}))
         sort
         (map (fn [[ns var]] (get-in state [:namespaces ns :vars var]))))))

(defn- fail-line [opts v]
  (str "   " (paint opts :red (str "✗ " (qualified (:ns v) (:var v))))
       (when-let [loc (location v)] (str " " (paint opts :gray (str "(" loc ")"))))))

(def ^:private spinner-frames ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn- spinner [opts]
  (nth spinner-frames (mod (quot (long (or (:now opts) 0)) 80) (count spinner-frames))))

(defn- running-line [opts {:keys [ns var ns-at]}]
  (let [width (:width opts default-width)
        now   (or (:now opts) ns-at 0)
        left  (str " " (paint opts :cyan (spinner opts)) " " (ext ns)
                   (when var (str (paint opts :gray " › ") (ext var))))
        right (paint opts :gray (str (fmt-ms (- now (or ns-at now))) " "))]
    (two-col left right width)))

(defn- count-parts [opts parts]
  (let [shown (for [[n label style] parts :when (pos? n)]
                (paint opts style (str n " " label)))]
    (if (seq shown) (str/join (paint opts :gray " | ") shown) (paint opts :gray "0"))))

(defn- footer [opts state]
  (let [now (or (:now opts) (:now state))
        {:keys [namespaces vars wall-ms]} (rs/summary state now)
        row (fn [label body] (str (pad-left label 11) "  " body))]
    [(row "Namespaces"
          (str (count-parts opts [[(:passed namespaces) "passed" :green]
                                  [(:failed namespaces) "failed" :red]
                                  [(:errored namespaces) "errored" :red]
                                  [(:running namespaces) "running" :cyan]
                                  [(:pending namespaces) "pending" :gray]])
               (paint opts :gray (str " (" (:total namespaces) ")"))))
     (row "Tests"
          (str (count-parts opts [[(:passed vars) "passed" :green]
                                  [(:failed vars) "failed" :red]
                                  [(:errored vars) (if (= 1 (:errored vars)) "error" "errors") :red]
                                  [(:skipped vars) "skipped" :yellow]
                                  [(:running vars) "running" :cyan]])
               (paint opts :gray (str " (" (:total vars) ")"))))
     (row "Duration" (fmt-ms wall-ms))]))

(defn- running-block [opts state]
  (let [workers (rs/running state)
        cap     (:max-running opts 10)
        shown   (take cap workers)
        more    (- (count workers) (count shown))]
    (cond-> (mapv #(running-line opts %) shown)
      (pos? more) (conj (paint opts :gray (str "   … and " more " more running"))))))

(defn live-frame
  "Splits a state transition into `{:emit [...] :frame [...]}`. `prev` is the
  state last rendered, or nil. :emit holds permanent lines: a line for each
  var that failed since `prev` and for each namespace that finished since
  `prev`. :frame holds the redrawn part: one line per busy worker, then the
  counts footer. With `:final? true` in `opts` the frame is empty.
  `(:only opts)`, when given, holds every namespace whose events arrived
  since `prev`, and only those are looked at."
  [prev state opts]
  (let [emit  (concat (map #(fail-line opts %) (newly-failed-vars prev state opts))
                      (map #(ns-line opts %) (newly-finished prev state opts)))
        block (running-block opts state)
        frame (if (:final? opts)
                []
                (concat block (when (seq block) [""]) (footer opts state)))]
    {:emit  (finish emit opts)
     :frame (finish frame opts)}))

(defn ci-lines
  "Append-only lines for non-TTY output: a line for each namespace that
  finished since `prev` (or nil), followed by detailed reports for that
  namespace's failures. `(:only opts)` as in `live-frame`."
  [prev state opts]
  (->> (newly-finished prev state opts)
       (mapcat (fn [rec]
                 (let [nfs (rs/ns-failures-of state (:ns rec))]
                   (cons (ns-line opts rec)
                         (mapcat (fn [[f g]]
                                   (cons "" (failure-lines opts f (not= [(:ns f) (:var f)]
                                                                        [(:ns g) (:var g)]))))
                                 (map vector nfs (concat (rest nfs) [nil])))))))
       (#(finish % opts))))

;;; Documentation reporter

(defn- doc-var-line [opts v]
  (let [status (rs/var-status v)
        name*  (ext (:var v))
        dur    (when (number? (:duration-ms v)) (paint opts :gray (str " " (fmt-ms (:duration-ms v)))))]
    (case status
      :passed  (str "  " (paint opts :green (str "✓ " name*)) dur)
      :skipped (str "  " (paint opts :yellow (str "- " name*))
                    (paint opts :gray (str " (skipped" (when-let [r (:reason v)] (str ": " (name r))) ")")))
      (str "  " (paint opts :red (str "✗ " name*)) dur
           (when (= :crashed status) (paint opts :red " (worker crashed)"))))))

(defn- doc-ns-lines [opts rec]
  (concat
   [(str (paint opts :bold (ext (:ns rec)))
         (when-let [d (ns-duration rec)] (paint opts :gray (str " " (fmt-ms d)))))]
   (map #(doc-var-line opts %) (sort-by (comp str :var) (rs/run-vars rec)))
   (case (rs/ns-result rec)
     :error   [(str "  " (paint opts :red (if (= :setup (:phase rec)) "✗ worker setup failed" "✗ failed to load")))]
     :crashed (when (empty? (:vars rec))
                [(str "  " (paint opts :red "✗ worker crashed"))])
     :interrupted [(str "  " (paint opts :yellow (str "- " (interrupted-text rec))))]
     nil)
   [""]))

(defn documentation-lines
  "Append-only lines for the documentation reporter (Kaocha's
  `kaocha.report/documentation`): for each namespace that finished since
  `prev` (or nil), its name and one line per test var with ✓, ✗ or - and
  its duration. Namespaces are printed whole when they finish, so lines of
  namespaces running in parallel never interleave. `(:only opts)` as in
  `live-frame`."
  [prev state opts]
  (finish (mapcat #(doc-ns-lines opts %) (newly-finished prev state opts)) opts))

;;; Final summary

(defn- focus-ids [state]
  (->> (rs/failures state)
       (keep (fn [{:keys [ns var kind]}]
               (when ns (if (and var (#{:fail :error} kind)) (qualified ns var) (qualified ns nil)))))
       distinct
       sort))

(def ^:private max-rerun-ids 20)

(defn- rerun-lines
  "The command to rerun the failures: `(:invocation opts)` (how the user
  ran volley, default `volley`) with a `--focus` per failure, at most
  `max-rerun-ids` of them, then how many were left out."
  [opts ids]
  (let [cmd    (:invocation opts "volley")
        shown  (take max-rerun-ids ids)
        more   (- (count ids) (count shown))
        flag   (fn [id] (str "--focus " id))
        single (str "  " cmd " " (str/join " " (map flag shown)))]
    (concat
     [(paint opts :yellow "Rerun failures:")]
     (if (<= (visible-width single) (:width opts default-width))
       [single]
       (cons (str "  " cmd " \\")
             (map-indexed (fn [i id]
                            (str "    " (flag id) (when (< i (dec (count shown))) " \\")))
                          shown)))
     (when (pos? more)
       [(paint opts :gray (str "  … and " more " more " (if (= 1 more) "failure" "failures")
                               " not listed"))]))))

(defn- slowest-lines [opts state]
  (let [tests (rs/slowest state 5)
        w     (apply max 0 (map #(count (fmt-ms (:duration-ms %))) tests))]
    (when (seq tests)
      (cons (paint opts :yellow "Slowest tests:")
            (map #(str "  " (pad-left (fmt-ms (:duration-ms %)) w) "  "
                       (qualified (:ns %) (:var %)))
                 tests)))))

(defn- join-fitting
  "Joins `parts` with `sep` into as few lines of at most `width` columns as
  possible, breaking only between parts, so a summary is wrapped rather
  than cut."
  [parts sep width]
  (reduce (fn [lines part]
            (let [cur (peek lines)
                  candidate (str cur sep part)]
              (if (or (nil? cur) (> (visible-width candidate) width))
                (conj lines part)
                (conj (pop lines) candidate))))
          []
          parts))

(def ^:private not-run-reasons
  {:fail-fast  "stopped by fail-fast"
   :no-workers "no worker process could be started"
   :unfinished "did not finish"
   :stopped "stopped before all its tests ran"
   :interrupted "interrupted"})

(defn- wrap-words
  "Joins `words` with \", \" into lines of at most `width` columns, each
  starting with `indent`. A word longer than a line gets a line of its own."
  [words indent width]
  (let [room (max 1 (- width (count indent)))]
    (->> (reduce (fn [lines w]
                   (let [cur (peek lines)
                         candidate (if (str/blank? cur) w (str cur ", " w))]
                     (if (or (str/blank? cur) (<= (visible-width candidate) room))
                       (conj (pop lines) candidate)
                       (conj (pop lines) (str cur ",") w))))
                 [""]
                 words)
         (remove str/blank?)
         (map #(str indent %)))))

(def ^:private max-not-run-listed 50)

(defn- listed-lines
  "`Title (reason):` and the namespaces of `entries` (`{ns reason}`),
  grouped by reason."
  [opts title entries]
  (let [by-reason (group-by val entries)
        width (:width opts default-width)]
    (mapcat (fn [[reason entries]]
              (let [nses (sort (map (comp str key) entries))
                    shown (take max-not-run-listed nses)
                    more (- (count nses) (count shown))]
                (concat
                 [(paint opts :yellow (str title " (" (get not-run-reasons reason (name reason)) "):"))]
                 (wrap-words (map ext shown) "  " width)
                 (when (pos? more) [(str "  … and " more " more")]))))
            (sort-by (comp str key) by-reason))))

(defn- not-run-lines [opts state]
  (concat (listed-lines opts "Interrupted" (rs/interrupted state))
          (listed-lines opts "Not run" (rs/not-run state))))

(defn summary
  "Final block: the counts line, namespaces that never ran, the five slowest
  tests, and a command to rerun the failures; with `:compact?` in `opts`
  (watch mode) the counts line and the namespaces that never ran only. The counts line has the shape
  `N tests | P passed | F failed | E errors | S skipped (A assertions, 2.31s)`. The time
  is the span of the events in `state`, so it does not depend on a clock."
  [state opts]
  (let [{:keys [vars namespaces assertions wall-ms]} (rs/summary state (or (:now state) (:now opts)))
        ok?   (zero? (rs/exit-code state))
        style (if ok? :green :red)
        ids   (focus-ids state)
        parts (cond-> [(plural (:total vars) "test") (str (:passed vars) " passed")
                       (str (:failed vars) " failed") (plural (:errored vars) "error")
                       (str (:skipped vars) " skipped")]
                (pos? (:errored namespaces)) (conj (str (:errored namespaces) " ns failed to run"))
                (pos? (:pending namespaces)) (conj (str (:pending namespaces) " ns not run")))
        parts (update parts (dec (count parts)) str
                      " (" (plural (:total assertions) "assertion") ", " (fmt-ms wall-ms) ")")
        lines (join-fitting parts " | " (:width opts default-width))]
    (->> (concat [""]
                 ;; kaocha.plugin.randomize prints the seed of a failed run
                 (when (and (:seed opts) (not ok?)) [(str "Randomized with --seed " (:seed opts)) ""])
                 (map #(paint opts style %) lines)
                 (when-let [s (seq (not-run-lines opts state))] (cons "" s))
                 (when-let [s (and (not (:compact? opts)) (seq (slowest-lines opts state)))] (cons "" s))
                 (when (and (seq ids) (not (:compact? opts))) (cons "" (rerun-lines opts ids))))
         (#(finish % opts))
         (str/join "\n"))))
