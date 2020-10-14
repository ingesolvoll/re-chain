(defproject re-chain "1.2"
  :description "Easy chaining of re-frame events"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :url "https://github.com/ingesolvoll/re-chain"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.10.597"]
                 [re-frame "1.0.0"]
                 [expound "0.8.2"]
                 [day8.re-frame/test "0.1.5"]]

  :plugins [[com.jakemccrary/lein-test-refresh "0.24.1"]]
  :deploy-repositories [["clojars" {:sign-releases false
                                    :url           "https://clojars.org/repo"
                                    :username      :env/clojars_username
                                    :password      :env/clojars_password}]]
  :aliases {"deploy!" ["do" ["test"] ["deploy" "clojars"]]})