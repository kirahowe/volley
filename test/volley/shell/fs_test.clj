(ns volley.shell.fs-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [volley.shell.fs :as vfs]))

(deftest cache-dir-gets-a-gitignore-when-created
  (fs/with-temp-dir [tmp {}]
    (let [dir (fs/path tmp ".volley" "nested")]
      (vfs/ensure-cache-dir! dir)
      (is (fs/directory? dir))
      (is (= "*\n" (slurp (str (fs/path dir ".gitignore"))))))))

(deftest existing-gitignore-is-not-overwritten
  (fs/with-temp-dir [tmp {}]
    (let [dir (fs/path tmp ".volley")
          ignore (str (fs/path dir ".gitignore"))]
      (fs/create-dirs dir)
      (spit ignore "timings.edn\n")
      (vfs/ensure-cache-dir! dir)
      (is (= "timings.edn\n" (slurp ignore))))))

(deftest existing-cache-dir-is-left-alone
  (testing "pytest only writes the ignore file when it creates the cache dir"
    (fs/with-temp-dir [tmp {}]
      (let [dir (fs/path tmp ".volley")]
        (fs/create-dirs dir)
        (vfs/ensure-cache-dir! dir)
        (is (not (fs/exists? (fs/path dir ".gitignore"))))))))
