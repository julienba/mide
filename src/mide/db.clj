(ns mide.db
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [crux.api :as crux]
            mide.db-spec
            mide.tempid))

(defn gen-uuid []
  (java.util.UUID/randomUUID))

(defn start-crux! []
  (letfn [(kv-store [dir]
                    {:kv-store {:crux/module 'crux.rocksdb/->kv-store
                                :db-dir      (io/file dir)
                                :sync?       true}})]
    (crux/start-node
     {:crux/tx-log              (kv-store "data/dev/tx-log")
      :crux/document-store      (kv-store "data/dev/doc-store")
      :crux/index-store         (kv-store "data/dev/index-store")})))

(def crux-node (crux/start-node {}))

;(.close crux-node)

(defn stop-crux! []
  (.close crux-node))

(defn insert!
  "xs is a vector of [type data]
  type have to be defined as a spec"
  ([xs] (insert! xs nil))
  ([xs valid-time]
   (assert (= #{2} (into #{} (distinct (map count xs))))
           "Data should be a tuple of [type data]")
   (let [xs               (->> xs
                               (mide.tempid/resolve-tempids!)
                               (map (fn [[type {:keys [id] :as data}]]
                                      [type
                                       (if id
                                         (-> data
                                             (assoc :crux.db/id id)
                                             (dissoc :id))
                                         data)])))
         invalid-data     (remove (fn [[type data]] (s/valid? type data)) xs)
         invalid-data-ids (into #{} (map (fn [[_ data]] (:crux.db/id data)) invalid-data))
         valid-data       (->> xs
                               (remove (fn [[_ data]] (get invalid-data-ids (:crux.db/id data))))
                               (map second))]
     (when (seq invalid-data)
       (doseq [[type data] invalid-data]
         (log/errorf "Wrong data shape %s => %s" type data)))

     (when (seq valid-data)
       (crux/submit-tx
        crux-node
        (mapv (fn [x]
                (if valid-time
                  [:crux.tx/put x valid-time]
                  [:crux.tx/put x]))
              valid-data))))))

(defn query
  "Query helper"
  ([x] (crux/q (crux/db crux-node) x))
  ([at x] (crux/q (crux/db crux-node at) x)))

(defn list-by-attributes [attrs]
  (let [db (crux/db crux-node)]
    (->> (crux/q db {:find '[id]
                     :where (mapv (fn [[attr value]] ['id attr value]) attrs)})
         (map first)
         (map #(crux/entity db %)))))

(def tempid mide.tempid/tempid)
(def tempid? mide.tempid/tempid?)

(defn get-by-attributes [attrs]
  (first (list-by-attributes attrs)))

(defn index->id-fn [attr]
  (memoize
    (fn [x]
      (or (:crux.db/id (get-by-attributes {attr x}))
          (tempid)))))
