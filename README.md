# tsd_proxy

A Clojure program that accepts incoming OpenTSDB requests (clients
dumping data points into OpenTSDB) and sends them on to multiple
backend OpenTSDB servers.  Since this is a one-way proxy, it doesn't
respond to status requests.

## Notes

The proxy looks for the config file at /etc/tsd_proxy.conf, it should
have the format below.  All of the four keys are required.

    {
      :end-points
        [[:tsd_proxy.kafka_consumer "zkhost.local.net:2181/kafka"],
         [:tsd_proxy.opentsdb_consumer "opentsdb.local.net:9999"]],
         [:tsd_proxy.influxdb_consumer
           "http://influxdb.local.net:8086/db/metrics/series?time_precision=s&u=foo&p=bar"]]
      :listen-port 4242,
      :junk-filter ["^put" "host=(?!localhost)"],
      :limit 1024
    }

If the proxy cannot forward messages to the downstream consumers, it
buffers (upto :limit across all the consumers combined) them if the
underlying jvm heap size can support it.  The :limit parameter gives
you a knob to help keep the proxy from getting into the Full-GC death
loop.  In previous versions, this was a multiple of the incoming
message rate, which in retrospect was a poor choice.  The limit is now
a hard upper bound.

The proxy will continue trying to reach the consumers every few
seconds.  Once the consumer is up, it sends the buffered input to the
consumer in the order it was received.  If you can allocate a lot of
memory to the jvm, you should set the :limit to be reasonably high.

You can enable filters on the incoming data stream by setting the
:junk-filter array to a regex pattern list.  All of these patterns
should match for a message to be sent to the downstream consumers.

Specifically, the regex patterns you supply are converted to a java
regex pattern and used with java.util.regex.Matcher.find to determine
if there is a match.  If you turn this feature off (set :junk-filter
to an empty array [] in the config) - you will see better performance
(CPU wise).

This release (0.2.1) also adds a few backend consumer types.  The
consumer code has been reworked and now lives in its own namespace.
To add a new consumer type, please take a look at the existing
examples - every consumer has to implement a "make-consumer" function
that takes a single argument and should return a (permanent?)
channel.  This channel will be used as a queue for metrics being
dumped into the consumer.  It is the responsibility of the consumer to
ensure that these messages are delivered to their final destination.
If the consumer can't do this, it should stop consuming messages from
the queue.  The tsd_proxy will then detect this (when queue sizes are
greater than :limit) and regulate the listener as needed.

There are consumers for storing metrics into a kafka topic (hard-coded
to metrics), and to send them to an influxdb server.  If you use the
influxdb consumer, remember to set the time_precision in the url
(opentsdb has a 1s precision, influxdb default precision is a
millisecond).  The influxdb consumer batches requests in 2s intervals,
so you definitely need a higher queue limit in the tsd_proxy config to
support this.  If you use the Kafka/Influxdb consumers, please keep in
mind that the tsd_proxy process will probably need a bigger heap to
work well.

## Usage

lein run (or lein trampoline run) or make an uberjar (lein uberjar)
and run it with "java -jar UBERJAR.jar"

## License

Copyright © 2014 Tumblr Inc.

Distributed under the Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0
