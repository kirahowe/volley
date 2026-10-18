(ns build
  "Build tasks. Run with: clojure -T:build <task>"
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.tools.build.api :as b]
   [deps-deploy.deps-deploy :as dd]))

(def ^:private lib 'com.kirahowe/volley)
(def ^:private version (:version (edn/read-string (slurp "src/volley/version.edn"))))
(def ^:private class-dir "target/classes")
(def ^:private basis (delay (b/create-basis {:project "deps.edn"})))
(def ^:private jar-file (format "target/%s-%s.jar" (name lib) version))

(defn- required-env [env-var guidance]
  (let [value (System/getenv env-var)]
    (when (str/blank? value)
      (throw (ex-info guidance {:type ::missing-deploy-credential
                                :environment-variable env-var})))
    value))

(defn- deployment-detail [exception deploy-token]
  (let [detail (ex-message exception)]
    (if (str/blank? detail)
      "No additional details were provided."
      (str/replace detail deploy-token "[REDACTED]"))))

(defn clean
  "Delete target/."
  [_]
  (b/delete {:path "target"}))

(defn jar
  "Build the jar and its pom into target/."
  [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/kirahowe/volley"
                      :connection "scm:git:git://github.com/kirahowe/volley.git"
                      :developerConnection "scm:git:ssh://git@github.com/kirahowe/volley.git"
                      :tag (str "v" version)}
                :pom-data [[:description "A parallel, live-output clojure.test runner for babashka and the JVM that reads Kaocha's tests.edn and flags."]
                           [:url "https://github.com/kirahowe/volley"]
                           [:licenses
                            [:license
                             [:name "MIT License"]
                             [:url "https://opensource.org/license/mit/"]]]]})
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install
  "Build the jar and install it in the local Maven repository."
  [_]
  (jar nil)
  (b/install {:basis @basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy
  "Build the jar and deploy it to Clojars. Needs CLOJARS_USERNAME and
  CLOJARS_PASSWORD (a deploy token)."
  [_]
  (let [username (required-env
                  "CLOJARS_USERNAME"
                  "Set CLOJARS_USERNAME to a nonblank Clojars username before deploying.")
        deploy-token (required-env
                      "CLOJARS_PASSWORD"
                      "Set CLOJARS_PASSWORD to a nonblank Clojars deploy token before deploying.")]
    (jar nil)
    (try
      (dd/deploy {:installer :remote
                  :artifact (b/resolve-path jar-file)
                  :pom-file (b/pom-path {:lib lib :class-dir class-dir})
                  :repository {"clojars" {:url "https://clojars.org/repo"
                                          :username username
                                          :password deploy-token}}})
      (catch Exception exception
        (let [detail (deployment-detail exception deploy-token)]
          (throw (ex-info (str "Clojars deployment failed: " detail)
                          {:type ::deployment-failed
                           :exception-class (.getName (class exception))
                           :detail detail})))))))
