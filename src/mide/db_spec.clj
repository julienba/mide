(ns mide.db-spec
  (:require [clojure.spec.alpha :as s]))

(s/def :crux.db/id uuid?)

(s/def :repo/uri string?)
(s/def :repo/name string?)

(s/def :db/repo
  (s/keys :req [:crux.db/id :repo/uri :repo/name]))


(s/def :git/type #{:commit :blob})
(s/def :git/sha string?)
(s/def :repo/ref uuid?)
(s/def :commit/parents (s/nilable (s/coll-of string?)))
(s/def :commit/message string?)
(s/def :commit/author string?)
(s/def :commit/authorAt inst?)

(s/def :file/path string?)
(s/def :git/sha string?)
(s/def :commit/ref uuid?)

(s/def :db/file
  (s/keys :req [:crux.db/id :file/path :git/type :git/sha :commit/ref]))

(s/def :db/commit
  (s/keys :req [:crux.db/id :git/sha :repo/ref :commit/parents
                :commit/message :commit/author :commit/authorAt]))

(s/def :codeq/file-ref uuid?)
(s/def :codeq/location string?) ; example "1 1 1 17"
(s/def :codeq/sha string?)
(s/def :code/text string?)
(s/def :clj/defop string?) ;#{"defn" "def" "defmacro"}) ; 
(s/def :code/name string?)
(s/def :clj/ns? boolean?)

(s/def :db/codeq
  (s/keys :req [:crux.db/id :codeq/file-ref :codeq/location :code/sha :code/text]
          :opt [:clj/ns? :clj/defop :code/name]))

(s/def :tx/file-ref uuid?)
(s/def :tx/sha string?)
(s/def :tx/analyzer keyword?)
(s/def :tx/analyzer-revision int?)

(s/def :db/op
  (s/keys :req [:crux.db/id :tx/file-ref :tx/sha :tx/analyzer :tx/analyzer-revision]))
