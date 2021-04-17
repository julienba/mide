(ns mide.tempid
  (:require [clojure.walk :refer [prewalk]])
  (:import [java.io Writer]))

(def tag "crux/tempid")

(defrecord TempId [id]
  Object
  (toString [this]
    (pr-str this)))

(defmethod print-method TempId [^TempId x ^Writer writer]
  (.write writer (str "#" tag "[\"" (.id x) "\"]")))

(defn tempid
  "Create a new tempid."
  ([]
   (tempid (java.util.UUID/randomUUID)))
  ([uuid]
   (TempId. uuid)))

(defn tempid?
  "Returns true if the given `x` is a tempid."
  [x]
  (instance? TempId x))

(defn resolve-tempids!
  [data-structure]
  (prewalk
   #(if (tempid? %) (:id %) %)
   data-structure))
