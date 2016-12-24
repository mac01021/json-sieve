(ns sifter.app
  (:require [clojure.data.json :as json]
            [ring.util.response :as resp]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.handler :refer [site]]
            [compojure.route :refer [resources not-found]]
            [org.httpkit.server :refer [with-channel
                                        on-receive
                                        on-close
                                        send!
                                        run-server]]
            [sifter.sessions :as sessions]
            [sifter.kafka :as kafka])
  (:gen-class))


(defn connection-handler [req]
  (binding [kafka/*brokers* "dp-dev-kb0-metadata.dev.dp.pvt"
            kafka/*client-id* "logsifter"
            kafka/*cxn-pool-size* 128,
            kafka/*topic* "dp-coolbetm-fake-log-topic"
            kafka/*block-size* (* 2 1000 1000)]

    (with-channel req chan

                  (println "new session")
                  (sessions/create chan)

                  (on-close chan (bound-fn [_status]
                                   (println "close session:" _status)
                                   (sessions/destroy chan)))

                  (on-receive chan (bound-fn [msg]
                                     (println msg)
                                     (-> msg
                                         (json/read-str :key-fn keyword)
                                         (sessions/handle-request chan)))))))


(defroutes the-routes
           (resources "/")
           (GET "/" [] (resp/resource-response "index.html" {:root "public"}))
           (GET "/websock" [] connection-handler)
           (not-found "<p> No such page... </p>"))



(def sifters (atom {}))

(defn start
   ([port]
      (run-server #'the-routes {:port port}))
   ([port name]
     (swap! sifters assoc name (start port))))

(defn stop [name]
  (let [s (@sifters name)]
    (s)
    (swap! sifters dissoc name)))

(defn stop-all []
  (doseq [[k v] @sifters]
    (v)
    (swap! sifters dissoc k)))



(defn -main
  "ENTRY POINT!"
  [& _args]
  (println "STARTING UP!")
  (run-server the-routes {:port 5555}))

