(ns wombats.game.dev-mode.clojure
  (:require [cheshire.core :as cheshire]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]))

(defn handler
  "This evaluates the clj bot code against the state given."
  [code state timeout-in]
  (let [timeout (fn [] (- timeout-in (tc/to-long (t/now))))
        state-obj (:state (cheshire/parse-string state true))
        ret-value ((load-string code) state-obj timeout)]
    (cheshire/generate-string ret-value)))

