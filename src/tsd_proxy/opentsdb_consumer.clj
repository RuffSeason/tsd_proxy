(ns tsd_proxy.opentsdb_consumer)

(use 'aleph.tcp 'lamina.core 'lamina.connections 'gloss.core)

(require '[clojure.tools.logging :as log])

(def tsd-msg-format (string :utf-8 :delimiters ["\n"]))

(defn connect-qch-to-consumer [source-ch end-point consumer-ch]
  (log/info "Connected to:" end-point)
  (join source-ch consumer-ch)
  ; Discard any response from the consumer.
  (ground consumer-ch))

(defn make-consumer [end-point]
  (log/info "Connecting to:" end-point)
  (let [[host port] (clojure.string/split end-point #":")
        qch (permanent-channel)
        consumer (pipelined-client
                  #(tcp-client {:name end-point,
                                :host host,
                                :port (Integer/parseInt port),
                                :frame tsd-msg-format})
                  {:on-connected (partial connect-qch-to-consumer qch end-point)})]
    ; the client is lazy, it doesn't actually initiate until you send
    ; it something.  Send an empty string to prime it.
    (consumer "")
    qch))

