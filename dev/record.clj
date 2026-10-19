(ns record
  "`bb record`: records a real `bb test` run with asciinema and renders it to
  doc/volley.gif with agg, for the README. Needs both on PATH
  (`brew install asciinema agg`)."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def ^:private out "doc/volley.gif")

(def ^:private command "bb test")

;; Rendered at twice the size the README displays it (see the img width
;; there), so it stays sharp on high-DPI screens.

(defn- prompt-events
  "asciicast v3 events that type `$ <command>` before the recorded output."
  []
  (concat [[0.5 "o" "\u001b[32m$\u001b[0m "]]
          (map (fn [c] [0.08 "o" (str c)]) command)
          [[0.4 "o" "\r\n"]]))

(defn- exit-status
  "The exit status recorded in the cast's final \"x\" event, or nil."
  [lines]
  (some->> (reverse lines)
           (map json/parse-string)
           (some (fn [[_ kind data]] (when (= "x" kind) data)))
           parse-long))

(defn- fail! [& msg]
  (binding [*out* *err*] (apply println msg))
  (System/exit 1))

(defn -main
  "Records the run and writes the GIF; exits 1 if a tool is missing or the
  tests fail, leaving the existing GIF alone."
  [& _]
  (when-let [missing (seq (remove fs/which ["asciinema" "agg"]))]
    (fail! "Missing" (str/join ", " missing) "- install with: brew install asciinema agg"))
  (let [dir   (fs/create-temp-dir)
        raw   (str (fs/path dir "raw.cast"))
        cast  (str (fs/path dir "demo.cast"))
        _     (p/shell "asciinema" "rec" "--headless" "--overwrite" "--quiet"
                       "--window-size" "100x30" "-c" command raw)
        [header & events] (fs/read-all-lines raw)
        status (exit-status events)]
    (when-not (= 0 status)
      (fail! command "exited with" status "- not updating" out))
    (fs/write-lines cast (concat [header] (map json/generate-string (prompt-events)) events))
    (p/shell {:out :string :err :string}
             "agg" "--idle-time-limit" "1" "--last-frame-duration" "5" "--speed" "1.5"
             "--font-size" "28" "--fps-cap" "15" "--theme" "monokai" cast out)
    (fs/delete-tree dir)
    (println "Wrote" out)))
