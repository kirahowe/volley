(ns he.hooks
  "Hooks that go wrong in the ways a run must report.")

(defn throw-post-run [_result] (throw (ex-info "post-run boom" {})))

(defn throw-pre-test [testable _plan]
  (when (= :kaocha.type/var (:kaocha.testable/type testable))
    (throw (ex-info "pre-test boom" {})))
  testable)

(defn skip-wrap-run
  "A wrap-run hook that never calls run."
  [_run]
  (fn [testable _plan] testable))
