(defproject mide "0.1.0-SNAPSHOT"
  :description "A modern IDE tool"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.3"]

                 ; Functionnal database
                 [juxt/crux-core "21.02-1.15.0-beta"]
                 [juxt/crux-rocksdb "21.02-1.15.0-beta"]
                 ; reading config
                 ;[aero "1.1.3"]

                 [commons-codec "1.7"]

                 ; Logging hell
                 [org.clojure/tools.logging "0.5.0"]
                 [org.slf4j/slf4j-log4j12 "1.7.28"]
                 [log4j/log4j "1.2.17" :exclusions [javax.mail/mail
                                                    javax.jms/jms
                                                    com.sun.jmdk/jmxtools
                                                    com.sun.jmx/jmxri]]]
  :profiles {:dev {:source-paths ["dev" "src" "test"]}})
