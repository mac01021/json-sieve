(ns espn.sifter.compiler.parser
  (:require [espn.sifter.compiler.error :refer [map->CompilerError]]))

(defrecord Conjunction [pos children])
(defrecord EqualitySign [pos])
(defrecord Fragment [pos content])
(defrecord StringLiteral [pos content])


(def ^:dynamic *source*)
(def ^:dynamic *position*)
(def ^:dynamic *stack*)

(defn compile-error [reason]
  (map->CompilerError {:reason  reason
                       :charno  *position* }))


(defn- next-char []
  (set! *position* (inc *position*))
  (if (> (count *source*) *position*)
    (. *source* charAt *position*)
    :eof))


(defn- push-list []
  (swap! *stack* conj (->Conjunction *position* [])))

(defn- push-string []
  (swap! *stack* conj (->StringLiteral *position* (new StringBuilder))))

(defn- push-fragment []
  (swap! *stack* conj (->Fragment *position* (new StringBuilder))))

(defn- finalized [atom]
    (update atom :content str))


(defn- pop-atom []
  (swap! *stack* (fn [stack]
                   (let [atom (finalized (first stack))
                         stack (rest stack)
                         top (first stack)
                         under (rest stack)
                         top (update top :children conj atom)]
                     (cons top under)))))

(defn- pop-list []
  (swap! *stack* (fn [stack]
                   (let [lst (first stack)
                         top (-> stack (rest) (first))
                         under (-> stack (rest) (rest))
                         top (update top :children conj lst)]
                     (cons top under)))))

(defn- append-eq []
  (swap! *stack* (fn [stack]
                   (cons (update (first stack) :children conj (->EqualitySign *position*))
                         (rest stack)))))


(defn- integrate-char [ch]
  (let [sb (-> @*stack* (first) (:content) )]
    (. sb append ch)))


(defn- at-bottom? []
  (-> @*stack* rest empty?))


;;Parser States
(def <start-string>)
(def <in-string>)
(def <escape>)
(def <end-string>)
(def <start-fragment>)
(def <in-fragment>)
(def <end-fragment>)
(def <=>)
(def <end-of-file>)
(def <start-list>)
(def <end-list>)
(def <parse>)

(defmacro defstate [name & body]
  `(defn- ~name []
     ;(println ~(str name) (deref *stack*))
     ~@body))

(defstate <start-string>
  (push-string)
  <in-string>)


(defstate <in-string>
  (let [ch (next-char)]
    (cond
      (= ch \") <end-string>
      (= ch \\) <escape>
      (= ch :eof) <end-of-file>
      :else (do (integrate-char ch)
                <in-string>))))


(defstate <escape>
  (integrate-char \\)
  (let [ch (next-char)]
    (if (= ch :eof)
      <end-of-file>
      (do (integrate-char ch)
          <in-string>))))


(defstate <end-string>
  (pop-atom)
  <parse>)


(defstate <start-fragment>
  (push-fragment)
  (integrate-char (. *source* charAt *position*))
  <in-fragment>)


(defstate <in-fragment>
  (let [ch (next-char)]
    (cond
      (= ch \") (do (pop-atom) <start-string>)
      (= ch :eof) (do (pop-atom) <end-of-file>)
      (= ch \=) (do (pop-atom) <=>)
      (= ch \() (do (pop-atom) <start-list>)
      (= ch \)) (do (pop-atom) <end-list>)
      (Character/isWhitespace ch) <end-fragment>
      :else (do (integrate-char ch)
                <in-fragment>))))


(defstate <end-fragment>
  (pop-atom)
  <parse>)


(defstate <=>
  (append-eq)
  <parse>)


(defstate <end-of-file>
  (if (at-bottom?)
    (first @*stack*)
    (compile-error "unexpected end of file")))


(defstate <start-list>
  (push-list)
  <parse>)


(defstate <end-list>
  (if (at-bottom?)
    (compile-error "unexpected character")
    (do (pop-list)
        <parse>)))


(defstate <parse>
  (let [ch (next-char)]
    (cond
      (= ch :eof) <end-of-file>
      (= ch \") <start-string>
      (= ch \=) <=>
      (= ch \() <start-list>
      (= ch \)) <end-list>
      (Character/isWhitespace ch) <parse>
      :else <start-fragment>)))

(defn parse
  "
  Return the parse tree for the given text.
  "
  [text]
  (binding [*source* text
            *position* -1
            *stack* (atom (list (->Conjunction nil [])))]
    (trampoline <parse>)))


