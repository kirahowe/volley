(ns volley.plugins-test
  (:require
   [volley.config :as config]
   [volley.gen.compat :as g]
   [volley.plugins :as plugins]
   [clojure.string :as str]
   [clojure.test :refer [are deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

(deftest plugins-that-cannot-load-fail-like-kaocha
  ;; kaocha.plugin/register, try-load-third-party-lib
  (let [none (constantly nil)]
    (is (= {:error :plugin-error
            :message "Couldn't load plugin :kaocha.plugin/foo. Failed to load namespace kaocha.plugin.foo. This could be caused by a misspelling or a missing dependency."}
           (plugins/plugin-load-error :kaocha.plugin/foo none)))
    (is (= {:error :plugin-error
            :message "Couldn't load plugin :my.team/thing. Failed to load namespaces my.team.thing and my.team. This could be caused by a misspelling or a missing dependency."}
           (plugins/plugin-load-error :my.team/thing none)))
    (is (= [:my.team/thing] (:unknown-plugins (config/normalize {:plugins [:my.team/thing :notifier :hooks]} {}))))))

(deftest plugins-whose-namespace-defines-none-fail-like-kaocha
  ;; kaocha.plugin/register: "Namespaces succeeded, but plugin itself failed."
  (testing "every namespace Kaocha tried loaded but none defines the plugin: exit 249"
    (is (= {:error :plugin-undefined
            :message "Couldn't load plugin :my.team/thing. The plugin was not defined after loading namespace my.team.thing. Is the file missing a defplugin?"}
           (plugins/plugin-load-error :my.team/thing {'my.team.thing "(ns my.team.thing)"})))
    (is (= {:error :plugin-undefined
            :message "Couldn't load plugin :my.plugin. The plugin was not defined after loading namespace my.plugin. Is the file missing a defplugin?"}
           (plugins/plugin-load-error :my.plugin {'my.plugin "(ns my.plugin)"}))))
  (testing "try-load-third-party-lib tries a.b only after a.b.c fails, so that miss is the 248 error"
    (is (= {:error :plugin-error
            :message "Couldn't load plugin :my.team/thing. Failed to load namespace my.team.thing. This could be caused by a misspelling or a missing dependency."}
           (plugins/plugin-load-error :my.team/thing {'my.team "(ns my.team)"}))))
  (testing "a namespace that defines the plugin is fine"
    (are [plugin sources] (nil? (plugins/plugin-load-error plugin sources))
      :my.team/thing {'my.team.thing "(ns my.team.thing (:require [kaocha.plugin :refer [defplugin]]))\n(defplugin my.team/thing)"}
      :my.team/thing {'my.team "(ns my.team (:require [kaocha.plugin :as p]))\n(p/defplugin my.team/thing \"doc\")"}
      :my.team/thing {'my.team "(ns my.team)\n(defmethod p/-register :my.team/thing [_ ps] ps)"}
      :my.team/thing {'my.team "(ns my.team)\n(defmethod p/-register ::thing [_ ps] ps)"}
      :my.plugin {'my.plugin "(defmethod kaocha.plugin/-register :my.plugin [_ ps] ps)"}
      ;; kaocha.plugin/defplugin takes (keyword id), so a keyword id works too
      :my.team/thing {'my.team "(ns my.team (:require [kaocha.plugin :refer [defplugin]]))\n(defplugin ::thing)"}
      :my.team/thing {'my.team.thing "(ns my.team.thing)\n(kaocha.plugin/defplugin :my.team/thing \"doc\")"}))
  (testing "a -register method dispatching on a var, as kaocha.plugin.debug does, is taken to define it"
    (is (nil? (plugins/plugin-load-error
               :my.team/thing
               {'my.team.thing "(ns my.team.thing (:require [kaocha.plugin :as plugin]))\n(def id :my.team/thing)\n(defmethod plugin/-register id [_ ps] ps)"}))))
  (testing "a -register method of another plugin's keyword is not a definition"
    (is (= :plugin-undefined
           (:error (plugins/plugin-load-error
                    :my.team/thing
                    {'my.team.thing "(ns my.team.thing)\n(defmethod kaocha.plugin/-register :my.team/other [_ ps] ps)"})))))
  (testing "a definition in a namespace the candidate requires, directly or not, counts"
    (are [sources] (nil? (plugins/plugin-load-error :my.team/thing sources))
      {'my.team.thing "(ns my.team.thing (:require [my.team.impl]))"
       'my.team.impl "(ns my.team.impl (:require [kaocha.plugin :refer [defplugin]]))\n(defplugin my.team/thing)"}
      {'my.team.thing "(ns my.team.thing (:require [my.team.a]))"
       'my.team.a "(ns my.team.a (:require [my.team.b]))"
       'my.team.b "(ns my.team.b)\n(do (defplugin my.team/thing))"}))
  (testing "a definition volley may not see is taken to exist"
    (are [sources] (nil? (plugins/plugin-load-error :my.team/thing sources))
      {'my.team.thing "(ns my.team.thing (:require [my.team.gone]))"}
      {'my.team.thing "(ns my.team.thing (:require [my.team.macros :refer [defteam]]))\n(defteam thing)"
       'my.team.macros "(ns my.team.macros)\n(defmacro defteam [n] `(kaocha.plugin/defplugin ~n))"}
      {'my.team.thing "(ns my.team.thing)\n(def x ["}))
  (testing "a definition that is commented out is not one"
    (are [text] (= :plugin-undefined (:error (plugins/plugin-load-error :my.team/thing {'my.team.thing text})))
      "(ns my.team.thing)\n;; (p/defplugin my.team/thing)"
      "(ns my.team.thing)\n#_(p/defplugin my.team/thing)"
      "(ns my.team.thing)\n(comment (p/defplugin my.team/thing))"
      "(ns my.team.thing (:require [clojure.string :as str] [kaocha.plugin :as p]))"))
  (testing "a similar name is not a definition"
    (is (= :plugin-undefined
           (:error (plugins/plugin-load-error :my.team/thing
                                             {'my.team.thing "(defplugin my.team/thing-two) :my.team/things ::thing"}))))))

(defspec a-plugin-loads-only-when-a-namespace-kaocha-tries-defines-it 200
  ;; kaocha.plugin/try-load-third-party-lib loads a.b only when a.b.c fails
  (prop/for-all [plugin g/third-party-plugin
                 loads (gen/vector gen/boolean 2)
                 pick gen/nat]
    (let [candidates (plugins/plugin-candidates plugin)
          loaded (vec (keep-indexed (fn [i c] (when (nth loads i) c)) candidates))
          definer (when (seq loaded) (nth loaded (mod pick (count loaded))))
          sources (into {} (map (fn [c] [c (str "(ns " c ")")])) loaded)
          defined (cond-> sources definer (update definer str "\n(defplugin " (symbol plugin) ")"))
          tried (if (some #{(first candidates)} loaded) [(first candidates)] candidates)]
      (and (some? (plugins/plugin-load-error plugin sources))
           (= (nil? (plugins/plugin-load-error plugin defined))
              (boolean (some #{definer} tried)))))))

(defspec a-definition-counts-anywhere-down-the-requires-unless-commented-out 200
  ;; Kaocha loads the candidate, and so everything it requires, before it
  ;; checks the plugin registry (kaocha.plugin/try-load-third-party-lib)
  (prop/for-all [plugin g/third-party-plugin
                 depth (gen/choose 0 4)
                 back-edges (gen/vector gen/nat 5)
                 pick gen/nat
                 how (gen/elements [:real :line-comment :discard :comment-form])]
    (let [start (first (plugins/plugin-candidates plugin))
          chain (into [start] (map #(symbol (str "my.chain.n" %))) (range depth))
          at (mod pick (count chain))
          defn-text (str "(defplugin " (symbol plugin) ")")
          body {:real defn-text
                :line-comment (str ";; " defn-text)
                :discard (str "#_" defn-text)
                :comment-form (str "(comment " defn-text ")")}
          sources (into {}
                        (map-indexed
                         (fn [i ns-sym]
                           (let [reqs (cond-> []
                                        (< (inc i) (count chain)) (conj (chain (inc i)))
                                        (pos? i) (conj (chain (mod (nth back-edges i) i))))]
                             [ns-sym (cond-> (str "(ns " ns-sym " (:require " (str/join " " (map #(str "[" % "]") reqs)) "))")
                                       (= i at) (str "\n" (body how)))])))
                        chain)
          result (plugins/plugin-load-error plugin sources)]
      (if (= :real how)
        (nil? result)
        (= :plugin-undefined (:error result))))))
