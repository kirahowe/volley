(ns brisk.shell.terminal
  "The terminal edge: TTY detection, size, cursor control, in-place redraw
  and raw keyboard input. Decisions live in pure helpers here (`frame-string`,
  `parse-stty-size`, `pick-width`); the functions ending in `!` only print or
  run `stty`."
  (:require
   [babashka.process :as p]
   [clojure.string :as str]))

;;; Pure helpers

(def hide-cursor "\u001b[?25l")
(def show-cursor "\u001b[?25h")

(def ^:private clear-line "\u001b[2K")
(def ^:private clear-below "\u001b[J")

(defn parse-stty-size
  "Parses `stty size` output (\"rows cols\") into `{:rows n :cols n}`, or nil."
  [s]
  (when-let [[_ rows cols] (re-matches #"\s*(\d+)\s+(\d+)\s*" (str s))]
    (let [rows (parse-long rows) cols (parse-long cols)]
      (when (and (pos? rows) (pos? cols)) {:rows rows :cols cols}))))

(defn pick-width
  "Terminal width from `stty size` output, else the COLUMNS value, else 80."
  [stty-output columns-env]
  (or (:cols (parse-stty-size stty-output))
      (when-let [n (some-> columns-env str str/trim parse-long)]
        (when (pos? n) n))
      80))

(defn frame-string
  "The text that redraws a frame in place. `prev-height` is the number of
  frame lines printed last time (see `frame-height`). Lines in `:emit` are
  printed permanently where the old frame started; `:frame` lines follow and
  stay redrawable. Each line replaces whatever was on its row, and anything
  left over from a taller previous frame is cleared."
  [prev-height {:keys [emit frame]}]
  (let [row (fn [line] (str clear-line line "\n"))]
    (str (when (pos? prev-height) (str "\r\u001b[" prev-height "A"))
         (apply str (map row emit))
         (apply str (map row frame))
         clear-below)))

(defn frame-height
  "How many rows `frame-string` leaves redrawable for this frame."
  [{:keys [frame]}]
  (count frame))

;;; Shell

(defn- sh-out [cmd]
  (try
    (:out (p/shell {:out :string :err :string :continue true} "sh" "-c" cmd))
    (catch Exception _ nil)))

(defn tty?
  "True when stdout is a terminal."
  []
  (try
    (zero? (:exit (p/shell {:in :inherit :out :inherit :err :inherit :continue true}
                           "sh" "-c" "test -t 1")))
    (catch Exception _ false)))

(defn width
  "Current terminal width: `stty size`, then $COLUMNS, then 80."
  []
  (pick-width (sh-out "stty size < /dev/tty 2>/dev/null") (System/getenv "COLUMNS")))

(defn hide-cursor! [] (print hide-cursor) (flush))
(defn show-cursor! [] (print show-cursor) (flush))

(defn redraw!
  "Prints `frame` (`{:emit [...] :frame [...]}`) over the previous one and
  returns the height to pass to the next call."
  [prev-height frame]
  (print (frame-string prev-height frame))
  (flush)
  (frame-height frame))

(defn with-restore!
  "Runs `(f)` and then runs `(restore)`, also if `f` throws and also on
  Ctrl-C or any JVM exit, through a shutdown hook. `restore` must be safe to
  call twice."
  [restore f]
  (let [hook (Thread. ^Runnable restore)
        rt   (Runtime/getRuntime)]
    (.addShutdownHook rt hook)
    (try
      (f)
      (finally
        (restore)
        (try (.removeShutdownHook rt hook) (catch IllegalStateException _ nil))))))

(defn with-hidden-cursor!
  "Runs `(f)` with the cursor hidden, and shows it again however `f` ends."
  [f]
  (with-restore! show-cursor!
    (fn [] (hide-cursor!) (f))))

(defn- stty! [& args]
  (sh-out (str "stty " (str/join " " args) " < /dev/tty 2>/dev/null")))

(defn with-raw-mode!
  "Runs `(f)` with the terminal in raw-ish mode (no line buffering, no echo)
  and restores the saved settings when `f` ends, throws, or the JVM exits."
  [f]
  (let [saved (some-> (stty! "-g") str/trim not-empty)
        restore (fn [] (if saved (stty! saved) (stty! "icanon" "echo")))]
    (with-restore! restore
      (fn [] (stty! "-icanon" "-echo" "min" "1") (f)))))

(defn poll-key!
  "Waits up to `timeout-ms` for a key press and returns it as a character,
  or nil. Call inside `with-raw-mode!`."
  [timeout-ms]
  (loop [waited 0]
    (cond
      (pos? (.available System/in)) (char (.read System/in))
      (>= waited timeout-ms)        nil
      :else (do (Thread/sleep 10) (recur (+ waited 10))))))
