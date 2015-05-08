(defproject tsd_proxy "0.2.3"
  :description "A proxy to accept OpenTSDB traffic and send it to multiple destinations"
  :url "http://opentsdb.net"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :main tsd_proxy.core
  :aot [tsd_proxy.core]
  :jvm-opts ["-verbosegc" "-Xmx500M" "-Xms500M"]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [aleph "0.3.3"]
                 [clj-kafka "0.2.8-0.8.1.1"]
                 [org.clojure/tools.logging "0.2.6"]
                 [ch.qos.logback/logback-classic "1.0.9"]])
