(ns mide.db
  (:require [clojure.java.io :as io]
            [crux.api :as crux]
            mide.tempid))

(defn gen-uuid []
  (.toString (java.util.UUID/randomUUID)))

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
  "Transact several puts on node"
  ([xs] (insert! xs nil))
  ([xs valid-time]
   (let [xs' (if (map? xs) [xs] xs)
         xs' (mide.tempid/resolve-tempids! xs')]
     (crux/submit-tx
      crux-node
      (mapv (fn [x]
              (let [id (or (:crux.db/id x) (:id x) (throw (Exception. "no id in " x)))
                    x' (assoc (dissoc x :id) :crux.db/id id)]
                (if valid-time
                  [:crux.tx/put x' valid-time]
                  [:crux.tx/put x'])))
            xs')))))

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
