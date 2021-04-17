(ns mide.analyzer.clj
  (:require [mide.analyzer :as az]
            [mide.db :as db]
            [clojure.tools.reader :as reader]))

(defn- analyze*
  "returns [tx-data ctx]"
  [file-ref content location segment ret {:keys [added ns sha->id] :as ctx}]
  (if location
    (let [sha (-> segment az/ws-minify az/sha)
          code-id (sha->id sha)
          added (cond-> added (db/tempid? code-id) (conj code-id))
          op (first content)
          ns? (= op 'ns)
          defing? (and ns
                       (symbol? op)
                       (.startsWith (name op) "def"))

          naming (let [nsym (second content)]
                   (cond
                     ns? (str nsym)
                     defing? (if (try (namespace nsym)
                                   (catch Exception _ nil))
                               (str nsym)
                               (str (symbol (name ns) (name nsym))))))

          previous-codeq (db/get-by-attributes {:codeq/file-ref file-ref})

          new-codeq (cond-> (or previous-codeq
                                {:id (db/gen-uuid)
                                 :codeq/file-ref file-ref
                                 :codeq/location location
                                 :code/sha sha
                                 :code/text segment})

                            ns?
                            (assoc :clj/ns? true)
                            defing?
                            (assoc :clj/defop (str op))
                            naming
                            (assoc :code/name naming))

          ret (conj ret new-codeq)]
      [ret (assoc ctx :added added)])
    [ret ctx]))

(def sha->id
  (db/index->id-fn :code/sha))

(def codename->id
  (db/index->id-fn :code/name))

(defn analyze
  [file-ref src]
  (with-open [r (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. src))]
    (let [loffs (az/line-offsets src)
          eof (Object.)
          ctx {:sha->id sha->id
               :codename->id codename->id
               :added #{}}]
      ; Come from clindex
      ; Avoid error if namespace alias are used. A better solution would be to resolve them.
      (binding [;reader/*data-readers* (merge tags/*cljs-data-readers* readers)
                ;; this is relaying on a implementation detail, tools.reader/read calls
                ;; *alias-map* as a fn, so we can resolve to a dummy ns and allow reader to continue
                reader/*alias-map* (fn [alias]
                                     (if-let [ns nil] ;(get alias-map alias)]
                                       ns
                                       (let [unresolved-ns (symbol "unresolved" (str alias))]
                                         ; (println "[Warning] couldn't resolve alias, resolving to " unresolved-ns
                                         ;         {;:path full-path
                                         ;          :alias alias})
                                         unresolved-ns)))
                reader/*read-eval* false]

        (loop [ret [], ctx ctx, x (reader/read r false eof)]
          (if (= eof x)
            ret
            (let [{:keys [line column]} (meta x)
                  ctx (if (and (coll? x) (= (first x) 'ns))
                        (assoc ctx :ns (second x))
                        ctx)
                  endline (.getLineNumber r)
                  endcol (.getColumnNumber r)
                  [loc seg] (when (and line column)
                              [(str line " " column " " endline " " endcol)
                               (az/segment src loffs (dec line) (dec column) (dec endline) (dec endcol))])
                  [ret ctx] (analyze* file-ref x loc seg ret ctx)]
              ;(def ret_ ret)
              (recur ret ctx
                (reader/read r false eof)))))))))

;(analyze :whatever (slurp "/home/jba/REPO/tesser/src/tesser.clj"))
