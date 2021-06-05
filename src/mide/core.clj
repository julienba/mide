(ns mide.core
  (:require [clojure.java.io :as io]
            clojure.java.shell
            clojure.set
            [clojure.string :as string]
            clojure.stacktrace
            [mide.db :as db]
            [mide.analyzer.clj :as clj])
  (:import java.util.Date))

(defn ^java.io.Reader exec-stream
  [^String cmd]
  (-> (Runtime/getRuntime)
      (.exec cmd)
      .getInputStream
      io/reader))

(defn dir
  "Returns [[sha :type filename] ...]"
  [git-path tree]
  (with-open [s (exec-stream (format "git --git-dir=%s cat-file -p %s" git-path tree))]
    (let [es (line-seq s)]
      (mapv #(let [ss (string/split ^String % #"\s")]
               [(nth ss 2)
                (keyword (nth ss 1))
                (subs % (inc (.indexOf ^String % "\t")) (count %))])
            es))))

(defn commits
  "Returns log as [[sha msg] ...], in commit order. commit-name may be nil
  or any acceptable commit name arg for git log"
  [git-path commit-name]
  (let [cmd-line (format "git --git-dir=%s log --pretty=oneline --date-order --reverse " git-path)
        cmd-line (if commit-name
                   (str cmd-line commit-name)
                   cmd-line)
        commits (with-open [s (exec-stream cmd-line)]
                  (mapv
                   #(vector (subs % 0 40)
                            (subs % 41 (count %)))
                   (line-seq s)))]
    commits))

(defn sha+message->commit
  [git-path [sha _]]
  (let [trim-email (fn [s] (subs s 1 (dec (count s))))
        dt         (fn [ds] (Date. (* 1000 (Integer/parseInt ds))))
        [tree parents author committer msg]
        (with-open [s (exec-stream (format "git --git-dir=%s cat-file -p %s" git-path sha))]
          (let [lines       (line-seq s)
                slines      (mapv #(string/split % #"\s") lines)
                tree        (-> slines (nth 0) (nth 1))
                [plines xs] (split-with #(= (nth % 0) "parent") (rest slines))]
            [tree
             (seq (map second plines))
             (vec (reverse (first xs)))
             (vec (reverse (second xs)))
             (->> lines
                  (drop-while #(not= % ""))
                  rest
                  (interpose "\n")
                  (apply str))]))]
    {:sha       sha
     :msg       msg
     :tree      tree
     :parents   parents
     :author    (trim-email (author 2))
     :authored  (dt (author 1))
     :committer (trim-email (committer 2))
     :committed (dt (committer 1))}))

(defn git-walk
  "Traverse git tree and collect blob
   Return a vector of map with :file/path :git/type and :git/sha"
  [git-path {:keys [tree]} path type]
  (let [data [{:file/path path :git/type type}]]
    (when (= type :tree)
      (let [es (dir git-path tree)]
        (reduce
         (fn [acc child]
           (let [[sha type file-name] child]
             (if (= type :tree)
               (concat acc (git-walk git-path {:tree sha} (str path file-name "/") :tree))
               (conj acc {:file/path (str path file-name)
                          :git/type type
                          :git/sha sha}))))
         data
         es)))))

(defn ingest-project! [project-name git-path]
  (let [repo (db/get-by-attributes {:repo/uri git-path})
        repo-id (if repo
                  (:crux.db/id repo)
                  (let [repo-id (db/gen-uuid)]
                    (db/insert! [[:db/repo {:crux.db/id repo-id
                                            :repo/uri git-path
                                            :repo/name project-name}]])
                    repo-id))
        commits-seq (commits git-path nil)
        already-ingested (->> (db/query {:find '[sha]
                                         :where '[[e :git/type :commit]
                                                  [e :repo/ref repo-id]
                                                  [e :git/sha sha]]
                                         :args [{'repo-id repo-id}]})
                              (map first)
                              set)
        commits-to-ingest (remove (fn [[sha _]] (get already-ingested sha)) commits-seq)]
    (doall
      (for [commit commits-to-ingest]
       (let [_ (println "Ingesting commit: " commit)
             {:keys [msg sha parents author authored] :as raw-commit} (sha+message->commit git-path commit)
             commit-id (db/gen-uuid)
             commit-tx {:crux.db/id commit-id
                        :git/type :commit
                        :git/sha  sha
                        :repo/ref repo-id
                        :commit/parents parents
                        :commit/message msg
                        :commit/author author
                        :commit/authorAt authored}
             nodes-tx (->> (git-walk git-path raw-commit "/" :tree)
                           (map #(assoc % :crux.db/id (db/gen-uuid) :commit/ref commit-id))
                           (filter #(= :blob (:git/type %)))
                           (remove #(db/get-by-attributes (select-keys % [:file/path :git/sha]))))]
         (db/insert! (cons [:db/commit commit-tx] (map (fn [d] [:db/file d]) nodes-tx))))))))

(defn run-analyzer! [git-path]
  (let [analyzer-name :clj
        analyzer-version 1
        all-files (db/query '{:find [e path sha]
                              :where [[e :file/path path]
                                      [e :git/sha sha]]})
        candidate-files (->> all-files
                             (filter (fn [[_ path _]] (string/ends-with? path ".clj")))
                             set)

        analyzed-files  (->> all-files
                             (map (fn [[file-id _ sha]] (db/get-by-attributes {:tx/file-ref file-id
                                                                               :tx/sha sha
                                                                               :tx/analyzer analyzer-name
                                                                               :tx/analyzer-revision analyzer-version})))
                             (remove nil?)
                             ; put it in the same shape as candidate-files (could be done in one datalog query)
                             (map (fn [{:tx/keys [file-ref sha]}]
                                    [file-ref
                                     (:file/path (db/get-by-attributes {:crux.db/id file-ref}))
                                     sha]))

                             set)]

    (doseq [[file-id path sha] (clojure.set/difference candidate-files analyzed-files)]
      (let [_ (println "Analyze " path sha)
            src (with-open [s (exec-stream (format "git --git-dir=%s cat-file -p %s" git-path sha))]
                        (slurp s))
            codeq-data (try
                         (clj/analyze file-id src)
                         (catch Exception _ex
                           (println "Error during analyze of " {:file-id file-id
                                                                :path path
                                                                :sha sha})
                           ;(clojure.stacktrace/print-stack-trace ex)
                           []))
            op-data [:db/op
                     {:crux.db/id (db/tempid)
                      :tx/file-ref file-id
                      :tx/sha sha
                      :tx/analyzer analyzer-name
                      :tx/analyzer-revision analyzer-version}]]

        (db/insert! (cons op-data (map (fn [d] [:db/codeq d]) codeq-data)))))))
