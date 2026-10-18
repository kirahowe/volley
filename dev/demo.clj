(ns demo
  "`bb demo`: replays a scripted run through run-state, render and terminal so
  the live view can be judged by eye. Options: --speed N (default 1),
  --ci (append-only output), --no-color, --width N."
  (:require
   [brisk.render :as render]
   [brisk.run-state :as rs]
   [brisk.shell.terminal :as term]))

;;; Script

(defn- var-events
  "Events for one test starting at `t`; returns [events end-time]."
  [w ns t {:keys [name ms line pass failures output strays]}]
  (let [at   (fn [dt e] [(+ t dt) (assoc e :worker w :ns ns :var name)])
        file "test/demo/app_test.clj"]
    [(concat
      [(at 0 {:event :var/start :file file :line line})]
      (map-indexed (fn [i s] [(+ t (* (inc i) (quot ms (+ 2 (count strays)))))
                              {:event :output :worker w :text s :stream :stderr}])
                   strays)
      (map (fn [f] (at (dec ms) (merge {:event :assertion :file file :line line} f))) failures)
      [(at ms {:event :var/end :pass pass :duration-ms ms :output output})])
     (+ t ms)]))

(defn- ns-events
  "Events for a namespace on worker `w` from time `t`; returns [events end-time]."
  [w ns t vars]
  (let [start [[t {:event :ns/start :worker w :ns ns}]]
        [events end] (reduce (fn [[acc t] v]
                               (let [[es t'] (var-events w ns t v)]
                                 [(into acc es) t']))
                             [[] (+ t 20)]
                             vars)]
    [(concat start events [[(+ end 5) {:event :ns/end :worker w :ns ns :duration-ms (- end t)}]])
     (+ end 10)]))

(def ^:private expected-config
  {:db {:host "localhost" :port 5432 :pool {:min 2 :max 10}}
   :features #{:search :export}
   :retries [1 2 3 4]})

(def ^:private actual-config
  {:db {:host "localhost" :port 5433 :pool {:min 2 :max 20}}
   :features #{:search :billing}
   :retries [1 2 4]
   :debug true})

(defn- value [v] {:str (pr-str v) :edn v})

(def ^:private config-failure
  {:type :fail :message "config differs after merge" :contexts ["load-config" "with overrides"]
   :expected {:str (str "(= " (pr-str expected-config) " " (pr-str actual-config) ")")
              :edn (list '= expected-config actual-config)}
   :actual {:str "(not (= ...))"
            :edn (list 'not (list '= expected-config actual-config))}})

(def ^:private http-error
  {:type :error :message nil :contexts ["fetch user"]
   :expected (value :ok) :actual (value nil)
   :error {:class "clojure.lang.ExceptionInfo" :message "request failed: 503"
           :data "{:status 503, :url \"http://localhost:8080/users/1\"}"
           :trace ["demo.http$fetch.invokeStatic (http.clj:41)"
                   "demo.http_test$fn__123.invoke (http_test.clj:17)"
                   "clojure.test$test_var.invoke (test.clj:717)"]
           :cause {:class "java.net.ConnectException" :message "Connection refused"
                   :trace ["java.net.Socket.connect (Socket.java:633)"]}}})

(def ^:private timeline
  (let [w0 (let [[a t] (ns-events 0 'demo.core-test 0
                                  [{:name 'add-test :ms 180 :line 8 :pass 4}
                                   {:name 'merge-test :ms 320 :line 15 :pass 6}
                                   {:name 'parse-test :ms 240 :line 24 :pass 3}])
                 [b t] (ns-events 0 'demo.util-test t
                                  [{:name 'slug-test :ms 90 :line 5 :pass 2}
                                   {:name 'dates-test :ms 410 :line 12 :pass 9}])
                 [c _] (ns-events 0 'demo.cache-test t
                                  [{:name 'evict-test :ms 700 :line 9 :pass 5}])]
             (concat a b c))
        w1 (let [[a t] (ns-events 1 'demo.config-test 100
                                  [{:name 'defaults-test :ms 150 :line 6 :pass 3}
                                   {:name 'overrides-test :ms 1300 :line 20 :pass 2
                                    :failures [config-failure]
                                    :strays ["loading config from /tmp/demo.edn" "merging overrides"]
                                    :output "overrides applied: {:db {:port 5433}}"}])
                 [b _] (ns-events 1 'demo.router-test t
                                  [{:name 'routes-test :ms 520 :line 7 :pass 8}
                                   {:name 'params-test :ms 260 :line 19 :pass 4}])]
             (concat a b))
        w2 (let [[a t] (ns-events 2 'demo.http-test 200
                                  [{:name 'headers-test :ms 200 :line 6 :pass 2}
                                   {:name 'fetch-test :ms 900 :line 16 :pass 0
                                    :failures [http-error] :strays ["GET /users/1"]}])
                 [b _] (ns-events 2 'demo.auth-test t
                                  [{:name 'token-test :ms 640 :line 11 :pass 7}])]
             (concat a b))
        w3 (let [start [[300 {:event :ns/start :worker 3 :ns 'demo.crash-test}]
                        [320 {:event :var/start :worker 3 :ns 'demo.crash-test :var 'native-test
                              :file "test/demo/crash_test.clj" :line 4}]
                        [1500 {:event :output :worker 3 :stream :stderr
                               :text "#  SIGSEGV (0xb) at pc=0x00007f"}]
                        [1900 {:event :ns/error :worker 3 :ns 'demo.crash-test :synthetic? true
                               :error {:class "brisk.WorkerCrashed" :exit-code 139 :trace []
                                       :message "worker process exited with code 139"}}]
                        [1900 {:event :ns/end :worker 3 :ns 'demo.crash-test :synthetic? true
                               :crashed? true}]
                        [1900 {:event :worker/exit :worker 3 :code 139}]]]
             start)
        ready (for [w (range 4)] [0 {:event :worker/ready :worker w}])]
    (vec (sort-by first (concat ready w0 w1 w2 w3)))))

(def ^:private plan
  {:namespaces '[demo.core-test demo.util-test demo.cache-test demo.config-test
                 demo.router-test demo.http-test demo.auth-test demo.crash-test
                 demo.late-test]
   :started-at 0})

;;; Shell loop

(defn- parse-args [args]
  (loop [[a & more] args opts {:speed 1}]
    (case a
      nil opts
      "--ci" (recur more (assoc opts :ci? true))
      "--no-color" (recur more (assoc opts :color? false))
      "--speed" (recur (rest more) (assoc opts :speed (parse-double (first more))))
      "--width" (recur (rest more) (assoc opts :width (parse-long (first more))))
      (recur more opts))))

(defn- finish! [state opts live?]
  (when live? (println (render/failure-report state opts)))
  (println (render/summary state opts))
  (flush)
  (rs/exit-code state))

(defn- play
  "Replays the timeline in real time (scaled by :speed). Returns the exit
  code brisk would use."
  [{:keys [speed ci? color? width]}]
  (let [base   (System/currentTimeMillis)
        live?  (and (not ci?) (term/tty?))
        color? (and (not (false? color?)) live? (nil? (System/getenv "NO_COLOR")))
        cols   (or width (term/width))]
    (loop [state (rs/init plan) prev nil pending timeline height 0]
      (let [now     (long (* speed (- (System/currentTimeMillis) base)))
            due     (take-while #(<= (first %) now) pending)
            pending (drop (count due) pending)
            state'  (reduce rs/apply-event state (map (fn [[t e]] (assoc e :at t)) due))
            done?   (empty? pending)
            opts    {:width cols :color? color? :now now :final? done?}
            height' (if live?
                      (term/redraw! height (render/live-frame prev state' opts))
                      (do (run! println (render/ci-lines prev state' opts)) 0))]
        (if done?
          (finish! state' opts live?)
          (do (Thread/sleep 80)
              (recur state' state' pending height')))))))

(defn -main [args]
  (let [opts (parse-args args)]
    (if (and (not (:ci? opts)) (term/tty?))
      (term/with-hidden-cursor! #(play opts))
      (play opts))
    nil))
