(ns espn.sifter.sessions
  (:require [clojure.data.json :as json]
            [org.httpkit.server :refer [send!]]
            [espn.sifter.kafka :as kafka]
            [espn.sifter.compiler.main :refer [compile-filter]])
  (:import (java.util.concurrent CancellationException)
           (espn.sifter.compiler.error CompilerError)))

(def SESSIONS (atom {}))

(defn create
  "Register a new session based around the given websocket"
  [sock]
  (swap! SESSIONS assoc sock {:socket sock}))

(defn session-for-socket [sock]
  (@SESSIONS sock))

(defn- send-json!
  "Convert the data structure `msg` to a json string and send it as a message on the session's websocket"
  [session msg]
  (send! (session :socket) (json/write-str msg)))

(defn- maybe-send-event [session filt event]
  (when (filt (:event event))
    (send-json! session event)))

(defmacro start-future
  "
  Stop the session's currently running job (if one is running) and then
  start a new job executing the `body`
  "
  [session & body]
  `(let [session# ~session]
     (do (stop-future session#)
         (swap! SESSIONS
                assoc-in [(session# :socket) :future]
                         (future ~@body)))))

(defn- stop-future
  "Stop the session's currently running background job, if one is running"
  [session]
  (when (some? (session :future))
    (future-cancel (session :future))
    (try @(session :future)
         (catch CancellationException e nil))))


(defn- complain-about-msg
  "Send the client a notification that the latest request was malformed"
  [session req reason]
  (send-json! session {:invalid-request req
                       :reason reason}))

(defn destroy
  "End the session for the given websocket and release any associated resources"
  [sock]
  (stop-future (@SESSIONS sock))
  (swap! SESSIONS dissoc sock))


(defn- fetch-block
  "
  Send an event on the session's websocket for every kafka message in the
  requested block that satisfies the requested filter
  "
  [session req]
  (start-future session
     (let [_ (println "The filter is" (:filter req))
           f (compile-filter (:filter req))]
       (if (instance? CompilerError f)
         (send-json! session f)
         (do (kafka/for-block  (:instant req) #(maybe-send-event session f %))
             (send-json! session {:end-block (:instant req)}))))))


(defn- start-tail [session req]
  "
  Start tailing the kafka topic and send an event on the session's websocket
  for every kafka message that satisfies the requested filter
  "
  (start-future session
     (let [_ (println "The filter is" (:filter req))
           f (compile-filter (:filter req))]
       (if (instance? CompilerError f)
         (send-json! session f)
         (kafka/for-tail #(maybe-send-event session f %))))))


(defn handle-request
  "Handle the given request in the given socket's session"
  [req sock]
  (cond
    (contains? req :fetch) (fetch-block (session-for-socket sock) req)
    (contains? req :tail)  (start-tail  (session-for-socket sock) req)
    (contains? req :pause) (stop-future (session-for-socket sock))
    :else                  (complain-about-msg (session-for-socket sock) req "Command is not 'fetch', 'tail', or 'pause'")))

