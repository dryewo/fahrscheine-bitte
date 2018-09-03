(defproject fahrscheine-bitte "0.2.1"
  :description "Library for verifying OAuth2 access tokens"
  :url "https://github.com/dryewo/fahrscheine-bitte"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[clj-http "3.9.1"]
                 [com.netflix.hystrix/hystrix-clj "1.5.12"]
                 [org.clojure/core.memoize "0.7.1"]]
  :plugins [[lein-cloverage "1.0.13"]
            [lein-ancient "0.6.15"]
            [lein-shell "0.5.0"]
            [lein-changelog "0.3.1"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0"]
                                  [juxt/iota "0.2.3"]]}}
  :deploy-repositories [["releases" :clojars]]
  :aliases {"update-readme-version" ["shell" "sed" "-i" "s/\\\\[fahrscheine-bitte \"[0-9.]*\"\\\\]/[fahrscheine-bitte \"${:version}\"]/" "README.md"]}
  :release-tasks [["shell" "git" "diff" "--exit-code"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["changelog" "release"]
                  ["update-readme-version"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["deploy"]
                  ["vcs" "push"]])
