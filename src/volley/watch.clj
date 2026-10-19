(ns volley.watch
  "Watch mode as pure functions: no I/O, clocks or atoms.

  * `changes` compares two modification-time snapshots.
  * `update-infos` and `affected-by` turn the changes into the test
    namespaces to rerun, through `volley.deps-graph`.
  * `select-plan` narrows a `volley.select/plan` to a rerun selection and the
    user's filters.
  * `step` is the UI state machine: keys, file changes, ticks and finished
    runs in, `{:state :effects}` out, in the style of `volley.engine`.

  Effects `step` returns, for `volley.shell.watch` to carry out:

      {:effect :start-run :selection sel}   ; build a plan from sel and run it
      {:effect :cancel-run}                 ; kill the running run's workers
      {:effect :print :lines [str]}
      {:effect :prompt :label str :buffer str-or-nil}  ; nil closes the prompt
      {:effect :quit}

  A selection is `{:all? bool :failed? bool :namespaces #{ns}
  :reload-config? bool :reload-deps? bool :ns-pattern str-or-nil
  :test-pattern str-or-nil}`. `:reload-deps?` asks for the worker classpath
  to be computed again, after deps.edn or bb.edn changed.
  Without `:all?`, it names the namespaces to run: `:namespaces` plus, with
  `:failed?`, the ones that failed last time (only their failed vars when
  they are not also in `:namespaces`)."
  (:require
   [volley.deps-graph :as deps]
   [volley.discover :as discover]
   [volley.run-state :as rs]
   [volley.select :as select]
   [clojure.set :as set]
   [clojure.string :as str]))

(def poll-ms
  "How often the shell compares modification times."
  200)

(def debounce-ms
  "A burst of changes fires one rerun once this long passes without a new
  change. It is longer than `poll-ms`, so changes seen by consecutive polls
  merge."
  250)

;; ---------------------------------------------------------------------------
;; Snapshots

