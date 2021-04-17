(ns example
  (:require [mide.core :as mide]
            [mide.db :as db]))

(def project-path "/home/jba/REPO/tesser")
(def git-path (str project-path "/.git"))

; Ingest project and commits
(mide/ingest-project! "tesser" git-path)
(mide/run-analyzer! git-path)

(def all-commits
  (->> (db/query '{:find [e]
                   :where [[e :git/type :commit]]
                   :full-results? true})
       (map first)
       (sort-by :commit/authorAt)))

; All commit with files changed
(for [{:keys [crux.db/id commit/message commit/authorAt]} all-commits]
  [id message authorAt
   (db/query {:find '[e]
              :where '[[e :commit/ref commit-ref]]
              :full-results? true
              :args [{'commit-ref id}]})])


; All codeq for a namespace
(db/query {:find '[e]
           :where '[[e :code/name "tesser.utils"]]
           :full-results? true})

; All different versions of "tesser.core/min"
(->> (db/query {:find '[e]
                :where '[[e :code/name "tesser.core/min"]]
                :full-results? true})
     (map first)
     (reduce
      (fn [acc {:keys [code/sha] :as e}]
        (if (get acc sha)
          acc
          (assoc acc sha e)))
      {})
     vals)
