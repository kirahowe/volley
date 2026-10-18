(ns volley.shell.terminal
  "The terminal edge: TTY detection, size, cursor control, in-place redraw
  and raw keyboard input. Decisions live in pure helpers here (`frame-string`,
  `parse-stty-size`, `pick-width`); the functions ending in `!` only print or
  run `stty`."
  (:require
   [babashka.process :as p]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

;;; Pure helpers

(def hide-cursor "Escape sequence that hides the cursor." "\u001b[?25l")
(def show-cursor "Escape sequence that shows the cursor." "\u001b[?25h")

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

(def width-check-ms
  "How often a live view re-reads the terminal width. Each read runs
  `stty`; until it does, a resized terminal shows frames of the old width."
  250)

(defn width
  "Current terminal width: `stty size`, then $COLUMNS, then 80."
  []
  (pick-width (sh-out "stty size < /dev/tty 2>/dev/null") (System/getenv "COLUMNS")))

(defn hide-cursor! "Hide the cursor." [] (print hide-cursor) (flush))
(defn show-cursor! "Show the cursor again." [] (print show-cursor) (flush))

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

(defn- stty!
  "Runs `stty args` on the controlling terminal and returns its output, or
  nil. It starts the process with a plain ProcessBuilder because restoring
  the terminal happens in a shutdown hook too, and babashka.process refuses
  to start anything once shutdown is in progress."
  [& args]
  (try
    (let [proc (-> (ProcessBuilder. ^java.util.List (into ["stty"] args))
                   (.redirectInput (java.io.File. "/dev/tty"))
                   (.redirectError (java.io.File. "/dev/null"))
                   (.start))
          out (slurp (.getInputStream proc))]
      (.waitFor proc)
      out)
    (catch Exception _ nil)))

(defn with-raw-mode!
  "Runs `(f)` with the terminal in raw-ish mode (no line buffering, no echo)
  and restores the saved settings when `f` ends, throws, or the JVM exits.
  `after`, when given, runs right after the restore, in the same shutdown
  hook: JVM shutdown hooks run concurrently, so cleanup that kills child
  processes must not race the `stty` that restores the terminal."
  ([f] (with-raw-mode! f (fn [])))
  ([f after]
   (let [saved (some-> (stty! "-g") str/trim not-empty)
         restore (fn []
                   (if saved (stty! saved) (stty! "icanon" "echo"))
                   (after))]
     (with-restore! restore
       (fn [] (stty! "-icanon" "-echo" "min" "1") (f))))))

(defn stdin-tty?
  "True when stdin is a terminal, so raw mode and key presses make sense."
  []
  (try
    (zero? (:exit (p/shell {:in :inherit :out :string :err :string :continue true}
                           "sh" "-c" "test -t 0")))
    (catch Exception _ false)))

(defn- drain-escape!
  "After an ESC byte: swallow the rest of a CSI/SS3 sequence (arrow keys and
  the like) if one follows within a few milliseconds. True when one did."
  []
  (Thread/sleep 15)
  (when (and (pos? (.available System/in))
             (contains? #{(int \[) (int \O)} (.read System/in)))
    (loop []
      (when (pos? (.available System/in))
        (let [b (.read System/in)]
          (when-not (<= 0x40 b 0x7e) (recur)))))
    true))

(defn read-key!
  "A pending key press as a character, without waiting, or nil. A lone ESC
  comes back as `\\u001b`; escape sequences (arrows, function keys) are
  swallowed and give nil. Call inside `with-raw-mode!`."
  []
  (when (pos? (.available System/in))
    (let [b (.read System/in)]
      (cond
        (neg? b) nil
        (= b 27) (if (drain-escape!) nil \u001b)
        :else (char b)))))

(defn prompt!
  "Draws a one-line prompt in place: `label> buffer`. A nil `buffer` closes
  the prompt by moving to a fresh line."
  [label buffer]
  (print (if buffer (str "\r" clear-line label "> " buffer) "\r\n"))
  (flush))
