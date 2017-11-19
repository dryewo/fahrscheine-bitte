(ns fahrscheine-bitte.core-test
  (:require [clojure.test :refer :all]
            [fahrscheine-bitte.core :refer :all :as v]
            [juxt.iota :refer [given]]
            [clj-http.client :as http])
  (:import (com.netflix.hystrix.exception HystrixRuntimeException)))

(defn resolve-token-valid [token]
  {"scope"        ["uid"]
   "access_token" token
   "uid"          "mjackson"})

(defn resolve-token-invalid [token]
  nil)

(defn resolve-token-error [token]
  (throw (IllegalStateException. (str "tokeninfo endpoint returned status code: " (:status 503)))))

(def handler1 (make-oauth2-s1st-security-handler resolve-token-valid check-consented-scopes))
(def handler2 (make-oauth2-s1st-security-handler resolve-token-invalid (constantly true)))
(def handler3 (make-oauth2-s1st-security-handler resolve-token-error (constantly true)))
(def handler4 (make-oauth2-s1st-security-handler resolve-token-valid check-corresponding-attributes))

(deftest s1st-handler

  (testing "When token is valid"
    (given (handler1 {} nil [])
           :status := 401
           ::v/reason-code :token-missing)
    (given (handler1 {:headers {"authorization" "Bearer footoken"}} nil ["foo.read"])
           :status := 403
           ::v/reason-code := :scopes)
    (given (handler1 {:headers {"authorization" "Bearer footoken"}} nil ["uid"])
           :tokeninfo :> {"scope" ["uid"] "access_token" "footoken"}
           :status := nil
           ::v/reason-code := nil)
    (given (handler4 {:headers {"authorization" "Bearer footoken"}} nil ["foo.read"])
           :status := 403
           ::v/reason-code := :scopes)
    (given (handler4 {:headers {"authorization" "Bearer footoken"}} nil ["uid"])
           :tokeninfo :> {"uid" "mjackson" "access_token" "footoken"}
           :status := nil
           ::v/reason-code := nil))

  (testing "When token is invalid"
    (given (handler2 {} nil [])
           :status := 401
           ::v/reason-code :token-missing)
    (given (handler2 {:headers {"authorization" "Bearer footoken"}} nil [])
           :status := 401
           ::v/reason-code :token-invalid))

  (testing "When tokeninfo returns 5xx"
    (is (thrown-with-msg? Exception #"returned status" (handler3 {:headers {"authorization" "Bearer footoken"}} nil []))))

  (testing "integration"
    (testing "when everything is ok"
      (let [handler         (make-oauth2-s1st-security-handler (make-cached-access-token-resolver "tokeninfo-url" {})
                                                               check-consented-scopes)
            http-get-called (atom [])]
        (with-redefs [http/get (fn [url {:keys [oauth-token]}]
                                 (swap! http-get-called conj [url oauth-token])
                                 {:status 200 :body {"access_token" oauth-token "uid" "mjackson" "scope" ["uid"]}})]
          (testing "http/get should be called"
            (given (handler {:headers {"authorization" "Bearer footoken"}} nil ["uid"])
                   :status := nil
                   :tokeninfo :> {"scope" ["uid"], "access_token" "footoken"})
            (is (= [["tokeninfo-url" "footoken"]] @http-get-called)))
          (testing "Call again, cached response should be used"
            (handler {:headers {"authorization" "Bearer footoken"}} nil ["uid"])
            (is (= 1 (count @http-get-called)))))))

    (testing "when http/get returns 500"
      (let [handler (make-oauth2-s1st-security-handler (make-cached-access-token-resolver "tokeninfo-url" {})
                                                       check-consented-scopes)]
        (with-redefs [http/get (fn [_ _] {:status 503})]
          (is (thrown? HystrixRuntimeException (handler {:headers {"authorization" "Bearer footoken"}} nil [])))))))
  )

(defn ring-handler [request]
  {:status 200 :body {:incoming-tokeninfo (:tokeninfo request)}})

(def wrapped-ring-handler1 (-> ring-handler ((make-wrap-oauth2-token-verifier resolve-token-valid))))
(def wrapped-ring-handler2 (-> ring-handler ((make-wrap-oauth2-token-verifier resolve-token-invalid))))
(def wrapped-ring-handler3 (-> ring-handler ((make-wrap-oauth2-token-verifier resolve-token-error))))

(defn error-logger [message]
  (println "Access denied:" message))

(def wrapped-ring-handler4 (-> ring-handler
                               ((make-wrap-oauth2-token-verifier resolve-token-valid))
                               (wrap-log-auth-error error-logger)))

(deftest ring-middleware

  (given (wrapped-ring-handler1 {})
         :status := 401
         ::v/reason-code :token-missing)
  (given (wrapped-ring-handler1 {:headers {"authorization" "Bearer footoken"}})
         [:body :incoming-tokeninfo] :> {"scope" ["uid"] "access_token" "footoken"}
         :status := 200
         ::v/reason-code := nil)

  (testing "When token is invalid"
    (given (wrapped-ring-handler2 {})
           :status := 401
           ::v/reason-code :token-missing)
    (given (handler2 {:headers {"authorization" "Bearer footoken"}} nil [])
           :status := 401
           ::v/reason-code :token-invalid))

  (testing "When tokeninfo returns 5xx"
    (is (thrown-with-msg? Exception #"returned status" (wrapped-ring-handler3 {:headers {"authorization" "Bearer footoken"}}))))

  (testing "integration"
    (let [messages  (atom [])
          logger-fn (fn [msg]
                      (swap! messages conj msg))]
      (given ((wrap-log-auth-error wrapped-ring-handler1 logger-fn) {})
             :status := 401)
      (given ((wrap-log-auth-error wrapped-ring-handler2 logger-fn) {:headers {"authorization" "Bearer footoken"}})
             :status := 401)
      (is (thrown-with-msg? Exception #"returned status" ((wrap-log-auth-error wrapped-ring-handler3 logger-fn) {:headers {"authorization" "Bearer footoken"}})))
      (is (= ["no access token given" "invalid access token"] @messages))))

  )


