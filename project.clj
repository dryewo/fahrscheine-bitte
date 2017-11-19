(defproject org.zalando/fahrscheine-bitte "0.0.0"
  :description "Library for verifying OAuth2 access tokens"
  :url "https://github.com/dryewo/fahrscheine_bitte"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clj-http "3.7.0"]
                 [com.netflix.hystrix/hystrix-clj "1.5.12"]
                 [org.clojure/core.memoize "0.5.9"]]
  :plugins [[lein-cloverage "1.0.9"]
            [lein-shell "0.5.0"]]
  :profiles {:dev {:dependencies [[juxt/iota "0.2.3"]]}}
  :deploy-repositories [["releases" :clojars]]
  :release-tasks [["shell" "git" "diff" "--exit-code"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["deploy"]
                  ["vcs" "push"]])
