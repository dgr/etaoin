(ns ^:no-doc etaoin.impl.xpath
  "A special module to work with XPath language."
  (:require [clojure.string :as string]
            [slingshot.slingshot :refer [throw+]]))

(set! *warn-on-reflection* true)

(def Q \")

(defn node-by-text
  [text]
  (format ".//text()[contains(., %s%s%s)]/.." Q text Q))

(defmulti to-str type)

(defmethod to-str clojure.lang.Keyword
  [x]
  (name x))

(defmethod to-str String
  [x] x)

(defmethod to-str :default
  [x]
  (str x))

(defmulti clause first)

(defn node-contains
  [what text]
  (format "[contains(%s, %s%s%s)]" (to-str what) Q (to-str text) Q))

(defn node-equals
  [what text]
  (format "[%s=%s%s%s]" (to-str what) Q (to-str text) Q))

(defn node-boolean
  [what bool]
  (if bool
    (format "[%s=true()]" (to-str what))
    (format "[%s=false()]" (to-str what))))

(defn node-index
  [idx]
  (format "[%d]" idx))

(defn node-join
  [nodes]
  (string/join "" nodes))

(defmethod clause :default
  [[attr text]]
  ;; If we fall through to the default case and yet the attribute
  ;; looks like a query function, then the user probably has a typo
  ;; in their program, so we throw.
  ;; Else treat it like an attribute
  (if (and (keyword? attr) (= (namespace attr) "fn"))
    (throw+ {:type :etaoin/argument
             :message "Unknown query function"
             :fn attr
             :arg text})
    (node-equals (format "@%s" (to-str attr)) text)))

(defmethod clause :fn/text
  [[_ text]]
  (node-equals "text()" text))

(defmethod clause :fn/has-text
  [[_ text]]
  (node-contains "text()" text))

(defmethod clause :fn/has-string
  [[_ text]]
  (node-contains "string()" text))

(defmethod clause :fn/has-class
  [[_ class]]
  (node-contains "@class" class))

(defmethod clause :fn/has-classes
  [[_ classes]]
  (node-join
    (for [class classes]
      (node-contains "@class" class))))

(defmethod clause :fn/disabled
  [[_ bool]]
  (node-boolean "@disabled" bool))

(defmethod clause :fn/enabled
  [[_ bool]]
  (node-boolean "@disabled" (not bool)))

(defmethod clause :fn/index
  [[_ idx]]
  (if idx
    (node-index idx)
    ""))

(defmethod clause :fn/link
  [[_ text]]
  (node-contains "@href" text))

(defn pop-map
  [m k]
  [(get m k) (dissoc m k)])

(defn expand
  [q]
  (let [[tag q]   (pop-map q :tag)
        tag       (or tag :*)
        [fn-index q] (pop-map q :fn/index)
        [index q] (pop-map q :index)
        nodes     (concat (into [] q) {:fn/index (or fn-index index)})]
    (node-join (concat [".//" (to-str tag)] (map clause nodes)))))
