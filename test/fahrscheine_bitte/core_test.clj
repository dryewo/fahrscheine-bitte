(ns fahrscheine-bitte.core-test
  (:require [clojure.test :refer :all]
            [fahrscheine-bitte.core :refer :all :as v]
            [juxt.iota :refer [given]]
            [clj-http.client :as http]))


(defn resolve-token-valid [token]
  {"scope"        ["uid"]
   "access_token" token
   "uid"          "mjackson"})


(defn resolve-token-invalid [token]
  nil)


(defn resolve-token-error [token]
  (throw (IllegalStateException. (str "tokeninfo endpoint returned status code: " 503))))


(defn wrap-client-tracing [http-get]
  (fn [url params]
    (println "Calling tokeninfo URL:" url)
    (let [res (http-get url (assoc params :foo "bar"))]
      (println "Tokeninfo result status:" (:status res))
      res)))


(def handler1 (make-oauth2-s1st-security-handler resolve-token-valid check-consented-scopes))
(def handler2 (make-oauth2-s1st-security-handler resolve-token-invalid (constantly true)))
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
              ;; :status being nil means we still have a request, not a response - normal operation
              :status := nil
              ;; :tokeninfo key is injected into the request
              :tokeninfo :> {"scope" ["uid"], "access_token" "footoken"})
            (is (= [["tokeninfo-url" "footoken"]] @http-get-called)))
          (testing "Call again, cached response should be used"
            (handler {:headers {"authorization" "Bearer footoken"}} nil ["uid"])
            (is (= 1 (count @http-get-called)))))))

    (testing "when http/get returns 5xx"
      (let [handler (make-oauth2-s1st-security-handler (make-cached-access-token-resolver "tokeninfo-url" {})
                                                       check-consented-scopes)]
        (with-redefs [http/get (fn [_ _] {:status 503})]
          (given (handler {:headers {"authorization" "Bearer footoken"}} nil [])
            :status := 504
            :body := "access-token-resolver-fn call threw an exception: java.lang.IllegalStateException: tokeninfo endpoint returned status code: 503"))))

    (testing "when http/get throws an exception"
      (let [handler (make-oauth2-s1st-security-handler (make-cached-access-token-resolver "tokeninfo-url" {})
                                                       check-consented-scopes)]
        (with-redefs [http/get (fn [_ _] (throw (Exception. "EXPLODED!")))]
          (given (handler {:headers {"authorization" "Bearer footoken"}} nil [])
            :status := 504
            :body := "access-token-resolver-fn call threw an exception: java.lang.Exception: EXPLODED!"))))

    (testing "wrapped http/get call"
      (let [handler (make-oauth2-s1st-security-handler (make-cached-access-token-resolver "tokeninfo-url"
                                                                                          {:client-middleware wrap-client-tracing})
                                                       check-consented-scopes)]
        (with-redefs [http/get (fn [url {:keys [oauth-token foo]}]
                                 {:status 200 :body {"access_token" oauth-token "uid" "mjackson" "scope" ["uid"] "foo" foo}})]
          (testing "http/get is called with a middleware"
            (given (handler {:headers {"authorization" "Bearer footoken"}} nil ["uid"])
              ;; :status being nil means we still have a request, not a response - normal operation
              :status := nil
              ;; Injected parameter is passed back, this proves that our middleware was used
              :tokeninfo :> {"foo" "bar"})))))))


(defn ring-handler [request]
  {:status 200 :body {:incoming-tokeninfo (:tokeninfo request)}})


(def wrapped-ring-handler1 (-> ring-handler ((make-wrap-oauth2-token-verifier resolve-token-valid))))
(def wrapped-ring-handler2 (-> ring-handler ((make-wrap-oauth2-token-verifier resolve-token-invalid))))
(def wrapped-ring-handler3 (-> ring-handler ((make-wrap-oauth2-token-verifier resolve-token-error))))


(defn error-logger [message]
  (println "Access denied:" message))


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
    (given (wrapped-ring-handler3 {:headers {"authorization" "Bearer footoken"}})
      :status := 504))

  (testing "integration"
    (let [messages  (atom [])
          logger-fn (fn [msg]
                      (swap! messages conj msg))]
      (given ((wrap-log-auth-error wrapped-ring-handler1 logger-fn) {})
        :status := 401)
      (given ((wrap-log-auth-error wrapped-ring-handler2 logger-fn) {:headers {"authorization" "Bearer footoken"}})
        :status := 401)
      (given ((wrap-log-auth-error wrapped-ring-handler3 logger-fn) {:headers {"authorization" "Bearer footoken"}})
        :status := 504)
      (is (= ["no access token given"
              "invalid access token"
              "access-token-resolver-fn call threw an exception: java.lang.IllegalStateException: tokeninfo endpoint returned status code: 503"]
             @messages)))))