(defn watched-file?
  "False for editor droppings and anything under a dot directory: hidden
  files, `foo~`, `#foo#`, `.swp` files. The shell skips these when it
  takes a snapshot."
  [path]
  (let [segments (str/split (str path) #"[/\\]")
        file (last segments)]
    (not (or (some #(and (str/starts-with? % ".") (not (#{"." ".."} %))) segments)
             (str/ends-with? file "~")
             (re-matches #"#.*#" file)
             (re-find #"\.(swp|swx|tmp)$" file)))))

(def deps-files
  "The project files that decide the classpath, relative to the project
  directory. The shell watches them, and a change to one recomputes the
  worker classpath."
  ["deps.edn" "bb.edn"])

(defn deps-file?
  "True for a snapshot path that is one of `deps-files`."
  [path]
  (boolean (some #{(str path)} deps-files)))

(defn changes
  "What happened between two snapshots (`{path mtime}`): paths present only
  in `snapshot` are `:added`, paths in both with a different mtime are
  `:modified`, paths only in `prev` are `:deleted`. Each is a sorted vector."
  [prev snapshot]
  {:added (vec (sort (remove #(contains? prev %) (keys snapshot))))
   :modified (vec (sort (filter #(and (contains? prev %) (not= (get prev %) (get snapshot %)))
                                (keys snapshot))))
   :deleted (vec (sort (remove #(contains? snapshot %) (keys prev))))})

(defn changed-paths
  "All paths in a `changes` result, sorted."
  [{:keys [added modified deleted]}]
  (vec (sort (concat added modified deleted))))

;; ---------------------------------------------------------------------------
;; Graph updates

(defn- source-file? [path] (boolean (re-find #"\.cljc?$" (str path))))

(defn update-infos
  "Apply `changes` to `infos` (`{path ns-info}`). `texts` maps each added
  or modified path to its new contents (nil when it could not be read).
  Only Clojure files are parsed (`volley.discover/ns-info`)."
  [infos {:keys [added modified deleted]} texts]
  (reduce (fn [m path]
            (if-let [text (and (source-file? path) (get texts path))]
              (assoc m path (discover/ns-info path text))
              (dissoc m path)))
          (apply dissoc infos deleted)
          (concat added modified)))

(defn- graph-of [suites infos]
  (deps/graph (discover/mark-tests suites (vals infos))))

(defn affected-by
  "Test namespaces affected by `changes`: added and modified files are
  looked up in the new graph, deleted ones in the old graph (the new one no
  longer knows them). Files the graph cannot place, such as resources,
  affect every test namespace, as in `volley.deps-graph/affected`."
  [suites old-infos new-infos {:keys [added modified deleted]}]
  (set/union (deps/affected (graph-of suites new-infos) (concat added modified))
             (deps/affected (graph-of suites old-infos) deleted)))

;; ---------------------------------------------------------------------------
;; Selection

(defn merge-selection
  "The selection that covers both `a` and `b`."
  [a b]
  {:all? (boolean (or (:all? a) (:all? b)))
   :failed? (boolean (or (:failed? a) (:failed? b)))
   :namespaces (set/union (set (:namespaces a)) (set (:namespaces b)))
   :reload-config? (boolean (or (:reload-config? a) (:reload-config? b)))
   :reload-deps? (boolean (or (:reload-deps? a) (:reload-deps? b)))})

(defn compile-pattern
  "A regex for the non-blank string `s`, or nil when `s` is blank or not a
  valid pattern."
  [s]
  (when-not (str/blank? s)
    (try (re-pattern s) (catch Exception _ nil))))

(defn- plan-entries [plan] (mapcat :namespaces (:suites plan)))

(defn plan-empty?
  "True when `plan` has no namespace to run."
  [plan]
  (empty? (plan-entries plan)))

(defn select-plan
  "The plan for a watch run. Starts from `volley.select/plan` (so the config's
  suites, patterns and Kaocha filters always apply) and keeps only entries
  that

  * are in the selection: everything with `:all?`, else `:namespaces` and,
    with `:failed?`, the keys of `failed` (`{ns #{var}}`; an empty set means
    the whole namespace);
  * match `:ns-pattern` (a regex searched in the namespace name).

  `:test-pattern` prunes no namespace: any of them may hold a
  macro-defined test whose name matches, so it goes into each select spec
  as `:var-pattern` and the worker applies it to the real vars.

  A namespace selected only as failed with known failed vars runs just
  those vars."
  [config infos cli-suites {:keys [all? failed? namespaces ns-pattern test-pattern]} failed]
  (let [infos (vals infos)
        base (select/plan config infos cli-suites)
        namespaces (set namespaces)
        wanted (into namespaces (when failed? (keys failed)))
        ns-re (compile-pattern ns-pattern)
        test-re (compile-pattern test-pattern)
        keep? (fn [{:keys [ns]}]
                (and (or all? (contains? wanted ns))
                     (or (nil? ns-re) (boolean (re-find ns-re (str ns))))))
        decorate (fn [{:keys [ns] :as entry}]
                   (let [vars (when (and failed? (not all?) (not (contains? namespaces ns)))
                                (seq (get failed ns)))]
                     (cond-> entry
                       test-re (assoc-in [:select :var-pattern] test-pattern)
                       vars (update-in [:select :passes] conj
                                       {:focus (mapv #(symbol (str ns) (str %)) vars)
                                        :skip [] :focus-meta [] :skip-meta []
                                        :suite-focused? false}))))]
    (update base :suites
            (fn [suites]
              (mapv (fn [suite]
                      (update suite :namespaces #(into [] (comp (filter keep?) (map decorate)) %)))
                    suites)))))

;; ---------------------------------------------------------------------------
;; Run outcome

(defn- filtered-out?
  "True for a namespace record that has vars, all of them skipped by the filters."
  [rec]
  (boolean (and (seq (:vars rec)) (rs/unselected? rec))))

(defn outcome
  "What the UI needs to remember about a finished run (a `volley.run-state`
  value): `{:ran #{ns} :failed {key #{var}}}`. A key is a namespace, or the
  path of an unreadable test file. A key whose failure is not tied to a var
  (load error, crash, not run, unreadable file) has an empty set, meaning
  \"all of it\". A namespace whose vars the filters all skipped is left out
  of `:ran`, so its earlier failures carry over. One that finished with no
  vars at all still counts as run and clears them."
  [run]
  (let [failed (reduce (fn [m {:keys [ns file var kind]}]
                         (let [k (or ns file)]
                           (cond
                             (nil? k) m
                             (not (and var (#{:fail :error} kind))) (assoc m k #{})
                             (= #{} (get m k)) m
                             :else (update m k (fnil conj #{}) var))))
                       {}
                       (rs/failures run))]
    {:ran (into #{}
                (remove #(filtered-out? (get-in run [:namespaces %])))
                (into (set (:planned run)) (keys (:namespaces run))))
     :failed failed}))

(defn merge-outcome
  "The picture after a run: failures of namespaces the run did not cover
  carry over, those of namespaces it did cover are replaced. Unreadable
  files are re-reported by every run, so they are never carried over.
  `prev` may be nil. Returns `{:ok? bool :failures n :failed {key #{var}}}`."
  [prev {:keys [ran failed]}]
  (let [carried (into {} (remove (fn [[k _]] (or (string? k) (contains? ran k)))) (:failed prev))
        failed (merge carried failed)]
    {:ok? (empty? failed)
     :failures (reduce + (map (fn [vars] (max 1 (count vars))) (vals failed)))
     :failed failed}))

;; ---------------------------------------------------------------------------
;; State machine

(defn init
  "Initial state. opts: `:keys?` (the shell reads the keyboard), `:config-file`
  (a change to it reruns everything with a fresh config), `:color?`."
  [{:keys [keys? config-file color?]}]
  {:mode :idle                ; :idle | :running | :prompting | :quit
   :prompt nil                ; {:kind :ns|:test :buffer str} while prompting
   :filters {:ns nil :test nil}
   :last nil                  ; outcome of the last finished run
   :last-selection nil        ; selection of the last finished run
   :running nil               ; selection of the run in progress
   :pending nil               ; {:paths #{} :affected #{} :config? bool :deps? bool :last-at ms}
   :keys? (boolean keys?)
   :color? (boolean color?)
   :config-file config-file})

(defn- add-pending
  "Fold a burst member into the pending change. Never drops a path."
  [pending {:keys [paths affected config? deps? at]}]
  (-> (or pending {:paths #{} :affected #{} :config? false :deps? false})
      (update :paths into paths)
      (update :affected into affected)
      (update :config? #(boolean (or % config?)))
      (update :deps? #(boolean (or % deps?)))
      (assoc :last-at at)))

(defn due?
  "True when `pending` has been quiet for `debounce-ms` at time `at`."
  [pending at]
  (boolean (and pending (number? at) (>= (- at (:last-at pending)) debounce-ms))))

(defn- print* [& lines] {:effect :print :lines (vec lines)})

(defn- paint [{:keys [color?]} code s]
  (if color? (str "\u001b[" code "m" s "\u001b[0m") s))

(defn- filter-line [{:keys [filters]}]
  (let [parts (cond-> []
                (:ns filters) (conj (str "namespace /" (:ns filters) "/"))
                (:test filters) (conj (str "test /" (:test filters) "/")))]
    (when (seq parts) (str "Filters: " (str/join ", " parts)))))

(defn status-lines
  "The lines printed after a run: PASS or FAIL, what to do next, and the
  active filters."
  [{:keys [last keys?] :as state}]
  (let [wait (str "Waiting for file changes..." (when keys? "  press h to show help, q to quit"))
        head (cond
               (nil? last) ""
               (:ok? last) (str (paint state "1;32" "PASS") "  ")
               :else (str (paint state "1;31" "FAIL") "  "
                          (:failures last) (if (= 1 (:failures last)) " failure" " failures") "  "))]
    (into [(str head wait)] (some-> (filter-line state) vector))))

(def help-lines
  "What `h` prints."
  ["Watch mode keys:"
   "  a      rerun all tests"
   "  f      rerun only the tests that failed last time"
   "  p      filter by namespace regex"
   "  t      filter by test name regex"
   "  Enter  rerun the last selection"
   "  c      clear filters"
   "  h      show this help"
   "  q      quit"])

(defn- begin
  "Start a run for `selection`, cancelling and merging with the one in
  progress."
  [state selection prints]
  (let [running? (= :running (:mode state))
        sel (if running? (merge-selection selection (:running state)) selection)
        full (assoc sel
                    :ns-pattern (get-in state [:filters :ns])
                    :test-pattern (get-in state [:filters :test]))]
    {:state (assoc state :mode :running :running sel)
     :effects (vec (concat prints
                           (when running? [{:effect :cancel-run}])
                           [{:effect :start-run :selection full}]))}))

(defn- quit [state]
  {:state (assoc state :mode :quit :prompt nil)
   :effects (vec (concat (when (= :running (:mode state)) [{:effect :cancel-run}])
                         (when (= :prompting (:mode state)) [{:effect :prompt :buffer nil}])
                         [{:effect :quit}]))})

(defn- no-op [state] {:state state :effects []})

(def ^:private prompt-labels
  {:ns "filter namespaces (regex)" :test "filter test names (regex)"})

(defn- open-prompt [state kind]
  {:state (assoc state :mode :prompting :prompt {:kind kind :buffer ""})
   :effects [{:effect :prompt :label (prompt-labels kind) :buffer ""}]})

(defn- apply-prompt [state]
  (let [{:keys [kind buffer]} (:prompt state)
        blank? (str/blank? buffer)
        k (if (= :ns kind) :ns :test)]
    (if (and (not blank?) (nil? (compile-pattern buffer)))
      {:state state
       :effects [(print* (str "Invalid regex: " buffer))
                 {:effect :prompt :label (prompt-labels kind) :buffer buffer}]}
      (let [state (-> state
                      (assoc :mode :idle :prompt nil)
                      (assoc-in [:filters k] (when-not blank? buffer)))]
        (update (begin state {:all? true} (some-> (filter-line state) print* vector))
                :effects #(into [{:effect :prompt :buffer nil}] %))))))

(defn- prompt-key [state ch]
  (let [{:keys [kind buffer]} (:prompt state)]
    (cond
      (= ch \u0003) (quit state)
      (= ch \u001b) {:state (assoc state :mode :idle :prompt nil)
                     :effects [{:effect :prompt :buffer nil}]}
      (#{\return \newline} ch) (apply-prompt state)
      (#{\backspace \u007f} ch)
      (let [b (if (seq buffer) (subs buffer 0 (dec (count buffer))) buffer)]
        {:state (assoc-in state [:prompt :buffer] b)
         :effects [{:effect :prompt :label (prompt-labels kind) :buffer b}]})
      (and (char? ch) (>= (int ch) 32) (not= (int ch) 127))
      (let [b (str buffer ch)]
        {:state (assoc-in state [:prompt :buffer] b)
         :effects [{:effect :prompt :label (prompt-labels kind) :buffer b}]})
      :else (no-op state))))

(defn- clear-filters [state]
  (if (or (get-in state [:filters :ns]) (get-in state [:filters :test]))
    (begin (assoc state :filters {:ns nil :test nil}) {:all? true} [(print* "Filters cleared.")])
    {:state state :effects [(print* "No filters to clear.")]}))

(defn- command-key [state ch]
  (let [running? (= :running (:mode state))]
    (case ch
      (\q \u0003) (quit state)
      \a (begin state {:all? true} [])
      \f (if (seq (get-in state [:last :failed]))
           (begin state {:failed? true} [])
           {:state state :effects [(print* "No failed tests to rerun.")]})
      (\return \newline) (begin state
                                (if running?
                                  (:running state)
                                  (or (:last-selection state) {:all? true}))
                                [])
      \c (clear-filters state)
      \h {:state state :effects [(apply print* help-lines)]}
      (\p \t) (if running?
                {:state state
                 :effects [(print* "Wait for the run to finish before changing filters.")]}
                (open-prompt state (if (= \p ch) :ns :test)))
      (no-op state))))

(defn- on-key [state {:keys [char]}]
  (if (= :prompting (:mode state))
    (prompt-key state char)
    (command-key state char)))

(defn- change-banner [paths]
  (let [shown (take 3 (sort paths))
        more (- (count paths) (count shown))]
    (str "Change detected: " (str/join ", " shown) (when (pos? more) (str " and " more " more")))))

(defn- fire-pending [state]
  (let [{:keys [paths affected config? deps?]} (:pending state)
        state (assoc state :pending nil)]
    (if (and (= :running (:mode state)) (empty? affected) (not config?))
      {:state state :effects []}
      (begin state
             {:all? (boolean config?) :failed? false :namespaces affected
              :reload-config? (boolean config?) :reload-deps? (boolean deps?)}
             [(print* (change-banner paths))]))))

(defn- forget-gone
  "Drop failures of namespaces that no longer exist (`known` is the set of
  namespaces in the project's files, or nil to keep everything)."
  [last known]
  (if (and last known)
    (let [failed (into {} (filter (fn [[k _]] (or (string? k) (contains? known k)))) (:failed last))]
      (assoc last :failed failed :ok? (empty? failed)))
    last))

(defn- on-finished [state {run-outcome :outcome nothing-ran? :empty? known :known}]
  (if (not= :running (:mode state))
    (no-op state)
    (let [merged (if nothing-ran? (:last state) (merge-outcome (:last state) run-outcome))
          state (assoc state :mode :idle :last-selection (:running state) :running nil
                       :last (forget-gone merged known))]
      {:state state
       :effects [(apply print*
                        (concat (when nothing-ran? ["No test namespaces matched."])
                                (status-lines state)))]})))

(defn step
  "Fold one input into the watch state. Inputs:

      {:event :start}                                      ; first run
      {:event :key :char c}
      {:event :files-changed :paths [p ...] :affected #{ns} :at ms}
      {:event :tick :at ms}
      {:event :run/finished :outcome o :empty? bool}

  A change to `:config-file` (or `:config? true` on the input) reruns
  everything with a fresh config; a change to deps.edn or bb.edn
  (`deps-file?`) also recomputes the classpath. Once the state is `:quit`,
  inputs are ignored. Returns `{:state :effects}`."
  [state {:keys [event at paths] :as input}]
  (if (= :quit (:mode state))
    (no-op state)
    (case event
      :start (begin state {:all? true} [])
      :key (on-key state input)
      :files-changed (if (empty? paths)
                       (no-op state)
                       (let [deps? (boolean (some deps-file? paths))
                             config? (or (:config? input) deps? (some #{(:config-file state)} paths))]
                         (no-op (update state :pending add-pending
                                        (assoc input :config? config? :deps? deps?)))))
      :tick (if (and (not= :prompting (:mode state)) (due? (:pending state) at))
              (fire-pending state)
              (no-op state))
      :run/finished (on-finished state input)
      (no-op state))))
