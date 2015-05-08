(ns tsd_proxy.core
  (:gen-class))

(use 'lamina.core 'lamina.time 'aleph.tcp 'gloss.core)

(require 'tsd_proxy.opentsdb_consumer)
(require 'tsd_proxy.kafka_consumer)
(require 'tsd_proxy.influxdb_consumer)

(require '[clojure.tools.logging :as log])
(require 'clojure.edn)

(def config-file "/etc/tsd_proxy.conf")
(def tsd-msg-format (string :utf-8 :delimiters ["\n"]))
(def broadcast-ch (permanent-channel))
(def listener-enabled? (atom false))

(def get-config
  (memoize
   (fn [cfg-file]
     (clojure.edn/read-string
      (slurp (clojure.java.io/file cfg-file))))))

(defn setup-consumer [source-ch end-point]
  (let [consumer-fn (resolve
                     (symbol
                      (str (name (first end-point))
                           "/make-consumer")))
        connection-params (second end-point)
        queue-ch (consumer-fn connection-params)]
    (join source-ch queue-ch)
    queue-ch))

(let [pattern-list (map re-pattern (:junk-filter (get-config config-file)))]

  (defn make-filter [ch client-info]
    "returns a function that tests if a message is matched by all of
    the supplied patterns in the :junk-filter"
    (fn [msg]
      (every? (fn [pattern]
                "Match the message against the pattern and enqueue a
                response back to the client if the message is blocked."
                (if (re-find pattern msg)
                  true
                  (do
                    (enqueue ch (str "Input:" msg ", blocked by:" pattern))
                    (log/warn "Client:" (:address client-info)
                              "sent:" msg "," pattern "blocks this.")
                    false)))
              pattern-list)))

  (defn tsd-incoming-handler [ch client-info]
    (log/info "New connection from:" (:address client-info))
    (if (empty? pattern-list)
      (join ch broadcast-ch)
      ; we have a pattern list - filter* pipes the channels messages
      ; through the list, we then join the output to the broadcast.
      (join (filter* (make-filter ch client-info) ch) broadcast-ch))))

(defn start-tsd-listener []
  "Start the tcp listener and return a zero parameter function that
   can then be used to shutdown the tcp server."
  (let [config (get-config config-file)]
    (log/info "Server starting.. will listen on port:" (:listen-port config))
    (start-tcp-server tsd-incoming-handler
                      {:port (:listen-port config)
                       :frame tsd-msg-format})))

(defn queue-length [queue-channels]
  (reduce + (map count queue-channels)))

(defn enable-listener? [queue-channels]
  (< (queue-length queue-channels)
         (:limit (get-config config-file))))

(defn server-controller [queue-channels]
  "generates a controller function that decides if the listener should
   be shut down based on the number of messages queued in the
   channels."
  (fn [_]
    (if (enable-listener? queue-channels)
      ; we don't have too many messages pending in the queues, start
      ; the listener if it's not already alive.
      (when (not @listener-enabled?)
        (log/warn "Queue channels have room, enabling listener.")
        (reset! listener-enabled? (start-tsd-listener)))
      ; kill the listener here if it's up and running.
      (when @listener-enabled?
        (log/warn "Queue channels have" (queue-length queue-channels) "entries.")
        (log/warn "Shutting down listener till we catch up.")
        (@listener-enabled?)
        (reset! listener-enabled? false)))))

(defn -main [& args]
  (let [queue-channels (doall
                        (for [end-point (:end-points (get-config config-file))]
                          (do (log/info "Enabling:" end-point)
                              (setup-consumer broadcast-ch end-point))))
        controller-fn (server-controller queue-channels)]
    (reset! listener-enabled? (start-tsd-listener))
    ; Invoke the controller every second to regulate the tcp listener.
    (invoke-repeatedly 1000 controller-fn)
    ; waits here forever
    (wait-for-message (channel))))

