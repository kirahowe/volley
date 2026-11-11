(ns volley.shell.runtime-test
  "What this process's classpath holds, against the classpath the test run
  itself has (`src` is on it under every `bb test*` task)."
  (:require
   [babashka.fs :as fs]
   [volley.shell.runtime :as runtime]
   [clojure.test :refer [deftest is testing]]))

(deftest paths-off-classpath-are-the-existing-ones-the-classpath-lacks
  (let [dir (str (fs/canonicalize (fs/create-temp-dir {:prefix "volley-paths-"})))
        nested "src/volley/shell"]
    (try
      (testing "a classpath entry, however it is spelled, and a missing path are left out"
        (is (= [] (runtime/paths-off-classpath ["src" (str (fs/absolutize "src")) "./src" "no/such/dir"]))))
      (testing "a directory outside the classpath, and one inside an entry, come back canonical, once"
        (is (= [dir (str (fs/canonicalize nested))]
               (runtime/paths-off-classpath [dir "src" nested (str dir "/.")]))))
      (finally (fs/delete-tree dir)))))
